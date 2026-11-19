package gamalprojects.autosavecontactscrm.akramalahmadi.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActivationResult
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActiveSubscriptionInfo
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.DbConnectionStatus
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.FreeAccessState
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ReviewRequestResult
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repo = SubscriptionRepository(db)

    private val _identitySnapshot = MutableStateFlow(UserIdentityManager.getCachedSnapshot(application))
    val identitySnapshot: StateFlow<UserNetworkSnapshot> = _identitySnapshot.asStateFlow()

    private val _isRefreshingIdentity = MutableStateFlow(false)
    val isRefreshingIdentity: StateFlow<Boolean> = _isRefreshingIdentity.asStateFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    private val _isSubmittingReview = MutableStateFlow(false)
    val isSubmittingReview: StateFlow<Boolean> = _isSubmittingReview.asStateFlow()

    private val _gateError = MutableStateFlow<String?>(null)
    val gateError: StateFlow<String?> = _gateError.asStateFlow()

    private val _reviewNotice = MutableStateFlow<String?>(null)
    val reviewNotice: StateFlow<String?> = _reviewNotice.asStateFlow()

    private val _isConnectionError = MutableStateFlow(false)
    val isConnectionError: StateFlow<Boolean> = _isConnectionError.asStateFlow()

    private val _dbConnectionStatus = MutableStateFlow<DbConnectionStatus>(DbConnectionStatus.Checking)
    val dbConnectionStatus: StateFlow<DbConnectionStatus> = _dbConnectionStatus.asStateFlow()

    private val _activeSubscription = MutableStateFlow<ActiveSubscriptionInfo?>(null)
    val activeSubscription: StateFlow<ActiveSubscriptionInfo?> = _activeSubscription.asStateFlow()

    private val _freeAccessState = MutableStateFlow(FreeAccessState())
    val freeAccessState: StateFlow<FreeAccessState> = _freeAccessState.asStateFlow()

    private val _isExplicitlyLocked = MutableStateFlow(false)

    val hasAppAccess: StateFlow<Boolean> = combine(
        _activeSubscription,
        _freeAccessState,
        _isExplicitlyLocked
    ) { active, free, locked ->
        active != null || (!free.isExhausted && !locked)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _isDemoTrialExhausted = MutableStateFlow(false)
    val isDemoTrialExhausted: StateFlow<Boolean> = _isDemoTrialExhausted.asStateFlow()

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    val isBackendConfigured: Boolean = repo.isDatabaseConfigured()

    init {
        refreshIdentity()
        refreshTrialState()

        // Reactively observe free tier counter (0..100 contacts)
        viewModelScope.launch {
            repo.observeFreeAccessState().collect { state ->
                _freeAccessState.value = state
            }
        }

        // Reactively observe explicit lock flag
        viewModelScope.launch {
            repo.observeSubscriptionLockedFlag().collect { locked ->
                _isExplicitlyLocked.value = locked
            }
        }

        // Reactively observe active subscription & quota changes
        viewModelScope.launch {
            repo.observeActiveSubscription().collect { liveInfo ->
                if (_activeSubscription.value != null && liveInfo == null) {
                    _activeSubscription.value = null
                    refreshTrialState()
                } else if (liveInfo != null) {
                    _activeSubscription.value = liveInfo
                }
            }
        }

        viewModelScope.launch {
            repo.observeLockReason().collect { reason ->
                if (!reason.isNullOrBlank()) {
                    _gateError.value = reason
                    _isConnectionError.value = false
                    refreshTrialState()
                }
            }
        }

        // Live countdown & automatic expiration guard
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                _nowMillis.value = now
                val current = _activeSubscription.value
                if (current != null && current.isExpired(now)) {
                    _activeSubscription.value = null
                    _isExplicitlyLocked.value = true
                    _isConnectionError.value = false
                    _gateError.value =
                        "انتهت مدة اشتراكك الحالية. يرجى إدخال كود جديد أو التواصل مع الإدارة على الرقم ${SubscriptionRepository.ADMIN_PHONE_DISPLAY}"
                    repo.lockSession(explicitLock = true)
                }
            }
        }
    }

    private fun refreshTrialState() {
        viewModelScope.launch {
            _isDemoTrialExhausted.value = repo.isDemoTrialExhausted()
        }
    }

    fun refreshIdentity() {
        viewModelScope.launch {
            _isRefreshingIdentity.value = true
            try {
                _identitySnapshot.value = repo.captureIdentity(getApplication())
            } finally {
                _isRefreshingIdentity.value = false
            }
        }
    }

    fun syncConnectionAndUserData(onAutoUnlocked: (() -> Unit)? = null) {
        viewModelScope.launch {
            _isRefreshingIdentity.value = true
            _dbConnectionStatus.value = DbConnectionStatus.Checking
            try {
                val outcome = repo.verifyCurrentSession(getApplication())
                _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                _activeSubscription.value = outcome.activeInfo
                _freeAccessState.value = outcome.freeAccessState
                _dbConnectionStatus.value = outcome.dbStatus
                _isExplicitlyLocked.value = !outcome.isAllowed && outcome.activeInfo == null && !outcome.freeAccessState.isExhausted
                if (outcome.activeInfo != null) {
                    _gateError.value = null
                    _isConnectionError.value = false
                    onAutoUnlocked?.invoke()
                }
            } finally {
                _isRefreshingIdentity.value = false
            }
        }
    }

    fun clearGateError() {
        _gateError.value = null
        _isConnectionError.value = false
        _reviewNotice.value = null
    }

    fun verifySessionOnLaunch(onResult: (isAllowed: Boolean) -> Unit) {
        viewModelScope.launch {
            _isVerifying.value = true
            _dbConnectionStatus.value = DbConnectionStatus.Checking
            try {
                val outcome = repo.verifyCurrentSession(getApplication())
                _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                _activeSubscription.value = outcome.activeInfo
                _freeAccessState.value = outcome.freeAccessState
                _dbConnectionStatus.value = outcome.dbStatus
                _isExplicitlyLocked.value = !outcome.isAllowed && outcome.activeInfo == null && !outcome.freeAccessState.isExhausted
                _gateError.value = outcome.errorMessage
                _isConnectionError.value = outcome.isConnectionError
                _isDemoTrialExhausted.value = repo.isDemoTrialExhausted()
                onResult(outcome.isAllowed)
            } finally {
                _isVerifying.value = false
            }
        }
    }

    fun submitSubscriptionCode(code: String, onUnlocked: () -> Unit) {
        viewModelScope.launch {
            _isVerifying.value = true
            _gateError.value = null
            _reviewNotice.value = null
            _isConnectionError.value = false
            try {
                when (val result = repo.activateSubscriptionCode(getApplication(), code)) {
                    is ActivationResult.Success -> {
                        _identitySnapshot.value = result.snapshot
                        _activeSubscription.value = result.info
                        _isExplicitlyLocked.value = false
                        _gateError.value = null
                        _isConnectionError.value = false
                        if (repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Connected("تم التحقق من قاعدة البيانات وتفعيل الاشتراك")
                        }
                        _isDemoTrialExhausted.value = repo.isDemoTrialExhausted()
                        onUnlocked()
                    }
                    is ActivationResult.Failure -> {
                        _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                        _gateError.value = result.reason
                        _isConnectionError.value = result.isConnectionError
                        if (result.isConnectionError && repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Disconnected(result.reason)
                        } else if (!result.isConnectionError && repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Connected("متصل بقاعدة البيانات PostgreSQL")
                        }
                        _isDemoTrialExhausted.value = repo.isDemoTrialExhausted()
                    }
                }
            } finally {
                _isVerifying.value = false
            }
        }
    }

    fun requestCodeReview(code: String) {
        viewModelScope.launch {
            _isSubmittingReview.value = true
            _gateError.value = null
            _reviewNotice.value = null
            _isConnectionError.value = false
            try {
                when (val result = repo.submitReviewRequest(getApplication(), code)) {
                    is ReviewRequestResult.Success -> {
                        _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                        _reviewNotice.value = result.message
                        _dbConnectionStatus.value = DbConnectionStatus.Connected("تم إرسال طلب المراجعة إلى قاعدة البيانات")
                    }
                    is ReviewRequestResult.Failure -> {
                        _gateError.value = result.reason
                        _isConnectionError.value = result.isConnectionError
                        if (result.isConnectionError && repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Disconnected(result.reason)
                        }
                    }
                }
            } finally {
                _isSubmittingReview.value = false
            }
        }
    }

    fun lockAndSwitchCode(onLocked: () -> Unit) {
        viewModelScope.launch {
            repo.lockSession(explicitLock = true)
            _activeSubscription.value = null
            _isExplicitlyLocked.value = true
            _gateError.value = null
            _reviewNotice.value = null
            _isConnectionError.value = false
            _isDemoTrialExhausted.value = repo.isDemoTrialExhausted()
            onLocked()
        }
    }
}
