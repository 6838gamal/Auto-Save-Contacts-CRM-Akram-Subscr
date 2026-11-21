package gamalprojects.autosavecontactscrm.akramalahmadi.repositories

import android.content.Context
import gamalprojects.autosavecontactscrm.akramalahmadi.BuildConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.Contact
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SettingEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SubscriptionCodeEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.UserDeviceProfileEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ApiDebugLogger
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncBatchRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncItem
import gamalprojects.autosavecontactscrm.akramalahmadi.network.NetworkExchangeCapture
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ServerEndpointConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionActivateRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiResponse
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiService
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionConnectRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionReviewRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionUsageRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionVerifyRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed class DbConnectionStatus {
    data object Checking : DbConnectionStatus()
    data class Connected(val message: String, val syncedAt: Long = System.currentTimeMillis()) : DbConnectionStatus()
    data class Disconnected(val reason: String) : DbConnectionStatus()
    data class NotConfigured(val message: String) : DbConnectionStatus()
}

sealed class ActivationResult {
    data class Success(
        val info: ActiveSubscriptionInfo,
        val snapshot: UserNetworkSnapshot,
        val message: String
    ) : ActivationResult()

    data class Failure(
        val reason: String,
        val isConnectionError: Boolean = false
    ) : ActivationResult()
}

sealed class ReviewRequestResult {
    data class Success(val message: String, val requestId: String? = null) : ReviewRequestResult()
    data class Failure(val reason: String, val isConnectionError: Boolean = false) : ReviewRequestResult()
}

data class ActiveSubscriptionInfo(
    val code: String,
    val label: String,
    val isLifetime: Boolean,
    val isSubscribed: Boolean,
    val isActive: Boolean,
    val isTrial: Boolean,
    val maxContacts: Int?,
    val usedContacts: Int,
    val activatedAt: Long,
    val expiresAt: Long?,
    val boundUserId: String,
    val boundIpAddress: String,
    val deviceModel: String
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAt ?: return false
        return now >= exp
    }

    fun isQuotaReached(): Boolean {
        val max = maxContacts ?: return false
        if (max <= 0) return false
        return usedContacts >= max
    }

    fun remainingContacts(): Int? {
        val max = maxContacts ?: return null
        if (max <= 0) return null
        return (max - usedContacts).coerceAtLeast(0)
    }
}

data class FreeAccessState(
    val usedContacts: Int = 0,
    val limit: Int = SubscriptionRepository.FREE_CONTACTS_LIMIT,
    val isExhausted: Boolean = false
) {
    val remainingContacts: Int get() = (limit - usedContacts).coerceAtLeast(0)
}

data class ConnectionDetailsReport(
    val succeeded: Boolean,
    val statusHeadline: String,
    val statusDetail: String,
    val connectedWithTarget: String,
    val authMethodSummary: String,
    val httpStatusCode: Int?,
    val latencyMs: Long?,
    val errorDetail: String?,
    val networkType: String,
    val deviceId: String,
    val userId: String,
    val publicIp: String,
    val localIp: String,
    val planType: String,
    val freeContactsUsed: Int,
    val freeContactsLimit: Int,
    val freeTierExhausted: Boolean,
    val isSubscribed: Boolean,
    val isSubscriptionActive: Boolean,
    val activeCode: String?,
    val serverStatusText: String,
    val serverMessageText: String,
    val rawServerResponse: String,
    val clientsSyncSucceeded: Boolean?,
    val clientsSyncResponse: String?,
    val localContactsCount: Int,
    val syncedToPhoneCount: Int,
    val localLogsCount: Int,
    val pendingDeletesCount: Int,
    val sampleContacts: List<Contact>,
    val capturedAt: Long = System.currentTimeMillis()
)

data class SessionVerifyOutcome(
    val activeInfo: ActiveSubscriptionInfo?,
    val freeAccessState: FreeAccessState,
    val isAllowed: Boolean,
    val dbStatus: DbConnectionStatus,
    val errorMessage: String?,
    val isConnectionError: Boolean,
    val connectionReport: ConnectionDetailsReport? = null
)

data class LocalAccessDecision(
    val activeInfo: ActiveSubscriptionInfo?,
    val freeAccessState: FreeAccessState,
    val isAllowed: Boolean,
    val errorMessage: String?
)

class SubscriptionRepository(
    private val db: AppDatabase,
    private val apiServiceOverride: SubscriptionApiService? = null
) {

    companion object {
        private const val KEY_ACTIVE_CODE = "active_subscription_code"
        private const val KEY_LOCK_REASON = "subscription_lock_reason"
        private const val KEY_SUBSCRIPTION_LOCKED = "subscription_explicitly_locked"
        private const val KEY_FREE_USED = "free_contacts_used"
        private const val KEY_FREE_EXHAUSTED = "free_tier_exhausted"
        private const val KEY_PENDING_DELETED_CLIENTS = "pending_deleted_clients_json"
        private const val KEY_PENDING_TELEMETRY_SYNC = "pending_telemetry_sync"

        const val FREE_CONTACTS_LIMIT = 100
        const val ADMIN_PHONE_DISPLAY = "+967 772 920 748"
        const val ADMIN_PHONE_DIGITS = "967772920748"

        const val MSG_CONTACT_OWNER_OFFLINE =
            "يرجى التواصل مع الإدارة لمراجعة الأمر والتحقق من الاتصال على الرقم $ADMIN_PHONE_DISPLAY"
        const val MSG_FREE_LIMIT_REACHED =
            "لقد وصلت إلى الحد المجاني المسموح ($FREE_CONTACTS_LIMIT جهة اتصال). يرجى إدخال كود اشتراك صحيح للمتابعة أو التواصل مع الإدارة على الرقم $ADMIN_PHONE_DISPLAY"
        const val MSG_DB_NOT_CONFIGURED =
            "يرجى التواصل مع الإدارة لمراجعة الأمر وتفعيل الاشتراك على الرقم $ADMIN_PHONE_DISPLAY"
    }

    private val codeDao = db.subscriptionCodeDao()
    private val profileDao = db.userDeviceProfileDao()
    private val settingDao = db.settingDao()
    private val contactDao = db.contactDao()
    private val logDao = db.logEntryDao()

    fun isDatabaseConfigured(): Boolean = resolveApiService() != null

    private fun resolveApiService(): SubscriptionApiService? {
        return apiServiceOverride ?: SubscriptionApiService.create(BuildConfig.DATABASE_URL)
    }

    private fun resolveAuthHeaders(): Pair<String?, String?> {
        val key = SubscriptionApiService.extractApiKey(BuildConfig.DATABASE_URL) ?: return null to null
        return "Bearer $key" to key
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActiveSubscription(): Flow<ActiveSubscriptionInfo?> {
        return settingDao.getSettingFlow(KEY_ACTIVE_CODE).flatMapLatest { setting ->
            val activeCode = setting?.value?.trim()?.uppercase().orEmpty()
            if (activeCode.isBlank()) {
                flowOf(null)
            } else {
                codeDao.observeCode(activeCode).map { entity ->
                    if (entity == null || !entity.isSubscribed || !entity.isActive) {
                        null
                    } else if (entity.maxContacts != null && entity.maxContacts > 0 && entity.usedContacts >= entity.maxContacts) {
                        null
                    } else {
                        entity.toActiveInfo()
                    }
                }
            }
        }
    }

    fun observeFreeAccessState(): Flow<FreeAccessState> {
        return combine(
            settingDao.getSettingFlow(KEY_FREE_USED),
            settingDao.getSettingFlow(KEY_FREE_EXHAUSTED),
            contactDao.getContactsCount()
        ) { usedSetting, exhaustedSetting, dbCount ->
            val storedUsed = usedSetting?.value?.toIntOrNull() ?: 0
            val effectiveUsed = maxOf(storedUsed, dbCount)
            val exhausted = (exhaustedSetting?.value == "1") || (effectiveUsed >= FREE_CONTACTS_LIMIT)
            FreeAccessState(
                usedContacts = effectiveUsed,
                limit = FREE_CONTACTS_LIMIT,
                isExhausted = exhausted
            )
        }
    }

    fun observeSubscriptionLockedFlag(): Flow<Boolean> {
        return settingDao.getSettingFlow(KEY_SUBSCRIPTION_LOCKED).map { it?.value == "1" }
    }

    suspend fun getFreeAccessStateSync(): FreeAccessState = withContext(Dispatchers.IO) {
        val storedUsed = settingDao.getSetting(KEY_FREE_USED)?.value?.toIntOrNull() ?: 0
        val dbCount = contactDao.getContactsCountSync()
        val effectiveUsed = maxOf(storedUsed, dbCount)
        val exhaustedFlag = settingDao.getSetting(KEY_FREE_EXHAUSTED)?.value == "1"
        val exhausted = exhaustedFlag || effectiveUsed >= FREE_CONTACTS_LIMIT
        if (exhausted && !exhaustedFlag) {
            settingDao.insertSetting(SettingEntity(KEY_FREE_USED, effectiveUsed.toString()))
            settingDao.insertSetting(SettingEntity(KEY_FREE_EXHAUSTED, "1"))
        }
        FreeAccessState(
            usedContacts = effectiveUsed,
            limit = FREE_CONTACTS_LIMIT,
            isExhausted = exhausted
        )
    }

    fun observeLockReason(): Flow<String?> {
        return settingDao.getSettingFlow(KEY_LOCK_REASON).map { setting ->
            setting?.value?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun clearLockReason() = withContext(Dispatchers.IO) {
        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
    }

    suspend fun captureIdentity(context: Context): UserNetworkSnapshot = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        upsertUserProfile(snapshot, activeCode = null)
        snapshot
    }

    /**
     * Instant local Room check (< 10ms, zero network) used so the splash screen
     * never hangs when offline or when the remote server is waking up from cold start.
     */
    suspend fun checkLocalAccessQuick(context: Context): LocalAccessDecision = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val freeState = getFreeAccessStateSync()
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        val explicitlyLocked = settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1"
        val now = System.currentTimeMillis()

        val cached = activeCodeKey?.takeIf { it.isNotBlank() }?.let { codeDao.getCode(it) }
        if (cached != null) {
            if (!cached.isLifetime && cached.expiresAt != null && now >= cached.expiresAt) {
                val msg = "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد أو التواصل مع الإدارة على الرقم $ADMIN_PHONE_DISPLAY"
                settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
                return@withContext LocalAccessDecision(null, freeState, isAllowed = false, errorMessage = msg)
            }
            if (cached.maxContacts != null && cached.maxContacts > 0 && cached.usedContacts >= cached.maxContacts) {
                val msg = "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
                settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
                return@withContext LocalAccessDecision(null, freeState, isAllowed = false, errorMessage = msg)
            }
            if (cached.isActive && cached.isSubscribed &&
                (cached.boundUserId.isNullOrBlank() || cached.boundUserId == snapshot.userId)
            ) {
                return@withContext LocalAccessDecision(
                    activeInfo = cached.toActiveInfo(snapshot),
                    freeAccessState = freeState,
                    isAllowed = true,
                    errorMessage = null
                )
            }
        }

        val allowFree = !freeState.isExhausted && !explicitlyLocked
        val reason = if (allowFree) {
            null
        } else {
            settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                ?: if (freeState.isExhausted) MSG_FREE_LIMIT_REACHED else null
        }
        LocalAccessDecision(
            activeInfo = null,
            freeAccessState = freeState,
            isAllowed = allowFree,
            errorMessage = reason
        )
    }

    /**
     * Connects to the remote Server API (via DATABASE_URL) using the immutable device ID:
     * 1. Captures stable device identity & public IP.
     * 2. Sends device check/registration + free-plan or subscription telemetry immediately.
     * 3. Validates active subscription or checks if the admin approved a subscription on the server.
     * 4. Replays pending client updates/deletions whenever the server responds 2xx.
     */
    suspend fun verifyCurrentSession(context: Context): SessionVerifyOutcome = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        val freeState = getFreeAccessStateSync()
        val totalContacts = contactDao.getContactsCountSync()
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        val explicitlyLocked = settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1"

        upsertUserProfile(snapshot, activeCode = activeCodeKey?.takeIf { it.isNotBlank() })

        val cached = activeCodeKey?.takeIf { it.isNotBlank() }?.let { codeDao.getCode(it) }
        val now = System.currentTimeMillis()

        // Local expiration guard
        if (cached != null && !cached.isLifetime && cached.expiresAt != null && now >= cached.expiresAt) {
            val msg = "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد أو التواصل مع الإدارة على الرقم $ADMIN_PHONE_DISPLAY"
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
            upsertUserProfile(snapshot, activeCode = null)
            val syncStatus = pingAndSyncUserOnly(snapshot, totalContacts, freeState, null)
            if (syncStatus is DbConnectionStatus.Connected) {
                syncAllClientsToDatabase(context)
            }
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = false,
                dbStatus = syncStatus,
                errorMessage = msg,
                isConnectionError = false
            )
        }

        // Local quota guard
        if (cached != null && cached.maxContacts != null && cached.maxContacts > 0 && cached.usedContacts >= cached.maxContacts) {
            val msg = "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
            upsertUserProfile(snapshot, activeCode = null)
            val syncStatus = pingAndSyncUserOnly(snapshot, totalContacts, freeState, null)
            if (syncStatus is DbConnectionStatus.Connected) {
                syncAllClientsToDatabase(context)
            }
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = false,
                dbStatus = syncStatus,
                errorMessage = msg,
                isConnectionError = false
            )
        }

        val api = resolveApiService()
        if (api != null) {
            val serverCapture = verifyViaServerApi(
                api = api,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = activeCodeKey,
                cachedCode = cached,
                explicitlyLocked = explicitlyLocked
            )
            val authExchange = SubscriptionApiService.lastNetworkExchange
            var clientsSyncOk: Boolean? = null
            var clientsSyncExchange: NetworkExchangeCapture? = null
            if (serverCapture.outcome.dbStatus is DbConnectionStatus.Connected) {
                clientsSyncOk = syncAllClientsToDatabase(context)
                clientsSyncExchange = SubscriptionApiService.lastNetworkExchange
            }
            val report = buildConnectionDetailsReport(
                snapshot = snapshot,
                outcome = serverCapture.outcome,
                apiResponse = serverCapture.apiResponse,
                explicitHttpCode = serverCapture.httpCode,
                explicitError = serverCapture.errorDetail,
                authExchange = authExchange,
                clientsSyncSucceeded = clientsSyncOk,
                clientsSyncExchange = clientsSyncExchange
            )
            return@withContext serverCapture.outcome.copy(connectionReport = report)
        }

        // Server not configured yet: allow free usage up to 100 or valid cached code, prompt to contact owner
        ApiDebugLogger.logWarn("REPO", "الخادم غير مربوط أو الرابط غير صالح — التشغيل في الوضع المحلي.")
        val configInvalidReason = ServerEndpointConfig.isPlaceholderOrInvalidScheme(BuildConfig.DATABASE_URL)
        val notConfiguredStatus = DbConnectionStatus.NotConfigured(MSG_DB_NOT_CONFIGURED)
        val rawOutcome = if (!activeCodeKey.isNullOrBlank()) {
            if (cached != null && cached.isActive && cached.isSubscribed &&
                (cached.boundUserId.isNullOrBlank() || cached.boundUserId == snapshot.userId)
            ) {
                SessionVerifyOutcome(
                    activeInfo = cached.toActiveInfo(snapshot),
                    freeAccessState = freeState,
                    isAllowed = true,
                    dbStatus = notConfiguredStatus,
                    errorMessage = null,
                    isConnectionError = false
                )
            } else {
                SessionVerifyOutcome(
                    activeInfo = null,
                    freeAccessState = freeState,
                    isAllowed = !freeState.isExhausted && !explicitlyLocked,
                    dbStatus = notConfiguredStatus,
                    errorMessage = if (!freeState.isExhausted && !explicitlyLocked) null else MSG_DB_NOT_CONFIGURED,
                    isConnectionError = true
                )
            }
        } else if (!freeState.isExhausted && !explicitlyLocked) {
            SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = true,
                dbStatus = notConfiguredStatus,
                errorMessage = null,
                isConnectionError = false
            )
        } else {
            val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                ?: if (freeState.isExhausted) MSG_FREE_LIMIT_REACHED else null
            SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = false,
                dbStatus = notConfiguredStatus,
                errorMessage = reason,
                isConnectionError = false
            )
        }
        val report = buildConnectionDetailsReport(
            snapshot = snapshot,
            outcome = rawOutcome,
            apiResponse = null,
            explicitHttpCode = null,
            explicitError = configInvalidReason ?: "رابط الخادم غير مضبوط في متغيرات البيئة",
            authExchange = null,
            clientsSyncSucceeded = null,
            clientsSyncExchange = null
        )
        return@withContext rawOutcome.copy(connectionReport = report)
    }

    private data class ServerVerifyInternalResult(
        val outcome: SessionVerifyOutcome,
        val apiResponse: SubscriptionApiResponse? = null,
        val httpCode: Int? = null,
        val errorDetail: String? = null
    )

    private suspend fun verifyViaServerApi(
        api: SubscriptionApiService,
        snapshot: UserNetworkSnapshot,
        freeState: FreeAccessState,
        totalContacts: Int,
        activeCodeKey: String?,
        cachedCode: SubscriptionCodeEntity?,
        explicitlyLocked: Boolean
    ): ServerVerifyInternalResult {
        val cachedUsedQuota = cachedCode?.usedContacts ?: 0
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)

        return try {
            val connectResp = try {
                api.connectAndSyncUser(
                    authorization = authHeader,
                    apiKey = apiKeyHeader,
                    request = SubscriptionConnectRequest(
                        userId = snapshot.userId,
                        deviceId = deviceId,
                        androidId = snapshot.androidId,
                        installUuid = snapshot.installUuid,
                        publicIp = snapshot.publicIp,
                        localIp = snapshot.localIp,
                        locationInfo = snapshot.locationInfo,
                        deviceModel = snapshot.deviceModel,
                        osVersion = snapshot.osVersion,
                        networkType = snapshot.networkType,
                        savedContactsCount = totalContacts,
                        freeContactsUsed = freeState.usedContacts,
                        freeContactsLimit = freeState.limit,
                        freeTierExhausted = freeState.isExhausted,
                        planType = if (!activeCodeKey.isNullOrBlank()) "SUBSCRIBED" else if (freeState.isExhausted) "FREE_EXHAUSTED" else "FREE",
                        activeCode = activeCodeKey
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (activeCodeKey.isNullOrBlank()) throw t
                null
            }

            val connectSucceeded = connectResp?.isSuccessful == true
            val connectBody = if (connectSucceeded) connectResp?.body() else null
            val connectErrText = if (!connectSucceeded && connectResp != null) {
                "HTTP ${connectResp.code()}: ${extractErrorMessage(connectResp.errorBody()?.string()) ?: connectResp.message()}"
            } else {
                null
            }

            // Reconcile server-stored free usage if the server recognizes this immutable device after reinstall
            val serverFreeUsed = connectBody?.freeContactsUsed ?: 0
            val reconciledUsed = maxOf(freeState.usedContacts, serverFreeUsed)
            val reconciledExhausted = freeState.isExhausted ||
                (connectBody?.freeTierExhausted == true) ||
                reconciledUsed >= FREE_CONTACTS_LIMIT

            if (reconciledUsed > freeState.usedContacts) {
                settingDao.insertSetting(SettingEntity(KEY_FREE_USED, reconciledUsed.toString()))
            }
            if (reconciledExhausted && !freeState.isExhausted) {
                settingDao.insertSetting(SettingEntity(KEY_FREE_EXHAUSTED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, MSG_FREE_LIMIT_REACHED))
            }

            val effectiveFreeState = FreeAccessState(
                usedContacts = reconciledUsed,
                limit = FREE_CONTACTS_LIMIT,
                isExhausted = reconciledExhausted
            )

            if (connectSucceeded) {
                settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "0"))
            } else {
                settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            }

            if (activeCodeKey.isNullOrBlank()) {
                if (!connectSucceeded) {
                    return ServerVerifyInternalResult(
                        outcome = offlineFallbackOutcome(snapshot, effectiveFreeState, activeCodeKey, cachedCode, explicitlyLocked),
                        apiResponse = null,
                        httpCode = connectResp?.code(),
                        errorDetail = connectErrText
                    )
                }

                val dbStatus = DbConnectionStatus.Connected("متصل")
                if (connectBody != null && connectBody.valid && connectBody.isSubscribed && connectBody.isActive && connectBody.code.isNotBlank()) {
                    val failure = validateApiStatusFlags(connectBody, 0)
                    if (failure == null) {
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                        val info = persistActiveSubscription(connectBody.code, connectBody, snapshot, existingUsed = connectBody.usedContacts)
                        return ServerVerifyInternalResult(
                            outcome = SessionVerifyOutcome(info, effectiveFreeState, true, dbStatus, null, false),
                            apiResponse = connectBody,
                            httpCode = connectResp.code(),
                            errorDetail = null
                        )
                    }
                }
                val outcome = if (!effectiveFreeState.isExhausted && !explicitlyLocked) {
                    SessionVerifyOutcome(null, effectiveFreeState, true, dbStatus, null, false)
                } else {
                    val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                        ?: MSG_FREE_LIMIT_REACHED
                    SessionVerifyOutcome(null, effectiveFreeState, false, dbStatus, reason, false)
                }
                return ServerVerifyInternalResult(
                    outcome = outcome,
                    apiResponse = connectBody,
                    httpCode = connectResp.code(),
                    errorDetail = null
                )
            }

            val response = api.verifySession(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionVerifyRequest(
                    code = activeCodeKey,
                    userId = snapshot.userId,
                    deviceId = deviceId,
                    androidId = snapshot.androidId,
                    publicIp = snapshot.publicIp,
                    localIp = snapshot.localIp,
                    deviceModel = snapshot.deviceModel,
                    savedContactsCount = totalContacts,
                    usedQuotaCount = cachedUsedQuota
                )
            )

            if (response.isSuccessful) {
                val body = response.body()
                val failureReason = validateApiStatusFlags(body, cachedUsedQuota)
                val connectedStatus = DbConnectionStatus.Connected("متصل")
                val outcome = if (body != null && failureReason == null) {
                    settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "0"))
                    val info = persistActiveSubscription(activeCodeKey, body, snapshot, existingUsed = cachedUsedQuota)
                    SessionVerifyOutcome(info, effectiveFreeState, true, connectedStatus, null, false)
                } else {
                    val err = failureReason ?: MSG_CONTACT_OWNER_OFFLINE
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, err))
                    upsertUserProfile(snapshot, activeCode = null)
                    SessionVerifyOutcome(null, effectiveFreeState, false, connectedStatus, err, false)
                }
                ServerVerifyInternalResult(
                    outcome = outcome,
                    apiResponse = body ?: connectBody,
                    httpCode = response.code(),
                    errorDetail = failureReason
                )
            } else {
                val errBodyRaw = response.errorBody()?.string()
                val serverErr = extractErrorMessage(errBodyRaw)
                if (response.code() in setOf(400, 403, 409, 422) && serverErr != null) {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, serverErr))
                    ServerVerifyInternalResult(
                        outcome = SessionVerifyOutcome(null, effectiveFreeState, false, DbConnectionStatus.Connected("متصل"), serverErr, false),
                        apiResponse = null,
                        httpCode = response.code(),
                        errorDetail = serverErr
                    )
                } else {
                    ServerVerifyInternalResult(
                        outcome = offlineFallbackOutcome(snapshot, effectiveFreeState, activeCodeKey, cachedCode, explicitlyLocked),
                        apiResponse = null,
                        httpCode = response.code(),
                        errorDetail = serverErr ?: "HTTP ${response.code()}"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            ServerVerifyInternalResult(
                outcome = offlineFallbackOutcome(snapshot, freeState, activeCodeKey, cachedCode, explicitlyLocked),
                apiResponse = null,
                httpCode = null,
                errorDetail = "${t.javaClass.simpleName}: ${t.message ?: "تعذر الوصول إلى الخادم"}"
            )
        }
    }

    private fun offlineFallbackOutcome(
        snapshot: UserNetworkSnapshot,
        freeState: FreeAccessState,
        activeCodeKey: String?,
        cachedCode: SubscriptionCodeEntity?,
        explicitlyLocked: Boolean
    ): SessionVerifyOutcome {
        val offlineStatus = DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
        val now = System.currentTimeMillis()
        val validCached = if (!activeCodeKey.isNullOrBlank() && cachedCode != null &&
            cachedCode.isActive && cachedCode.isSubscribed &&
            (cachedCode.boundUserId.isNullOrBlank() || cachedCode.boundUserId == snapshot.userId) &&
            (cachedCode.isLifetime || cachedCode.expiresAt == null || now < cachedCode.expiresAt) &&
            (cachedCode.maxContacts == null || cachedCode.maxContacts <= 0 || cachedCode.usedContacts < cachedCode.maxContacts)
        ) {
            cachedCode
        } else {
            null
        }
        if (validCached != null) {
            return SessionVerifyOutcome(
                activeInfo = validCached.toActiveInfo(snapshot),
                freeAccessState = freeState,
                isAllowed = true,
                dbStatus = offlineStatus,
                errorMessage = null,
                isConnectionError = false
            )
        }
        val allowFree = !freeState.isExhausted && !explicitlyLocked
        val reason = if (allowFree) null else MSG_CONTACT_OWNER_OFFLINE
        return SessionVerifyOutcome(
            activeInfo = null,
            freeAccessState = freeState,
            isAllowed = allowFree,
            dbStatus = offlineStatus,
            errorMessage = reason,
            isConnectionError = !allowFree
        )
    }

    private suspend fun pingAndSyncUserOnly(
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        activeCode: String?
    ): DbConnectionStatus {
        val api = resolveApiService() ?: return DbConnectionStatus.NotConfigured(MSG_DB_NOT_CONFIGURED)
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return try {
            val resp = api.connectAndSyncUser(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionConnectRequest(
                    userId = snapshot.userId,
                    deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
                    androidId = snapshot.androidId,
                    installUuid = snapshot.installUuid,
                    publicIp = snapshot.publicIp,
                    localIp = snapshot.localIp,
                    locationInfo = snapshot.locationInfo,
                    deviceModel = snapshot.deviceModel,
                    osVersion = snapshot.osVersion,
                    networkType = snapshot.networkType,
                    savedContactsCount = totalContacts,
                    freeContactsUsed = freeState.usedContacts,
                    freeContactsLimit = freeState.limit,
                    freeTierExhausted = freeState.isExhausted,
                    planType = if (!activeCode.isNullOrBlank()) "SUBSCRIBED" else if (freeState.isExhausted) "FREE_EXHAUSTED" else "FREE",
                    activeCode = activeCode
                )
            )
            if (resp.isSuccessful) {
                settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "0"))
                DbConnectionStatus.Connected("متصل")
            } else {
                settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
                DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
        }
    }

    suspend fun activateSubscriptionCode(context: Context, rawCode: String): ActivationResult = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase()
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        upsertUserProfile(snapshot, activeCode = null)

        if (code.isBlank()) {
            return@withContext ActivationResult.Failure(
                reason = "يرجى إدخال كود الاشتراك أولاً أو إرسال طلب مراجعة للإدارة.",
                isConnectionError = false
            )
        }

        val totalContacts = contactDao.getContactsCountSync()
        val existingUsed = codeDao.getCode(code)?.usedContacts ?: 0

        val api = resolveApiService()
            ?: return@withContext ActivationResult.Failure(
                reason = MSG_DB_NOT_CONFIGURED,
                isConnectionError = true
            )

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val response = api.activateCode(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionActivateRequest(
                    code = code,
                    userId = snapshot.userId,
                    deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
                    androidId = snapshot.androidId,
                    installUuid = snapshot.installUuid,
                    publicIp = snapshot.publicIp,
                    localIp = snapshot.localIp,
                    locationInfo = snapshot.locationInfo,
                    deviceModel = snapshot.deviceModel,
                    osVersion = snapshot.osVersion,
                    networkType = snapshot.networkType,
                    savedContactsCount = totalContacts
                )
            )

            if (response.isSuccessful) {
                val body = response.body()
                val failureReason = validateApiStatusFlags(body, existingUsed)
                if (body != null && failureReason == null) {
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                    val info = persistActiveSubscription(code, body, snapshot, existingUsed = existingUsed)
                    val msg = body.message.ifBlank { "تم التحقق من الخادم وتفعيل الاشتراك بنجاح." }
                    ActivationResult.Success(info, snapshot, msg)
                } else {
                    ActivationResult.Failure(
                        reason = failureReason ?: "كود الاشتراك غير صحيح أو بانتظار المراجعة لدى الإدارة.",
                        isConnectionError = false
                    )
                }
            } else {
                val parsed = extractErrorMessage(response.errorBody()?.string())
                if (response.code() in 400..499) {
                    ActivationResult.Failure(
                        reason = parsed ?: "كود الاشتراك غير صالح أو قيد المراجعة.",
                        isConnectionError = false
                    )
                } else {
                    ActivationResult.Failure(reason = MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ActivationResult.Failure(reason = MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
        }
    }

    /**
     * Submits a formal activation / review request to the Server API so the project owner
     * can review and approve the user from their dashboard.
     */
    suspend fun submitReviewRequest(
        context: Context,
        rawCode: String = "",
        notes: String = ""
    ): ReviewRequestResult = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        upsertUserProfile(snapshot, activeCode = null)
        val totalContacts = contactDao.getContactsCountSync()
        val cleanCode = rawCode.trim().uppercase()

        val api = resolveApiService()
            ?: return@withContext ReviewRequestResult.Failure(MSG_DB_NOT_CONFIGURED, isConnectionError = true)

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val response = api.submitReviewRequest(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionReviewRequest(
                    userId = snapshot.userId,
                    deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
                    requestedCode = cleanCode,
                    publicIp = snapshot.publicIp,
                    localIp = snapshot.localIp,
                    deviceModel = snapshot.deviceModel,
                    locationInfo = snapshot.locationInfo,
                    savedContactsCount = totalContacts,
                    notes = notes.ifBlank { "طلب مراجعة وتفعيل اشتراك من التطبيق" }
                )
            )
            if (response.isSuccessful) {
                val body = response.body()
                val msg = body?.message?.takeIf { it.isNotBlank() }
                    ?: "تم إرسال طلب المراجعة بنجاح. تواصل مع الإدارة على $ADMIN_PHONE_DISPLAY"
                ReviewRequestResult.Success(msg)
            } else {
                val err = extractErrorMessage(response.errorBody()?.string())
                ReviewRequestResult.Failure(
                    err ?: MSG_CONTACT_OWNER_OFFLINE,
                    isConnectionError = response.code() >= 500 || response.code() == 404
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ReviewRequestResult.Failure(MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
        }
    }

    suspend fun canSaveNewContact(): Boolean = withContext(Dispatchers.IO) {
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        if (activeCodeKey.isNullOrBlank()) {
            val explicitlyLocked = settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1"
            if (explicitlyLocked) return@withContext false
            val freeState = getFreeAccessStateSync()
            return@withContext !freeState.isExhausted && freeState.usedContacts < FREE_CONTACTS_LIMIT
        }
        val entity = codeDao.getCode(activeCodeKey) ?: return@withContext false
        if (!entity.isSubscribed || !entity.isActive) return@withContext false
        val now = System.currentTimeMillis()
        if (!entity.isLifetime && entity.expiresAt != null && now >= entity.expiresAt) return@withContext false
        val max = entity.maxContacts
        if (max != null && max > 0 && entity.usedContacts >= max) return@withContext false
        true
    }

    suspend fun recordContactSaved(context: Context): Boolean = withContext(Dispatchers.IO) {
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        if (activeCodeKey.isNullOrBlank()) {
            val storedUsed = settingDao.getSetting(KEY_FREE_USED)?.value?.toIntOrNull() ?: 0
            val dbCount = contactDao.getContactsCountSync()
            val nextFreeUsed = maxOf(storedUsed + 1, dbCount)
            val isExhausted = nextFreeUsed >= FREE_CONTACTS_LIMIT
            settingDao.insertSetting(SettingEntity(KEY_FREE_USED, nextFreeUsed.toString()))

            if (isExhausted) {
                settingDao.insertSetting(SettingEntity(KEY_FREE_EXHAUSTED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, MSG_FREE_LIMIT_REACHED))
            }

            val updatedFreeState = FreeAccessState(
                usedContacts = nextFreeUsed,
                limit = FREE_CONTACTS_LIMIT,
                isExhausted = isExhausted
            )
            syncFreeUsageToBackend(context, updatedFreeState)

            if (isExhausted) {
                NotificationHelper.showNotification(
                    context,
                    "انتهى الحد المجاني (100 جهة اتصال)",
                    MSG_FREE_LIMIT_REACHED
                )
                return@withContext false
            }
            return@withContext true
        }

        val entity = codeDao.getCode(activeCodeKey) ?: return@withContext false
        val nextUsed = entity.usedContacts + 1
        val max = entity.maxContacts
        val reachedLimit = max != null && max > 0 && nextUsed >= max

        val updated = entity.copy(
            usedContacts = nextUsed,
            isActive = if (reachedLimit) false else entity.isActive
        )
        codeDao.upsertCode(updated)

        syncUsageToBackend(context, updated.code, nextUsed)

        if (reachedLimit) {
            val lockMessage = "تم استهلاك الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال). يرجى إدخال كود اشتراك جديد."
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, lockMessage))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            NotificationHelper.showNotification(
                context,
                "انتهى حد الباقة الحالية",
                lockMessage
            )
            return@withContext false
        }
        true
    }

    private suspend fun resolveCurrentPlanType(): String {
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        if (!activeCodeKey.isNullOrBlank()) {
            val entity = codeDao.getCode(activeCodeKey)
            if (entity != null && entity.isActive && entity.isSubscribed) {
                return "SUBSCRIBED"
            }
        }
        val freeState = getFreeAccessStateSync()
        return if (freeState.isExhausted) "FREE_EXHAUSTED" else "FREE"
    }

    private suspend fun syncFreeUsageToBackend(context: Context, freeState: FreeAccessState) {
        val api = resolveApiService()
        if (api == null) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            return
        }
        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val totalContacts = contactDao.getContactsCountSync()
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        try {
            val resp = api.connectAndSyncUser(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionConnectRequest(
                    userId = snapshot.userId,
                    deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
                    androidId = snapshot.androidId,
                    installUuid = snapshot.installUuid,
                    publicIp = snapshot.publicIp,
                    localIp = snapshot.localIp,
                    locationInfo = snapshot.locationInfo,
                    deviceModel = snapshot.deviceModel,
                    osVersion = snapshot.osVersion,
                    networkType = snapshot.networkType,
                    savedContactsCount = totalContacts,
                    freeContactsUsed = freeState.usedContacts,
                    freeContactsLimit = freeState.limit,
                    freeTierExhausted = freeState.isExhausted,
                    planType = if (freeState.isExhausted) "FREE_EXHAUSTED" else "FREE",
                    activeCode = null
                )
            )
            settingDao.insertSetting(
                SettingEntity(KEY_PENDING_TELEMETRY_SYNC, if (resp.isSuccessful) "0" else "1")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
        }
    }

    private suspend fun syncUsageToBackend(context: Context, code: String, usedCount: Int) {
        val api = resolveApiService()
        if (api == null) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            return
        }
        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val totalContacts = contactDao.getContactsCountSync()
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        try {
            val resp = api.reportUsage(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionUsageRequest(
                    code = code,
                    userId = snapshot.userId,
                    deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
                    publicIp = snapshot.publicIp,
                    usedContacts = usedCount,
                    totalContacts = totalContacts,
                    planType = "SUBSCRIBED"
                )
            )
            settingDao.insertSetting(
                SettingEntity(KEY_PENDING_TELEMETRY_SYNC, if (resp.isSuccessful) "0" else "1")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
        }
    }

    private fun validateApiStatusFlags(body: SubscriptionApiResponse?, localUsed: Int): String? {
        if (body == null) return MSG_CONTACT_OWNER_OFFLINE
        if (!body.isSubscribed) {
            return body.message.ifBlank { "لم يتم الاشتراك بعد أو الكود قيد المراجعة. يرجى التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY" }
        }
        if (!body.isActive) {
            return body.message.ifBlank { "الاشتراك قيد المراجعة أو غير مفعل حالياً لدى الإدارة. يرجى التواصل مع $ADMIN_PHONE_DISPLAY" }
        }
        if (!body.valid) {
            return body.message.ifBlank { "كود الاشتراك غير صحيح أو منتهي الصلاحية." }
        }
        val effectiveUsed = maxOf(localUsed, body.usedContacts)
        val max = body.maxContacts
        if (max != null && max > 0 && effectiveUsed >= max) {
            return body.message.ifBlank { "تم الوصول إلى الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال)." }
        }
        return null
    }

    suspend fun syncSingleClientToDatabase(
        context: Context,
        contact: Contact,
        isDeleted: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (isDeleted) {
            markPendingClientDeleted(contact)
        } else {
            unmarkPendingClientDeleted(contact.phone)
        }

        val item = contact.toClientSyncItem(isDeleted = isDeleted)
        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)
        val planType = resolveCurrentPlanType()

        val api = resolveApiService() ?: return@withContext false
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val resp = api.syncClients(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = ClientSyncBatchRequest(
                    userId = snapshot.userId,
                    deviceId = deviceId,
                    publicIp = snapshot.publicIp,
                    planType = planType,
                    clients = listOf(item)
                )
            )
            if (resp.isSuccessful) {
                if (isDeleted) {
                    unmarkPendingClientDeleted(contact.phone)
                }
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun syncAllClientsToDatabase(context: Context): Boolean = withContext(Dispatchers.IO) {
        val api = resolveApiService() ?: return@withContext false

        val contacts = contactDao.getAllContactsSync()
        val activePhones = contacts.mapTo(HashSet()) { it.phone }
        val pendingDeletes = loadPendingDeletedClients().filterNot { it.phone in activePhones }
        if (pendingDeletes.size != loadPendingDeletedClients().size) {
            savePendingDeletedClients(pendingDeletes)
        }

        val items = contacts.map { it.toClientSyncItem(isDeleted = false) } + pendingDeletes
        if (items.isEmpty()) return@withContext true

        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)
        val planType = resolveCurrentPlanType()

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val resp = api.syncClients(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = ClientSyncBatchRequest(
                    userId = snapshot.userId,
                    deviceId = deviceId,
                    publicIp = snapshot.publicIp,
                    planType = planType,
                    clients = items
                )
            )
            if (resp.isSuccessful) {
                if (pendingDeletes.isNotEmpty()) {
                    savePendingDeletedClients(emptyList())
                }
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private fun Contact.toClientSyncItem(isDeleted: Boolean): ClientSyncItem {
        return ClientSyncItem(
            phone = phone,
            originalPhone = originalPhone,
            name = name,
            source = source,
            createdAt = timestamp,
            lastActivity = lastActivity,
            interactionCount = interactionCount,
            isSyncedToPhone = isSyncedToPhone,
            isDeleted = isDeleted
        )
    }

    private suspend fun markPendingClientDeleted(contact: Contact) {
        val existing = loadPendingDeletedClients().filterNot { it.phone == contact.phone }.toMutableList()
        existing.add(contact.toClientSyncItem(isDeleted = true))
        savePendingDeletedClients(existing)
    }

    private suspend fun unmarkPendingClientDeleted(normalizedPhone: String) {
        val existing = loadPendingDeletedClients()
        val filtered = existing.filterNot { it.phone == normalizedPhone }
        if (filtered.size != existing.size) {
            savePendingDeletedClients(filtered)
        }
    }

    private suspend fun loadPendingDeletedClients(): List<ClientSyncItem> {
        val raw = settingDao.getSetting(KEY_PENDING_DELETED_CLIENTS)?.value.orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val phone = obj.optString("phone")
                    if (phone.isBlank()) continue
                    add(
                        ClientSyncItem(
                            phone = phone,
                            originalPhone = obj.optString("original_phone", phone),
                            name = obj.optString("name", ""),
                            source = obj.optString("source", ""),
                            createdAt = obj.optLong("created_at", 0L),
                            lastActivity = obj.optLong("last_activity", 0L),
                            interactionCount = obj.optInt("interaction_count", 1),
                            isSyncedToPhone = obj.optBoolean("is_synced_to_phone", false),
                            isDeleted = true
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun savePendingDeletedClients(items: List<ClientSyncItem>) {
        if (items.isEmpty()) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_DELETED_CLIENTS, ""))
            return
        }
        val array = JSONArray()
        for (item in items) {
            array.put(
                JSONObject().apply {
                    put("phone", item.phone)
                    put("original_phone", item.originalPhone)
                    put("name", item.name)
                    put("source", item.source)
                    put("created_at", item.createdAt)
                    put("last_activity", item.lastActivity)
                    put("interaction_count", item.interactionCount)
                    put("is_synced_to_phone", item.isSyncedToPhone)
                }
            )
        }
        settingDao.insertSetting(SettingEntity(KEY_PENDING_DELETED_CLIENTS, array.toString()))
    }

    suspend fun lockSession(explicitLock: Boolean = true) = withContext(Dispatchers.IO) {
        settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
        if (explicitLock) {
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
        }
    }

    private suspend fun persistActiveSubscription(
        fallbackCode: String,
        body: SubscriptionApiResponse,
        snapshot: UserNetworkSnapshot,
        existingUsed: Int
    ): ActiveSubscriptionInfo {
        val now = System.currentTimeMillis()
        val resolvedCode = body.code.ifBlank { fallbackCode }.uppercase()
        val activatedAt = body.activatedAt ?: now
        val expiresAt = if (body.isLifetime) {
            null
        } else {
            body.expiresAt ?: if (body.durationMinutes > 0L) {
                activatedAt + body.durationMinutes * 60_000L
            } else {
                null
            }
        }
        val label = body.planLabel.ifBlank { "اشتراك مفعل" }
        val boundUser = body.boundUserId?.ifBlank { null } ?: snapshot.userId
        val boundIp = body.boundIp?.ifBlank { null } ?: snapshot.publicIp
        val normalizedMax = body.maxContacts?.takeIf { it > 0 }
        val effectiveUsed = maxOf(existingUsed, body.usedContacts)

        codeDao.upsertCode(
            SubscriptionCodeEntity(
                code = resolvedCode,
                label = label,
                durationMinutes = body.durationMinutes,
                isLifetime = body.isLifetime,
                isSubscribed = body.isSubscribed,
                isActive = body.isActive,
                isTrial = body.isTrial,
                maxContacts = normalizedMax,
                usedContacts = effectiveUsed,
                enforceIpLock = false,
                preventDuplicatePerDevice = true,
                boundUserId = boundUser,
                boundAndroidId = snapshot.androidId,
                boundIpAddress = boundIp,
                boundDeviceModel = snapshot.deviceModel,
                activatedAt = activatedAt,
                expiresAt = expiresAt,
                sourceType = "SERVER_API"
            )
        )
        settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, resolvedCode))
        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
        upsertUserProfile(snapshot, activeCode = resolvedCode)

        return ActiveSubscriptionInfo(
            code = resolvedCode,
            label = label,
            isLifetime = body.isLifetime,
            isSubscribed = body.isSubscribed,
            isActive = body.isActive,
            isTrial = body.isTrial,
            maxContacts = normalizedMax,
            usedContacts = effectiveUsed,
            activatedAt = activatedAt,
            expiresAt = expiresAt,
            boundUserId = boundUser,
            boundIpAddress = boundIp,
            deviceModel = snapshot.deviceModel
        )
    }

    private fun SubscriptionCodeEntity.toActiveInfo(snapshot: UserNetworkSnapshot? = null): ActiveSubscriptionInfo {
        return ActiveSubscriptionInfo(
            code = code,
            label = label.ifBlank { "اشتراك مفعل" },
            isLifetime = isLifetime,
            isSubscribed = isSubscribed,
            isActive = isActive,
            isTrial = isTrial,
            maxContacts = maxContacts?.takeIf { it > 0 },
            usedContacts = usedContacts,
            activatedAt = activatedAt ?: System.currentTimeMillis(),
            expiresAt = expiresAt,
            boundUserId = boundUserId ?: snapshot?.userId.orEmpty(),
            boundIpAddress = snapshot?.publicIp ?: boundIpAddress.orEmpty(),
            deviceModel = snapshot?.deviceModel ?: boundDeviceModel.orEmpty()
        )
    }

    private suspend fun upsertUserProfile(snapshot: UserNetworkSnapshot, activeCode: String?) {
        val existing = profileDao.getProfile(snapshot.userId)
        val profile = UserDeviceProfileEntity(
            userId = snapshot.userId,
            androidId = snapshot.androidId,
            installUuid = snapshot.installUuid,
            publicIp = snapshot.publicIp,
            localIp = snapshot.localIp,
            locationInfo = snapshot.locationInfo,
            deviceModel = snapshot.deviceModel,
            osVersion = snapshot.osVersion,
            networkType = snapshot.networkType,
            activeCode = activeCode ?: existing?.activeCode,
            redeemedCodesHistory = existing?.redeemedCodesHistory.orEmpty(),
            firstSeenAt = existing?.firstSeenAt ?: System.currentTimeMillis(),
            lastSeenAt = System.currentTimeMillis()
        )
        profileDao.upsertProfile(profile)
    }

    private fun extractErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun formatJsonOrRaw(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return ""
        return try {
            when {
                text.startsWith("{") -> JSONObject(text).toString(2)
                text.startsWith("[") -> JSONArray(text).toString(2)
                else -> text
            }
        } catch (_: Exception) {
            text
        }
    }

    private fun synthesizeApiResponseJson(body: SubscriptionApiResponse?): String {
        if (body == null) return ""
        return try {
            JSONObject().apply {
                put("valid", body.valid)
                put("is_subscribed", body.isSubscribed)
                put("is_active", body.isActive)
                put("status", body.status)
                if (body.message.isNotBlank()) put("message", body.message)
                if (!body.planType.isNullOrBlank()) put("plan_type", body.planType)
                if (body.code.isNotBlank()) put("code", body.code)
                if (body.planLabel.isNotBlank()) put("plan_label", body.planLabel)
                put("free_contacts_used", body.freeContactsUsed)
                body.freeContactsLimit?.let { put("free_contacts_limit", it) }
                put("free_tier_exhausted", body.freeTierExhausted)
                body.maxContacts?.let { put("max_contacts", it) }
                put("used_contacts", body.usedContacts)
                body.deviceId?.let { put("device_id", it) }
            }.toString(2)
        } catch (_: Exception) {
            body.toString()
        }
    }

    suspend fun buildCurrentLocalReport(context: Context, dbStatus: DbConnectionStatus): ConnectionDetailsReport =
        withContext(Dispatchers.IO) {
            val snapshot = UserIdentityManager.getCachedSnapshot(context)
            val freeState = getFreeAccessStateSync()
            val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val cached = activeCodeKey?.let { codeDao.getCode(it) }
            val activeInfo = cached?.takeIf { it.isActive && it.isSubscribed }?.toActiveInfo(snapshot)
            val outcome = SessionVerifyOutcome(
                activeInfo = activeInfo,
                freeAccessState = freeState,
                isAllowed = activeInfo != null || !freeState.isExhausted,
                dbStatus = dbStatus,
                errorMessage = null,
                isConnectionError = dbStatus !is DbConnectionStatus.Connected
            )
            buildConnectionDetailsReport(
                snapshot = snapshot,
                outcome = outcome,
                apiResponse = null,
                explicitHttpCode = SubscriptionApiService.lastNetworkExchange?.httpCode,
                explicitError = SubscriptionApiService.lastNetworkExchange?.errorDetail,
                authExchange = SubscriptionApiService.lastNetworkExchange,
                clientsSyncSucceeded = null,
                clientsSyncExchange = null
            )
        }

    private suspend fun buildConnectionDetailsReport(
        snapshot: UserNetworkSnapshot,
        outcome: SessionVerifyOutcome,
        apiResponse: SubscriptionApiResponse?,
        explicitHttpCode: Int?,
        explicitError: String?,
        authExchange: NetworkExchangeCapture?,
        clientsSyncSucceeded: Boolean?,
        clientsSyncExchange: NetworkExchangeCapture?
    ): ConnectionDetailsReport {
        val allContacts = contactDao.getAllContactsSync()
        val syncedToPhoneCount = allContacts.count { it.isSyncedToPhone }
        val logsCount = logDao.getLogsCountSync()
        val pendingDeletesCount = loadPendingDeletedClients().size

        val endpointConfig = ServerEndpointConfig.parse(BuildConfig.DATABASE_URL)
        val connectedTarget = authExchange?.sanitizedEndpointUrl?.takeIf { it.isNotBlank() }
            ?: endpointConfig?.baseUrl?.let { "${it}api/subscription/connect" }
            ?: "غير مهيأ (رابط الخادم غير مضبوط)"

        val authMethodSummary = when {
            endpointConfig?.authParamName != null && !endpointConfig.authToken.isNullOrBlank() ->
                "مفعل عبر ${endpointConfig.authParamName} + Authorization Bearer + X-API-Key"
            apiServiceOverride != null ->
                "واجهة خادم مباشرة (الاختبار/الحقن)"
            else ->
                "غير متوفر"
        }

        val httpCode = explicitHttpCode ?: authExchange?.httpCode
        val latency = authExchange?.latencyMs
        val rawErr = explicitError ?: authExchange?.errorDetail

        val succeeded = outcome.dbStatus is DbConnectionStatus.Connected
        val (headline, detail) = when (outcome.dbStatus) {
            is DbConnectionStatus.Connected -> {
                val codePart = if (httpCode != null) " (HTTP $httpCode)" else ""
                val timePart = if (latency != null) " خلال ${latency}ms" else ""
                "نجح الاتصال بالخادم ✅" to "تم التحقق من الجلسة ومزامنة البيانات بنجاح$codePart$timePart."
            }
            is DbConnectionStatus.NotConfigured -> {
                "الخادم غير مهيأ ⚠️" to (rawErr ?: "التطبيق يعمل بالوضع المحلي، يرجى التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY.")
            }
            is DbConnectionStatus.Disconnected -> {
                val errSuffix = rawErr?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                "فشل الاتصال بالخادم ❌" to "تعذر إتمام الاتصال بالخادم$errSuffix. التطبيق يواصل العمل محلياً."
            }
            is DbConnectionStatus.Checking -> {
                "جاري التحقق من الاتصال..." to "يتم الآن فحص الاتصال بالخادم..."
            }
        }

        val effectivePlan = apiResponse?.planType?.takeIf { it.isNotBlank() }
            ?: if (outcome.activeInfo != null) "SUBSCRIBED" else if (outcome.freeAccessState.isExhausted) "FREE_EXHAUSTED" else "FREE"

        val formattedServerJson = formatJsonOrRaw(authExchange?.rawResponseBody)
            .ifBlank { synthesizeApiResponseJson(apiResponse) }
            .ifBlank {
                if (succeeded) {
                    "{\"status\": \"connected\"}"
                } else {
                    rawErr ?: "لا توجد استجابة من الخادم"
                }
            }

        val formattedSyncJson = formatJsonOrRaw(clientsSyncExchange?.rawResponseBody)
            .takeIf { it.isNotBlank() }

        return ConnectionDetailsReport(
            succeeded = succeeded,
            statusHeadline = headline,
            statusDetail = detail,
            connectedWithTarget = connectedTarget,
            authMethodSummary = authMethodSummary,
            httpStatusCode = httpCode,
            latencyMs = latency,
            errorDetail = rawErr,
            networkType = snapshot.networkType,
            deviceId = UserIdentityManager.getImmutableDeviceId(snapshot),
            userId = snapshot.userId,
            publicIp = snapshot.publicIp,
            localIp = snapshot.localIp,
            planType = effectivePlan,
            freeContactsUsed = outcome.freeAccessState.usedContacts,
            freeContactsLimit = outcome.freeAccessState.limit,
            freeTierExhausted = outcome.freeAccessState.isExhausted,
            isSubscribed = outcome.activeInfo != null,
            isSubscriptionActive = outcome.activeInfo?.isActive == true,
            activeCode = outcome.activeInfo?.code,
            serverStatusText = apiResponse?.status?.ifBlank { if (succeeded) "connected" else "disconnected" }
                ?: if (succeeded) "connected" else "offline",
            serverMessageText = apiResponse?.message.orEmpty(),
            rawServerResponse = formattedServerJson,
            clientsSyncSucceeded = clientsSyncSucceeded,
            clientsSyncResponse = formattedSyncJson,
            localContactsCount = allContacts.size,
            syncedToPhoneCount = syncedToPhoneCount,
            localLogsCount = logsCount,
            pendingDeletesCount = pendingDeletesCount,
            sampleContacts = allContacts.take(15)
        )
    }
}
