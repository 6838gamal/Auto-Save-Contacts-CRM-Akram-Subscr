package gamalprojects.autosavecontactscrm.akramalahmadi.services

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ContactAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var repo: ContactsRepository
    private val recentlyProcessed = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(this)
        repo = ContactsRepository(db)
        Log.d("ContactAccessibility", "Accessibility Service Created")
        AutoSaveForegroundService.startIfEnabled(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ContactAccessibility", "Accessibility Service Connected")
        AutoSaveForegroundService.startIfEnabled(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        val sourceLabel = when {
            packageName == "com.whatsapp" -> "واتساب"
            packageName == "com.whatsapp.w4b" -> "واتساب الأعمال"
            packageName.contains("dialer") || packageName.contains("phone") -> "مكالمة / الهاتف"
            else -> null
        }

        // Only monitor WhatsApp, WhatsApp Business, or Dialer layouts to preserve battery
        if (sourceLabel != null) {
            val rootNode = rootInActiveWindow ?: return
            val candidates = LinkedHashSet<String>()
            collectCandidateNumbers(rootNode, candidates)
            if (candidates.isEmpty()) return

            val now = System.currentTimeMillis()
            val toProcess = candidates.filter { candidate ->
                val norm = PhoneNumberUtils.normalize(candidate)
                if (norm.length < 7) return@filter false
                val lastSeen = recentlyProcessed[norm]
                if (lastSeen != null && (now - lastSeen) < 60_000L) {
                    false
                } else {
                    recentlyProcessed[norm] = now
                    true
                }
            }
            if (recentlyProcessed.size > 500) {
                recentlyProcessed.entries.removeIf { (now - it.value) > 120_000L }
            }
            if (toProcess.isEmpty()) return

            scope.launch {
                for (potentialNumber in toProcess) {
                    Log.d("ContactAccessibility", "Detected potential number in $sourceLabel UI: $potentialNumber")
                    repo.processNewConnection(
                        applicationContext,
                        potentialNumber,
                        sourceLabel,
                        "التقاط من شاشة $sourceLabel"
                    )
                }
            }
        }
    }

    private fun collectCandidateNumbers(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            val potentialNumber = extractRawNumberIfValid(text)
            if (potentialNumber != null) {
                out.add(potentialNumber)
            }
        }

        for (i in 0 until node.childCount) {
            collectCandidateNumbers(node.getChild(i), out)
        }
    }

    /**
     * Checks if a string looks specifically like a Yemen or international mobile phone number.
     */
    private fun extractRawNumberIfValid(text: String): String? {
        // Avoid alphabetic texts
        if (text.any { it.isLetter() }) return null

        val cleaned = PhoneNumberUtils.cleanNumber(text)
        if (cleaned.length < 8 || cleaned.length > 16) return null

        val plainDigits = cleaned.removePrefix("+")

        // Match Yemen Mobile 9-digit formats (77, 78, 73, 71, 70)
        if (plainDigits.length == 9 && (
                plainDigits.startsWith("77") ||
                    plainDigits.startsWith("78") ||
                    plainDigits.startsWith("73") ||
                    plainDigits.startsWith("71") ||
                    plainDigits.startsWith("70")
                )
        ) {
            return cleaned
        }

        // Match Yemen international lengths (967xxxxxxxxx or 00967xxxxxxxxx)
        if ((plainDigits.startsWith("967") && plainDigits.length == 12) ||
            (plainDigits.startsWith("00967") && plainDigits.length == 14)
        ) {
            return cleaned
        }

        // Match explicit international format starting with + (e.g. +966..., +20..., +971...)
        if (cleaned.startsWith("+") && plainDigits.length in 10..15) {
            return cleaned
        }

        return null
    }

    override fun onInterrupt() {
        Log.d("ContactAccessibility", "Accessibility Service Interrupted")
    }
}
