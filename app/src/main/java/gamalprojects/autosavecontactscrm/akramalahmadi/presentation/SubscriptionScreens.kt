@file:OptIn(ExperimentalMaterial3Api::class)

package gamalprojects.autosavecontactscrm.akramalahmadi.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gamalprojects.autosavecontactscrm.akramalahmadi.R
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActiveSubscriptionInfo
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.DbConnectionStatus
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.FreeAccessState
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository

@Composable
fun SubscriptionGateScreen(
    subscriptionViewModel: SubscriptionViewModel,
    canNavigateBack: Boolean = false,
    onNavigateBack: (() -> Unit)? = null,
    onUnlocked: () -> Unit
) {
    // Strictly prevent Back button from returning to the app when locked (e.g. after 100 contacts or expired code)
    BackHandler(enabled = true) {
        if (canNavigateBack) {
            onNavigateBack?.invoke()
        }
    }

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val snapshot by subscriptionViewModel.identitySnapshot.collectAsStateWithLifecycle()
    val isRefreshing by subscriptionViewModel.isRefreshingIdentity.collectAsStateWithLifecycle()
    val isVerifying by subscriptionViewModel.isVerifying.collectAsStateWithLifecycle()
    val isSubmittingReview by subscriptionViewModel.isSubmittingReview.collectAsStateWithLifecycle()
    val gateError by subscriptionViewModel.gateError.collectAsStateWithLifecycle()
    val reviewNotice by subscriptionViewModel.reviewNotice.collectAsStateWithLifecycle()
    val isConnectionError by subscriptionViewModel.isConnectionError.collectAsStateWithLifecycle()
    val freeAccessState by subscriptionViewModel.freeAccessState.collectAsStateWithLifecycle()
    val dbStatus by subscriptionViewModel.dbConnectionStatus.collectAsStateWithLifecycle()

    var codeInput by remember { mutableStateOf("") }

    LaunchedEffect(gateError) {
        if (!gateError.isNullOrBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val submitAction = {
        subscriptionViewModel.submitSubscriptionCode(codeInput) {
            Toast.makeText(context, "تم تفعيل الاشتراك بنجاح!", Toast.LENGTH_SHORT).show()
            onUnlocked()
        }
    }

    val adminPhoneDisplay = SubscriptionRepository.ADMIN_PHONE_DISPLAY
    val adminPhoneDigits = SubscriptionRepository.ADMIN_PHONE_DIGITS

    val openWhatsAppSupport = {
        val message = buildString {
            appendLine("مرحباً، أرغب في تفعيل الاشتراك أو الاستفسار في تطبيق AutoSave Contacts CRM.")
            appendLine("معرف المستخدم: ${snapshot.userId}")
            appendLine("عنوان IP: ${snapshot.publicIp}")
            appendLine("الجهاز: ${snapshot.deviceModel}")
        }
        val waUri = Uri.parse("https://wa.me/$adminPhoneDigits?text=${Uri.encode(message)}")
        val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(waIntent)
        } catch (_: Exception) {
            copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay)
            Toast.makeText(context, "تم نسخ رقم الإدارة: $adminPhoneDisplay", Toast.LENGTH_LONG).show()
        }
    }

    val dialAdminPhone = {
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$adminPhoneDigits")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dialIntent)
        } catch (_: Exception) {
            copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay)
        }
    }

    val contactOwnerAction = {
        openWhatsAppSupport()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (canNavigateBack && onNavigateBack != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("subscription_gate_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "العودة للتطبيق",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("العودة للتطبيق", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            // Security Shield Hero
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = "بوابة الاشتراك",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.title_subscription_gate),
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = if (freeAccessState.isExhausted) {
                    "وصلت إلى الحد المجاني (${freeAccessState.usedContacts} / ${freeAccessState.limit} جهة اتصال). أدخل كود اشتراك صحيح للمتابعة."
                } else {
                    stringResource(R.string.subtitle_subscription_gate)
                },
                fontSize = 12.sp,
                color = if (freeAccessState.isExhausted) {
                    Color(0xFFF59E0B)
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
                },
                fontWeight = if (freeAccessState.isExhausted) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Prominent Direct Contact Highlight Banner (+967 772 920 748) above fold
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.SupportAgent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "للتفعيل أو الاستفسار تواصل مع الإدارة",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = adminPhoneDisplay,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.testTag("prominent_admin_phone_banner")
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = openWhatsAppSupport,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("banner_whatsapp_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("واتساب", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = dialAdminPhone,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("banner_call_button")
                        ) {
                            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اتصال", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("banner_copy_button")
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "نسخ الرقم", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            PostgresConnectionBadgeCard(
                dbStatus = dbStatus,
                isBusy = isRefreshing || isVerifying,
                onRetrySync = {
                    subscriptionViewModel.syncConnectionAndUserData(onAutoUnlocked = {
                        Toast.makeText(context, "تم تفعيل اشتراكك بنجاح!", Toast.LENGTH_SHORT).show()
                        onUnlocked()
                    })
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Code Entry Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.label_subscription_code),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it.uppercase()
                            if (gateError != null) subscriptionViewModel.clearGateError()
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.hint_subscription_code),
                                fontSize = 12.sp,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (codeInput.isNotEmpty()) {
                                    IconButton(onClick = {
                                        codeInput = ""
                                        subscriptionViewModel.clearGateError()
                                    }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "مسح")
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                        if (!clip.isNullOrBlank()) {
                                            codeInput = clip.uppercase()
                                            subscriptionViewModel.clearGateError()
                                        } else {
                                            Toast.makeText(context, "الحافظة فارغة", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Filled.ContentPaste, contentDescription = "لصق الكود")
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        isError = !gateError.isNullOrBlank(),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            textDirection = TextDirection.Ltr
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitAction() }),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("subscription_code_input")
                    )

                    AnimatedVisibility(
                        visible = !gateError.isNullOrBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        val alertColor = if (isConnectionError) Color(0xFFF59E0B) else Color(0xFFEF4444)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(alertColor.copy(alpha = 0.14f))
                                .border(1.dp, alertColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isConnectionError) Icons.Filled.CloudOff else Icons.Filled.ErrorOutline,
                                    contentDescription = "تنبيه",
                                    tint = alertColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = gateError.orEmpty(),
                                    color = alertColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 17.sp
                                )
                            }
                            if (isConnectionError) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = contactOwnerAction,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("contact_owner_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SupportAgent,
                                        contentDescription = null,
                                        tint = alertColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "التواصل مع صاحب المشروع",
                                        color = alertColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = submitAction,
                        enabled = !isVerifying && !isSubmittingReview,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("activate_subscription_button")
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("جاري التحقق...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.LockOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.action_activate_subscription),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { subscriptionViewModel.requestCodeReview(codeInput) },
                        enabled = !isVerifying && !isSubmittingReview,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("request_review_button")
                    ) {
                        if (isSubmittingReview) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري إرسال طلب المراجعة...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Filled.RateReview, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "طلب مراجعة وتفعيل الاشتراك",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = !reviewNotice.isNullOrBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.14f))
                                .border(1.dp, Color(0xFF10B981).copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.MarkEmailRead,
                                    contentDescription = "طلب مراجعة",
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = reviewNotice.orEmpty(),
                                    color = Color(0xFF10B981),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 17.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    subscriptionViewModel.syncConnectionAndUserData(onAutoUnlocked = {
                                        Toast.makeText(context, "تم اعتماد اشتراكك والدخول بنجاح!", Toast.LENGTH_SHORT).show()
                                        onUnlocked()
                                    })
                                },
                                modifier = Modifier.testTag("check_approval_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("فحص حالة الموافقة الآن", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Captured User Identity & Public IP Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "بيانات التعريف ومنع التكرار",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        IconButton(
                            onClick = { subscriptionViewModel.refreshIdentity() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "تحديث عنوان IP",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    IdentityInfoRow(
                        label = stringResource(R.string.label_user_identifier),
                        value = snapshot.userId,
                        isMonospace = true,
                        onCopy = {
                            copyToClipboard(context, "User ID", snapshot.userId)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.15f))
                    IdentityInfoRow(
                        label = stringResource(R.string.label_user_ip),
                        value = if (isRefreshing) "جاري الجلب..." else snapshot.publicIp,
                        isMonospace = true,
                        onCopy = {
                            copyToClipboard(context, "IP Address", snapshot.publicIp)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.15f))
                    IdentityInfoRow(
                        label = stringResource(R.string.label_device_name),
                        value = "${snapshot.deviceModel} • ${snapshot.networkType}",
                        isMonospace = false
                    )
                    if (snapshot.locationInfo.isNotBlank()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.Gray.copy(alpha = 0.15f))
                        IdentityInfoRow(
                            label = "الشبكة / الموقع",
                            value = snapshot.locationInfo,
                            isMonospace = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Admin Support & Contact Card (+967 772 920 748)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(18.dp)
                    )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SupportAgent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "التواصل مع الإدارة للتفعيل أو الاستفسار",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "لطلب كود التفعيل، تجديد الاشتراك، أو لأي استفسار يمكنكم التواصل مباشرة مع الإدارة على الرقم:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = adminPhoneDisplay,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                                modifier = Modifier.testTag("admin_support_phone_text")
                            )
                            IconButton(
                                onClick = {
                                    copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("copy_admin_phone_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "نسخ رقم الإدارة",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = openWhatsAppSupport,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("whatsapp_admin_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("واتساب الإدارة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = dialAdminPhone,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("call_admin_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اتصال هاتفي", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PostgresConnectionBadgeCard(
    dbStatus: DbConnectionStatus,
    isBusy: Boolean,
    onRetrySync: () -> Unit
) {
    val (bgColor, accentColor, icon, title, subtitle) = when (dbStatus) {
        is DbConnectionStatus.Checking -> Tuple5(
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.08f),
            MaterialTheme.colorScheme.secondary,
            Icons.Filled.Sync,
            "حالة الاتصال: جاري التحقق...",
            null
        )
        is DbConnectionStatus.Connected -> Tuple5(
            Color(0xFF10B981).copy(alpha = 0.10f),
            Color(0xFF10B981),
            Icons.Filled.CloudDone,
            "حالة الاتصال: متصل",
            null
        )
        is DbConnectionStatus.Disconnected,
        is DbConnectionStatus.NotConfigured -> Tuple5(
            Color(0xFFF59E0B).copy(alpha = 0.12f),
            Color(0xFFF59E0B),
            Icons.Filled.CloudOff,
            "حالة الاتصال: غير متصل",
            "تواصل مع الإدارة لمراجعة الأمر (${SubscriptionRepository.ADMIN_PHONE_DISPLAY})"
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .testTag("postgres_connection_status_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dbStatus is DbConnectionStatus.Checking || isBusy) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                        lineHeight = 14.sp
                    )
                }
            }
            IconButton(
                onClick = onRetrySync,
                enabled = !isBusy,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("retry_postgres_sync_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "تحديث الاتصال",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private data class Tuple5<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)

@Composable
fun FreeUsageStatusCard(
    freeState: FreeAccessState,
    onActivateCodeClick: () -> Unit
) {
    val context = LocalContext.current
    val adminPhoneDisplay = SubscriptionRepository.ADMIN_PHONE_DISPLAY
    val adminPhoneDigits = SubscriptionRepository.ADMIN_PHONE_DIGITS
    val progress = (freeState.usedContacts.toFloat() / freeState.limit.toFloat()).coerceIn(0f, 1f)
    val isNearLimit = freeState.usedContacts >= (freeState.limit * 0.8f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isNearLimit) Color(0xFFF59E0B).copy(alpha = 0.55f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                (if (isNearLimit) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LockOpen,
                            contentDescription = null,
                            tint = if (isNearLimit) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "الوصول المجاني المبدئي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "متاح حفظ حتى ${freeState.limit} جهة اتصال مجاناً",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "الأرقام المحفوظة:", fontSize = 12.sp, color = Color.Gray)
                Text(
                    text = "${freeState.usedContacts} / ${freeState.limit} (متبقي ${freeState.remainingContacts})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isNearLimit) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (isNearLimit) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onActivateCodeClick,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("direct_activate_subscription_button")
            ) {
                Icon(Icons.Filled.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "مباشرة التفعيل والاشتراك",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "للحصول على كود اشتراك أو الاستفسار:",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = adminPhoneDisplay,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.testTag("free_card_admin_phone")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val waUri = Uri.parse("https://wa.me/$adminPhoneDigits")
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, waUri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {
                                copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay)
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "واتساب الإدارة", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$adminPhoneDigits")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {
                                copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay)
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "اتصال بالإدارة", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionStatusCard(
    info: ActiveSubscriptionInfo?,
    nowMillis: Long,
    onSwitchCode: () -> Unit
) {
    if (info == null) return
    val context = LocalContext.current
    val adminPhoneDisplay = SubscriptionRepository.ADMIN_PHONE_DISPLAY
    val remainingText = formatRemainingDuration(info.expiresAt, info.isLifetime, nowMillis)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "حالة الاشتراك: نشط",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "${info.label} (${info.code})",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                TextButton(
                    onClick = onSwitchCode,
                    modifier = Modifier.testTag("switch_subscription_code_button")
                ) {
                    Text("تبديل الكود", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "حالة التفعيل:", fontSize = 12.sp, color = Color.Gray)
                Text(
                    text = "اشتراك مفعل",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "حد الحفظ المسموح:", fontSize = 12.sp, color = Color.Gray)
                val max = info.maxContacts
                Text(
                    text = if (max != null && max > 0) "${info.usedContacts} / $max جهة اتصال" else "غير محدود (${info.usedContacts} محفوظة)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (max != null && max > 0 && info.usedContacts >= (max * 0.9)) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurface
                )
            }
            val maxQuota = info.maxContacts
            if (maxQuota != null && maxQuota > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (info.usedContacts.toFloat() / maxQuota.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (info.usedContacts >= (maxQuota * 0.9)) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "المدة المتبقية:", fontSize = 12.sp, color = Color.Gray)
                Text(
                    text = remainingText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "معرف المستخدم:", fontSize = 12.sp, color = Color.Gray)
                Text(
                    text = info.boundUserId,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "عنوان IP المسجل:", fontSize = 12.sp, color = Color.Gray)
                Text(
                    text = info.boundIpAddress,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color.Gray.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "دعم الإدارة:", fontSize = 12.sp, color = Color.Gray)
                TextButton(
                    onClick = { copyToClipboard(context, "رقم الإدارة", adminPhoneDisplay) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = adminPhoneDisplay,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityInfoRow(
    label: String,
    value: String,
    isMonospace: Boolean,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "نسخ $label",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "تم نسخ $label", Toast.LENGTH_SHORT).show()
}

fun formatRemainingDuration(expiresAt: Long?, isLifetime: Boolean, now: Long): String {
    if (isLifetime || expiresAt == null) return "مفتوح (مدى الحياة)"
    val diff = expiresAt - now
    if (diff <= 0L) return "منتهي"
    val totalMinutes = diff / 60_000L
    val days = totalMinutes / 1440L
    val hours = (totalMinutes % 1440L) / 60L
    val mins = totalMinutes % 60L
    return when {
        days > 0 -> "$days يوم و $hours ساعة"
        hours > 0 -> "$hours ساعة و $mins دقيقة"
        else -> "$mins دقيقة"
    }
}
