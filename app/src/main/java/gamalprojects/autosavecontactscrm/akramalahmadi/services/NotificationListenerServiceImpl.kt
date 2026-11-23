package gamalprojects.autosavecontactscrm.akramalahmadi.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class NotificationListenerServiceImpl : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var repo: ContactsRepository
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
        
        val sourceLabel = when (packageName) {
            "com.whatsapp" -> "واتساب"
            "com.whatsapp.w4b" -> "واتساب الأعمال"
            else -> null
        }

        // WhatsApp & WhatsApp Business notification processing
        if (sourceLabel != null) {
            val extras = sbn.notification.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

            Log.i("NotificationListener", "$sourceLabel Notification Posted: Title: $title, Text: $text")

            val potentialPhone = extractPhoneNumber(title)
            if (potentialPhone != null) {
                val normalized = PhoneNumberUtils.normalize(potentialPhone)
                val now = System.currentTimeMillis()
                val lastSeen = recentlyProcessed[normalized]
                if (normalized.length >= 7 && (lastSeen == null || (now - lastSeen) >= 60_000L)) {
                    recentlyProcessed[normalized] = now
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
