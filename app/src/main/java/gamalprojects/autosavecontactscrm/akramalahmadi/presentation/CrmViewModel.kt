package gamalprojects.autosavecontactscrm.akramalahmadi.presentation

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.Contact
import gamalprojects.autosavecontactscrm.akramalahmadi.database.LogEntry
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SettingEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.export.ExportManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.PermissionManager
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import gamalprojects.autosavecontactscrm.akramalahmadi.services.AutoSaveForegroundService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class CrmViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repo = ContactsRepository(db)

    // UI screen handling
    private val _currentScreen = MutableStateFlow(Screen.SPLASH)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    // Search and filtering state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _logFilterSource = MutableStateFlow("الكل")
    val logFilterSource: StateFlow<String> = _logFilterSource.asStateFlow()

    // CRM Contacts Flow
    val contacts: StateFlow<List<Contact>> = combine(repo.allContacts, _searchQuery) { list, query ->
        if (query.isBlank()) {
            list
        } else {
            list.filter {
                it.name.contains(query, ignoreCase = true) || 
                it.phone.contains(query) || 
                it.originalPhone.contains(query)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Logs Flow
    val logs: StateFlow<List<LogEntry>> = combine(repo.allLogs, _logFilterSource) { list, filter ->
        list.filter { matchesLogFilter(it, filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Dashboard core variables
    val contactsCount: StateFlow<Int> = repo.contactsCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val latestContact: StateFlow<Contact?> = repo.latestContact.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Service operational monitoring state
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    // Live permission monitoring states
    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _notificationAccessGranted = MutableStateFlow(false)
    val notificationAccessGranted: StateFlow<Boolean> = _notificationAccessGranted.asStateFlow()

    private val _accessibilityAccessGranted = MutableStateFlow(false)
    val accessibilityAccessGranted: StateFlow<Boolean> = _accessibilityAccessGranted.asStateFlow()

    private val _batteryOptimizationIgnored = MutableStateFlow(false)
    val batteryOptimizationIgnored: StateFlow<Boolean> = _batteryOptimizationIgnored.asStateFlow()

    // Theme state (Dark/Light Mode)
    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    // Gmail Sync States
    private val _gmailAccount = MutableStateFlow("")
    val gmailAccount: StateFlow<String> = _gmailAccount.asStateFlow()

    private val _googleSyncEnabled = MutableStateFlow(false)
    val googleSyncEnabled: StateFlow<Boolean> = _googleSyncEnabled.asStateFlow()

    private val _autoExportCrm = MutableStateFlow(false)
    val autoExportCrm: StateFlow<Boolean> = _autoExportCrm.asStateFlow()

    init {
        checkAllPermissionStates()
        // Check stored theme preference
        viewModelScope.launch {
            val setting = db.settingDao().getSetting("app_theme")
            if (setting != null) {
                _isDarkMode.value = (setting.value == "dark")
            }

            val emailSetting = db.settingDao().getSetting("gmail_account")
            if (emailSetting != null) {
                _gmailAccount.value = emailSetting.value
            }

            val syncSetting = db.settingDao().getSetting("google_sync_enabled")
            if (syncSetting != null) {
                _googleSyncEnabled.value = (syncSetting.value == "1")
            }

            val autoExpSetting = db.settingDao().getSetting("auto_export_crm")
            if (autoExpSetting != null) {
                _autoExportCrm.value = (autoExpSetting.value == "1")
            }
        }
    }

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setLogFilterSource(source: String) {
        _logFilterSource.value = source
    }

    /**
     * Checks all required permissions on demand.
     */
    fun checkAllPermissionStates() {
        val context = getApplication<Application>()
        val allPerms = PermissionManager.hasAllRuntimePermissions(context)
        val hasNotifPerm = PermissionManager.hasNotificationPermission(context)
        _permissionsGranted.value = allPerms
        _notificationAccessGranted.value = PermissionManager.isNotificationListenerEnabled(context)
        _accessibilityAccessGranted.value = PermissionManager.isAccessibilityServiceEnabled(context)
        _batteryOptimizationIgnored.value = PermissionManager.isIgnoringBatteryOptimization(context)

        val userPaused = isMonitoringManuallyPaused(context)
        val inUnlockedApp = _currentScreen.value != Screen.SPLASH && _currentScreen.value != Screen.SUBSCRIPTION_GATE
        if (inUnlockedApp && !userPaused && (hasNotifPerm || allPerms) && !AutoSaveForegroundService.isRunning) {
            AutoSaveForegroundService.start(context)
        }
        _isServiceRunning.value = AutoSaveForegroundService.isRunning ||
            (!userPaused && AutoSaveForegroundService.isMonitoringEnabled(context) && (hasNotifPerm || allPerms))
    }

    /**
     * Controls background service lifecycle.
     */
    fun toggleService() {
        val context = getApplication<Application>()
        if (_isServiceRunning.value) {
            setMonitoringManuallyPaused(context, true)
            AutoSaveForegroundService.stop(context)
            _isServiceRunning.value = false
        } else {
            setMonitoringManuallyPaused(context, false)
            AutoSaveForegroundService.start(context)
            _isServiceRunning.value = true
        }
    }

    fun tryStartMonitoringAutomatically() {
        val context = getApplication<Application>()
        if (!isMonitoringManuallyPaused(context) &&
            (PermissionManager.hasNotificationPermission(context) || PermissionManager.hasAllRuntimePermissions(context))
        ) {
            AutoSaveForegroundService.start(context)
            _isServiceRunning.value = true
        }
    }

    private fun isMonitoringManuallyPaused(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences("service_monitoring_prefs", Context.MODE_PRIVATE)
            .getBoolean("user_manually_paused", false)
    }

    private fun setMonitoringManuallyPaused(context: Context, paused: Boolean) {
        context.applicationContext
            .getSharedPreferences("service_monitoring_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("user_manually_paused", paused)
            .apply()
    }

    fun toggleTheme() {
        viewModelScope.launch {
            val nextVal = !_isDarkMode.value
            _isDarkMode.value = nextVal
            db.settingDao().insertSetting(SettingEntity("app_theme", if (nextVal) "dark" else "light"))
        }
    }

    // Actions
    fun updateContactName(contact: Contact, newName: String) {
        viewModelScope.launch {
            repo.updateContactName(getApplication(), contact, newName)
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            repo.deleteContact(getApplication(), contact)
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repo.clearLogs()
        }
    }

    fun exportContacts() {
        val list = contacts.value
        ExportManager.exportContactsToCsv(getApplication(), list)
    }

    // Gmail & Sync Settings actions
    fun updateGmailAccount(email: String) {
        viewModelScope.launch {
            _gmailAccount.value = email
            db.settingDao().insertSetting(SettingEntity("gmail_account", email))
        }
    }

    fun toggleGoogleSync() {
        viewModelScope.launch {
            val nextVal = !_googleSyncEnabled.value
            _googleSyncEnabled.value = nextVal
            db.settingDao().insertSetting(SettingEntity("google_sync_enabled", if (nextVal) "1" else "0"))
        }
    }

    fun toggleAutoExportCrm() {
        viewModelScope.launch {
            val nextVal = !_autoExportCrm.value
            _autoExportCrm.value = nextVal
            db.settingDao().insertSetting(SettingEntity("auto_export_crm", if (nextVal) "1" else "0"))
        }
    }

    fun importFromGoogleContacts(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val subscriptionRepo = gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository(db)
            val account = _gmailAccount.value
            val imported = gamalprojects.autosavecontactscrm.akramalahmadi.managers.ContactsManager.importFromGoogleContacts(getApplication(), if (account.isBlank()) null else account)
            var count = 0
            ContactsRepository.saveMutex.withLock {
                for ((name, phone) in imported) {
                    if (!subscriptionRepo.canSaveNewContact()) {
                        break
                    }
                    val normalized = gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils.normalize(phone)
                    if (normalized.length < 7) continue
                    val existing = db.contactDao().getContactByPhone(normalized)
                        ?: db.contactDao().getAllContactsSync().firstOrNull {
                            gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils.areNumbersEqual(it.phone, phone)
                        }
                    if (existing == null) {
                        val nextId = db.settingDao().getSetting("next_customer_id")?.value?.toIntOrNull() ?: 1
                        val clientName = "عميل $nextId"
                        val newContact = Contact(
                            name = clientName,
                            phone = normalized,
                            originalPhone = phone,
                            source = "واتساب",
                            isSyncedToPhone = true
                        )
                        val rowId = db.contactDao().insertContactIfAbsent(newContact)
                        if (rowId <= 0L) continue
                        db.settingDao().insertSetting(SettingEntity("next_customer_id", (nextId + 1).toString()))

                        val alreadyLogged = db.logEntryDao().getAllLogsSync().any { entry ->
                            entry.status == "تم الاستيراد" &&
                                gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils.areNumbersEqual(entry.phone, phone)
                        }
                        if (!alreadyLogged) {
                            db.logEntryDao().insertLog(
                                LogEntry(
                                    phone = phone,
                                    source = "استيراد من جمايل",
                                    status = "تم الاستيراد",
                                    details = "تم استيراد جهة الاتصال: $name وحفظه كعميل: $clientName"
                                )
                            )
                        }
                        count++
                        subscriptionRepo.syncSingleClientToDatabase(getApplication(), newContact.copy(id = rowId.toInt()), isDeleted = false)
                        val canContinue = subscriptionRepo.recordContactSaved(getApplication())
                        if (!canContinue) break
                    }
                }
            }
            onResult(count)
        }
    }

    fun exportToGoogleContacts(onResult: (Int) -> Unit) {
        viewModelScope.launch {
            val subscriptionRepo = gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository(db)
            val account = _gmailAccount.value
            if (account.isBlank()) {
                onResult(-1)
                return@launch
            }
            var count = 0
            val list = contacts.value
            list.forEach { contact ->
                if (!contact.isSyncedToPhone) {
                    val success = gamalprojects.autosavecontactscrm.akramalahmadi.managers.ContactsManager.saveToGoogleContacts(
                        getApplication(),
                        contact.name,
                        contact.originalPhone,
                        account
                    )
                    if (success) {
                        val updatedContact = contact.copy(isSyncedToPhone = true)
                        db.contactDao().updateContact(updatedContact)
                        subscriptionRepo.syncSingleClientToDatabase(getApplication(), updatedContact, isDeleted = false)
                        val alreadyLogged = db.logEntryDao().getAllLogsSync().any { entry ->
                            entry.status == "تصدير ناجح" &&
                                gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils.areNumbersEqual(entry.phone, contact.originalPhone)
                        }
                        if (!alreadyLogged) {
                            db.logEntryDao().insertLog(
                                LogEntry(
                                    phone = contact.originalPhone,
                                    source = "تصدير إلى جمايل",
                                    status = "تصدير ناجح",
                                    details = "تم تصدير العميل ${contact.name} إلى حساب جوجل: $account"
                                )
                            )
                        }
                        count++
                    }
                }
            }
            onResult(count)
        }
    }
    fun stopMonitoring() {
        AutoSaveForegroundService.stop(getApplication())
        _isServiceRunning.value = false
    }
}

enum class Screen {
    SPLASH,
    SUBSCRIPTION_GATE,
    DASHBOARD,
    SAVED_CONTACTS,
    LOGS,
    EXPORT,
    PERMISSIONS,
    SETTINGS
}

internal fun matchesLogFilter(log: LogEntry, filter: String): Boolean {
    if (filter == "الكل") return true
    val src = log.source.trim()
    return when (filter) {
        "واتساب" -> src == "واتساب" ||
            src == "واتساب الأعمال" ||
            src.contains("واتساب", ignoreCase = true) ||
            src.contains("whatsapp", ignoreCase = true) ||
            src == "خدمة إمكانية الوصول"
        "مكالمة واردة" -> src == "مكالمة واردة" || src == "مكالمة صادرة" || src.contains("مكالمة")
        "تعديل يدوي" -> src == "تعديل يدوي" || src == "حذف يدوي"
        else -> src == filter
    }
}
