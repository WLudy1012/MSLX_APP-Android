package com.wludy.rolithax.launcher.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.wludy.rolithax.launcher.data.localengine.InstanceStorage
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.localengine.LocalStorage
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class BackupImportSummary(val instances: Int, val files: Int)

class BackupManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    private val app = context.applicationContext
    private val gson = Gson()

    suspend fun export(uri: Uri, password: CharArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(password.size >= MIN_PASSWORD_LENGTH) { "备份口令至少需要 $MIN_PASSWORD_LENGTH 个字符" }
            val resolver = app.contentResolver
            val output = resolver.openOutputStream(uri, "w") ?: error("无法写入目标文件")
            output.use { raw ->
                val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
                val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
                writeHeader(raw, salt, iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
                }
                CipherOutputStream(BufferedOutputStream(raw), cipher).use { encrypted ->
                    ZipOutputStream(encrypted).use { zip -> writeArchive(zip) }
                }
            }
        }.onFailure { AppLogger.w("Backup", "导出失败", it) }
    }

    suspend fun import(uri: Uri, password: CharArray): Result<BackupImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(password.size >= MIN_PASSWORD_LENGTH) { "备份口令至少需要 $MIN_PASSWORD_LENGTH 个字符" }
            val input = app.contentResolver.openInputStream(uri) ?: error("无法读取备份文件")
            val staging = File(app.cacheDir, "backup-import-${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val (settings, files) = input.use { raw -> readArchive(raw, password, staging) }
                commit(staging, settings)
                BackupImportSummary(files.instances, files.files)
            } finally {
                staging.deleteRecursively()
            }
        }.onFailure { AppLogger.w("Backup", "导入失败", it) }
    }

    private suspend fun writeArchive(zip: ZipOutputStream) {
        val settings = settingsStore.settingsFlow.first()
        putText(zip, "manifest.json", gson.toJson(Manifest()))
        putText(zip, "settings.json", gson.toJson(settings))
        var total = 0L
        var count = 0

        fun putFile(name: String, file: File) {
            require(file.isFile) { "备份文件不存在：$name" }
            val size = file.length()
            require(size <= MAX_FILE_BYTES) { "备份文件过大：$name" }
            total += size
            count++
            require(count <= MAX_ENTRIES && total <= MAX_TOTAL_BYTES) { "备份内容超过大小限制" }
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip, BUFFER_BYTES) }
            zip.closeEntry()
        }

        themeFile(settings.lightBackgroundPath)?.let { putFile("theme/background_light", it) }
        themeFile(settings.darkBackgroundPath)?.let { putFile("theme/background_dark", it) }
        LocalInstanceStore.list(app).forEach { summary ->
            val dir = LocalInstanceStore.dir(app, summary.dirName)
            val root = dir.canonicalFile
            if (!root.isDirectory) return@forEach
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative == "" || relative.startsWith("logs/") || relative.startsWith("cache/") || file.name.endsWith(".part")) return@forEach
                val entryName = "instances/${summary.storage.key}/${summary.dirName}/$relative"
                putFile(entryName, file)
            }
        }
    }

    private fun readArchive(input: InputStream, password: CharArray, staging: File): Pair<AppSettings, FileStats> {
        val buffered = BufferedInputStream(input)
        val header = readHeader(buffered)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, header.salt, header.iterations), GCMParameterSpec(TAG_BITS, header.iv))
        }
        var settings: AppSettings? = null
        var manifestRead = false
        var total = 0L
        var entries = 0
        var instances = mutableSetOf<String>()
        var files = 0
        val seenNames = mutableSetOf<String>()
        ZipInputStream(CipherInputStream(buffered, cipher)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "备份条目过多" }
                if (entry.isDirectory) continue
                val name = validateEntry(entry.name)
                require(seenNames.add(name)) { "备份包含重复路径：$name" }
                when {
                    name == "manifest.json" -> {
                        val text = zip.readLimited(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                        val manifest = gson.fromJson(text, Manifest::class.java)
                        require(manifest?.version == FORMAT_VERSION) { "不支持的备份版本" }
                        manifestRead = true
                    }
                    name == "settings.json" -> {
                        val text = zip.readLimited(MAX_SETTINGS_BYTES).toString(Charsets.UTF_8)
                        settings = gson.fromJson(text, AppSettings::class.java)
                    }
                    name.startsWith("instances/") -> {
                        val parts = name.split('/')
                        require(parts.size >= 4) { "实例路径格式错误" }
                        val storage = InstanceStorage.fromKey(parts[1])
                        if (storage == InstanceStorage.PUBLIC) require(LocalStorage.publicStorageGranted()) { "当前设备未授予公共目录权限" }
                        val dirName = LocalStorage.sanitizeName(parts[2])
                        require(dirName == parts[2]) { "实例目录名不合法" }
                        val relative = parts.drop(3).joinToString("/")
                        require(relative.isNotBlank()) { "实例文件路径为空" }
                        val out = File(staging, "instances/${storage.key}/$dirName/$relative").canonicalFile
                        val base = File(staging, "instances/${storage.key}/$dirName").canonicalFile
                        require(out.path.startsWith(base.path + File.separator)) { "实例路径越界" }
                        out.parentFile?.mkdirs()
                        val copied = copyLimited(zip, out, MAX_FILE_BYTES, MAX_TOTAL_BYTES - total)
                        total += copied
                        files++
                        instances += "${storage.key}/$dirName"
                    }
                    name == "theme/background_light" || name == "theme/background_dark" -> {
                        val out = File(staging, name).canonicalFile
                        val base = File(staging, "theme").canonicalFile
                        require(out.path.startsWith(base.path + File.separator)) { "主题文件路径越界" }
                        out.parentFile?.mkdirs()
                        copyLimited(zip, out, MAX_FILE_BYTES, MAX_TOTAL_BYTES - total)
                        total += out.length()
                    }
                    else -> error("未知备份条目：$name")
                }
                zip.closeEntry()
            }
        }
        require(manifestRead) { "备份缺少版本清单" }
        return (settings ?: error("备份缺少设置清单")) to FileStats(instances.size, files)
    }

    private suspend fun commit(staging: File, settings: AppSettings) {
        val importedRoot = File(staging, "instances")
        val instances = importedRoot.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(importedRoot).invariantSeparatorsPath.split('/') }
            .onEach { require(it.size >= 3) { "导入实例路径错误" } }
            .map { it[0] to it[1] }
            .distinct()
            .toList()
        val prepared = instances.map { (storageKey, dirName) ->
            val storage = InstanceStorage.fromKey(storageKey)
            if (storage == InstanceStorage.PUBLIC) require(LocalStorage.publicStorageGranted()) { "当前设备未授予公共目录权限" }
            val source = File(importedRoot, "$storageKey/$dirName").canonicalFile
            val root = LocalStorage.serversDir(app, storage).canonicalFile
            val target = File(root, dirName).canonicalFile
            require(target.path.startsWith(root.path + File.separator)) { "导入目标路径越界" }
            require(!target.exists()) { "实例已存在：$dirName" }
            val temporary = File(root, ".${dirName}.import-${UUID.randomUUID()}")
            ImportDirectory(source, target, temporary)
        }
        val committed = mutableListOf<File>()
        try {
            prepared.forEach { item ->
                item.temporary.parentFile?.mkdirs()
                item.source.copyRecursively(item.temporary, overwrite = false)
                require(item.temporary.renameTo(item.target)) { "无法提交实例：${item.target.name}" }
                committed += item.target
            }

            val themeDir = File(app.filesDir, "theme")
            val stagedLight = File(staging, "theme/background_light")
            val stagedDark = File(staging, "theme/background_dark")
            themeDir.mkdirs()
            val lightTarget = File(themeDir, "background_light")
            val darkTarget = File(themeDir, "background_dark")
            val themeBackup = File(app.cacheDir, "backup-theme-${UUID.randomUUID()}").apply { mkdirs() }
            val oldLight = backupThemeFile(lightTarget, themeBackup, "background_light")
            val oldDark = backupThemeFile(darkTarget, themeBackup, "background_dark")
            try {
                replaceThemeFile(stagedLight, lightTarget)
                replaceThemeFile(stagedDark, darkTarget)
                settingsStore.update { current ->
                    current.copy(
                        daemons = settings.daemons,
                        activeDaemonId = settings.activeDaemonId,
                        themeMode = settings.themeMode,
                        seedColor = settings.seedColor,
                        glassAlpha = settings.glassAlpha,
                        lightBackgroundPath = lightTarget.takeIf { it.isFile }?.absolutePath.orEmpty(),
                        darkBackgroundPath = darkTarget.takeIf { it.isFile }?.absolutePath.orEmpty(),
                        updateChannel = settings.updateChannel,
                        localMinMemMb = settings.localMinMemMb,
                        localMaxMemMb = settings.localMaxMemMb,
                        localJvmArgs = settings.localJvmArgs,
                        localKeepAlive = settings.localKeepAlive,
                        localKeepScreenOn = settings.localKeepScreenOn,
                        localUseSerialGc = settings.localUseSerialGc,
                        localUseShizuku = false,
                    )
                }
            } catch (error: Throwable) {
                restoreThemeFile(oldLight, lightTarget)
                restoreThemeFile(oldDark, darkTarget)
                throw error
            } finally {
                themeBackup.deleteRecursively()
            }
        } catch (error: Throwable) {
            committed.forEach { it.deleteRecursively() }
            throw error
        } finally {
            prepared.forEach { it.temporary.deleteRecursively() }
        }
    }

    private fun replaceThemeFile(source: File, target: File) {
        if (!source.isFile) {
            target.delete()
            return
        }
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        source.copyTo(temporary, overwrite = false)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    private fun backupThemeFile(source: File, backupDir: File, name: String): File? {
        if (!source.isFile) return null
        val backup = File(backupDir, name)
        source.copyTo(backup, overwrite = true)
        return backup
    }

    private fun restoreThemeFile(backup: File?, target: File) {
        if (backup == null) {
            target.delete()
        } else {
            backup.copyTo(target, overwrite = true)
        }
    }

    private fun themeFile(path: String): File? {
        if (path.isBlank()) return null
        val file = File(path).canonicalFile
        val root = File(app.filesDir, "theme").canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun copyLimited(input: InputStream, output: File, limit: Long, remainingTotal: Long): Long {
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        output.outputStream().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= limit && total <= remainingTotal) { "备份文件超过大小限制" }
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "备份清单过大" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateEntry(name: String): String {
        require(name.isNotBlank() && name.length <= 240 && !name.startsWith('/') && !name.contains('\\')) { "备份路径不合法" }
        val normalized = name.split('/').filter { it.isNotBlank() }
        require(normalized.none { it == "." || it == ".." }) { "备份路径越界" }
        require(normalized.size <= 16) { "备份路径层级过深" }
        return normalized.joinToString("/")
    }

    private fun writeHeader(output: OutputStream, salt: ByteArray, iv: ByteArray) {
        output.write(MAGIC)
        output.write(FORMAT_VERSION)
        output.write(ByteBuffer.allocate(4).putInt(PBKDF2_ITERATIONS).array())
        output.write(salt)
        output.write(iv)
    }

    private fun readHeader(input: InputStream): Header {
        val magic = input.readExact(MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "不是 MSLX 加密备份" }
        require(input.read() == FORMAT_VERSION) { "不支持的备份版本" }
        val iterations = ByteBuffer.wrap(input.readExact(4)).int
        require(iterations in 100_000..1_000_000) { "备份参数不安全" }
        val salt = input.readExact(SALT_BYTES)
        val iv = input.readExact(IV_BYTES)
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) { "备份头部损坏" }
        return Header(salt, iv, iterations)
    }

    private fun InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            require(read > 0) { "备份头部截断" }
            offset += read
        }
        return result
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private data class Header(val salt: ByteArray, val iv: ByteArray, val iterations: Int)
    private data class ImportDirectory(val source: File, val target: File, val temporary: File)
    private data class FileStats(val instances: Int, val files: Int)
    private data class Manifest(val version: Int = FORMAT_VERSION, val createdAt: Long = System.currentTimeMillis())

    private companion object {
        const val FORMAT_VERSION = 1
        const val MIN_PASSWORD_LENGTH = 8
        const val PBKDF2_ITERATIONS = 210_000
        const val TAG_BITS = 128
        const val SALT_BYTES = 16
        const val IV_BYTES = 12
        const val MAX_ENTRIES = 5_000
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 16 * 1024
        const val MAX_SETTINGS_BYTES = 2 * 1024 * 1024
        const val BUFFER_BYTES = 64 * 1024
        val MAGIC = "MSLX-BACKUP".toByteArray(Charsets.US_ASCII)
    }
}
