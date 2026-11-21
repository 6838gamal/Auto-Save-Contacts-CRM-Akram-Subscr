package gamalprojects.autosavecontactscrm.akramalahmadi.presentation

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActivationResult
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ActiveSubscriptionInfo
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ConnectionDetailsReport
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val repo = SubscriptionRepository(db)
    private val syncMutex = Mutex()

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

    private val _connectionReport = MutableStateFlow<ConnectionDetailsReport?>(null)
    val connectionReport: StateFlow<ConnectionDetailsReport?> = _connectionReport.asStateFlow()

    private val _showConnectionDialog = MutableStateFlow(false)
    val showConnectionDialog: StateFlow<Boolean> = _showConnectionDialog.asStateFlow()

    private var hasShownInitialPopup = false

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

    private val _nowMillis = MutableStateFlow(System.currentTimeMillis())
    val nowMillis: StateFlow<Long> = _nowMillis.asStateFlow()

    private val _initialSyncCompleted = MutableStateFlow(false)

    val isBackendConfigured: Boolean = repo.isDatabaseConfigured()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        refreshIdentity()
        // Immediately verify Server API connection, build connection report popup, and send telemetry + client sync on app open
        syncConnectionAndUserData(showPopup = true)
        registerNetworkReconnectCallback()

        // Reactively observe free tier counter (0..100 contacts strictly)
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
                }
            }
        }

        // Background reconnect loop: keeps retrying until Server API connection is verified
        viewModelScope.launch {
            while (isActive) {
                delay(10_000L)
                if (repo.isDatabaseConfigured() && _dbConnectionStatus.value !is DbConnectionStatus.Connected) {
                    performConnectionAndClientSync(showCheckingState = false)
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

    private fun registerNetworkReconnectCallback() {
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (repo.isDatabaseConfigured()) {
                        viewModelScope.launch {
                            if (_dbConnectionStatus.value !is DbConnectionStatus.Connected) {
                                performConnectionAndClientSync(showCheckingState = false)
                            } else {
                                repo.syncAllClientsToDatabase(getApplication())
                            }
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, callback)
            connectivityManager = cm
            networkCallback = callback
        } catch (_: Throwable) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cm = connectivityManager
            val cb = networkCallback
            if (cm != null && cb != null) {
                cm.unregisterNetworkCallback(cb)
            }
        } catch (_: Throwable) {
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

    fun openConnectionDetailsDialog() {
        viewModelScope.launch {
            if (_connectionReport.value == null) {
                _connectionReport.value = repo.buildCurrentLocalReport(getApplication(), _dbConnectionStatus.value)
            }
            _showConnectionDialog.value = true
        }
    }

    fun dismissConnectionDetailsDialog() {
        _showConnectionDialog.value = false
    }

    fun syncConnectionAndUserData(
        showPopup: Boolean = false,
        onAutoUnlocked: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            performConnectionAndClientSync(
                showCheckingState = true,
                triggerPopup = showPopup,
                onAutoUnlocked = onAutoUnlocked
            )
        }
    }

    private suspend fun performConnectionAndClientSync(
        showCheckingState: Boolean,
        triggerPopup: Boolean = false,
        onAutoUnlocked: (() -> Unit)? = null
    ) {
        syncMutex.withLock {
            val previousStatus = _dbConnectionStatus.value
            if (showCheckingState) {
                _isRefreshingIdentity.value = true
                _dbConnectionStatus.value = DbConnectionStatus.Checking
            }
            try {
                val outcome = repo.verifyCurrentSession(getApplication())
                _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                _activeSubscription.value = outcome.activeInfo
                _freeAccessState.value = outcome.freeAccessState
                _dbConnectionStatus.value = outcome.dbStatus
                if (outcome.connectionReport != null) {
                    _connectionReport.value = outcome.connectionReport
                }
                _isExplicitlyLocked.value =
                    !outcome.isAllowed && outcome.activeInfo == null && !outcome.freeAccessState.isExhausted
                if (outcome.activeInfo != null) {
                    _gateError.value = null
                    _isConnectionError.value = false
                    onAutoUnlocked?.invoke()
                }
                val becameConnected =
                    previousStatus !is DbConnectionStatus.Connected && outcome.dbStatus is DbConnectionStatus.Connected
                if (triggerPopup || !hasShownInitialPopup || becameConnected) {
                    hasShownInitialPopup = true
                    if (_connectionReport.value != null) {
                        _showConnectionDialog.value = true
                    }
                }
            } catch (_: Throwable) {
                if (repo.isDatabaseConfigured() && _dbConnectionStatus.value !is DbConnectionStatus.Connected) {
                    val disconnected = DbConnectionStatus.Disconnected(SubscriptionRepository.MSG_CONTACT_OWNER_OFFLINE)
                    _dbConnectionStatus.value = disconnected
                    _connectionReport.value = repo.buildCurrentLocalReport(getApplication(), disconnected)
                }
                if (triggerPopup || !hasShownInitialPopup) {
                    hasShownInitialPopup = true
                    if (_connectionReport.value != null) {
                        _showConnectionDialog.value = true
                    }
                }
            } finally {
                _initialSyncCompleted.value = true
                if (showCheckingState) {
                    _isRefreshingIdentity.value = false
                }
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
            try {
                // Initial sync already started in init {}; wait briefly (max 600ms) so Splash never hangs if offline/stalled.
                if (!_initialSyncCompleted.value) {
                    withTimeoutOrNull(600L) {
                        _initialSyncCompleted.first { it }
                    }
                }
                val quick = repo.checkLocalAccessQuick(getApplication())
                _activeSubscription.value = quick.activeInfo
                _freeAccessState.value = quick.freeAccessState
                _isExplicitlyLocked.value =
                    !quick.isAllowed && quick.activeInfo == null && !quick.freeAccessState.isExhausted
                if (!quick.isAllowed) {
                    _gateError.value = quick.errorMessage
                }
                onResult(quick.isAllowed)
            } catch (_: Throwable) {
                onResult(true)
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
                            _dbConnectionStatus.value = DbConnectionStatus.Connected("متصل")
                        }
                        repo.syncAllClientsToDatabase(getApplication())
                        onUnlocked()
                    }
                    is ActivationResult.Failure -> {
                        _identitySnapshot.value = UserIdentityManager.getCachedSnapshot(getApplication())
                        _gateError.value = result.reason
                        _isConnectionError.value = result.isConnectionError
                        if (result.isConnectionError && repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Disconnected(result.reason)
                        } else if (!result.isConnectionError && repo.isDatabaseConfigured()) {
                            _dbConnectionStatus.value = DbConnectionStatus.Connected("متصل")
                        }
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
                        _dbConnectionStatus.value = DbConnectionStatus.Connected("متصل")
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
            onLocked()
        }
    }
}
