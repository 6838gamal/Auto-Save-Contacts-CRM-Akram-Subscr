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
import gamalprojects.autosavecontactscrm.akramalahmadi.network.PostgresConnectionConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.network.PostgresSubscriptionGateway
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

data class SessionVerifyOutcome(
    val activeInfo: ActiveSubscriptionInfo?,
    val freeAccessState: FreeAccessState,
    val isAllowed: Boolean,
    val dbStatus: DbConnectionStatus,
    val errorMessage: String?,
    val isConnectionError: Boolean
)

class SubscriptionRepository(
    private val db: AppDatabase,
    private val apiServiceOverride: SubscriptionApiService? = null,
    private val postgresGatewayOverride: PostgresSubscriptionGateway? = null
) {

    companion object {
        private const val KEY_ACTIVE_CODE = "active_subscription_code"
        private const val KEY_LOCK_REASON = "subscription_lock_reason"
        private const val KEY_SUBSCRIPTION_LOCKED = "subscription_explicitly_locked"
        private const val KEY_FREE_USED = "free_contacts_used"
        private const val KEY_FREE_EXHAUSTED = "free_tier_exhausted"
        private const val KEY_PENDING_DELETED_CLIENTS = "pending_deleted_clients_json"

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

    fun isDatabaseConfigured(): Boolean =
        resolvePostgresGateway() != null || resolveApiService() != null

    private fun resolvePostgresGateway(): PostgresSubscriptionGateway? {
        if (postgresGatewayOverride != null) return postgresGatewayOverride
        val cfg = PostgresConnectionConfig.parse(BuildConfig.DATABASE_URL) ?: return null
        return PostgresSubscriptionGateway(cfg)
    }

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
     * Runs on app launch (and on manual connection refresh):
     * 1. Captures user identity & public IP.
     * 2. Connects to PostgreSQL (via DATABASE_URL) and sends/syncs user telemetry immediately.
     * 3. Validates any active subscription or checks if the admin approved/bound a subscription in PostgreSQL.
     * 4. Never crashes if PostgreSQL is unreachable; alerts user to contact owner (+967 772 920 748).
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

        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            val outcome = verifyViaDirectPostgres(
                gateway = pgGateway,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = activeCodeKey,
                cachedUsedQuota = cached?.usedContacts ?: 0,
                explicitlyLocked = explicitlyLocked
            )
            if (outcome.dbStatus is DbConnectionStatus.Connected) {
                syncAllClientsToDatabase(context)
            }
            return@withContext outcome
        }

        val api = resolveApiService()
        if (api != null) {
            val outcome = verifyViaHttpApi(
                api = api,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = activeCodeKey,
                cachedUsedQuota = cached?.usedContacts ?: 0,
                explicitlyLocked = explicitlyLocked
            )
            if (outcome.dbStatus is DbConnectionStatus.Connected) {
                syncAllClientsToDatabase(context)
            }
            return@withContext outcome
        }

        // Database not configured: app still works normally and prompts to contact administration for review
        val notConfiguredStatus = DbConnectionStatus.NotConfigured(MSG_DB_NOT_CONFIGURED)
        if (!activeCodeKey.isNullOrBlank()) {
            if (cached != null && cached.isActive && cached.isSubscribed &&
                (cached.boundUserId.isNullOrBlank() || cached.boundUserId == snapshot.userId)
            ) {
                return@withContext SessionVerifyOutcome(
                    activeInfo = cached.toActiveInfo(snapshot),
                    freeAccessState = freeState,
                    isAllowed = true,
                    dbStatus = notConfiguredStatus,
                    errorMessage = null,
                    isConnectionError = false
                )
            }
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = !freeState.isExhausted && !explicitlyLocked,
                dbStatus = notConfiguredStatus,
                errorMessage = if (!freeState.isExhausted && !explicitlyLocked) null else MSG_DB_NOT_CONFIGURED,
                isConnectionError = true
            )
        }

        if (!freeState.isExhausted && !explicitlyLocked) {
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
            ?: if (freeState.isExhausted) MSG_FREE_LIMIT_REACHED else null
        return@withContext SessionVerifyOutcome(
            activeInfo = null,
            freeAccessState = freeState,
            isAllowed = false,
            dbStatus = notConfiguredStatus,
            errorMessage = reason,
            isConnectionError = false
        )
    }

    private suspend fun verifyViaDirectPostgres(
        gateway: PostgresSubscriptionGateway,
        snapshot: UserNetworkSnapshot,
        freeState: FreeAccessState,
        totalContacts: Int,
        activeCodeKey: String?,
        cachedUsedQuota: Int,
        explicitlyLocked: Boolean
    ): SessionVerifyOutcome {
        return try {
            val connectOutcome = gateway.connectAndSyncUser(
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = activeCodeKey
            )
            val dbStatus = DbConnectionStatus.Connected(connectOutcome.message)

            if (!activeCodeKey.isNullOrBlank()) {
                val verifiedBody = gateway.verifySession(
                    code = activeCodeKey,
                    snapshot = snapshot,
                    totalContacts = totalContacts,
                    usedQuota = cachedUsedQuota
                )
                val failureReason = validateApiStatusFlags(verifiedBody, cachedUsedQuota)
                if (failureReason == null) {
                    val info = persistActiveSubscription(activeCodeKey, verifiedBody, snapshot, existingUsed = cachedUsedQuota)
                    SessionVerifyOutcome(info, freeState, isAllowed = true, dbStatus = dbStatus, errorMessage = null, isConnectionError = false)
                } else {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, failureReason))
                    upsertUserProfile(snapshot, activeCode = null)
                    SessionVerifyOutcome(null, freeState, isAllowed = false, dbStatus = dbStatus, errorMessage = failureReason, isConnectionError = false)
                }
            } else {
                // Check if admin approved a subscription for this user_id directly in PostgreSQL
                val approved = connectOutcome.activeSubscription
                val failure = if (approved != null) validateApiStatusFlags(approved, 0) else null
                if (approved != null && failure == null) {
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                    val info = persistActiveSubscription(approved.code, approved, snapshot, existingUsed = approved.usedContacts)
                    return SessionVerifyOutcome(info, freeState, isAllowed = true, dbStatus = dbStatus, errorMessage = null, isConnectionError = false)
                }

                if (!freeState.isExhausted && !explicitlyLocked) {
                    SessionVerifyOutcome(null, freeState, isAllowed = true, dbStatus = dbStatus, errorMessage = null, isConnectionError = false)
                } else {
                    val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                        ?: MSG_FREE_LIMIT_REACHED
                    SessionVerifyOutcome(null, freeState, isAllowed = false, dbStatus = dbStatus, errorMessage = reason, isConnectionError = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val offlineStatus = DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
            val allowFree = activeCodeKey.isNullOrBlank() && !freeState.isExhausted && !explicitlyLocked
            val reason = if (allowFree) null else MSG_CONTACT_OWNER_OFFLINE
            SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = allowFree,
                dbStatus = offlineStatus,
                errorMessage = reason,
                isConnectionError = !allowFree
            )
        }
    }

    private suspend fun verifyViaHttpApi(
        api: SubscriptionApiService,
        snapshot: UserNetworkSnapshot,
        freeState: FreeAccessState,
        totalContacts: Int,
        activeCodeKey: String?,
        cachedUsedQuota: Int,
        explicitlyLocked: Boolean
    ): SessionVerifyOutcome {
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return try {
            // Send user telemetry on launch even before subscribing
            val connectResp = try {
                api.connectAndSyncUser(
                    authorization = authHeader,
                    apiKey = apiKeyHeader,
                    request = SubscriptionConnectRequest(
                        userId = snapshot.userId,
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
                        freeTierExhausted = freeState.isExhausted,
                        activeCode = activeCodeKey
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (activeCodeKey.isNullOrBlank()) throw t
                null
            }

            val connectedOk = connectResp?.isSuccessful == true
            val dbStatus = if (connectedOk) {
                DbConnectionStatus.Connected("متصل")
            } else if (connectResp != null && connectResp.code() >= 500) {
                throw IllegalStateException("Server 5xx")
            } else {
                DbConnectionStatus.Connected("متصل")
            }

            if (activeCodeKey.isNullOrBlank()) {
                val body = connectResp?.body()
                if (body != null && body.valid && body.isSubscribed && body.isActive && body.code.isNotBlank()) {
                    val failure = validateApiStatusFlags(body, 0)
                    if (failure == null) {
                        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                        val info = persistActiveSubscription(body.code, body, snapshot, existingUsed = body.usedContacts)
                        return SessionVerifyOutcome(info, freeState, true, dbStatus, null, false)
                    }
                }
                if (!freeState.isExhausted && !explicitlyLocked) {
                    return SessionVerifyOutcome(null, freeState, true, dbStatus, null, false)
                }
                val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
                    ?: MSG_FREE_LIMIT_REACHED
                return SessionVerifyOutcome(null, freeState, false, dbStatus, reason, false)
            }

            val response = api.verifySession(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionVerifyRequest(
                    code = activeCodeKey,
                    userId = snapshot.userId,
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
                if (body != null && failureReason == null) {
                    val info = persistActiveSubscription(activeCodeKey, body, snapshot, existingUsed = cachedUsedQuota)
                    SessionVerifyOutcome(info, freeState, true, DbConnectionStatus.Connected("متصل"), null, false)
                } else {
                    val err = failureReason ?: MSG_CONTACT_OWNER_OFFLINE
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, err))
                    upsertUserProfile(snapshot, activeCode = null)
                    SessionVerifyOutcome(null, freeState, false, dbStatus, err, false)
                }
            } else {
                val serverErr = extractErrorMessage(response.errorBody()?.string())
                if (response.code() in 400..499 && serverErr != null) {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, serverErr))
                    SessionVerifyOutcome(null, freeState, false, dbStatus, serverErr, false)
                } else {
                    SessionVerifyOutcome(null, freeState, false, DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE), MSG_CONTACT_OWNER_OFFLINE, true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            val offlineStatus = DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
            val allowFree = activeCodeKey.isNullOrBlank() && !freeState.isExhausted && !explicitlyLocked
            val reason = if (allowFree) null else MSG_CONTACT_OWNER_OFFLINE
            SessionVerifyOutcome(
                activeInfo = null,
                freeAccessState = freeState,
                isAllowed = allowFree,
                dbStatus = offlineStatus,
                errorMessage = reason,
                isConnectionError = !allowFree
            )
        }
    }

    private suspend fun pingAndSyncUserOnly(
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        activeCode: String?
    ): DbConnectionStatus {
        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            return try {
                val outcome = pgGateway.connectAndSyncUser(snapshot, totalContacts, freeState, activeCode)
                DbConnectionStatus.Connected(outcome.message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
            }
        }
        val api = resolveApiService()
        if (api != null) {
            val (authHeader, apiKeyHeader) = resolveAuthHeaders()
            return try {
                val resp = api.connectAndSyncUser(
                    authorization = authHeader,
                    apiKey = apiKeyHeader,
                    request = SubscriptionConnectRequest(
                        userId = snapshot.userId,
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
                        freeTierExhausted = freeState.isExhausted,
                        activeCode = activeCode
                    )
                )
                if (resp.isSuccessful) {
                    DbConnectionStatus.Connected("متصل")
                } else {
                    DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                DbConnectionStatus.Disconnected(MSG_CONTACT_OWNER_OFFLINE)
            }
        }
        return DbConnectionStatus.NotConfigured(MSG_DB_NOT_CONFIGURED)
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
        val freeState = getFreeAccessStateSync()
        val existingUsed = codeDao.getCode(code)?.usedContacts ?: 0

        // 1. Direct PostgreSQL connection via DATABASE_URL
        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            return@withContext try {
                val body = pgGateway.activateCode(
                    code = code,
                    snapshot = snapshot,
                    totalContacts = totalContacts,
                    freeState = freeState
                )
                val failureReason = validateApiStatusFlags(body, existingUsed)
                if (failureReason == null) {
                    settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
                    val info = persistActiveSubscription(code, body, snapshot, existingUsed = existingUsed)
                    ActivationResult.Success(info, snapshot, body.message.ifBlank { "تم التحقق من قاعدة البيانات وتفعيل الاشتراك بنجاح." })
                } else {
                    ActivationResult.Failure(reason = failureReason, isConnectionError = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ActivationResult.Failure(reason = MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
            }
        }

        // 2. HTTP Backend fronting PostgreSQL via DATABASE_URL
        val api = resolveApiService()
        if (api != null) {
            val (authHeader, apiKeyHeader) = resolveAuthHeaders()
            return@withContext try {
                val response = api.activateCode(
                    authorization = authHeader,
                    apiKey = apiKeyHeader,
                    request = SubscriptionActivateRequest(
                        code = code,
                        userId = snapshot.userId,
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
                        val msg = body.message.ifBlank { "تم التحقق من قاعدة البيانات وتفعيل الاشتراك بنجاح." }
                        ActivationResult.Success(info, snapshot, msg)
                    } else {
                        ActivationResult.Failure(
                            reason = failureReason ?: "كود الاشتراك غير صحيح أو بانتظار المراجعة في قاعدة البيانات.",
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

        // 3. No DATABASE_URL configured yet -> prompt to contact administration
        return@withContext ActivationResult.Failure(
            reason = MSG_DB_NOT_CONFIGURED,
            isConnectionError = true
        )
    }

    /**
     * Submits a formal activation / review request to PostgreSQL so the project owner
     * can review and approve the user from their external dashboard.
     */
    suspend fun submitReviewRequest(
        context: Context,
        rawCode: String = "",
        notes: String = ""
    ): ReviewRequestResult = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        upsertUserProfile(snapshot, activeCode = null)
        val totalContacts = contactDao.getContactsCountSync()
        val freeState = getFreeAccessStateSync()
        val cleanCode = rawCode.trim().uppercase()

        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            return@withContext try {
                val outcome = pgGateway.submitReviewRequest(
                    requestedCode = cleanCode,
                    snapshot = snapshot,
                    totalContacts = totalContacts,
                    freeState = freeState,
                    notes = notes
                )
                ReviewRequestResult.Success(outcome.message, outcome.requestId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ReviewRequestResult.Failure(MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
            }
        }

        val api = resolveApiService()
        if (api != null) {
            val (authHeader, apiKeyHeader) = resolveAuthHeaders()
            return@withContext try {
                val response = api.submitReviewRequest(
                    authorization = authHeader,
                    apiKey = apiKeyHeader,
                    request = SubscriptionReviewRequest(
                        userId = snapshot.userId,
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
                        ?: "تم إرسال طلب المراجعة إلى قاعدة البيانات بنجاح. تواصل مع الإدارة على $ADMIN_PHONE_DISPLAY"
                    ReviewRequestResult.Success(msg)
                } else {
                    val err = extractErrorMessage(response.errorBody()?.string())
                    ReviewRequestResult.Failure(err ?: MSG_CONTACT_OWNER_OFFLINE, isConnectionError = response.code() >= 500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                ReviewRequestResult.Failure(MSG_CONTACT_OWNER_OFFLINE, isConnectionError = true)
            }
        }

        ReviewRequestResult.Failure(MSG_DB_NOT_CONFIGURED, isConnectionError = true)
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
            settingDao.insertSetting(SettingEntity(KEY_FREE_USED, nextFreeUsed.toString()))

            if (nextFreeUsed >= FREE_CONTACTS_LIMIT) {
                settingDao.insertSetting(SettingEntity(KEY_FREE_EXHAUSTED, "1"))
                settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, MSG_FREE_LIMIT_REACHED))
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

    private suspend fun syncUsageToBackend(context: Context, code: String, usedCount: Int) {
        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        val totalContacts = contactDao.getContactsCountSync()
        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            try {
                pgGateway.reportUsage(code, snapshot, usedCount, totalContacts)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
            return
        }

        val api = resolveApiService() ?: return
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        try {
            api.reportUsage(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = SubscriptionUsageRequest(
                    code = code,
                    userId = snapshot.userId,
                    publicIp = snapshot.publicIp,
                    usedContacts = usedCount,
                    totalContacts = totalContacts
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun validateApiStatusFlags(body: SubscriptionApiResponse?, localUsed: Int): String? {
        if (body == null) return MSG_CONTACT_OWNER_OFFLINE
        if (!body.isSubscribed) {
            return body.message.ifBlank { "لم يتم الاشتراك بعد أو الكود قيد المراجعة. يرجى التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY" }
        }
        if (!body.isActive) {
            return body.message.ifBlank { "الاشتراك قيد المراجعة أو غير مفعل حالياً في قاعدة البيانات. يرجى التواصل مع $ADMIN_PHONE_DISPLAY" }
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

        val pgGateway = resolvePostgresGateway()
        if (pgGateway != null) {
            return@withContext try {
                pgGateway.syncClientsBatch(snapshot.userId, listOf(item))
                if (isDeleted) {
                    unmarkPendingClientDeleted(contact.phone)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }

        val api = resolveApiService() ?: return@withContext false
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val resp = api.syncClients(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = ClientSyncBatchRequest(
                    userId = snapshot.userId,
                    publicIp = snapshot.publicIp,
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
        val pgGateway = resolvePostgresGateway()
        val api = if (pgGateway == null) resolveApiService() else null
        if (pgGateway == null && api == null) return@withContext false

        val contacts = contactDao.getAllContactsSync()
        val activePhones = contacts.mapTo(HashSet()) { it.phone }
        val pendingDeletes = loadPendingDeletedClients().filterNot { it.phone in activePhones }
        if (pendingDeletes.size != loadPendingDeletedClients().size) {
            savePendingDeletedClients(pendingDeletes)
        }

        val items = contacts.map { it.toClientSyncItem(isDeleted = false) } + pendingDeletes
        if (items.isEmpty()) return@withContext true

        val snapshot = UserIdentityManager.getCachedSnapshot(context)
        if (pgGateway != null) {
            return@withContext try {
                pgGateway.syncClientsBatch(snapshot.userId, items)
                if (pendingDeletes.isNotEmpty()) {
                    savePendingDeletedClients(emptyList())
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
        }

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        return@withContext try {
            val resp = api!!.syncClients(
                authorization = authHeader,
                apiKey = apiKeyHeader,
                request = ClientSyncBatchRequest(
                    userId = snapshot.userId,
                    publicIp = snapshot.publicIp,
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
                sourceType = "POSTGRES_DB"
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
}
