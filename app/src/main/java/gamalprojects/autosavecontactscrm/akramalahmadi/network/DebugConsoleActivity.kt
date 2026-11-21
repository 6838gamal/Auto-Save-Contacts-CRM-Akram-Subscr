package gamalprojects.autosavecontactscrm.akramalahmadi.network

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ApiDebugLogger.Level
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ApiDebugLogger.LogEvent
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiService
import kotlinx.coroutines.launch

class DebugConsoleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DebugConsoleScreen(
                    onClose = { finish() },
                    onTestConnection = { url -> testConnection(url) }
                )
            }
        }
    }

    private fun testConnection(databaseUrl: String) {
        ApiDebugLogger.log("TEST", "بدء اختبار الاتصال بـ: $databaseUrl")
        if (databaseUrl.isBlank()) {
            ApiDebugLogger.logError("TEST", "❌ databaseUrl فارغ — لا يمكن الاختبار")
            return
        }
        val api = SubscriptionApiService.create(databaseUrl)
        if (api == null) {
            ApiDebugLogger.logError("TEST", "❌ فشل إنشاء Retrofit — راجع الرسائل أعلاه")
            return
        }
        lifecycleScope.launch {
            try {
                val response = api.connectAndSyncUser(
                    authorization = null,
                    apiKey = SubscriptionApiService.extractApiKey(databaseUrl),
                    request = SubscriptionConnectRequest(
                        userId = "debug-test",
                        androidId = "debug",
                        installUuid = "debug-uuid",
                        publicIp = "0.0.0.0",
                        localIp = "0.0.0.0",
                        locationInfo = "debug",
                        deviceModel = android.os.Build.MODEL ?: "unknown",
                        osVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
                        networkType = "debug"
                    )
                )
                ApiDebugLogger.logSuccess("TEST", "✅ وصل الطلب! HTTP ${response.code()}")
            } catch (e: Exception) {
                ApiDebugLogger.logFailure("TEST", e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugConsoleScreen(
    onClose: () -> Unit,
    onTestConnection: (String) -> Unit
) {
    val context = LocalContext.current
    val events by ApiDebugLogger.events.collectAsState()
    val listState = rememberLazyListState()
    var showTestDialog by remember { mutableStateOf(false) }

    // تمرير تلقائي لآخر حدث
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🐞 سجل التشخيص", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "إغلاق")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("API Logs", ApiDebugLogger.exportAsText()))
                        Toast.makeText(context, "تم النسخ (${events.size} حدث)", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ") }

                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "سجل تشخيص API")
                            putExtra(Intent.EXTRA_TEXT, ApiDebugLogger.exportAsText())
                        }
                        context.startActivity(Intent.createChooser(intent, "مشاركة السجل"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "مشاركة") }

                    IconButton(onClick = {
                        ApiDebugLogger.clear()
                        Toast.makeText(context, "تم مسح السجل", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Delete, contentDescription = "مسح") }

                    IconButton(onClick = { showTestDialog = true }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "اختبار")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B1B1B),
                    titleContentColor = Color(0xFF00E676)
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF111111))
        ) {
            // مسار الملفات
            Surface(
                color = Color(0xFF1E1E1E),
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "📁 ${ApiDebugLogger.getDownloadsFilePath()}",
                        color = Color(0xFF888888),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "📁 ${ApiDebugLogger.getLogFilePath()}",
                        color = Color(0xFF888888),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (events.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "لا توجد أحداث بعد.\nاضغط ▶ لاختبار الاتصال\nأو نفّذ إجراءً في التطبيق.",
                        color = Color(0xFF888888),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(events) { ev -> LogEventItem(ev) }
                }
            }
        }
    }

    if (showTestDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("اختبار الاتصال") },
            text = {
                Column {
                    Text("أدخل databaseUrl (مثل https://server.com?api_key=xxx):", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://...") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTestDialog = false
                    onTestConnection(url)
                }) { Text("اختبار") }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) { Text("إلغاء") }
            }
        )
    }
}

@Composable
private fun LogEventItem(ev: LogEvent) {
    val (color, icon) = when (ev.level) {
        Level.ERROR -> Color(0xFFFF5252) to "❌"
        Level.SUCCESS -> Color(0xFF00E676) to "✅"
        Level.WARN -> Color(0xFFFFB300) to "⚠️"
        else -> Color(0xFFEEEEEE) to "•"
    }
    Surface(
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            Row {
                Text(
                    "[${ev.timeText}]",
                    color = Color(0xFF666666),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$icon [${ev.tag}]",
                    color = color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                ev.message,
                color = color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            ev.throwable?.let { t ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "${t.javaClass.simpleName}: ${t.message}",
                    color = Color(0xFFFF8888),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
