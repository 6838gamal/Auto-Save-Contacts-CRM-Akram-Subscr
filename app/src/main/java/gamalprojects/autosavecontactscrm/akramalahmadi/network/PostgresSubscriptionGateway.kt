package gamalprojects.autosavecontactscrm.akramalahmadi.network

import gamalprojects.autosavecontactscrm.akramalahmadi.managers.UserNetworkSnapshot
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.FreeAccessState
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.SubscriptionRepository

data class RemoteConnectOutcome(
    val connected: Boolean,
    val message: String,
    val activeSubscription: SubscriptionApiResponse? = null
)

data class RemoteReviewOutcome(
    val submitted: Boolean,
    val message: String,
    val requestId: String? = null
)

class PostgresSubscriptionGateway(
    private val config: PostgresConnectionConfig,
    private val clientFactory: (PostgresConnectionConfig) -> PostgresWireClient = { PostgresWireClient(it) }
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
            upsertUserTelemetry(
                client = client,
                snapshot = snapshot,
                totalContacts = totalContacts,
                freeState = freeState,
                activeCode = activeCode,
                isSubscribed = !activeCode.isNullOrBlank()
            )

            // Check if this user has an approved/bound active subscription in PostgreSQL
            val approvedCode = findApprovedCodeForUser(client, snapshot, activeCode)
            return RemoteConnectOutcome(
                connected = true,
                message = "متصل",
                activeSubscription = approvedCode
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
            upsertUserTelemetry(
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
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_ip,
                       activated_at, expires_at
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

            if (!isSubscribed || !isActive) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
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

            if (boundUserId != null && boundUserId != snapshot.userId) {
                insertRequestRecord(
                    client = client,
                    snapshot = snapshot,
                    requestedCode = dbCode,
                    requestType = "CODE_VERIFICATION",
                    status = "rejected_duplicate",
                    totalContacts = totalContacts,
                    notes = "محاولة استخدام كود مرتبط بمستخدم آخر ($boundUserId)"
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

            // Bind code to this user + IP in PostgreSQL
            client.query(
                """
                UPDATE subscription_codes
                SET bound_user_id = $2,
                    bound_ip = $3,
                    activated_at = COALESCE(activated_at, $4::bigint),
                    expires_at = $5::bigint,
                    updated_at = NOW()
                WHERE UPPER(code) = UPPER($1)
                """.trimIndent(),
                listOf(
                    dbCode,
                    snapshot.userId,
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
                boundUserId = snapshot.userId,
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
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)

            val queryResult = client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_ip,
                       activated_at, expires_at
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
            val activatedAt = row["activated_at"]?.toLongOrNull()
            val expiresAt = row["expires_at"]?.toLongOrNull()

            if (boundUserId != null && boundUserId != snapshot.userId) {
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

            client.query(
                """
                UPDATE crm_users
                SET public_ip = $2, local_ip = $3, saved_contacts_count = $4::int, last_seen_at = NOW()
                WHERE user_id = $1
                """.trimIndent(),
                listOf(snapshot.userId, snapshot.publicIp, snapshot.localIp, totalContacts.toString())
            )

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
                boundUserId = boundUserId ?: snapshot.userId,
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
        clientFactory(config).use { client ->
            client.connect()
            client.query(
                """
                UPDATE subscription_codes
                SET used_contacts = GREATEST(used_contacts, $2::int), updated_at = NOW()
                WHERE UPPER(code) = UPPER($1)
                """.trimIndent(),
                listOf(code.trim().uppercase(), usedContacts.toString())
            )
            client.query(
                """
                UPDATE crm_users
                SET saved_contacts_count = $2::int, public_ip = $3, last_seen_at = NOW()
                WHERE user_id = $1
                """.trimIndent(),
                listOf(snapshot.userId, totalContacts.toString(), snapshot.publicIp)
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
            upsertUserTelemetry(
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
        clients: List<ClientSyncItem>
    ) {
        if (clients.isEmpty()) return
        clientFactory(config).use { client ->
            client.connect()
            ensureSchema(client)
            for (item in clients) {
                client.query(
                    """
                    INSERT INTO crm_clients (
                        user_id, phone, original_phone, name, source,
                        created_at, last_activity, interaction_count,
                        is_synced_to_phone, is_deleted, updated_at
                    )
                    VALUES (
                        $1, $2, $3, $4, $5,
                        $6::bigint, $7::bigint, $8::int,
                        $9::boolean, $10::boolean, NOW()
                    )
                    ON CONFLICT (user_id, phone) DO UPDATE SET
                        original_phone = EXCLUDED.original_phone,
                        name = EXCLUDED.name,
                        source = EXCLUDED.source,
                        last_activity = GREATEST(crm_clients.last_activity, EXCLUDED.last_activity),
                        interaction_count = GREATEST(crm_clients.interaction_count, EXCLUDED.interaction_count),
                        is_synced_to_phone = crm_clients.is_synced_to_phone OR EXCLUDED.is_synced_to_phone,
                        is_deleted = EXCLUDED.is_deleted,
                        updated_at = NOW()
                    """.trimIndent(),
                    listOf(
                        userId,
                        item.phone,
                        item.originalPhone,
                        item.name,
                        item.source,
                        item.createdAt.toString(),
                        item.lastActivity.toString(),
                        item.interactionCount.toString(),
                        item.isSyncedToPhone.toString(),
                        item.isDeleted.toString()
                    )
                )
            }
        }
    }

    private fun ensureSchema(client: PostgresWireClient) {
        client.executeSimple(
            """
            CREATE TABLE IF NOT EXISTS crm_users (
                user_id TEXT PRIMARY KEY,
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
                free_tier_exhausted BOOLEAN NOT NULL DEFAULT FALSE,
                active_code TEXT,
                is_subscribed BOOLEAN NOT NULL DEFAULT FALSE,
                is_active BOOLEAN NOT NULL DEFAULT TRUE,
                first_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            )
            """.trimIndent()
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
            """
            CREATE TABLE IF NOT EXISTS subscription_requests (
                id BIGSERIAL PRIMARY KEY,
                user_id TEXT NOT NULL,
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
            """
            CREATE TABLE IF NOT EXISTS crm_clients (
                user_id TEXT NOT NULL,
                phone TEXT NOT NULL,
                original_phone TEXT NOT NULL,
                name TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT '',
                created_at BIGINT NOT NULL,
                last_activity BIGINT NOT NULL,
                interaction_count INTEGER NOT NULL DEFAULT 1,
                is_synced_to_phone BOOLEAN NOT NULL DEFAULT FALSE,
                is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                PRIMARY KEY (user_id, phone)
            )
            """.trimIndent()
        )
    }

    private fun upsertUserTelemetry(
        client: PostgresWireClient,
        snapshot: UserNetworkSnapshot,
        totalContacts: Int,
        freeState: FreeAccessState,
        activeCode: String?,
        isSubscribed: Boolean
    ) {
        client.query(
            """
            INSERT INTO crm_users (
                user_id, android_id, install_uuid, public_ip, local_ip,
                location_info, device_model, os_version, network_type,
                saved_contacts_count, free_contacts_used, free_tier_exhausted,
                active_code, is_subscribed, last_seen_at
            )
            VALUES (
                $1, $2, $3, $4, $5,
                $6, $7, $8, $9,
                $10::int, $11::int, $12::boolean,
                $13, $14::boolean, NOW()
            )
            ON CONFLICT (user_id) DO UPDATE SET
                android_id = EXCLUDED.android_id,
                install_uuid = EXCLUDED.install_uuid,
                public_ip = EXCLUDED.public_ip,
                local_ip = EXCLUDED.local_ip,
                location_info = EXCLUDED.location_info,
                device_model = EXCLUDED.device_model,
                os_version = EXCLUDED.os_version,
                network_type = EXCLUDED.network_type,
                saved_contacts_count = GREATEST(crm_users.saved_contacts_count, EXCLUDED.saved_contacts_count),
                free_contacts_used = GREATEST(crm_users.free_contacts_used, EXCLUDED.free_contacts_used),
                free_tier_exhausted = crm_users.free_tier_exhausted OR EXCLUDED.free_tier_exhausted,
                active_code = COALESCE(EXCLUDED.active_code, crm_users.active_code),
                is_subscribed = crm_users.is_subscribed OR EXCLUDED.is_subscribed,
                last_seen_at = NOW()
            """.trimIndent(),
            listOf(
                snapshot.userId,
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
                freeState.isExhausted.toString(),
                activeCode,
                isSubscribed.toString()
            )
        )
    }

    private fun findApprovedCodeForUser(
        client: PostgresWireClient,
        snapshot: UserNetworkSnapshot,
        preferredCode: String?
    ): SubscriptionApiResponse? {
        val result = if (!preferredCode.isNullOrBlank()) {
            client.query(
                """
                SELECT code, plan_label, is_subscribed, is_active, is_trial, is_lifetime,
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_ip,
                       activated_at, expires_at
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
                       duration_minutes, max_contacts, used_contacts, bound_user_id, bound_ip,
                       activated_at, expires_at
                FROM subscription_codes
                WHERE bound_user_id = $1 AND is_subscribed = TRUE AND is_active = TRUE
                ORDER BY updated_at DESC
                LIMIT 1
                """.trimIndent(),
                listOf(snapshot.userId)
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
            boundUserId = row["bound_user_id"] ?: snapshot.userId,
            boundIp = row["bound_ip"] ?: snapshot.publicIp
        )
    }

    private fun insertRequestRecord(
        client: PostgresWireClient,
        snapshot: UserNetworkSnapshot,
        requestedCode: String,
        requestType: String,
        status: String,
        totalContacts: Int,
        notes: String
    ): String? {
        val result = client.query(
            """
            INSERT INTO subscription_requests (
                user_id, requested_code, request_type, status,
                public_ip, local_ip, device_model, location_info,
                saved_contacts_count, notes
            )
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::int, $10)
            RETURNING id
            """.trimIndent(),
            listOf(
                snapshot.userId,
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
