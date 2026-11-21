package gamalprojects.autosavecontactscrm.akramalahmadi.network

import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserIdentityManager
import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.FreeAccessState
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository

data class RemoteConnectOutcome(
    val connected: Boolean,
    val message: String,
    val activeSubscription: SubscriptionApiResponse? = null,
    val serverFreeUsed: Int = 0,
    val serverFreeExhausted: Boolean = false
)

data class RemoteReviewOutcome(
    val submitted: Boolean,
    val message: String,
    val requestId: String? = null
)

private data class SyncedDeviceRecord(
    val canonicalUserId: String,
    val deviceId: String,
    val serverFreeUsed: Int,
    val serverFreeExhausted: Boolean,
    val serverActiveCode: String?,
    val serverIsSubscribed: Boolean,
    val wasNewlyInserted: Boolean
)

class PostgresSubscriptionGateway(
    private val config: PostgresConnectionConfig,
    private val clientFactory: (PostgresConnectionConfig) -> PostgresSqlExecutor = { PostgresWireClient(it) }
) {

    fun connectAndSyncUser(
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        activeCode: String?
    ): RemoteConnectOutcome {
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            val synced = upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = activeCode,
                isSubscribed = !activeCode.isNullOrBlank()
            )

            // Check if this device has an approved/bound active subscription in PostgreSQL
            val approvedCode = findApprovedCodeForUser(
                client = client,
                snapshot = snapshot,
                canonicalUserId = synced.canonicalUserId,
                deviceId = synced.deviceId,
                preferredCode = activeCode ?: synced.serverActiveCode
            )
            return RemoteConnectOutcome(
                connected = true,
                message = "متصل",
                activeSubscription = approvedCode,
                serverFreeUsed = synced.serverFreeUsed,
                serverFreeExhausted = synced.serverFreeExhausted
            )
        }
    }

    fun activateCode(
        code: String,
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState
    ): SubscriptionApiResponse {
        val cleanCode = code.trim().uppercase()
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            val synced = upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = null,
                isSubscribed = false
            )

            val queryResult = client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_android_id,
                       bound_ip, activated_at, expires_at
                FROM subscription_codes
                WHERE UPPER(code) = UPPER($1)
                LIMIT 1
                """.trimIndent(),
                listOf(cleanCode)
            )

            val row = queryResult.rows.firstOrNull()
            if (row == null) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
                    canonicalUserId = synced.canonicalUserId,
                    deviceId = synced.deviceId,
                    requestedCode = cleanCode,
                    requestType = "CODE_VERIFICATION",
                    status = "pending_review",
                    totalContacts = totalContacts,
                    notes = "محاولة تحقق من كود غير مسجل - بانتظار المراجعة"
                )
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = false,
                    isActive = false,
                    status = "pending_review",
                    message = "الكود غير مسجل أو بانتظار المراجعة في قاعدة البيانات. تم تسجيل طلب تحقق؛ يرجى التواصل مع الإدارة على ${SubscriptionRepository.ADMIN_PHONE_DISPLAY}",
                    code = cleanCode
                )
            }

            val now = System.currentTimeMillis()
            val dbCode = row["code"] ?: cleanCode
            val planLabel = row["plan_label"].orEmpty()
            val isSubscribed = parsePgBool(row["is_subscribed"], default = true)
            val isActive = parsePgBool(row["is_active"], default = true)
            val isTrial = parsePgBool(row["is_trial"], default = false)
            val isLifetime = parsePgBool(row["is_lifetime"], default = false)
            val durationMinutes = row["duration_minutes"]?.toLongOrNull() ?: 43200L
            val maxContacts = row["max_contacts"]?.toIntOrNull()
            val usedContacts = row["used_contacts"]?.toIntOrNull() ?: 0
            val boundUserId = row["bound_user_id"]?.takeIf { it.isNotBlank() }
            val boundAndroidId = row["bound_android_id"]?.takeIf { it.isNotBlank() }

            if (!isSubscribed || !isActive) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
                    canonicalUserId = synced.canonicalUserId,
                    deviceId = synced.deviceId,
                    requestedCode = dbCode,
                    requestType = "CODE_VERIFICATION",
                    status = "pending_review",
                    totalContacts = totalContacts,
                    notes = "الكود غير مفعل أو قيد المراجعة من الإدارة"
                )
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = isSubscribed,
                    isActive = isActive,
                    isTrial = isTrial,
                    status = "inactive",
                    message = "الكود قيد المراجعة أو غير مفعل حالياً في قاعدة البيانات. يرجى التواصل مع الإدارة على ${SubscriptionRepository.ADMIN_PHONE_DISPLAY}",
                    code = dbCode
                )
            }

            if (isBoundToDifferentDevice(
                    boundUserId = boundUserId,
                    boundAndroidId = boundAndroidId,
                    snapshot = snapshot,
                    canonicalUserId = synced.canonicalUserId,
                    deviceId = synced.deviceId
                )
            ) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
                    canonicalUserId = synced.canonicalUserId,
                    deviceId = synced.deviceId,
                    requestedCode = dbCode,
                    requestType = "CODE_VERIFICATION",
                    status = "rejected_duplicate",
                    totalContacts = totalContacts,
                    notes = "محاولة استخدام كود مرتبط بجهاز آخر ($boundUserId)"
                )
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = true,
                    isActive = true,
                    status = "duplicate_device",
                    message = "هذا الكود مستخدم مسبقاً على جهاز آخر ولا يمكن تكراره.",
                    code = dbCode
                )
            }

            val activatedAt = row["activated_at"]?.toLongOrNull() ?: now
            val expiresAt = if (isLifetime) {
                null
            } else {
                row["expires_at"]?.toLongOrNull() ?: if (durationMinutes > 0L) {
                    activatedAt + durationMinutes * 60_000L
                } else {
                    null
                }
            }

            if (expiresAt != null && now >= expiresAt) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
                    canonicalUserId = synced.canonicalUserId,
                    deviceId = synced.deviceId,
                    requestedCode = dbCode,
                    requestType = "CODE_VERIFICATION",
                    status = "expired",
                    totalContacts = totalContacts,
                    notes = "كود منتهي الصلاحية"
                )
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = true,
                    isActive = false,
                    status = "expired",
                    message = "انتهت مدة صلاحية هذا الكود. يرجى تجديد الاشتراك عبر الإدارة ${SubscriptionRepository.ADMIN_PHONE_DISPLAY}",
                    code = dbCode
                )
            }

            if (maxContacts != null && maxContacts > 0 && usedContacts >= maxContacts) {
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = true,
                    isActive = false,
                    isTrial = isTrial,
                    maxContacts = maxContacts,
                    usedContacts = usedContacts,
                    status = "quota_exceeded",
                    message = "تم استهلاك الحد الأقصى لجهات الاتصال في هذا الكود ($maxContacts).",
                    code = dbCode
                )
            }

            // Bind code to this immutable device + canonical userId in PostgreSQL
            client.query(
                """
                UPDATE subscription_codes
                SET bound_user_id = $2,
                    bound_android_id = $3,
                    bound_ip = $4,
                    activated_at = COALESCE(activated_at, $5::bigint),
                    expires_at = $6::bigint,
                    updated_at = NOW()
                WHERE UPPER(code) = UPPER($1)
                """.trimIndent(),
                listOf(
                    dbCode,
                    synced.canonicalUserId,
                    synced.deviceId,
                    snapshot.publicIp,
                    activatedAt.toString(),
                    expiresAt?.toString()
                )
            )

            upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = dbCode,
                isSubscribed = true
            )

            insertRequestRecord(
                client = client,
                snapshot = snapshot,
                canonicalUserId = synced.canonicalUserId,
                deviceId = synced.deviceId,
                requestedCode = dbCode,
                requestType = "CODE_ACTIVATED",
                status = "approved",
                totalContacts = totalContacts,
                notes = "تم التحقق وتفعيل الكود بنجاح"
            )

            return SubscriptionApiResponse(
                valid = true,
                isSubscribed = true,
                isActive = true,
                isTrial = isTrial,
                status = "active",
                message = "تم التحقق من قاعدة البيانات وتفعيل الاشتراك بنجاح.",
                code = dbCode,
                planLabel = planLabel.ifBlank { "اشتراك مفعل" },
                durationMinutes = durationMinutes,
                isLifetime = isLifetime,
                maxContacts = maxContacts,
                usedContacts = usedContacts,
                activatedAt = activatedAt,
                expiresAt = expiresAt,
                boundUserId = synced.canonicalUserId,
                boundIp = snapshot.publicIp
            )
        }
    }

    fun verifySession(
        code: String,
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        usedQuota: Int
    ): SubscriptionApiResponse {
        val cleanCode = code.trim().uppercase()
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)

            val existingDevice = findExistingDeviceRow(client, snapshot, deviceId)
            val canonicalUserId = existingDevice?.get("user_id")?.takeIf { it.isNotBlank() } ?: snapshot.userId

            val queryResult = client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_android_id,
                       bound_ip, activated_at, expires_at
                FROM subscription_codes
                WHERE UPPER(code) = UPPER($1)
                LIMIT 1
                """.trimIndent(),
                listOf(cleanCode)
            )
            val row = queryResult.rows.firstOrNull()
                ?: return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = false,
                    isActive = false,
                    status = "not_found",
                    message = "لم يتم العثور على الكود في قاعدة البيانات."
                )

            val isSubscribed = parsePgBool(row["is_subscribed"], default = false)
            val isActive = parsePgBool(row["is_active"], default = false)
            val isTrial = parsePgBool(row["is_trial"], default = false)
            val isLifetime = parsePgBool(row["is_lifetime"], default = false)
            val durationMinutes = row["duration_minutes"]?.toLongOrNull() ?: 43200L
            val maxContacts = row["max_contacts"]?.toIntOrNull()
            val dbUsed = row["used_contacts"]?.toIntOrNull() ?: 0
            val effectiveUsed = maxOf(dbUsed, usedQuota)
            val boundUserId = row["bound_user_id"]?.takeIf { it.isNotBlank() }
            val boundAndroidId = row["bound_android_id"]?.takeIf { it.isNotBlank() }
            val activatedAt = row["activated_at"]?.toLongOrNull()
            val expiresAt = row["expires_at"]?.toLongOrNull()

            if (isBoundToDifferentDevice(
                    boundUserId = boundUserId,
                    boundAndroidId = boundAndroidId,
                    snapshot = snapshot,
                    canonicalUserId = canonicalUserId,
                    deviceId = deviceId
                )
            ) {
                return SubscriptionApiResponse(
                    valid = false,
                    isSubscribed = false,
                    isActive = false,
                    status = "duplicate_device",
                    message = "الكود مرتبط بمعرف جهاز آخر."
                )
            }

            if (effectiveUsed > dbUsed) {
                client.query(
                    """
                    UPDATE subscription_codes
                    SET used_contacts = GREATEST(used_contacts, $2::int), updated_at = NOW()
                    WHERE UPPER(code) = UPPER($1)
                    """.trimIndent(),
                    listOf(cleanCode, effectiveUsed.toString())
                )
            }

            // Check first: if device record exists update it by canonical user_id; otherwise insert it first
            if (existingDevice != null) {
                client.query(
                    """
                    UPDATE crm_users
                    SET device_id = $2,
                        android_id = $3,
                        public_ip = $4,
                        local_ip = $5,
                        device_model = $6,
                        os_version = $7,
                        network_type = $8,
                        saved_contacts_count = GREATEST(saved_contacts_count, $9::int),
                        active_code = COALESCE($10, active_code),
                        is_subscribed = TRUE,
                        plan_type = 'SUBSCRIBED',
                        last_seen_at = NOW()
                    WHERE user_id = $1
                    """.trimIndent(),
                    listOf(
                        canonicalUserId,
                        deviceId,
                        snapshot.androidId,
                        snapshot.publicIp,
                        snapshot.localIp,
                        snapshot.deviceModel,
                        snapshot.osVersion,
                        snapshot.networkType,
                        totalContacts.toString(),
                        cleanCode
                    )
                )
            } else {
                insertNewDeviceRow(
                    client = client,
                    snapshot = snapshot,
                    deviceId = deviceId,
                    totalContacts = totalContacts,
                    freeState = FreeAccessState(
                        usedContacts = 0,
                        limit = SubscriptionRepository.FREE_CONTACTS_LIMIT,
                        isExhausted = false
                    ),
                    planType = "SUBSCRIBED",
                    activeCode = cleanCode,
                    isSubscribed = isSubscribed && isActive
                )
            }

            return SubscriptionApiResponse(
                valid = isSubscribed && isActive,
                isSubscribed = isSubscribed,
                isActive = isActive,
                isTrial = isTrial,
                status = if (isActive) "active" else "inactive",
                message = if (isActive) "الاشتراك نشط" else "تم إيقاف الاشتراك من لوحة التحكم",
                code = row["code"] ?: cleanCode,
                planLabel = row["plan_label"].orEmpty(),
                durationMinutes = durationMinutes,
                isLifetime = isLifetime,
                maxContacts = maxContacts,
                usedContacts = effectiveUsed,
                activatedAt = activatedAt,
                expiresAt = expiresAt,
                boundUserId = boundUserId ?: canonicalUserId,
                boundIp = row["bound_ip"] ?: snapshot.publicIp
            )
        }
    }

    fun reportUsage(
        code: String,
        snapshot: UserNetworkSnapshot,
        usedContacts: Int,
        totalContacts: Int
    ) {
        val cleanCode = code.trim().uppercase()
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            client.query(
                """
                UPDATE subscription_codes
                SET used_contacts = GREATEST(used_contacts, $2::int), updated_at = NOW()
                WHERE UPPER(code) = UPPER($1)
                """.trimIndent(),
                listOf(cleanCode, usedContacts.toString())
            )
            // Check first whether device exists in crm_users, update if found or insert if missing
            val existing = findExistingDeviceRow(client, snapshot, deviceId)
            if (existing != null) {
                val canonicalUserId = existing["user_id"]?.takeIf { it.isNotBlank() } ?: snapshot.userId
                client.query(
                    """
                    UPDATE crm_users
                    SET device_id = $2,
                        android_id = $3,
                        saved_contacts_count = GREATEST(saved_contacts_count, $4::int),
                        public_ip = $5,
                        local_ip = $6,
                        plan_type = 'SUBSCRIBED',
                        active_code = COALESCE($7, active_code),
                        is_subscribed = TRUE,
                        last_seen_at = NOW()
                    WHERE user_id = $1
                    """.trimIndent(),
                    listOf(
                        canonicalUserId,
                        deviceId,
                        snapshot.androidId,
                        totalContacts.toString(),
                        snapshot.publicIp,
                        snapshot.localIp,
                        cleanCode
                    )
                )
            } else {
                insertNewDeviceRow(
                    client = client,
                    snapshot = snapshot,
                    deviceId = deviceId,
                    totalContacts = totalContacts,
                    freeState = FreeAccessState(
                        usedContacts = 0,
                        limit = SubscriptionRepository.FREE_CONTACTS_LIMIT,
                        isExhausted = false
                    ),
                    planType = "SUBSCRIBED",
                    activeCode = cleanCode,
                    isSubscribed = true
                )
            }
        }
    }

    fun reportFreeUsage(
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState
    ) {
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = null,
                isSubscribed = false
            )
        }
    }

    fun submitReviewRequest(
        requestedCode: String,
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        notes: String
    ): RemoteReviewOutcome {
        val cleanCode = requestedCode.trim().uppercase()
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            val synced = upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = null,
                isSubscribed = false
            )
            val id = insertRequestRecord(
                client = client,
                snapshot = snapshot,
                canonicalUserId = synced.canonicalUserId,
                deviceId = synced.deviceId,
                requestedCode = cleanCode,
                requestType = "REVIEW_REQUEST",
                status = "pending",
                totalContacts = totalContacts,
                notes = notes.ifBlank { "طلب مراجعة وتفعيل اشتراك من داخل التطبيق" }
            )
            val reqTag = if (id != null) "#$id" else ""
            return RemoteReviewOutcome(
                submitted = true,
                requestId = id,
                message = "تم إرسال طلب المراجعة $reqTag إلى قاعدة البيانات بنجاح. يرجى التواصل مع الإدارة على ${SubscriptionRepository.ADMIN_PHONE_DISPLAY} لتسريع التفعيل."
            )
        }
    }

    fun syncClientsBatch(
        userId: String,
        clients: List<ClientSyncItem>,
        planType: String = "FREE",
        deviceId: String = ""
    ) {
        if (clients.isEmpty()) return
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)

            // Resolve canonical user_id from immutable device_id first so clients always link cleanly
            val effectiveDeviceId = deviceId.ifBlank { userId }
            val canonicalUserId = if (deviceId.isNotBlank()) {
                val res = client.query(
                    """
                    SELECT user_id
                    FROM crm_users
                    WHERE device_id = $1
                       OR (android_id = $1 AND android_id <> 'android_id_unavailable' AND android_id <> '')
                       OR user_id = $2
                    ORDER BY CASE WHEN device_id = $1 THEN 0 WHEN android_id = $1 THEN 1 ELSE 2 END,
                             last_seen_at DESC
                    LIMIT 1
                    """.trimIndent(),
                    listOf(deviceId, userId)
                )
                res.rows.firstOrNull()?.get("user_id")?.takeIf { it.isNotBlank() } ?: userId
            } else {
                userId
            }

            for (item in clients) {
                client.query(
                    """
                    INSERT INTO crm_clients (
                        user_id, device_id, phone, original_phone, name, source,
                        created_at, last_activity, interaction_count,
                        is_synced_to_phone, is_deleted, plan_type, updated_at
                    )
                    VALUES (
                        $1, $2, $3, $4, $5, $6,
                        $7::bigint, $8::bigint, $9::int,
                        $10::boolean, $11::boolean, $12, NOW()
                    )
                    ON CONFLICT (user_id, phone) DO UPDATE SET
                        device_id = CASE WHEN EXCLUDED.device_id <> '' THEN EXCLUDED.device_id ELSE crm_clients.device_id END,
                        original_phone = EXCLUDED.original_phone,
                        name = EXCLUDED.name,
                        source = EXCLUDED.source,
                        last_activity = GREATEST(crm_clients.last_activity, EXCLUDED.last_activity),
                        interaction_count = GREATEST(crm_clients.interaction_count, EXCLUDED.interaction_count),
                        is_synced_to_phone = crm_clients.is_synced_to_phone OR EXCLUDED.is_synced_to_phone,
                        is_deleted = EXCLUDED.is_deleted,
                        plan_type = EXCLUDED.plan_type,
                        updated_at = NOW()
                    """.trimIndent(),
                    listOf(
                        canonicalUserId,
                        effectiveDeviceId,
                        item.phone,
                        item.originalPhone,
                        item.name,
                        item.source,
                        item.createdAt.toString(),
                        item.lastActivity.toString(),
                        item.interactionCount.toString(),
                        item.isSyncedToPhone.toString(),
                        item.isDeleted.toString(),
                        planType
                    )
                )
            }
        }
    }

    private fun ensureSchema(client: PostgresSqlExecutor) {
        client.executeSimple(
            """
            CREATE TABLE IF NOT EXISTS crm_users (
                user_id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL DEFAULT '',
                android_id TEXT NOT NULL,
                install_uuid TEXT NOT NULL,
                public_ip TEXT NOT NULL,
                local_ip TEXT NOT NULL,
                location_info TEXT NOT NULL DEFAULT '',
                device_model TEXT NOT NULL DEFAULT '',
                os_version TEXT NOT NULL DEFAULT '',
                network_type TEXT NOT NULL DEFAULT '',
                saved_contacts_count INTEGER NOT NULL DEFAULT 0,
                free_contacts_used INTEGER NOT NULL DEFAULT 0,
                free_contacts_limit INTEGER NOT NULL DEFAULT 100,
                free_tier_exhausted BOOLEAN NOT NULL DEFAULT FALSE,
                plan_type TEXT NOT NULL DEFAULT 'FREE',
                active_code TEXT,
                is_subscribed BOOLEAN NOT NULL DEFAULT FALSE,
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent()
        )
        client.executeSimple(
            "ALTER TABLE crm_users ADD COLUMN IF NOT EXISTS device_id TEXT NOT NULL DEFAULT ''"
        )
        client.executeSimple(
            "ALTER TABLE crm_users ADD COLUMN IF NOT EXISTS plan_type TEXT NOT NULL DEFAULT 'FREE'"
        )
        client.executeSimple(
            "ALTER TABLE crm_users ADD COLUMN IF NOT EXISTS free_contacts_limit INTEGER NOT NULL DEFAULT 100"
        )
        client.executeSimple(
            "CREATE INDEX IF NOT EXISTS idx_crm_users_device_id ON crm_users (device_id)"
        )
        client.executeSimple(
            "CREATE INDEX IF NOT EXISTS idx_crm_users_android_id ON crm_users (android_id)"
        )
        client.executeSimple(
            """
            CREATE TABLE IF NOT EXISTS subscription_codes (
                code TEXT PRIMARY KEY,
                plan_label TEXT NOT NULL DEFAULT 'اشتراك مفعل',
                is_subscribed BOOLEAN NOT NULL DEFAULT TRUE,
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                is_trial BOOLEAN NOT NULL DEFAULT FALSE,
                is_lifetime BOOLEAN NOT NULL DEFAULT FALSE,
                duration_minutes BIGINT NOT NULL DEFAULT 43200,
                max_contacts INTEGER,
                used_contacts INTEGER NOT NULL DEFAULT 0,
                bound_user_id TEXT,
                bound_android_id TEXT,
                bound_ip TEXT,
                activated_at BIGINT,
                expires_at BIGINT,
                notes TEXT NOT NULL DEFAULT '',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent()
        )
        client.executeSimple(
            "ALTER TABLE subscription_codes ADD COLUMN IF NOT EXISTS bound_android_id TEXT"
        )
        client.executeSimple(
            """
            CREATE TABLE IF NOT EXISTS subscription_requests (
                id BIGSERIAL PRIMARY KEY,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL DEFAULT '',
                requested_code TEXT NOT NULL DEFAULT '',
                request_type TEXT NOT NULL DEFAULT 'REVIEW_REQUEST',
                status TEXT NOT NULL DEFAULT 'pending',
                public_ip TEXT NOT NULL DEFAULT '',
                local_ip TEXT NOT NULL DEFAULT '',
                device_model TEXT NOT NULL DEFAULT '',
                location_info TEXT NOT NULL DEFAULT '',
                saved_contacts_count INTEGER NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent()
        )
        client.executeSimple(
            "ALTER TABLE subscription_requests ADD COLUMN IF NOT EXISTS device_id TEXT NOT NULL DEFAULT ''"
        )
        client.executeSimple(
            """
            CREATE TABLE IF NOT EXISTS crm_clients (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL,
                original_phone TEXT NOT NULL,
                name TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '',
                created_at BIGINT NOT NULL,
                last_activity BIGINT NOT NULL,
                interaction_count INTEGER NOT NULL DEFAULT 1,
                is_synced_to_phone BOOLEAN NOT NULL DEFAULT FALSE,
                is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                plan_type TEXT NOT NULL DEFAULT 'FREE',
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (user_id, phone)
            )
            """.trimIndent()
        )
        client.executeSimple(
            "ALTER TABLE crm_clients ADD COLUMN IF NOT EXISTS device_id TEXT NOT NULL DEFAULT ''"
        )
        client.executeSimple(
            "ALTER TABLE crm_clients ADD COLUMN IF NOT EXISTS plan_type TEXT NOT NULL DEFAULT 'FREE'"
        )
    }

    private fun findExistingDeviceRow(
        client: PostgresSqlExecutor,
        snapshot: UserNetworkSnapshot,
        deviceId: String
    ): Map<String, String?>? {
        val result = client.query(
            """
            SELECT user_id, device_id, android_id, saved_contacts_count, free_contacts_used,
                   free_tier_exhausted, plan_type, active_code, is_subscribed
            FROM crm_users
            WHERE (device_id = $1 AND device_id <> '')
               OR (android_id = $2 AND android_id <> 'android_id_unavailable' AND android_id <> '')
               OR user_id = $3
            ORDER BY CASE
                         WHEN device_id = $1 THEN 0
                         WHEN android_id = $2 THEN 1
                         ELSE 2
                     END,
                     last_seen_at DESC
            LIMIT 1
            """.trimIndent(),
            listOf(deviceId, snapshot.androidId, snapshot.userId)
        )
        return result.rows.firstOrNull()
    }

    private fun insertNewDeviceRow(
        client: PostgresSqlExecutor,
        snapshot: UserNetworkSnapshot,
        deviceId: String,
        totalContacts: Int,
        freeState: FreeAccessState,
        planType: String,
        activeCode: String?,
        isSubscribed: Boolean
    ) {
        client.query(
            """
            INSERT INTO crm_users (
                user_id, device_id, android_id, install_uuid, public_ip, local_ip,
                location_info, device_model, os_version, network_type,
                saved_contacts_count, free_contacts_used, free_contacts_limit,
                free_tier_exhausted, plan_type, active_code, is_subscribed, last_seen_at
            )
            VALUES (
                $1, $2, $3, $4, $5, $6,
                $7, $8, $9, $10,
                $11::int, $12::int, $13::int,
                $14::boolean, $15, $16, $17::boolean, NOW()
            )
            """.trimIndent(),
            listOf(
                snapshot.userId,
                deviceId,
                snapshot.androidId,
                snapshot.installUuid,
                snapshot.publicIp,
                snapshot.localIp,
                snapshot.locationInfo,
                snapshot.deviceModel,
                snapshot.osVersion,
                snapshot.networkType,
                totalContacts.toString(),
                freeState.usedContacts.toString(),
                freeState.limit.toString(),
                freeState.isExhausted.toString(),
                planType,
                activeCode,
                isSubscribed.toString()
            )
        )
    }

    /**
     * Checks FIRST whether the device already exists in PostgreSQL (by immutable device_id /
     * android_id / user_id). If absent, inserts it; if already registered, updates it cleanly
     * without creating duplicates and returns the reconciled device state.
     */
    private fun upsertUserTelemetry(
        client: PostgresSqlExecutor,
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        activeCode: String?,
        isSubscribed: Boolean
    ): SyncedDeviceRecord {
        val deviceId = UserIdentityManager.getImmutableDeviceId(snapshot)
        val planType = when {
            isSubscribed -> "SUBSCRIBED"
            freeState.isExhausted -> "FREE_EXHAUSTED"
            else -> "FREE"
        }

        // Step 1: Check if device data already exists in PostgreSQL
        val existingRow = findExistingDeviceRow(client, snapshot, deviceId)
        if (existingRow == null) {
            // Step 2a: Not registered yet -> INSERT new device record
            insertNewDeviceRow(
                client = client,
                snapshot = snapshot,
                deviceId = deviceId,
                totalContacts = totalContacts,
                freeState = freeState,
                planType = planType,
                activeCode = activeCode,
                isSubscribed = isSubscribed
            )
            return SyncedDeviceRecord(
                canonicalUserId = snapshot.userId,
                deviceId = deviceId,
                serverFreeUsed = freeState.usedContacts,
                serverFreeExhausted = freeState.isExhausted,
                serverActiveCode = activeCode,
                serverIsSubscribed = isSubscribed,
                wasNewlyInserted = true
            )
        }

        // Step 2b: Already exists -> UPDATE by canonical device row key (no duplicate insert)
        val canonicalUserId = existingRow["user_id"]?.takeIf { it.isNotBlank() } ?: snapshot.userId
        val existingFreeUsed = existingRow["free_contacts_used"]?.toIntOrNull() ?: 0
        val existingFreeExhausted = parsePgBool(existingRow["free_tier_exhausted"], default = false)
        val existingActiveCode = existingRow["active_code"]?.takeIf { it.isNotBlank() }
        val existingSubscribed = parsePgBool(existingRow["is_subscribed"], default = false)

        val mergedFreeUsed = maxOf(existingFreeUsed, freeState.usedContacts)
        val mergedFreeExhausted = existingFreeExhausted || freeState.isExhausted ||
            (mergedFreeUsed >= freeState.limit)
        val mergedSubscribed = existingSubscribed || isSubscribed
        val mergedPlanType = when {
            mergedSubscribed -> "SUBSCRIBED"
            mergedFreeExhausted -> "FREE_EXHAUSTED"
            else -> "FREE"
        }
        val mergedActiveCode = activeCode ?: existingActiveCode

        client.query(
            """
            UPDATE crm_users
            SET device_id = $2,
                android_id = $3,
                install_uuid = $4,
                public_ip = $5,
                local_ip = $6,
                location_info = $7,
                device_model = $8,
                os_version = $9,
                network_type = $10,
                saved_contacts_count = GREATEST(saved_contacts_count, $11::int),
                free_contacts_used = GREATEST(free_contacts_used, $12::int),
                free_contacts_limit = $13::int,
                free_tier_exhausted = free_tier_exhausted OR $14::boolean,
                plan_type = $15,
                active_code = COALESCE($16, active_code),
                is_subscribed = is_subscribed OR $17::boolean,
                last_seen_at = NOW()
            WHERE user_id = $1
            """.trimIndent(),
            listOf(
                canonicalUserId,
                deviceId,
                snapshot.androidId,
                snapshot.installUuid,
                snapshot.publicIp,
                snapshot.localIp,
                snapshot.locationInfo,
                snapshot.deviceModel,
                snapshot.osVersion,
                snapshot.networkType,
                totalContacts.toString(),
                mergedFreeUsed.toString(),
                freeState.limit.toString(),
                mergedFreeExhausted.toString(),
                mergedPlanType,
                activeCode,
                isSubscribed.toString()
            )
        )

        return SyncedDeviceRecord(
            canonicalUserId = canonicalUserId,
            deviceId = deviceId,
            serverFreeUsed = mergedFreeUsed,
            serverFreeExhausted = mergedFreeExhausted,
            serverActiveCode = mergedActiveCode,
            serverIsSubscribed = mergedSubscribed,
            wasNewlyInserted = false
        )
    }

    private fun isBoundToDifferentDevice(
        boundUserId: String?,
        boundAndroidId: String?,
        snapshot: UserNetworkSnapshot,
        canonicalUserId: String,
        deviceId: String
    ): Boolean {
        val matchesByAndroidId = boundAndroidId != null &&
            UserIdentityManager.hasImmutableAndroidId(deviceId) &&
            boundAndroidId == deviceId
        if (matchesByAndroidId) return false

        val matchesByUserId = boundUserId != null &&
            (boundUserId == canonicalUserId || boundUserId == snapshot.userId)
        if (matchesByUserId) return false

        return boundUserId != null || boundAndroidId != null
    }

    private fun findApprovedCodeForUser(
        client: PostgresSqlExecutor,
        snapshot: UserNetworkSnapshot,
        canonicalUserId: String,
        deviceId: String,
        preferredCode: String?
    ): SubscriptionApiResponse? {
        val result = if (!preferredCode.isNullOrBlank()) {
            client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_android_id,
                       bound_ip, activated_at, expires_at
                FROM subscription_codes
                WHERE UPPER(code) = UPPER($1)
                LIMIT 1
                """.trimIndent(),
                listOf(preferredCode.trim().uppercase())
            )
        } else {
            client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_android_id,
                       bound_ip, activated_at, expires_at
                FROM subscription_codes
                WHERE (
                    bound_user_id = $1
                    OR bound_user_id = $2
                    OR (bound_android_id = $3 AND $3 <> 'android_id_unavailable' AND $3 <> '')
                )
                  AND is_subscribed = TRUE
                  AND is_active = TRUE
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                listOf(canonicalUserId, snapshot.userId, deviceId)
            )
        }
        val row = result.rows.firstOrNull() ?: return null
        val isSubscribed = parsePgBool(row["is_subscribed"], default = false)
        val isActive = parsePgBool(row["is_active"], default = false)
        if (!isSubscribed || !isActive) return null
        return SubscriptionApiResponse(
            valid = true,
            isSubscribed = true,
            isActive = true,
            isTrial = parsePgBool(row["is_trial"], default = false),
            status = "active",
            message = "تم التحقق من اشتراكك عبر PostgreSQL",
            code = row["code"].orEmpty(),
            planLabel = row["plan_label"].orEmpty(),
            durationMinutes = row["duration_minutes"]?.toLongOrNull() ?: 43200L,
            isLifetime = parsePgBool(row["is_lifetime"], default = false),
            maxContacts = row["max_contacts"]?.toIntOrNull(),
            usedContacts = row["used_contacts"]?.toIntOrNull() ?: 0,
            activatedAt = row["activated_at"]?.toLongOrNull(),
            expiresAt = row["expires_at"]?.toLongOrNull(),
            boundUserId = row["bound_user_id"] ?: canonicalUserId,
            boundIp = row["bound_ip"] ?: snapshot.publicIp
        )
    }

    private fun insertRequestRecord(
        client: PostgresSqlExecutor,
        snapshot: UserNetworkSnapshot,
        canonicalUserId: String,
        deviceId: String,
        requestedCode: String,
        requestType: String,
        status: String,
        totalContacts: Int,
        notes: String
    ): String? {
        val result = client.query(
            """
            INSERT INTO subscription_requests (
                user_id, device_id, requested_code, request_type, status,
                public_ip, local_ip, device_model, location_info,
                saved_contacts_count, notes
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::int, $11)
            RETURNING id
            """.trimIndent(),
            listOf(
                canonicalUserId,
                deviceId,
                requestedCode,
                requestType,
                status,
                snapshot.publicIp,
                snapshot.localIp,
                snapshot.deviceModel,
                snapshot.locationInfo,
                totalContacts.toString(),
                notes
            )
        )
        return result.rows.firstOrNull()?.get("id")
    }

    private fun parsePgBool(raw: String?, default: Boolean): Boolean {
        return when (raw?.trim()?.lowercase()) {
            "t", "true", "1", "yes", "y" -> true
            "f", "false", "0", "no", "n" -> false
            else -> default
        }
    }
}
