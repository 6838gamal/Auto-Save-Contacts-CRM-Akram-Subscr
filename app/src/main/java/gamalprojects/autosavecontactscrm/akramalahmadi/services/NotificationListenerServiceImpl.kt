package gamalprojects.autosavecontactscrm.akramalahmadi.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.WhatsAppMonitoringConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class NotificationListenerServiceImpl : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    internal lateinit var repo: ContactsRepository
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(this)
        repo = ContactsRepository(db)
        Log.d("NotificationListener", "Notification Listener Service Created")
        AutoSaveForegroundService.startIfEnabled(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotificationListener", "Notification Listener Connected")
        AutoSaveForegroundService.startIfEnabled(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        processNotificationContent(packageName, title, text)
    }

    internal suspend fun processNotificationContentSync(
        packageName: String?,
        title: String,
        text: String
    ): Boolean {
        val pkg = packageName ?: return false
        val sourceLabel = WhatsAppMonitoringConfig.resolveNotificationSourceIfEnabled(applicationContext, pkg)
        if (sourceLabel == null) {
            if (pkg == WhatsAppMonitoringConfig.PKG_WHATSAPP || pkg == WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS) {
                Log.d("NotificationListener", "Notification monitoring is disabled independently for $pkg; skipping.")
            }
            return false
        }

        Log.i("NotificationListener", "$sourceLabel Notification Posted: Title: $title, Text: $text")

        val potentialPhone = extractPhoneNumber(title)
        if (potentialPhone == null) {
            Log.d("NotificationListener", "$sourceLabel Notification Title contains letters or short, ignoring (probably saved contact): $title")
            return false
        }

        val normalized = PhoneNumberUtils.normalize(potentialPhone)
        val now = System.currentTimeMillis()
        val dedupKey = "$pkg:$normalized"
        val lastSeen = recentlyProcessed[dedupKey]
        if (normalized.length >= 7 && (lastSeen == null || (now - lastSeen) >= 60_000L)) {
            recentlyProcessed[dedupKey] = now
            if (recentlyProcessed.size > 500) {
                recentlyProcessed.entries.removeIf { (now - it.value) > 120_000L }
            }
            Log.d("NotificationListener", "Found $sourceLabel phone number to parse: $potentialPhone")
            return repo.processNewConnection(
                applicationContext,
                potentialPhone,
                sourceLabel,
                "رسالة $sourceLabel: ${text.take(60)}"
            )
        }
        return false
    }

    internal fun processNotificationContent(packageName: String?, title: String, text: String) {
        val pkg = packageName ?: return
        val sourceLabel = WhatsAppMonitoringConfig.resolveNotificationSourceIfEnabled(applicationContext, pkg)
        if (sourceLabel == null) {
            if (pkg == WhatsAppMonitoringConfig.PKG_WHATSAPP || pkg == WhatsAppMonitoringConfig.PKG_WHATSAPP_BUSINESS) {
                Log.d("NotificationListener", "Notification monitoring is disabled independently for $pkg; skipping.")
            }
            return
        }

        Log.i("NotificationListener", "$sourceLabel Notification Posted: Title: $title, Text: $text")

        val potentialPhone = extractPhoneNumber(title)
        if (potentialPhone != null) {
            val normalized = PhoneNumberUtils.normalize(potentialPhone)
            val now = System.currentTimeMillis()
            val dedupKey = "$pkg:$normalized"
            val lastSeen = recentlyProcessed[dedupKey]
            if (normalized.length >= 7 && (lastSeen == null || (now - lastSeen) >= 60_000L)) {
                recentlyProcessed[dedupKey] = now
                if (recentlyProcessed.size > 500) {
                    recentlyProcessed.entries.removeIf { (now - it.value) > 120_000L }
                }
                Log.d("NotificationListener", "Found $sourceLabel phone number to parse: $potentialPhone")
                scope.launch {
                    repo.processNewConnection(
                        applicationContext,
                        potentialPhone,
                        sourceLabel,
                        "رسالة $sourceLabel: ${text.take(60)}"
                    )
                }
            }
        } else {
            Log.d("NotificationListener", "$sourceLabel Notification Title contains letters or short, ignoring (probably saved contact): $title")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not used
    }

    /**
     * Determines if a text contains an unsaved phone number (starts with +, digits, spaces)
     * and does not contain alphabetic names (which indicates a saved contact name).
     */
    private fun extractPhoneNumber(title: String): String? {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return null

        // If it contains Arabic alphabets or English letters, WhatsApp is showing a saved contact name.
        // Unsaved numbers are shown purely as "+967 777 777 777" or "777 777 777".
        val hasLetters = trimmed.any { c ->
            c.isLetter() || (c.code in 0x0600..0x06FF) // Arabic characters block range
        }
        if (hasLetters) {
            return null
        }

        // Convert Arabic-Indic numerals and strip directional/formatting characters
        val cleaned = PhoneNumberUtils.cleanNumber(trimmed)
        if (cleaned.length >= 7) {
            return cleaned
        }

        return null
    }
}
