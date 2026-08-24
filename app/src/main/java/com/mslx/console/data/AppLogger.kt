package com.mslx.console.data

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全应用可落盘运行日志（filesDir/logs/app.log，1MB 轮转）。
 *
 * - 所有关键阶段（连接/网络/桥接/后台任务）记录 start/success/fail。
 * - 严禁记录 API Key / 密码 / token / cookie / 完整请求头等敏感信息；
 *   追加时统一走 [sanitize] 脱敏。
 * - 崩溃通过 [installCrashHandler] 捕获未处理异常，写入主日志 + 独立 crash 报告文件，
 *   重启后由崩溃弹窗读取。
 */
object AppLogger {

    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val CRASH_FILE = "crash.log"
    private const val MAX_BYTES = 1L * 1024L * 1024L // 1MB

    private var logDir: File? = null
    private var appVersion: String = "unknown"

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 初始化日志目录并安装全局崩溃处理器（幂等）。 */
    fun init(context: Context) {
        if (logDir != null) return
        appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("unknown")
        val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
        logDir = dir
        installCrashHandler()
        i("AppLogger", "日志已初始化 pid=${android.os.Process.myPid()} " +
            "version=$appVersion sdk=${Build.VERSION.SDK_INT} os=${android.os.Build.VERSION.RELEASE}")
    }

    fun v(tag: String, message: String) = write("V", tag, message, null)
    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    /** 读取主日志全文（供设置页查看）。可能较大，UI 侧按需截断展示。 */
    fun getLogs(): String {
        val file = logDir?.let { File(it, LOG_FILE) } ?: return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    /** 清空主日志。 */
    fun clearLogs() {
        val file = logDir?.let { File(it, LOG_FILE) } ?: return
        runCatching { file.writeText("") }
    }

    /** 读取上次崩溃报告（无则返回 null）。 */
    fun readLastCrash(): String? {
        val file = logDir?.let { File(it, CRASH_FILE) } ?: return null
        if (!file.exists()) return null
        val content = runCatching { file.readText() }.getOrDefault("")
        return content.takeIf { it.isNotBlank() }
    }

    /** 用户已处理崩溃弹窗，清除崩溃标记。 */
    fun markCrashHandled() {
        logDir?.let { File(it, CRASH_FILE) }?.delete()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                e("Crash", "未捕获异常 thread=${thread.name}", throwable)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                writeCrashReport(sw.toString())
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(stack: String) {
        val dir = logDir ?: return
        val sb = StringBuilder()
        sb.append("========== 崩溃报告 ==========\n")
        sb.append("时间: ${dateFormat.format(Date())}\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("应用: v$appVersion\n")
        sb.append("========== 堆栈 ==========\n")
        sb.append(stack)
        runCatching { File(dir, CRASH_FILE).writeText(sb.toString()) }
    }

    @Synchronized
    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        val line = buildString {
            append(dateFormat.format(Date()))
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(sanitize(message))
            if (throwable != null) {
                append('\n')
                append(throwable.javaClass.name)
                if (!throwable.message.isNullOrBlank()) append(": ").append(throwable.message)
                throwable.stackTrace.take(20).forEach { append("\n    at ").append(it.toString()) }
            }
            append('\n')
        }
        runCatching {
            val file = File(dir, LOG_FILE)
            if (file.exists() && file.length() + line.length > MAX_BYTES) {
                // 1MB 轮转：保留一份历史 app.log.1，覆盖旧历史
                File(dir, "$LOG_FILE.1").apply { if (exists()) delete() }
                file.renameTo(File(dir, "$LOG_FILE.1"))
            }
            FileWriter(file, true).use { it.append(line) }
        }
    }

    /** 脱敏：隐藏 apiKey / token / password / cookie / 授权头等敏感值。 */
    private fun sanitize(text: String): String {
        var out = text
        val secretPatterns = listOf(
            Regex("(?i)(api[_-]?key|apikey|x-api-key)\\s*[:=]\\s*[\"']?[^\\s&\"']+"),
            Regex("(?i)(token|access_token|password|passwd)\\s*[:=]\\s*[\"']?[^\\s&\"']+"),
            Regex("(?i)(authorization)\\s*[:=]\\s*[\"']?[^\\s&\"']+"),
            Regex("(?i)(cookie)\\s*[:=]\\s*[\"']?[^\\s&\"']+"),
        )
        secretPatterns.forEach { regex ->
            out = regex.replace(out) { m ->
                val label = m.value.substringBefore('=').substringBefore(':').trim()
                "$label=***"
            }
        }
        return out
    }
}
