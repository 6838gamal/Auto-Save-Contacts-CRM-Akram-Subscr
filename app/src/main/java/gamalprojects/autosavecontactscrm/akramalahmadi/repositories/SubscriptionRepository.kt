package gamalprojects.autosavecontactscrm.akramalahmadi.repositories

import android.content.Context
import gamalprojects.autosavecontactscrm.akramalahmadi.BuildConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SettingEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SubscriptionCodeEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.UserDeviceProfileEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
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

        const val FREE_CONTACTS_LIMIT = 100
        const val ADMIN_PHONE_DISPLAY = "+967 772 920 748"
        const val ADMIN_PHONE_DIGITS = "967772920748"

        const val DEMO_TRIAL_CODE = "TRIAL-2026"
        const val DEMO_TRIAL_DURATION_MINUTES = 7L * 24L * 60L // 7 days
        const val DEMO_TRIAL_MAX_CONTACTS = 200
        const val MSG_CONTACT_OWNER_OFFLINE =
            "يرجى التواصل مع الإدارة لمراجعة الأمر والتحقق من الاتصال على الرقم $ADMIN_PHONE_DISPLAY"
        const val MSG_TRIAL_QUOTA_EXCEEDED =
            "وصلت للحد الأقصى للنسخة التجريبية ($DEMO_TRIAL_MAX_CONTACTS جهة اتصال). يرجى إدخال كود اشتراك مدفوع أو التواصل مع الإدارة على $ADMIN_PHONE_DISPLAY"
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

    suspend fun isDemoTrialExhausted(): Boolean = withContext(Dispatchers.IO) {
        val demo = codeDao.getCode(DEMO_TRIAL_CODE) ?: return@withContext false
        val max = demo.maxContacts ?: DEMO_TRIAL_MAX_CONTACTS
        !demo.isActive || (max > 0 && demo.usedContacts >= max)
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
            val msg = if (cached.isTrial) {
                MSG_TRIAL_QUOTA_EXCEEDED
            } else {
                "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
            }
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "1"))
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, msg))
            upsertUserProfile(snapshot, activeCode = null)
            val syncStatus = pingAndSyncUserOnly(snapshot, totalContacts, freeState, null)
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
            return@withContext verifyViaDirectPostgres(
                gateway = pgGateway,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = activeCodeKey,
                cachedUsedQuota = cached?.usedContacts ?: 0,
                explicitlyLocked = explicitlyLocked
            )
        }

        val api = resolveApiService()
        if (api != null) {
            return@withContext verifyViaHttpApi(
                api = api,
                snapshot = snapshot,
                freeState = freeState,
                totalContacts = totalContacts,
                activeCodeKey = activeCodeKey,
                cachedUsedQuota = cached?.usedContacts ?: 0,
                explicitlyLocked = explicitlyLocked
            )
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
                DbConnectionStatus.Connected("متصل بخادم PostgreSQL وتم إرسال بيانات المستخدم")
            } else if (connectResp != null && connectResp.code() >= 500) {
                throw IllegalStateException("Server 5xx")
            } else {
                DbConnectionStatus.Connected("متصل بقاعدة البيانات")
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
                    SessionVerifyOutcome(info, freeState, true, DbConnectionStatus.Connected("متصل بقاعدة البيانات والاشتراك مفعل"), null, false)
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
                    DbConnectionStatus.Connected("متصل بقاعدة البيانات")
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

        // 3. No DATABASE_URL configured yet -> only demo TRIAL-2026 allowed locally
        if (code == DEMO_TRIAL_CODE) {
            return@withContext activateDemoTrialCode(snapshot)
        }
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
            isActive = if (reachedLimit && entity.isTrial) false else entity.isActive
        )
        codeDao.upsertCode(updated)

        syncUsageToBackend(context, updated.code, nextUsed)

        if (reachedLimit) {
            val lockMessage = if (entity.isTrial) {
                MSG_TRIAL_QUOTA_EXCEEDED
            } else {
                "تم استهلاك الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال). يرجى إدخال كود اشتراك جديد."
            }
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
            return if (body.isTrial) {
                MSG_TRIAL_QUOTA_EXCEEDED
            } else {
                body.message.ifBlank { "تم الوصول إلى الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال)." }
            }
        }
        return null
    }

    private suspend fun activateDemoTrialCode(snapshot: UserNetworkSnapshot): ActivationResult {
        val now = System.currentTimeMillis()
        val existing = codeDao.getCode(DEMO_TRIAL_CODE)
        if (existing != null) {
            if (!existing.boundUserId.isNullOrBlank() && existing.boundUserId != snapshot.userId) {
                return ActivationResult.Failure(
                    reason = "الكود التجريبي مرتبط بمعرف مستخدم آخر ولا يمكن تكراره.",
                    isConnectionError = false
                )
            }
            val max = existing.maxContacts ?: DEMO_TRIAL_MAX_CONTACTS
            if (!existing.isActive || (max > 0 && existing.usedContacts >= max)) {
                return ActivationResult.Failure(
                    reason = MSG_TRIAL_QUOTA_EXCEEDED,
                    isConnectionError = false
                )
            }
            if (existing.expiresAt != null && now >= existing.expiresAt) {
                return ActivationResult.Failure(
                    reason = "انتهت مدة الكود التجريبي الافتراضي. يرجى إدخال كود مدفوع أو التواصل مع صاحب المشروع.",
                    isConnectionError = false
                )
            }
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
            settingDao.insertSetting(SettingEntity(KEY_SUBSCRIPTION_LOCKED, "0"))
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, existing.code))
            upsertUserProfile(snapshot, activeCode = existing.code)
            return ActivationResult.Success(
                info = existing.toActiveInfo(snapshot),
                snapshot = snapshot,
                message = "تم تفعيل الكود التجريبي بنجاح (الحد الأقصى $max جهة اتصال)."
            )
        }

        val response = SubscriptionApiResponse(
            valid = true,
            isSubscribed = true,
            isActive = true,
            isTrial = true,
            status = "active",
            message = "تم تفعيل الكود التجريبي (7 أيام / حد أقصى 200 جهة اتصال)",
            code = DEMO_TRIAL_CODE,
            planLabel = "باقة تجريبية (حد 200 جهة اتصال)",
            durationMinutes = DEMO_TRIAL_DURATION_MINUTES,
            isLifetime = false,
            maxContacts = DEMO_TRIAL_MAX_CONTACTS,
            usedContacts = 0,
            activatedAt = now,
            expiresAt = now + DEMO_TRIAL_DURATION_MINUTES * 60_000L,
            boundUserId = snapshot.userId,
            boundIp = snapshot.publicIp
        )
        settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, ""))
        val info = persistActiveSubscription(DEMO_TRIAL_CODE, response, snapshot, existingUsed = 0)
        return ActivationResult.Success(info, snapshot, response.message)
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
        val label = body.planLabel.ifBlank { if (body.isTrial) "باقة تجريبية" else "اشتراك مفعل" }
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
                sourceType = if (resolvedCode == DEMO_TRIAL_CODE && !isDatabaseConfigured()) "DEMO_TRIAL" else "POSTGRES_DB"
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
            label = label.ifBlank { if (isTrial) "باقة تجريبية" else "اشتراك مفعل" },
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
