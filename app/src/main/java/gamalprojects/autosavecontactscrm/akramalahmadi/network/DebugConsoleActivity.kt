package gamalprojects.autosavecontactscrm.akramalahmadi.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch

/**
 * شاشة تشخيص تعرض كل الأحداث فوراً.
 * افتحها من أي مكان: startActivity(Intent(this, DebugConsoleActivity::class.java))
 */
class DebugConsoleActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var autoScroll: Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoScroll = true

        // ── بناء الواجهة برمجياً (لا تحتاج layout xml) ──
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(16, 16, 16, 16)
        }

        // عنوان
        val title = TextView(this).apply {
            text = "🐞 سجل التشخيص — Debug Console"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        root.addView(title)

        // شريط أزرار علوي
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        topBar.addView(makeButton("نسخ") { copyLogs() })
        topBar.addView(makeButton("مشاركة") { shareLogs() })
        topBar.addView(makeButton("مسح") { clearLogs() })
        topBar.addView(makeButton("اختبار") { testConnection() })
        root.addView(topBar)

        // مسار الملف
        val pathView = TextView(this).apply {
            text = "📁 ${ApiDebugLogger.getDownloadsFilePath()}"
            setTextColor(Color.parseColor("#888888"))
            textSize = 10f
            setPadding(0, 8, 0, 8)
        }
        root.addView(pathView)

        // منطقة السجل
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(12, 12, 12, 12)
            movementMethod = ScrollingMovementMethod()
            text = "في انتظار الأحداث..."
        }
        scroll.addView(logTextView)
        root.addView(scroll)

        setContentView(root)

        // ── الاشتراك في الأحداث ──
        lifecycleScope.launch {
            ApiDebugLogger.events.collect { events ->
                val text = if (events.isEmpty()) {
                    "لا توجد أحداث بعد. جرّب إجراءً في التطبيق أو اضغط زر \"اختبار\"."
                } else {
                    events.joinToString("\n") { formatEvent(it) }
                }
                logTextView.text = text
                if (autoScroll) {
                    scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }

        ApiDebugLogger.log("DEBUG_UI", "تم فتح شاشة التشخيص.")
    }

    private fun formatEvent(ev: ApiDebugLogger.LogEvent): String {
        val color = when (ev.level) {
            ApiDebugLogger.Level.ERROR -> "#FF5252"
            ApiDebugLogger.Level.SUCCESS -> "#00E676"
            ApiDebugLogger.Level.WARN -> "#FFB300"
            else -> "#EEEEEE"
        }
        val prefix = when (ev.level) {
            ApiDebugLogger.Level.ERROR -> "❌"
            ApiDebugLogger.Level.SUCCESS -> "✅"
            ApiDebugLogger.Level.WARN -> "⚠️"
            else -> "•"
        }
        return "<font color='#888888'>[${ev.timeText}]</font> <font color='$color'>$prefix [${ev.tag}] ${escapeHtml(ev.message)}</font>"
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("API Logs", ApiDebugLogger.exportAsText()))
        Toast.makeText(this, "تم النسخ (${ApiDebugLogger.events.value.size} حدث)", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "سجل تشخيص API")
            putExtra(Intent.EXTRA_TEXT, ApiDebugLogger.exportAsText())
        }
        startActivity(Intent.createChooser(intent, "مشاركة السجل"))
    }

    private fun clearLogs() {
        ApiDebugLogger.clear()
        Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
    }

    private fun testConnection() {
        // TODO: استبدل هذه القيمة بالرابط الفعلي المستخدم في تطبيقك
        val databaseUrl = "ضع_رابط_قاعدتك_هنا"
        ApiDebugLogger.log("TEST", "بدء اختبار الاتصال بـ: $databaseUrl")

        val api = SubscriptionApiService.create(databaseUrl)
        if (api == null) {
            ApiDebugLogger.logError("TEST", "❌ فشل إنشاء Retrofit — راجع الرسائل أعلاه لمعرفة السبب.")
            return
        }

        lifecycleScope.launch {
            try {
                // نرسل طلباً وهمياً — السيرفر سيرد بـ 4xx عادة، لكن هذا يثبت أن الاتصال يعمل
                val response = api.connectAndSyncUser(
                    authorization = null,
                    apiKey = SubscriptionApiService.extractApiKey(databaseUrl),
                    request = gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionConnectRequest(
                        userId = "debug-test",
                        androidId = "debug",
                        installUuid = "debug-uuid",
                        publicIp = "0.0.0.0",
                        localIp = "0.0.0.0",
                        locationInfo = "debug",
                        deviceModel = android.os.Build.MODEL,
                        osVersion = android.os.Build.VERSION.RELEASE,
                        networkType = "debug"
                    )
                )
                ApiDebugLogger.logSuccess("TEST", "✅ وصل الطلب! الكود: ${response.code()}")
            } catch (e: Exception) {
                ApiDebugLogger.logFailure("TEST", e)
            }
        }
    }
}
