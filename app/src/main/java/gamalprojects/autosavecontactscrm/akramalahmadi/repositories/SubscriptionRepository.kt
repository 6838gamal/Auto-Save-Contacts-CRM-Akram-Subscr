package gamalprojects.autosavecontactscrm.akramalahmadi.repositories

import android.content.Context
import gamalprojects.autosavecontactscrm.akramalahmadi.BuildConfig
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SettingEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.SubscriptionCodeEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.database.UserDeviceProfileEntity
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionActivateRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiResponse
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionApiService
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionUsageRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.network.SubscriptionVerifyRequest
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

data class SessionVerifyOutcome(
    val activeInfo: ActiveSubscriptionInfo?,
    val errorMessage: String?,
    val isConnectionError: Boolean
)

class SubscriptionRepository(
    private val db: AppDatabase,
    private val apiServiceOverride: SubscriptionApiService? = null
) {

    companion object {
        private const val KEY_ACTIVE_CODE = "active_subscription_code"
        private const val KEY_LOCK_REASON = "subscription_lock_reason"
        const val DEMO_TRIAL_CODE = "TRIAL-2026"
        const val DEMO_TRIAL_DURATION_MINUTES = 7L * 24L * 60L // 7 days
        const val DEMO_TRIAL_MAX_CONTACTS = 200
        const val MSG_CONTACT_OWNER_OFFLINE =
            "تعذر الاتصال بخادم قاعدة البيانات حالياً. يرجى التواصل مع صاحب المشروع للتحقق من اشتراكك أو تفعيله."
        const val MSG_TRIAL_QUOTA_EXCEEDED =
            "وصلت للحد الأقصى للنسخة التجريبية ($DEMO_TRIAL_MAX_CONTACTS جهة اتصال). يرجى إدخال كود اشتراك مدفوع للمتابعة."
    }

    private val codeDao = db.subscriptionCodeDao()
    private val profileDao = db.userDeviceProfileDao()
    private val settingDao = db.settingDao()
    private val contactDao = db.contactDao()

    fun isApiConfigured(): Boolean = resolveApiService() != null

    private fun resolveApiService(): SubscriptionApiService? {
        return apiServiceOverride ?: SubscriptionApiService.create(BuildConfig.SUBSCRIPTION_API_BASE_URL)
    }

    private fun resolveAuthHeaders(): Pair<String?, String?> {
        val key = BuildConfig.SUBSCRIPTION_API_KEY.trim()
        if (key.isBlank() || key == "MY_SUBSCRIPTION_API_KEY") {
            return null to null
        }
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

    suspend fun verifyCurrentSession(context: Context): SessionVerifyOutcome = withContext(Dispatchers.IO) {
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        if (activeCodeKey.isNullOrBlank()) {
            upsertUserProfile(snapshot, activeCode = null)
            val reason = settingDao.getSetting(KEY_LOCK_REASON)?.value?.takeIf { it.isNotBlank() }
            return@withContext SessionVerifyOutcome(null, reason, false)
        }

        val cached = codeDao.getCode(activeCodeKey)
        val now = System.currentTimeMillis()
        if (cached != null && !cached.isLifetime && cached.expiresAt != null && now >= cached.expiresAt) {
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            upsertUserProfile(snapshot, activeCode = null)
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                errorMessage = "انتهت مدة كود الاشتراك الخاص بك. يرجى إدخال كود جديد أو التواصل مع صاحب المشروع.",
                isConnectionError = false
            )
        }

        if (cached != null && cached.maxContacts != null && cached.maxContacts > 0 && cached.usedContacts >= cached.maxContacts) {
            settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
            upsertUserProfile(snapshot, activeCode = null)
            val msg = if (cached.isTrial) {
                MSG_TRIAL_QUOTA_EXCEEDED
            } else {
                "تم استهلاك الحد الأقصى لجهات الاتصال (${cached.maxContacts} جهة اتصال). يرجى تجديد أو ترقية الاشتراك."
            }
            return@withContext SessionVerifyOutcome(null, msg, false)
        }

        val api = resolveApiService()
        if (api == null) {
            if (activeCodeKey == DEMO_TRIAL_CODE && cached != null && cached.isActive && cached.isSubscribed &&
                cached.boundUserId == snapshot.userId && cached.boundAndroidId == snapshot.androidId
            ) {
                upsertUserProfile(snapshot, activeCode = cached.code)
                return@withContext SessionVerifyOutcome(cached.toActiveInfo(snapshot), null, false)
            }
            return@withContext SessionVerifyOutcome(
                activeInfo = null,
                errorMessage = MSG_CONTACT_OWNER_OFFLINE,
                isConnectionError = true
            )
        }

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        val totalContacts = contactDao.getContactsCountSync()
        val usedQuota = cached?.usedContacts ?: 0

        return@withContext try {
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
                    usedQuotaCount = usedQuota
                )
            )

            if (response.isSuccessful) {
                val body = response.body()
                val failureReason = validateApiStatusFlags(body, usedQuota)
                if (body != null && failureReason == null) {
                    val info = persistActiveSubscription(activeCodeKey, body, snapshot, existingUsed = usedQuota)
                    SessionVerifyOutcome(info, null, false)
                } else {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    upsertUserProfile(snapshot, activeCode = null)
                    SessionVerifyOutcome(null, failureReason ?: MSG_CONTACT_OWNER_OFFLINE, false)
                }
            } else {
                val serverErr = extractErrorMessage(response.errorBody()?.string())
                if (response.code() in 400..499 && serverErr != null) {
                    settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
                    SessionVerifyOutcome(null, serverErr, false)
                } else {
                    SessionVerifyOutcome(null, MSG_CONTACT_OWNER_OFFLINE, true)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            SessionVerifyOutcome(null, MSG_CONTACT_OWNER_OFFLINE, true)
        }
    }

    suspend fun activateSubscriptionCode(context: Context, rawCode: String): ActivationResult = withContext(Dispatchers.IO) {
        val code = rawCode.trim().uppercase()
        val snapshot = UserIdentityManager.fetchPublicNetworkInfo(context)
        upsertUserProfile(snapshot, activeCode = null)

        if (code.isBlank()) {
            return@withContext ActivationResult.Failure(
                reason = "يرجى إدخال كود الاشتراك أولاً.",
                isConnectionError = false
            )
        }

        val api = resolveApiService()
        if (api == null) {
            if (code == DEMO_TRIAL_CODE) {
                return@withContext activateDemoTrialCode(snapshot)
            }
            return@withContext ActivationResult.Failure(
                reason = MSG_CONTACT_OWNER_OFFLINE,
                isConnectionError = true
            )
        }

        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        val totalContacts = contactDao.getContactsCountSync()
        val existingUsed = codeDao.getCode(code)?.usedContacts ?: 0

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
                    val msg = body.message.ifBlank { "تم تفعيل الاشتراك بنجاح." }
                    ActivationResult.Success(info, snapshot, msg)
                } else {
                    ActivationResult.Failure(
                        reason = failureReason ?: "كود الاشتراك غير صحيح أو مستخدم مسبقاً على جهاز آخر.",
                        isConnectionError = false
                    )
                }
            } else {
                val parsed = extractErrorMessage(response.errorBody()?.string())
                if (response.code() in 400..499) {
                    ActivationResult.Failure(
                        reason = parsed ?: "كود الاشتراك غير صالح أو مرفوض من الخادم.",
                        isConnectionError = false
                    )
                } else {
                    ActivationResult.Failure(
                        reason = MSG_CONTACT_OWNER_OFFLINE,
                        isConnectionError = true
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            ActivationResult.Failure(
                reason = MSG_CONTACT_OWNER_OFFLINE,
                isConnectionError = true
            )
        }
    }

    /**
     * Checks whether the active subscription allows saving a new contact right now.
     */
    suspend fun canSaveNewContact(): Boolean = withContext(Dispatchers.IO) {
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
        if (activeCodeKey.isNullOrBlank()) return@withContext false
        val entity = codeDao.getCode(activeCodeKey) ?: return@withContext false
        if (!entity.isSubscribed || !entity.isActive) return@withContext false
        val now = System.currentTimeMillis()
        if (!entity.isLifetime && entity.expiresAt != null && now >= entity.expiresAt) return@withContext false
        val max = entity.maxContacts
        if (max != null && max > 0 && entity.usedContacts >= max) return@withContext false
        true
    }

    /**
     * Increments usedContacts after saving a new contact.
     * When the quota limit (e.g. 200 contacts for trial) is reached, locks the session and forces
     * the user back to the subscription gate screen to enter a paid code.
     */
    suspend fun recordContactSaved(context: Context): Boolean = withContext(Dispatchers.IO) {
        val activeCodeKey = settingDao.getSetting(KEY_ACTIVE_CODE)?.value?.trim()?.uppercase()
            ?: return@withContext false
        val entity = codeDao.getCode(activeCodeKey) ?: return@withContext false

        val nextUsed = entity.usedContacts + 1
        val max = entity.maxContacts
        val reachedLimit = max != null && max > 0 && nextUsed >= max

        val updated = entity.copy(
            usedContacts = nextUsed,
            isActive = if (reachedLimit && entity.isTrial) false else entity.isActive
        )
        codeDao.upsertCode(updated)

        // Best-effort sync to PostgreSQL backend API
        syncUsageToBackend(context, updated.code, nextUsed)

        if (reachedLimit) {
            val lockMessage = if (entity.isTrial) {
                MSG_TRIAL_QUOTA_EXCEEDED
            } else {
                "تم استهلاك الحد الأقصى لحفظ جهات الاتصال ($max جهة اتصال). يرجى إدخال كود اشتراك جديد."
            }
            settingDao.insertSetting(SettingEntity(KEY_LOCK_REASON, lockMessage))
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
        val api = resolveApiService() ?: return
        val (authHeader, apiKeyHeader) = resolveAuthHeaders()
        try {
            val snapshot = UserIdentityManager.getCachedSnapshot(context)
            val totalContacts = contactDao.getContactsCountSync()
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
            // Never crash on background usage sync failure
        }
    }

    private fun validateApiStatusFlags(body: SubscriptionApiResponse?, localUsed: Int): String? {
        if (body == null) return MSG_CONTACT_OWNER_OFFLINE
        if (!body.isSubscribed) {
            return body.message.ifBlank { "لم يتم الاشتراك بعد. يرجى إدخال كود اشتراك صالح." }
        }
        if (!body.isActive) {
            return body.message.ifBlank { "الاشتراك غير مفعل حالياً. يرجى التواصل مع صاحب المشروع." }
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

    suspend fun lockSession() = withContext(Dispatchers.IO) {
        settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, ""))
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
                sourceType = if (resolvedCode == DEMO_TRIAL_CODE && resolveApiService() == null) "DEMO_TRIAL" else "POSTGRES_API"
            )
        )
        settingDao.insertSetting(SettingEntity(KEY_ACTIVE_CODE, resolvedCode))
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
