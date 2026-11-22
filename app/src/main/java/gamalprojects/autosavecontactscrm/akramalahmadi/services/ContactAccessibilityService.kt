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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        // Only monitor WhatsApp or Dialer layouts to preserve battery
        if (packageName == "com.whatsapp" || packageName.contains("dialer") || packageName.contains("phone")) {
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
                    Log.d("ContactAccessibility", "Detected potential number in UI: $potentialNumber")
                    repo.processNewConnection(
                        applicationContext,
                        potentialNumber,
                        "خدمة إمكانية الوصول",
                        "التقاط مباشر من الشاشة"
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
     * Checks if a string looks specifically like a Yemen or general mobile phone number.
     * Starts with + or 967 or 77/73/71/70 and has 9 digits, or is formatted with spaces.
     */
    private fun extractRawNumberIfValid(text: String): String? {
        val cleaned = text.replace(Regex("[^0-9+]"), "")
        if (cleaned.length < 8 || cleaned.length > 15) return null

        // Avoid alphabetic texts
        if (text.any { it.isLetter() }) return null

        // Check format matches common Yemen numbers (e.g., starts with 77, 73, 71, 70, 78 or with state prefix)
        val plainDigits = text.replace(Regex("[^0-9]"), "")
        
        // Match Yemen Mobile 9-digit formats (77xxxxxxx, 73xxxxxxx, 71xxxxxxx, 70xxxxxxx)
        if (plainDigits.length == 9 && (plainDigits.startsWith("77") || plainDigits.startsWith("73") || plainDigits.startsWith("71") || plainDigits.startsWith("70"))) {
            return text
        }

        // Match normalized international lengths (e.g. 967777777777)
        if (plainDigits.startsWith("967") && plainDigits.length == 12) {
            return text
        }

        return null
    }

    override fun onInterrupt() {
        Log.d("ContactAccessibility", "Accessibility Service Interrupted")
    }
}
