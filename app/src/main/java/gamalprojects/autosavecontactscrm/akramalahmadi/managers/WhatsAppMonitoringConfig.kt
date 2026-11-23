package gamalprojects.autosavecontactscrm.akramalahmadi.managers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Manages independent detection, monitoring toggles, and OS-level notification settings
 * for regular WhatsApp (com.whatsapp) and WhatsApp Business (com.whatsapp.w4b).
 */
object WhatsAppMonitoringConfig {

    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    const val SOURCE_WHATSAPP = "واتساب"
    const val SOURCE_WHATSAPP_BUSINESS = "واتساب الأعمال"

    private const val PREFS_NAME = "whatsapp_monitoring_prefs"
    private const val KEY_WA_NOTIF = "monitor_whatsapp_notif"
    private const val KEY_WA_ACCESS = "monitor_whatsapp_access"
    private const val KEY_W4B_NOTIF = "monitor_whatsapp_business_notif"
    private const val KEY_W4B_ACCESS = "monitor_whatsapp_business_access"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.applicationContext.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isWhatsAppInstalled(context: Context): Boolean =
        isAppInstalled(context, PKG_WHATSAPP)

    fun isWhatsAppBusinessInstalled(context: Context): Boolean =
        isAppInstalled(context, PKG_WHATSAPP_BUSINESS)

    // --- Independent Notification Listener Switches ---

    fun isWhatsAppNotificationMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WA_NOTIF, true)

    fun setWhatsAppNotificationMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WA_NOTIF, enabled).apply()
    }

    fun isWhatsAppBusinessNotificationMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_W4B_NOTIF, true)

    fun setWhatsAppBusinessNotificationMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_W4B_NOTIF, enabled).apply()
    }

    // --- Independent Accessibility Capture Switches ---

    fun isWhatsAppAccessibilityMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WA_ACCESS, true)

    fun setWhatsAppAccessibilityMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WA_ACCESS, enabled).apply()
    }

    fun isWhatsAppBusinessAccessibilityMonitoringEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_W4B_ACCESS, true)

    fun setWhatsAppBusinessAccessibilityMonitoringEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_W4B_ACCESS, enabled).apply()
    }

    // --- Package Resolution for Services ---

    fun resolveNotificationSourceIfEnabled(context: Context, packageName: String?): String? {
        return when (packageName) {
            PKG_WHATSAPP -> if (isWhatsAppNotificationMonitoringEnabled(context)) SOURCE_WHATSAPP else null
            PKG_WHATSAPP_BUSINESS -> if (isWhatsAppBusinessNotificationMonitoringEnabled(context)) SOURCE_WHATSAPP_BUSINESS else null
            else -> null
        }
    }

    fun resolveAccessibilitySourceIfEnabled(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return when {
            packageName == PKG_WHATSAPP ->
                if (isWhatsAppAccessibilityMonitoringEnabled(context)) SOURCE_WHATSAPP else null
            packageName == PKG_WHATSAPP_BUSINESS ->
                if (isWhatsAppBusinessAccessibilityMonitoringEnabled(context)) SOURCE_WHATSAPP_BUSINESS else null
            packageName.contains("dialer") || packageName.contains("phone") ->
                "مكالمة / الهاتف"
            else -> null
        }
    }

    // --- Combined OS Permission + Per-App Enable Helpers ---

    fun isWhatsAppAnyChannelActive(context: Context): Boolean {
        val notifOk = PermissionManager.isNotificationListenerEnabled(context) &&
            isWhatsAppNotificationMonitoringEnabled(context)
        val accessOk = PermissionManager.isAccessibilityServiceEnabled(context) &&
            isWhatsAppAccessibilityMonitoringEnabled(context)
        return notifOk || accessOk
    }

    fun isWhatsAppBusinessAnyChannelActive(context: Context): Boolean {
        val notifOk = PermissionManager.isNotificationListenerEnabled(context) &&
            isWhatsAppBusinessNotificationMonitoringEnabled(context)
        val accessOk = PermissionManager.isAccessibilityServiceEnabled(context) &&
            isWhatsAppBusinessAccessibilityMonitoringEnabled(context)
        return notifOk || accessOk
    }

    /**
     * Opens Android's per-app Notification Settings directly for either WhatsApp or WhatsApp Business,
     * falling back safely to App Info or the system Notification Listener Settings screen if unavailable.
     */
    fun openAppNotificationSettings(context: Context, packageName: String): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            openAppDetailsSettings(context, packageName)
        }
    }

    fun openAppDetailsSettings(context: Context, packageName: String): Boolean {
        return try {
            val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(detailsIntent)
            true
        } catch (_: Exception) {
            try {
                PermissionManager.openNotificationListenerSettings(context)
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
