package gamalprojects.autosavecontactscrm.akramalahmadi.network

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مسجّل أخطاء شامل:
 * - يكتب في Logcat
 * - يكتب في ملف txt داخل مجلد التطبيق الخاص (files/)
 * - يكتب في مجلد التنزيلات العام على ذاكرة الجهاز (best-effort)
 * - يبثّ الأحداث إلى StateFlow ليقرأها الـ UI فوراً
 */
object ApiDebugLogger {

    private const val TAG = "API_DEBUG"
    private const val FILE_NAME = "api_errors_log.txt"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L
    private const val MAX_IN_MEMORY_LINES = 500

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var appContext: Context? = null

    // ── قناة البث للـ UI ──
    private val _events = MutableStateFlow<List<LogEvent>>(emptyList())
    val events: StateFlow<List<LogEvent>> = _events.asStateFlow()

    data class LogEvent(
        val timestamp: Long,
        val timeText: String,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    enum class Level { INFO, WARN, ERROR, SUCCESS }

    fun init(context: Context) {
        appContext = context.applicationContext
        log("LOGGER", "=== ApiDebugLogger initialized ===")
        log("LOGGER", "Log file path: ${getLogFilePath()}")
        log("LOGGER", "Downloads path: ${getDownloadsFilePath()}")
    }

    // ═══════════════════════════════════════════════════════
    //  API العامة
    // ═══════════════════════════════════════════════════════

    fun log(tag: String, message: String, level: Level = Level.INFO) {
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            timeText = dateFormat.format(Date()),
            level = level,
            tag = tag,
            message = message
        )
        emit(event, toLogcat = true)
    }

    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val event = LogEvent(
            timestamp = System.currentTimeMillis(),
            timeText = dateFormat.format(Date()),
            level = Level.ERROR,
            tag = tag,
            message = message,
            throwable = throwable
        )
        emit(event, toLogcat = true, throwable = throwable)
    }

    fun logSuccess(tag: String, message: String) = log(tag, message, Level.SUCCESS)
    fun logWarn(tag: String, message: String) = log(tag, message, Level.WARN)

    fun logRequest(endpoint: String, method: String, body: String?, headers: Map<String, String?>) {
        val sb = StringBuilder()
        sb.appendLine("→ $method $endpoint")
        headers.forEach { (k, v) ->
            val safe = when {
                v == null -> "null ❌"
                v.isBlank() -> "blank ❌"
                k.equals("Authorization", true) -> "${v.take(12)}... (len=${v.length})"
                k.equals("X-API-Key", true) -> "${v.take(6)}... (len=${v.length})"
                else -> v
            }
            sb.appendLine("   $k: $safe")
        }
        sb.appendLine("   Body: ${body?.take(2000) ?: "null"}")
        log("REQUEST", sb.toString())
    }

    fun logResponse(endpoint: String, code: Int, body: String?) {
        val sb = StringBuilder()
        sb.appendLine("← $endpoint | HTTP $code")
        sb.appendLine("   Body: ${body?.take(3000) ?: "null"}")
        if (code in 200..299) {
            logSuccess("RESPONSE", sb.toString())
        } else {
            logError("RESPONSE", sb.toString())
        }
    }

    fun logFailure(endpoint: String, throwable: Throwable) {
        logError("NETWORK_FAIL", "فشل الطلب إلى $endpoint: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        when (throwable) {
            is java.net.UnknownHostException -> logError("DIAGNOSIS", "❌ DNS فشل — الرابط غير صحيح أو لا يوجد إنترنت. تحقق من databaseUrl.")
            is java.net.ConnectException -> logError("DIAGNOSIS", "❌ رفض الاتصال — السيرفر لا يعمل أو المنفذ مغلق أو الجدار الناري يمنع.")
            is java.net.SocketTimeoutException -> logError("DIAGNOSIS", "❌ انتهت المهلة — السيرفر بطيء أو غير متاح. ارفع timeouts.")
            is javax.net.ssl.SSLException -> logError("DIAGNOSIS", "❌ مشكلة SSL — شهادة غير صالحة، أو تستخدم http بدل https بدون cleartext.")
            is java.net.MalformedURLException -> logError("DIAGNOSIS", "❌ الرابط مشوّه (Malformed URL). تحقق من databaseUrl.")
            else -> logError("DIAGNOSIS", "❌ خطأ غير متوقع: ${throwable.javaClass.name}")
        }
    }

    fun logCreateFailure(reason: String) = logError("CREATE_FAIL", "❌ SubscriptionApiService.create() أرجع null. السبب: $reason")
    fun logCreateSuccess(baseUrl: String, apiKeyPresent: Boolean) =
        logSuccess("CREATE_OK", "✅ Retrofit created. baseUrl=$baseUrl | apiKey=${if (apiKeyPresent) "present" else "MISSING ❌"}")

    // ═══════════════════════════════════════════════════════
    //  إدارة السجل
    // ═══════════════════════════════════════════════════════

    fun clear() {
        _events.value = emptyList()
        getInternalFile()?.delete()
        getDownloadsFile()?.delete()
        log("LOGGER", "تم مسح السجل.")
    }

    fun exportAsText(): String {
        return _events.value.joinToString("\n") { ev ->
            val base = "[${ev.timeText}] [${ev.level}] [${ev.tag}] ${ev.message}"
            if (ev.throwable != null) {
                "$base\n    ↳ ${ev.throwable.javaClass.simpleName}: ${ev.throwable.message}\n    ↳ ${ev.throwable.stackTraceToString().take(1000)}"
            } else base
        }
    }

    // ═══════════════════════════════════════════════════════
    //  مسارات الملفات
    // ═══════════════════════════════════════════════════════

    /** الملف الداخلي (files/) — دائماً متاح */
    fun getInternalFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    /** الملف في التنزيلات العامة على ذاكرة الجهاز */
    fun getDownloadsFile(): File? {
        val ctx = appContext ?: return null
        return try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            dir?.let { File(it, FILE_NAME) }
        } catch (_: Exception) { null }
    }

    fun getLogFilePath(): String = getInternalFile()?.absolutePath ?: "N/A"
    fun getDownloadsFilePath(): String = getDownloadsFile()?.absolutePath ?: "N/A"

    // ═══════════════════════════════════════════════════════
    //  الداخلي
    // ═══════════════════════════════════════════════════════

    private fun emit(event: LogEvent, toLogcat: Boolean = false, throwable: Throwable? = null) {
        // 1) Logcat
        if (toLogcat) {
            val line = "[${event.timeText}] [${event.level}] [${event.tag}] ${event.message}"
            if (event.level == Level.ERROR) Log.e(TAG, line, throwable) else Log.d(TAG, line)
        }

        // 2) StateFlow للـ UI
        val current = _events.value.toMutableList()
        current.add(event)
        if (current.size > MAX_IN_MEMORY_LINES) current.removeAt(0)
        _events.value = current

        // 3) ملفات txt
        writeToFiles(formatForFile(event))
    }

    private fun formatForFile(event: LogEvent): String {
        val base = "[${dateFormat.format(Date(event.timestamp))}] [${event.level}] [${event.tag}] ${event.message}"
        return if (event.throwable != null) {
            "$base\n    ↳ ${event.throwable.javaClass.simpleName}: ${event.throwable.message}\n    ↳ ${event.throwable.stackTraceToString().take(1500)}"
        } else base
    }

    private fun writeToFiles(line: String) {
        try { appendSafely(getInternalFile(), line) } catch (_: Exception) {}
        try { appendSafely(getDownloadsFile(), line) } catch (_: Exception) {}
    }

    private fun appendSafely(file: File?, line: String) {
        if (file == null) return
        if (file.exists() && file.length() > MAX_FILE_SIZE) {
            val backup = File(file.parentFile, "${file.nameWithoutExtension}_old.${file.extension}")
            backup.delete()
            file.renameTo(backup)
        }
        file.appendText(line + "\n")
    }
}
