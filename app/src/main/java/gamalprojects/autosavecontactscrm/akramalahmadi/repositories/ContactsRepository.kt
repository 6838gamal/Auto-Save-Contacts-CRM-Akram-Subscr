package gamalprojects.autosavecontactscrm.akramalahmadi.repositories

import android.content.Context
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.database.*
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.ContactsManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ContactsRepository(private val db: AppDatabase) {

    companion object {
        // Process-wide lock prevents concurrent CallReceiver / SmsReceiver / NotificationListener / AccessibilityService
        // triggers from double-saving or double-logging the same phone number.
        val saveMutex = Mutex()
    }

    private val contactDao = db.contactDao()
    private val logEntryDao = db.logEntryDao()
    private val phoneEventDao = db.phoneEventDao()
    private val settingDao = db.settingDao()
    private val subscriptionRepo = SubscriptionRepository(db)

    val allContacts: Flow<List<Contact>> = contactDao.getAllContacts()
    val allLogs: Flow<List<LogEntry>> = logEntryDao.getAllLogs()
    val allEvents: Flow<List<PhoneEvent>> = phoneEventDao.getAllEvents()
    val contactsCount: Flow<Int> = contactDao.getContactsCount()
    val latestContact: Flow<Contact?> = contactDao.getLatestContact()

    /**
     * Engine's unified point of processing for any newly captured number.
     * Strictly guarantees each phone number is saved and logged ONCE only.
     */
    suspend fun processNewConnection(
        context: Context,
        rawNumber: String,
        source: String,
        contentSnippet: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val cleaned = PhoneNumberUtils.cleanNumber(rawNumber)
        if (cleaned.length < 7) {
            return@withContext false
        }

        val normalized = PhoneNumberUtils.normalize(rawNumber)
        if (normalized.length < 7) {
            return@withContext false
        }

        saveMutex.withLock {
            // 1. Check Device Native Contacts: Ignore completely if exists there, do not log, do not save.
            if (ContactsManager.isNumberInNativeContacts(context, rawNumber)) {
                Log.d("ContactsRepository", "Number $rawNumber already exists in native contacts. Ignoring entirely.")
                return@withLock false
            }

            // 2. Check Internal CRM database (exact normalized match + defensive equivalence scan)
            val existingContact = contactDao.getContactByPhone(normalized)
                ?: contactDao.getAllContactsSync().firstOrNull {
                    PhoneNumberUtils.areNumbersEqual(it.phone, rawNumber) ||
                        PhoneNumberUtils.areNumbersEqual(it.originalPhone, rawNumber)
                }

            if (existingContact != null) {
                // Already saved in CRM records! Never duplicate in contacts_table, native contacts, logs_table, or quota.
                Log.d("ContactsRepository", "Number $normalized already exists in CRM (${existingContact.name}). Skipping duplicate save/log.")
                return@withLock false
            }

            // 3. Check subscription & usage quota before saving any new contact
            if (!subscriptionRepo.canSaveNewContact()) {
                val alreadyLoggedLimitForNumber = logEntryDao.getAllLogsSync().any { entry ->
                    entry.status == "تجاوز حد الاشتراك" &&
                        PhoneNumberUtils.areNumbersEqual(entry.phone, rawNumber)
                }
                if (!alreadyLoggedLimitForNumber) {
                    logEntryDao.insertLog(
                        LogEntry(
                            phone = rawNumber,
                            source = source,
                            status = "تجاوز حد الاشتراك",
                            details = "تم إيقاف الحفظ التلقائي للرقم $rawNumber بسبب انتهاء الاشتراك أو الوصول للحد الأقصى للباقة."
                        )
                    )
                }
                return@withLock false
            }

            // 4. First-time save only: insert atomically using IGNORE conflict strategy
            val nextId = getNextCustomerId()
            val clientName = "عميل $nextId"

            val candidateContact = Contact(
                name = clientName,
                phone = normalized,
                originalPhone = rawNumber,
                source = source,
                isSyncedToPhone = false
            )
            val insertedRowId = contactDao.insertContactIfAbsent(candidateContact)
            if (insertedRowId <= 0L) {
                // Concurrent or unique-index collision -> guaranteed no duplicate
                return@withLock false
            }

            // Increment customer counter for future unique names
            incrementCustomerId(nextId)

            // Sync to native phone contacts or Google account contacts
            val googleSyncEnabled = settingDao.getSetting("google_sync_enabled")?.value == "1"
            val gmailAccount = settingDao.getSetting("gmail_account")?.value ?: ""
            val isSynced = if (googleSyncEnabled && gmailAccount.isNotBlank()) {
                ContactsManager.saveToGoogleContacts(context, clientName, rawNumber, gmailAccount)
            } else {
                ContactsManager.saveToNativeContacts(context, clientName, rawNumber)
            }

            val finalContact = candidateContact.copy(
                id = insertedRowId.toInt(),
                isSyncedToPhone = isSynced
            )
            if (isSynced) {
                contactDao.updateContact(finalContact)
            }

            // Register single event and single audit log entry for this number
            val alreadyLoggedEvent = phoneEventDao.getAllEventsSync().any { event ->
                PhoneNumberUtils.areNumbersEqual(event.phone, rawNumber)
            }
            if (!alreadyLoggedEvent) {
                phoneEventDao.insertEvent(
                    PhoneEvent(
                        phone = rawNumber,
                        type = source,
                        content = contentSnippet.ifEmpty { "تواصل متبادل عبر $source" }
                    )
                )
            }
            val alreadyLoggedSave = logEntryDao.getAllLogsSync().any { entry ->
                (entry.status == "تم الحفظ تلقائياً" || entry.status == "خطأ في الحفظ ومكتنز محلياً") &&
                    PhoneNumberUtils.areNumbersEqual(entry.phone, rawNumber)
            }
            if (!alreadyLoggedSave) {
                logEntryDao.insertLog(
                    LogEntry(
                        phone = rawNumber,
                        source = source,
                        status = if (isSynced) "تم الحفظ تلقائياً" else "خطأ في الحفظ ومكتنز محلياً",
                        details = "تم حفظ $clientName برقم $rawNumber بنجاح. المزامنة للهاتف: ${if (isSynced) "مكتمل" else "فشل (انتظار صلاحيات)"}"
                    )
                )
            }

            NotificationHelper.showNotification(
                context,
                "تم حفظ عميل جديد تلقائياً 👤",
                "تم التعرف على تواصل من رقم جديد وحفظه باسم '$clientName'"
            )

            // Record contact against free limit (100) or active subscription quota and sync to server
            subscriptionRepo.recordContactSaved(context)
            subscriptionRepo.syncSingleClientToDatabase(context, finalContact, isDeleted = false)

            return@withLock true
        }
    }

    /**
     * Gets next ID from settings to prevent reuse upon deletion.
     */
    private suspend fun getNextCustomerId(): Int {
        val setting = settingDao.getSetting("next_customer_id")
        return setting?.value?.toIntOrNull() ?: 1
    }

    private suspend fun incrementCustomerId(currentId: Int) {
        settingDao.insertSetting(SettingEntity("next_customer_id", (currentId + 1).toString()))
    }

    /**
     * Updates contact name inside both Native phonebook and local database
     */
    suspend fun updateContactName(context: Context, contact: Contact, newName: String): Boolean = withContext(Dispatchers.IO) {
        val updated = contact.copy(name = newName)
        contactDao.updateContact(updated)

        // Attempt updating native contact as well if synced before
        if (contact.isSyncedToPhone) {
            // Updating names inside Contacts contract is complex. If it fails, we flag isSyncedToPhone.
            ContactsManager.saveToNativeContacts(context, newName, contact.originalPhone)
        }

        logEntryDao.insertLog(
            LogEntry(
                phone = contact.originalPhone,
                source = "تعديل يدوي",
                status = "تعديل الاسم",
                details = "تم تعديل اسم العميل من ${contact.name} إلى $newName"
            )
        )
        subscriptionRepo.syncSingleClientToDatabase(context, updated, isDeleted = false)
        return@withContext true
    }

    suspend fun deleteContact(context: Context, contact: Contact) = withContext(Dispatchers.IO) {
        val deletedRows = saveMutex.withLock {
            contactDao.deleteContact(contact)
        }
        if (deletedRows > 0) {
            logEntryDao.insertLog(
                LogEntry(
                    phone = contact.originalPhone,
                    source = "حذف يدوي",
                    status = "حذف العميل",
                    details = "تم حذف ${contact.name} من سجلات التطبيق (يبقى محسوباً ضمن حد الخطة)"
                )
            )
            subscriptionRepo.recordContactDeleted(context)
            subscriptionRepo.syncSingleClientToDatabase(context, contact, isDeleted = true)
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        logEntryDao.deleteAllLogs()
    }

    suspend fun clearEvents() = withContext(Dispatchers.IO) {
        phoneEventDao.deleteAllEvents()
    }
}
