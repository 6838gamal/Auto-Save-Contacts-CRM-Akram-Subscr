package gamalprojects.autosavecontactscrm.akramalahmadi.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ConnectionDetailsReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionDetailsPopupDialog(
    report: ConnectionDetailsReport,
    isRefreshing: Boolean,
    onRetrySync: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val accentColor = when {
        report.succeeded -> Color(0xFF10B981)
        report.httpStatusCode != null -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }
    val statusIcon = when {
        report.succeeded -> Icons.Filled.CloudDone
        else -> Icons.Filled.CloudOff
    }
    val formattedTime = SimpleDateFormat("hh:mm:ss a", Locale.forLanguageTag("ar")).format(Date(report.capturedAt))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .widthIn(max = 580.dp)
                    .heightIn(max = 680.dp)
                    .border(1.5.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .testTag("connection_details_popup_dialog")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Header Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        accentColor.copy(alpha = 0.20f),
                                        accentColor.copy(alpha = 0.08f)
                                    )
                                )
                            )
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = statusIcon,
                                    contentDescription = report.statusHeadline,
                                    tint = accentColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = report.statusHeadline,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = accentColor
                                )
                                Text(
                                    text = report.statusDetail,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    lineHeight = 16.sp
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("close_connection_popup_icon")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "إغلاق نافذة تفاصيل الاتصال",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    // Metric Pills Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatusChip(
                            label = if (report.succeeded) "متصل" else "غير متصل",
                            color = accentColor
                        )
                        StatusChip(
                            label = report.httpStatusCode?.let { "HTTP $it" } ?: "لا يوجد رد HTTP",
                            color = if (report.httpStatusCode in 200..299) Color(0xFF10B981) else MaterialTheme.colorScheme.secondary
                        )
                        if (report.latencyMs != null) {
                            StatusChip(
                                label = "${report.latencyMs} ms",
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = formattedTime,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Section 1: Connection Target & Network ("بماذا تم الاتصال")
                        SectionCard(
                            title = "تفاصيل الاتصال والربط",
                            icon = Icons.Filled.Hub,
                            accent = MaterialTheme.colorScheme.secondary
                        ) {
                            DetailRow("حالة الاتصال", if (report.succeeded) "نجح الاتصال والمصادقة" else "لم ينجح الاتصال")
                            DetailRow("الهدف المتصل به", report.connectedWithTarget, isMonospace = true)
                            DetailRow("طريقة المصادقة", report.authMethodSummary)
                            DetailRow("نوع الشبكة", report.networkType)
                            DetailRow(
                                "مزامنة العملاء",
                                when (report.clientsSyncSucceeded) {
                                    true -> "تمت مزامنة جهات الاتصال بنجاح ✅"
                                    false -> "تعذرت مزامنة قائمة العملاء ⚠️"
                                    null -> "لم تُنفذ في هذه الجلسة"
                                }
                            )
                            DetailRow("معرف الجهاز الثابت", report.deviceId, isMonospace = true)
                            DetailRow("معرف المستخدم", report.userId, isMonospace = true)
                            DetailRow("عنوان IP العام / المحلي", "${report.publicIp} | ${report.localIp}", isMonospace = true)
                            if (!report.errorDetail.isNullOrBlank()) {
                                DetailRow("تفاصيل الخطأ", report.errorDetail, valueColor = Color(0xFFEF4444))
                            }
                        }

                        // Section 2: Remote Server & Plan Content ("محتوى الخادم والرد")
                        SectionCard(
                            title = "محتوى الخادم وحالة الباقة",
                            icon = Icons.Filled.Dns,
                            accent = Color(0xFF10B981)
                        ) {
                            DetailRow("الخطة الحالية", report.planType)
                            DetailRow(
                                "رصيد الخطة المجانية",
                                "${report.freeContactsUsed} / ${report.freeContactsLimit}" +
                                    if (report.freeTierExhausted) " (مكتمل - يتطلب اشتراك)" else " (نشط)"
                            )
                            DetailRow(
                                "حالة الاشتراك",
                                if (report.isSubscribed && report.isSubscriptionActive) {
                                    "مفعل (${report.activeCode ?: "مشترك"})"
                                } else {
                                    "غير مشترك (خطة مجانية)"
                                }
                            )
                            if (report.serverStatusText.isNotBlank()) {
                                DetailRow("حالة السيرفر", report.serverStatusText)
                            }
                            if (report.serverMessageText.isNotBlank()) {
                                DetailRow("رسالة الخادم", report.serverMessageText)
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "محتوى الاستجابة من الخادم (JSON / Payload):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            CodePayloadBox(report.rawServerResponse)

                            if (!report.clientsSyncResponse.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "استجابة مزامنة العملاء (/api/clients/sync):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                CodePayloadBox(report.clientsSyncResponse)
                            }
                        }

                        // Section 3: Local Database Content ("محتوى قاعدة البيانات")
                        SectionCard(
                            title = "محتوى قاعدة البيانات المحلية (${report.localContactsCount} عميل)",
                            icon = Icons.Filled.Storage,
                            accent = MaterialTheme.colorScheme.tertiary
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                MiniDbMetric("العملاء", report.localContactsCount.toString(), Modifier.weight(1f))
                                MiniDbMetric("مزامن للهاتف", report.syncedToPhoneCount.toString(), Modifier.weight(1f))
                                MiniDbMetric("السجلات", report.localLogsCount.toString(), Modifier.weight(1f))
                                MiniDbMetric("حذف معلق", report.pendingDeletesCount.toString(), Modifier.weight(1f))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (report.sampleContacts.isEmpty()) {
                                Text(
                                    text = "قاعدة البيانات فارغة حالياً (لا توجد جهات اتصال محفوظة بعد).",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            } else {
                                Text(
                                    text = "أحدث العملاء في قاعدة البيانات (عرض ${report.sampleContacts.size} من ${report.localContactsCount}):",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    report.sampleContacts.forEach { contact ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.65f),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (contact.isSyncedToPhone) Icons.Filled.CheckCircle else Icons.Filled.Person,
                                                    contentDescription = null,
                                                    tint = if (contact.isSyncedToPhone) Color(0xFF10B981) else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = contact.name.ifBlank { "عميل بدون اسم" },
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${contact.originalPhone.ifBlank { contact.phone }} • ${contact.source}",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Text(
                                                    text = "${contact.interactionCount}×",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                    // Bottom Action Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onRetrySync,
                            enabled = !isRefreshing,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("popup_retry_connection_button")
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تحديث", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                copyReportToClipboard(context, report)
                            },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("popup_copy_report_button")
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ التقرير", modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("نسخ", fontSize = 12.sp)
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("popup_close_dialog_button")
                        ) {
                            Text("حسناً", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isMonospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
            color = valueColor
        )
    }
}

@Composable
private fun CodePayloadBox(text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            color = Color(0xFF0B1220),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = text,
                    color = Color(0xFF38BDF8),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun MiniDbMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                maxLines = 1
            )
        }
    }
}

private fun copyReportToClipboard(context: Context, report: ConnectionDetailsReport) {
    val text = buildString {
        appendLine(report.statusHeadline)
        appendLine(report.statusDetail)
        appendLine("الهدف: ${report.connectedWithTarget}")
        appendLine("المصادقة: ${report.authMethodSummary}")
        appendLine("HTTP: ${report.httpStatusCode ?: "N/A"} | Latency: ${report.latencyMs ?: 0}ms")
        appendLine("Device ID: ${report.deviceId} | User ID: ${report.userId}")
        appendLine("Plan: ${report.planType} | Free: ${report.freeContactsUsed}/${report.freeContactsLimit}")
        appendLine("Local Contacts: ${report.localContactsCount} | Synced: ${report.syncedToPhoneCount} | Logs: ${report.localLogsCount}")
        appendLine("Server Response:\n${report.rawServerResponse}")
        if (!report.clientsSyncResponse.isNullOrBlank()) {
            appendLine("Clients Sync Response:\n${report.clientsSyncResponse}")
        }
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Connection Report", text))
    Toast.makeText(context, "تم نسخ تقرير الاتصال ومحتوى قاعدة البيانات", Toast.LENGTH_SHORT).show()
}
