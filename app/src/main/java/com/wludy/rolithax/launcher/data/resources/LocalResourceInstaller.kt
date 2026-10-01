package com.wludy.rolithax.launcher.data.resources

import android.content.Context
import com.google.gson.Gson
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.localengine.LocalDownloader
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.localengine.LocalServerRuntime
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

class LocalResourceInstaller {
    suspend fun install(
        context: Context,
        target: ServerRef,
        entries: List<ResourceCartEntry>,
        onProgress: (Float, String) -> Unit,
    ): List<String> {
        require(target.isLocal) { "本机安装目标无效" }
        require(entries.isNotEmpty()) { "购物清单为空" }
        if (LocalServerRuntime.running.value && LocalServerRuntime.currentDirName == target.instanceId) {
            throw IllegalStateException("实例正在运行，请先停止服务端再安装资源")
        }
        val root = LocalInstanceStore.resolve(context, target.instanceId)?.second
            ?: throw IllegalStateException("本机实例不存在")
        val rootPath = root.canonicalPath + File.separator
        val stage = File(root, ".rolithax-install-${UUID.randomUUID()}").apply { mkdirs() }
        val files = mutableListOf<PendingFile>()
        val committed = mutableListOf<CommittedFile>()
        var preserveStage = false
        try {
            var itemIndex = 0
            for (entry in entries) {
                val file = entry.version.files.firstOrNull { it.primary } ?: entry.version.files.first()
                val staged = File(stage, "package-$itemIndex")
                requireAllowedUrl(file.url)
                download(file, staged, ALLOWED_HOSTS) { progress ->
                    onProgress(((itemIndex + progress) / entries.size).coerceIn(0f, 1f), "下载 ${entry.project.name}")
                }
                require(file.size <= 0 || staged.length() == file.size) { "${entry.project.name} 文件长度校验失败" }
                if (entry.project.type == "modpack") {
                    expandModpack(staged, stage, files)
                } else {
                    files += PendingFile(staged, destination(entry.project.type, file.filename, entry, root), false)
                }
                itemIndex++
            }
            val duplicate = files.groupBy { it.relativeTarget.lowercase() }.entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) { "购物清单中的多个资源将覆盖同一文件：${duplicate?.key.orEmpty()}" }
            commit(root, rootPath, stage, files, committed)
            onProgress(1f, "安装完成")
            return committed.map { it.target.relativeTo(root).path.replace(File.separatorChar, '/') }
        } catch (error: Throwable) {
            preserveStage = !rollback(committed)
            if (preserveStage) throw IllegalStateException("安装失败且未能完整恢复，备份保留在实例临时目录", error)
            throw error
        } finally {
            if (!preserveStage) stage.deleteRecursively()
        }
    }

    private suspend fun download(file: ResourceFile, target: File, allowedHosts: Set<String>, onProgress: (Float) -> Unit = {}) {
        val (algorithm, hash) = file.sha512.split(':', limit = 2).let {
            if (it.size == 2) it[0].uppercase().replace("SHA", "SHA-") to it[1]
            else "SHA-512" to it.first()
        }
        LocalDownloader.downloadVerified(file.url, target, hash, algorithm, onProgress, allowedHosts)
    }

    private fun destination(type: String, filename: String, entry: ResourceCartEntry, root: File): String {
        val leaf = safeLeaf(filename)
        return when (type) {
            "mod" -> "mods/$leaf"
            "plugin" -> "plugins/$leaf"
            "datapack" -> "${safeRelative(entry.project.type.let { worldName(root) })}/datapacks/$leaf"
            else -> throw IllegalArgumentException("暂不支持安装资源类型：$type")
        }
    }

    private fun worldName(root: File): String {
        val meta = com.wludy.rolithax.launcher.data.localengine.ServerFiles.readMeta(root)
        return safeRelative(meta?.levelName?.ifBlank { "world" } ?: "world")
    }

    private suspend fun expandModpack(archive: File, stage: File, output: MutableList<PendingFile>) {
        ZipFile(archive).use { zip ->
            val index = zip.getEntry("modrinth.index.json") ?: throw IllegalArgumentException("整合包清单损坏")
            require(index.size in 1..4L * 1024 * 1024) { "整合包清单大小无效" }
            val manifest = zip.getInputStream(index).bufferedReader().use { Gson().fromJson(it, PackManifest::class.java) }
            require(manifest.files.size <= MAX_PACK_ENTRIES) { "整合包文件数量过多" }
            var unpacked = 0L
            manifest.files.forEachIndexed { fileIndex, item ->
                if (item.env?.server == "unsupported") return@forEachIndexed
                require(item.downloads.isNotEmpty()) { "整合包文件缺少下载地址：${item.path}" }
                requireAllowedUrl(item.downloads.first())
                val relative = safeRelative(item.path)
                val staged = File(stage, "pack-file-$fileIndex")
                val (algorithm, hash) = item.hashes.entries.firstOrNull { it.key in SUPPORTED_HASHES }
                    ?.let { (key, value) -> algorithmFor(key) to value }
                    ?: throw IllegalArgumentException("整合包文件缺少可用摘要：${item.path}")
                LocalDownloader.downloadVerified(item.downloads.first(), staged, hash, algorithm, allowedHosts = ALLOWED_HOSTS)
                require(item.fileSize <= 0 || staged.length() == item.fileSize) { "整合包文件长度不符：${item.path}" }
                unpacked += staged.length()
                require(unpacked <= MAX_PACK_BYTES) { "整合包下载内容超过限制" }
                output += PendingFile(staged, relative, false)
            }

            val hasServerOverrides = zip.entries().asSequence().any { it.name.startsWith("server-overrides/") }
            val prefix = if (hasServerOverrides) "server-overrides/" else "overrides/"
            var overrideIndex = 0
            zip.entries().asSequence()
                .filter { it.name.startsWith(prefix) && !it.isDirectory }
                .forEach { item ->
                    val relative = safeRelative(item.name.removePrefix(prefix))
                    val staged = File(stage, "override-${overrideIndex++}")
                    zip.getInputStream(item).use { input -> staged.outputStream().use { outputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_OVERRIDE_BYTES) { "整合包覆盖文件超过限制" }
                            outputStream.write(buffer, 0, count)
                        }
                    } }
                    output += PendingFile(staged, relative, true)
                }
        }
    }

    private fun commit(root: File, rootPath: String, stage: File, files: List<PendingFile>, committed: MutableList<CommittedFile>) {
        files.forEachIndexed { index, item ->
            val target = File(root, safeRelative(item.relativeTarget)).canonicalFile
            require(target.path.startsWith(rootPath)) { "安装路径越过实例目录" }
            if (item.preserveExisting && target.exists()) return@forEachIndexed
            target.parentFile?.mkdirs()
            val backup = if (target.exists()) File(stage, "backup-$index").also {
                check(target.renameTo(it)) { "无法备份目标文件：${item.relativeTarget}" }
            } else null
            try {
                check(item.staged.renameTo(target)) { "无法提交已校验文件：${item.relativeTarget}" }
                committed += CommittedFile(target, backup)
            } catch (error: Throwable) {
                if (target.exists()) target.delete()
                backup?.renameTo(target)
                throw error
            }
        }
    }

    private fun rollback(files: List<CommittedFile>): Boolean {
        var restored = true
        files.asReversed().forEach { file ->
            try {
                if (file.target.exists() && !file.target.delete()) throw IllegalStateException("无法移除新文件")
                if (file.backup != null && !file.backup.renameTo(file.target)) throw IllegalStateException("无法恢复备份文件")
            } catch (_: Throwable) {
                restored = false
            }
        }
        return restored
    }

    private fun safeLeaf(value: String): String {
        val normalized = value.replace('\\', '/')
        require('/' !in normalized && normalized.isNotBlank() && normalized != "." && normalized != "..") { "文件名无效" }
        return normalized
    }

    private fun safeRelative(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        val parts = normalized.split('/')
        require(normalized.isNotBlank() && ':' !in normalized && parts.none { it.isBlank() || it == "." || it == ".." }) { "资源路径无效" }
        return parts.joinToString(File.separator)
    }

    private fun requireAllowedUrl(value: String) {
        val uri = android.net.Uri.parse(value)
        require(uri.scheme == "https" && uri.userInfo == null && uri.host in ALLOWED_HOSTS) { "资源下载域名不受支持" }
    }

    private fun algorithmFor(name: String): String = when (name.lowercase()) {
        "sha512" -> "SHA-512"
        "sha256" -> "SHA-256"
        "sha1" -> "SHA-1"
        else -> error("不支持的摘要算法")
    }

    private data class PendingFile(val staged: File, val relativeTarget: String, val preserveExisting: Boolean)
    private data class CommittedFile(val target: File, val backup: File?)
    private data class PackManifest(val files: List<PackFile> = emptyList())
    private data class PackFile(
        val path: String = "",
        val hashes: Map<String, String> = emptyMap(),
        val downloads: List<String> = emptyList(),
        val fileSize: Long = 0,
        val env: PackEnvironment? = null,
    )
    private data class PackEnvironment(val server: String? = null)

    companion object {
        private val ALLOWED_HOSTS = setOf("cdn.modrinth.com", "cdn.modrinth.net", "edge.forgecdn.net", "mediafilez.forgecdn.net")
        private val SUPPORTED_HASHES = setOf("sha512", "sha256", "sha1")
        private const val MAX_PACK_ENTRIES = 10_000
        private const val MAX_PACK_BYTES = 4L * 1024 * 1024 * 1024
        private const val MAX_OVERRIDE_BYTES = 1024L * 1024 * 1024
    }
}
