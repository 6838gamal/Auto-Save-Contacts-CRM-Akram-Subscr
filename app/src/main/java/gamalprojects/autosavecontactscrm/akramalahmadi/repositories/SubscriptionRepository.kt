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
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncBatchRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ClientSyncItem
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
import java.util.Calendar

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

enum class SubscriptionPlanTier(
    val displayTitle: String,
    val expectedMonths: Int,
    val fallbackDays: Long
) {
    FIVE_MONTHS("باقة 5 أشهر", 5, 150L),
    TWELVE_MONTHS("باقة 12 شهراً", 12, 365L),
    TIMED("اشتراك محدد المدة", 0, 0L)
}

data class ActiveSubscriptionInfo(
    val code: String,
    val label: String,
    val planTier: SubscriptionPlanTier,
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
        val exp = expiresAt ?: return true
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

    fun remainingProgress(now: Long = System.currentTimeMillis()): Float {
        val exp = expiresAt ?: return 0f
        val total = (exp - activatedAt).coerceAtLeast(1L)
        val remaining = (exp - now).coerceAtLeast(0L)
        return (remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}

data class FreeAccessState(
    val usedContacts: Int = 0,
    val limit: Int = SubscriptionRepository.FREE_CONTACTS_LIMIT,
    val isExhausted: Boolean = false
) {
    val remainingContacts: Int get() = (limit - usedContacts).coerceAtLeast(0)
}

data class SessionVerifyOutcome(
    val activeInfo: ActiveSubscriptionInfo?,
    val freeAccessState: FreeAccessState,
    val isAllowed: Boolean,
    val dbStatus: DbConnectionStatus,
    val errorMessage: String?,
    val isConnectionError: Boolean
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
        private const val KEY_FREE_WARNING_TIER = "free_tier_warning_milestone"
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
                    val now = System.currentTimeMillis()
                    if (entity == null || !entity.isSubscribed || !entity.isActive) {
                        null
                    } else if (entity.expiresAt == null || now >= entity.expiresAt) {
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
        return combine(
            settingDao.getSettingFlow(KEY_LOCK_REASON),
            observeFreeAccessState(),
            observeActiveSubscription()
        ) { setting, freeState, activeSub ->
            if (activeSub != null || !freeState.isExhausted) {
                null
            } else {
                setting?.value?.takeIf { it.isNotBlank() }
            }
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
        val now = System.currentTimeMillis()

        val cached = activeCodeKey?.takeIf { it.isNotBlank() }?.let { codeDao.getCode(it) }
        if (cached != null) {
            if (cached.expiresAt == null || now >= cached.expiresAt) {
                settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                if (!freeState.isExhausted) {
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                    return@withContext LocalAccessDecision(null, freeState, isAllowed = true, errorMessage = null)
                }
                val msg = "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد (5 أشهر أو 12 شهراً) أو التواصل مع الإدارة على الرقم $ADMIN_PHONE_DISPLAY"
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
                return@withContext LocalAccessDecision(null, freeState, isAllowed = false, errorMessage = msg)
            }
            if (cached.maxContacts != null && cached.maxContacts > 0 && cached.usedContacts >= cached.maxContacts) {
                settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                if (!freeState.isExhausted) {
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                    return@withContext LocalAccessDecision(null, freeState, isAllowed = true, errorMessage = null)
                }
                val msg = "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
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

        // As long as Free Plan (< 100 contacts) is still active, never lock out or surface expired-code messages
        if (!freeState.isExhausted) {
            if (settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1") {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
            }
            if (!settingDao.getSetting(KEY_LOCK_REASON)?.value.isNullOrBlank()) {
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
            }
            return@withContext LocalAccessDecision(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = true,
                errorMessage = null
            )
        }

        val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
            ?: MSG_FREE_LIMIT_REACHED
        LocalAccessDecision(
            activeInfo = null,
            freeAccessState = freeState,
            isAllowed = false,
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
        val explicitlyLocked = freeState.isExhausted && (settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1")

        upsertUserProfile(snapshot, activeCode = activeCodeKey?.takeIf { it.isNotBlank() })

        val cached = activeCodeKey?.takeIf { it.isNotBlank() }?.let { codeDao.getCode(it) }
        val now = System.currentTimeMillis()
        var effectiveActiveCodeKey = activeCodeKey
        var effectiveCached = cached
        var effectiveExplicitlyLocked = explicitlyLocked

        // Local expiration guard (still queries server afterwards in case admin renewed a subscription for this device)
        if (cached != null && (cached.expiresAt == null || now >= cached.expiresAt)) {
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            upsertUserProfile(snapshot, activeCode = null)
            effectiveActiveCodeKey = null
            effectiveCached = null
            if (freeState.isExhausted) {
                val msg = "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد (5 أشهر أو 12 شهراً) أو التواصل مع الإدارة على الرقم $ADMIN_PHONE_DISPLAY"
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
                effectiveExplicitlyLocked = true
            } else {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                effectiveExplicitlyLocked = false
            }
        } else if (cached != null && cached.maxContacts != null && cached.maxContacts > 0 && cached.usedContacts >= cached.maxContacts) {
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            upsertUserProfile(snapshot, activeCode = null)
            effectiveActiveCodeKey = null
            effectiveCached = null
            if (freeState.isExhausted) {
                val msg = "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
                effectiveExplicitlyLocked = true
            } else {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                effectiveExplicitlyLocked = false
            }
        } else if (!freeState.isExhausted) {
            if (settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1") {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
            }
            if (!settingDao.getSetting(KEY_LOCK_REASON)?.value.isNullOrBlank()) {
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
            }
        }

        val api = resolveApiService()
        if (api != null) {
            val outcome = verifyViaServerApi(
                api = api,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = effectiveActiveCodeKey,
                cachedCode = effectiveCached,
                explicitlyLocked = effectiveExplicitlyLocked
            )
            if (outcome.dbStatus is DbConnectionStatus.Connected) {
                syncAllClientsToDatabase(context)
            }
            return@withContext outcome
        }

        // Server not configured yet: allow free usage up to 100 or valid cached code
        val notConfiguredStatus = DbConnectionStatus.NotConfigured(MSG_DB_NOT_CONFIGURED)
        if (!effectiveActiveCodeKey.isNullOrBlank() &&
            effectiveCached != null && effectiveCached.isActive && effectiveCached.isSubscribed &&
            effectiveCached.expiresAt != null && now < effectiveCached.expiresAt &&
            (effectiveCached.boundUserId.isNullOrBlank() || effectiveCached.boundUserId == snapshot.userId)
        ) {
            return@withContext SessionVerifyOutcome(
                activeInfo = effectiveCached.toActiveInfo(snapshot),
                freeAccessState = freeState,
                isAllowed = true,
                dbStatus = notConfiguredStatus,
                errorMessage = null,
                isConnectionError = false
            )
        }

        if (!freeState.isExhausted) {
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = true,
                dbStatus = notConfiguredStatus,
                errorMessage = null,
                isConnectionError = false
            )
        }

        val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
            ?: MSG_FREE_LIMIT_REACHED
        return@withContext SessionVerifyOutcome(
            activeInfo = null,
            freeAccessState = freeState,
            isAllowed = false,
            dbStatus = notConfiguredStatus,
            errorMessage = reason,
            isConnectionError = false
        )
    }

    private suspend fun verifyViaServerApi(
        api: SubscriptionApiService,
        snapshot: UserNetworkSnapshot,
        freeState: FreeAccessState,
        totalContacts: Int,
        activeCodeKey: String?,
        cachedCode: SubscriptionCodeEntity?,
        explicitlyLocked: Boolean
    ): SessionVerifyOutcome {
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
                    return offlineFallbackOutcome(snapshot, effectiveFreeState, activeCodeKey, cachedCode, explicitlyLocked)
                }

                val dbStatus = DbConnectionStatus.Connected("متصل")
                if (connectBody != null && connectBody.valid && connectBody.isSubscribed && connectBody.isActive && connectBody.code.isNotBlank()) {
                    val failure = validateApiStatusFlags(connectBody, 0, existingExpiresAt = cachedCode?.expiresAt)
                    if (failure == null) {
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                        val info = persistActiveSubscription(connectBody.code, connectBody, snapshot, existingUsed = connectBody.usedContacts, existingExpiresAt = cachedCode?.expiresAt)
                        return SessionVerifyOutcome(info, effectiveFreeState, true, dbStatus, null, false)
                    }
                }
                if (!effectiveFreeState.isExhausted) {
                    return SessionVerifyOutcome(null, effectiveFreeState, true, dbStatus, null, false)
                }
                val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                    ?: MSG_FREE_LIMIT_REACHED
                return SessionVerifyOutcome(null, effectiveFreeState, false, dbStatus, reason, false)
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
                val failureReason = validateApiStatusFlags(body, cachedUsedQuota, existingExpiresAt = cachedCode?.expiresAt)
                val connectedStatus = DbConnectionStatus.Connected("متصل")
                if (body != null && failureReason == null) {
                    settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "0"))
                    val info = persistActiveSubscription(activeCodeKey, body, snapshot, existingUsed = cachedUsedQuota, existingExpiresAt = cachedCode?.expiresAt)
                    SessionVerifyOutcome(info, effectiveFreeState, true, connectedStatus, null, false)
                } else {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    upsertUserProfile(snapshot, activeCode = null)
                    if (!effectiveFreeState.isExhausted) {
                        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                        SessionVerifyOutcome(null, effectiveFreeState, true, connectedStatus, null, false)
                    } else {
                        val err = failureReason ?: MSG_CONTACT_OWNER_OFFLINE
                        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, err))
                        SessionVerifyOutcome(null, effectiveFreeState, false, connectedStatus, err, false)
                    }
                }
            } else {
                val serverErr = extractErrorMessage(response.errorBody()?.string())
                if (response.code() in setOf(400, 403, 409, 422) && serverErr != null) {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    upsertUserProfile(snapshot, activeCode = null)
                    if (!effectiveFreeState.isExhausted) {
                        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                        SessionVerifyOutcome(null, effectiveFreeState, true, DbConnectionStatus.Connected("متصل"), null, false)
                    } else {
                        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, serverErr))
                        SessionVerifyOutcome(null, effectiveFreeState, false, DbConnectionStatus.Connected("متصل"), serverErr, false)
                    }
                } else {
                    offlineFallbackOutcome(snapshot, effectiveFreeState, activeCodeKey, cachedCode, explicitlyLocked)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            settingDao.insertSetting(SettingEntity(KEY_PENDING_TELEMETRY_SYNC, "1"))
            offlineFallbackOutcome(snapshot, freeState, activeCodeKey, cachedCode, explicitlyLocked)
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
            cachedCode.expiresAt != null && now < cachedCode.expiresAt &&
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
        val allowFree = !freeState.isExhausted
        val reason = if (allowFree) null else MSG_FREE_LIMIT_REACHED
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
        if (!activeCodeKey.isNullOrBlank()) {
            val entity = codeDao.getCode(activeCodeKey)
            val now = System.currentTimeMillis()
            val validSub = entity != null &&
                entity.isSubscribed &&
                entity.isActive &&
                entity.expiresAt != null &&
                now < entity.expiresAt &&
                (entity.maxContacts == null || entity.maxContacts <= 0 || entity.usedContacts < entity.maxContacts)
            if (validSub) {
                return@withContext true
            }
            // Clear expired/exhausted code silently; fall back cleanly to Free Plan if quota < 100 remains
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
        }

        val freeState = getFreeAccessStateSync()
        if (!freeState.isExhausted && freeState.usedContacts < FREE_CONTACTS_LIMIT) {
            if (settingDao.getSetting(KEY_SUBSCRIPTION_LOCKED)?.value == "1") {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
            }
            if (!settingDao.getSetting(KEY_LOCK_REASON)?.value.isNullOrBlank()) {
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
            }
            return@withContext true
        }

        settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
        if (settingDao.getSetting(KEY_LOCK_REASON)?.value.isNullOrBlank()) {
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, MSG_FREE_LIMIT_REACHED))
        }
        false
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
            } else {
                val milestone = when {
                    nextFreeUsed >= 95 -> 95
                    nextFreeUsed >= 90 -> 90
                    nextFreeUsed >= 80 -> 80
                    else -> 0
                }
                if (milestone > 0) {
                    val lastWarned = settingDao.getSetting(KEY_FREE_WARNING_TIER)?.value?.toIntOrNull() ?: 0
                    if (milestone > lastWarned) {
                        settingDao.insertSetting(SettingEntity(KEY_FREE_WARNING_TIER, milestone.toString()))
                        val remaining = (FREE_CONTACTS_LIMIT - nextFreeUsed).coerceAtLeast(0)
                        NotificationHelper.showNotification(
                            context,
                            "تنبيه الرصيد المجاني (متبقي $remaining جهة اتصال)",
                            "أوشك الحد المجاني المحدود ($FREE_CONTACTS_LIMIT جهة اتصال) على النفاد. يرجى تفعيل كود اشتراك (5 أشهر أو 12 شهراً)."
                        )
                    }
                }
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
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            val freeState = getFreeAccessStateSync()
            if (!freeState.isExhausted) {
                settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                return@withContext true
            }
            val lockMessage = "تم استهلاك الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال). يرجى إدخال كود اشتراك جديد."
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, lockMessage))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
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

    private fun normalizeEpochMillis(raw: Long?): Long? {
        if (raw == null || raw <= 0L) return null
        return if (raw < 10_000_000_000L) raw * 1000L else raw
    }

    private fun resolvePlanTier(
        planType: String?,
        planLabel: String?,
        durationMonths: Int,
        durationDays: Int,
        durationMinutes: Long
    ): SubscriptionPlanTier {
        val normalizedText = gamalprojects.autosavecontactscrm.akramalahmadi.managers.PhoneNumberUtils
            .cleanNumber("${planType.orEmpty()} ${planLabel.orEmpty()}")
        val rawCombined = "${planType.orEmpty()} ${planLabel.orEmpty()}".uppercase()
            .replace('-', '_')
            .replace(' ', '_')
        return when {
            durationMonths == 12 -> SubscriptionPlanTier.TWELVE_MONTHS
            durationMonths == 5 -> SubscriptionPlanTier.FIVE_MONTHS
            rawCombined.contains("12_MONTH") || rawCombined.contains("12MONTH") ||
                rawCombined.contains("TWELVE") || rawCombined.contains("12M") ||
                rawCombined.contains("12_شهر") || rawCombined.contains("12شهر") ||
                normalizedText == "12" ||
                rawCombined.contains("ANNUAL") || rawCombined.contains("YEAR") ||
                rawCombined.contains("سنة") || rawCombined.contains("سنوي") -> SubscriptionPlanTier.TWELVE_MONTHS
            rawCombined.contains("5_MONTH") || rawCombined.contains("5MONTH") ||
                rawCombined.contains("FIVE") || rawCombined.contains("5M") ||
                rawCombined.contains("5_أشهر") || rawCombined.contains("5أشهر") ||
                rawCombined.contains("5_شهور") || rawCombined.contains("5شهور") ||
                normalizedText == "5" -> SubscriptionPlanTier.FIVE_MONTHS
            durationDays in 350..380 -> SubscriptionPlanTier.TWELVE_MONTHS
            durationDays in 140..165 -> SubscriptionPlanTier.FIVE_MONTHS
            durationMinutes in 500_000L..550_000L -> SubscriptionPlanTier.TWELVE_MONTHS
            durationMinutes in 200_000L..240_000L -> SubscriptionPlanTier.FIVE_MONTHS
            else -> SubscriptionPlanTier.TIMED
        }
    }

    private fun extractMonthsFromBody(body: SubscriptionApiResponse): Int {
        return maxOf(body.durationMonths, body.months)
    }

    private fun extractPlanDescriptor(body: SubscriptionApiResponse): String {
        return listOfNotNull(body.planType, body.codeType, body.subscriptionType, body.plan)
            .joinToString(" ")
    }

    private fun addCalendarMonths(startMillis: Long, months: Int): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startMillis
        cal.add(Calendar.MONTH, months)
        return cal.timeInMillis
    }

    private fun resolveExpiresAt(
        body: SubscriptionApiResponse,
        activatedAt: Long,
        tier: SubscriptionPlanTier,
        existingExpiresAt: Long? = null
    ): Long? {
        val now = System.currentTimeMillis()
        normalizeEpochMillis(body.expiresAt)?.let { return it }
        body.remainingSeconds?.takeIf { it > 0L }?.let { return now + it * 1000L }
        body.expiresInSeconds?.takeIf { it > 0L }?.let { return now + it * 1000L }
        if (body.durationMinutes > 0L) {
            return activatedAt + body.durationMinutes * 60_000L
        }
        if (body.durationDays > 0) {
            return activatedAt + body.durationDays * 86_400_000L
        }
        val monthsCount = extractMonthsFromBody(body)
        if (monthsCount > 0) {
            return addCalendarMonths(activatedAt, monthsCount)
        }
        if (tier.expectedMonths > 0) {
            return addCalendarMonths(activatedAt, tier.expectedMonths)
        }
        return normalizeEpochMillis(existingExpiresAt)
    }

    private fun validateApiStatusFlags(
        body: SubscriptionApiResponse?,
        localUsed: Int,
        existingExpiresAt: Long? = null
    ): String? {
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
        val now = System.currentTimeMillis()
        val activatedAt = normalizeEpochMillis(body.activatedAt) ?: now
        val tier = resolvePlanTier(
            planType = extractPlanDescriptor(body),
            planLabel = body.planLabel,
            durationMonths = extractMonthsFromBody(body),
            durationDays = body.durationDays,
            durationMinutes = body.durationMinutes
        )
        val resolvedExpiresAt = resolveExpiresAt(body, activatedAt, tier, existingExpiresAt)
            ?: return body.message.ifBlank {
                "تعذر تحديد مدة صلاحية الكود من الخادم (باقات 5 أشهر أو 12 شهراً). يرجى التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY"
            }
        if (now >= resolvedExpiresAt) {
            return body.message.ifBlank {
                "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد أو التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY"
            }
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
        existingUsed: Int,
        existingExpiresAt: Long? = null
    ): ActiveSubscriptionInfo {
        val now = System.currentTimeMillis()
        val resolvedCode = body.code.ifBlank { fallbackCode }.uppercase()
        val existingEntity = codeDao.getCode(resolvedCode)
        val activatedAt = normalizeEpochMillis(body.activatedAt) ?: existingEntity?.activatedAt ?: now
        val tier = resolvePlanTier(
            planType = extractPlanDescriptor(body),
            planLabel = body.planLabel.ifBlank { existingEntity?.label.orEmpty() },
            durationMonths = extractMonthsFromBody(body),
            durationDays = body.durationDays,
            durationMinutes = body.durationMinutes.takeIf { it > 0L } ?: (existingEntity?.durationMinutes ?: 0L)
        )
        val resolvedTier = if (tier == SubscriptionPlanTier.TIMED && existingEntity != null) {
            resolvePlanTier(
                planType = existingEntity.label,
                planLabel = existingEntity.label,
                durationMonths = 0,
                durationDays = 0,
                durationMinutes = existingEntity.durationMinutes
            )
        } else {
            tier
        }
        val expiresAt = resolveExpiresAt(
            body = body,
            activatedAt = activatedAt,
            tier = resolvedTier,
            existingExpiresAt = existingExpiresAt ?: existingEntity?.expiresAt
        )
        val resolvedMinutes = when {
            body.durationMinutes > 0L -> body.durationMinutes
            expiresAt != null && expiresAt > activatedAt -> ((expiresAt - activatedAt) / 60_000L).coerceAtLeast(1L)
            resolvedTier.fallbackDays > 0L -> resolvedTier.fallbackDays * 1440L
            else -> existingEntity?.durationMinutes ?: 0L
        }
        val label = body.planLabel.ifBlank {
            if (resolvedTier != SubscriptionPlanTier.TIMED) resolvedTier.displayTitle else existingEntity?.label.orEmpty().ifBlank { resolvedTier.displayTitle }
        }
        val boundUser = body.boundUserId?.ifBlank { null } ?: snapshot.userId
        val boundIp = body.boundIp?.ifBlank { null } ?: snapshot.publicIp
        val normalizedMax = body.maxContacts?.takeIf { it > 0 } ?: existingEntity?.maxContacts?.takeIf { it > 0 }
        val effectiveUsed = maxOf(existingUsed, body.usedContacts, existingEntity?.usedContacts ?: 0)

        codeDao.upsertCode(
            SubscriptionCodeEntity(
                code = resolvedCode,
                label = label,
                durationMinutes = resolvedMinutes,
                isLifetime = false,
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
            planTier = resolvedTier,
            isLifetime = false,
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
        val tier = resolvePlanTier(
            planType = label,
            planLabel = label,
            durationMonths = 0,
            durationDays = 0,
            durationMinutes = durationMinutes
        )
        return ActiveSubscriptionInfo(
            code = code,
            label = label.ifBlank { tier.displayTitle },
            planTier = tier,
            isLifetime = false,
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

}
