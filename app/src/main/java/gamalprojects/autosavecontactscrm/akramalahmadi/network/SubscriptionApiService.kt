package gamalprojects.autosavecontactscrm.akramalahmadi.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class ServerEndpointConfig(
    val baseUrl: String,
    val authParamName: String?,
    val authToken: String?
) {
    companion object {
        private val AUTH_PARAM_KEYS = listOf(
            "mobile_api",
            "api_key",
            "apikey",
            "token",
            "secret",
            "key",
            "auth"
        )

        private fun normalizeRaw(rawUrl: String): String {
            var s = rawUrl.trim()
            if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
                (s.startsWith("'") && s.endsWith("'") && s.length >= 2)
            ) {
                s = s.substring(1, s.length - 1).trim()
            }
            return s
        }

        fun isPlaceholderOrInvalidScheme(rawUrl: String): String? {
            val trimmed = normalizeRaw(rawUrl)
            if (trimmed.isBlank()) return "رابط الخادم فارغ"
            if (trimmed == "MY_POSTGRES_DATABASE_URL" || trimmed == "MY_SERVER_URL") {
                return "لا يزال الرابط يحمل القيمة الافتراضية ($trimmed)"
            }
            if (trimmed.contains("api.example.com", ignoreCase = true) ||
                trimmed.contains("YOUR_TOKEN", ignoreCase = true) ||
                trimmed.contains("YOUR_SECRET", ignoreCase = true)
            ) {
                return "الرابط يحتوي على قيمة تجريبية"
            }
            if (trimmed.startsWith("postgresql://", ignoreCase = true) ||
                trimmed.startsWith("postgres://", ignoreCase = true) ||
                trimmed.startsWith("jdbc:", ignoreCase = true)
            ) {
                return "تم إيقاف الاتصال المباشر بـ PostgreSQL؛ يرجى وضع رابط خادم HTTP/HTTPS"
            }
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return "الرابط يجب أن يبدأ بـ https:// أو http://"
            }
            return null
        }

        fun parse(rawUrl: String): ServerEndpointConfig? {
            val trimmed = normalizeRaw(rawUrl)
            if (isPlaceholderOrInvalidScheme(trimmed) != null) return null

            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase() ?: return null
                val authority = uri.rawAuthority?.takeIf { it.isNotBlank() } ?: return null

                var authParamName: String? = null
                var authToken: String? = null

                // 1. Check query string first (e.g. ?mobile_api=vale or ?api_key=vale)
                val rawQuery = uri.rawQuery
                if (!rawQuery.isNullOrBlank()) {
                    val pairs = rawQuery.split('&').mapNotNull { pair ->
                        val idx = pair.indexOf('=')
                        if (idx > 0) {
                            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8").trim()
                            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8").trim()
                            k to v
                        } else null
                    }
                    for (priorityKey in AUTH_PARAM_KEYS) {
                        val match = pairs.firstOrNull { (k, v) ->
                            k.equals(priorityKey, ignoreCase = true) && v.isNotBlank()
                        }
                        if (match != null) {
                            authParamName = match.first
                            authToken = match.second
                            break
                        }
                    }
                    if (authToken == null) {
                        val firstNonBlank = pairs.firstOrNull { (k, v) -> k.isNotBlank() && v.isNotBlank() }
                        if (firstNonBlank != null) {
                            authParamName = firstNonBlank.first
                            authToken = firstNonBlank.second
                        }
                    }
                }

                // 2. Inspect path segments for trailing key=value style (e.g. .../mobile_api=vale)
                val segments = uri.rawPath.orEmpty()
                    .split('/')
                    .filter { it.isNotBlank() }
                    .toMutableList()

                if (segments.isNotEmpty()) {
                    val last = segments.last()
                    val eqIdx = last.indexOf('=')
                    if (eqIdx > 0) {
                        val decodedKey = URLDecoder.decode(last.substring(0, eqIdx), "UTF-8").trim()
                        val decodedVal = URLDecoder.decode(last.substring(eqIdx + 1), "UTF-8").trim()
                        if (decodedKey.isNotBlank() && decodedVal.isNotBlank()) {
                            segments.removeAt(segments.lastIndex)
                            if (authToken == null) {
                                authParamName = decodedKey
                                authToken = decodedVal
                            }
                        }
                    }
                }

                val pathPart = if (segments.isEmpty()) {
                    "/"
                } else {
                    "/" + segments.joinToString("/") + "/"
                }
                val cleanBase = "$scheme://$authority$pathPart"

                ServerEndpointConfig(
                    baseUrl = cleanBase,
                    authParamName = authParamName,
                    authToken = authToken
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class SubscriptionConnectRequest(
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "android_id") val androidId: String,
    @Json(name = "install_uuid") val installUuid: String,
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "local_ip") val localIp: String,
    @Json(name = "location_info") val locationInfo: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "os_version") val osVersion: String,
    @Json(name = "network_type") val networkType: String,
    @Json(name = "saved_contacts_count") val savedContactsCount: Int = 0,
    @Json(name = "free_contacts_used") val freeContactsUsed: Int = 0,
    @Json(name = "free_contacts_limit") val freeContactsLimit: Int = 100,
    @Json(name = "free_tier_exhausted") val freeTierExhausted: Boolean = false,
    @Json(name = "plan_type") val planType: String = "FREE",
    @Json(name = "active_code") val activeCode: String? = null,
    @Json(name = "app_version") val appVersion: String = "1.0"
)

@JsonClass(generateAdapter = true)
data class SubscriptionActivateRequest(
    @Json(name = "code") val code: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "android_id") val androidId: String,
    @Json(name = "install_uuid") val installUuid: String,
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "local_ip") val localIp: String,
    @Json(name = "location_info") val locationInfo: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "os_version") val osVersion: String,
    @Json(name = "network_type") val networkType: String,
    @Json(name = "saved_contacts_count") val savedContactsCount: Int = 0,
    @Json(name = "app_version") val appVersion: String = "1.0"
)

@JsonClass(generateAdapter = true)
data class SubscriptionVerifyRequest(
    @Json(name = "code") val code: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "android_id") val androidId: String,
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "local_ip") val localIp: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "saved_contacts_count") val savedContactsCount: Int = 0,
    @Json(name = "used_quota_count") val usedQuotaCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class SubscriptionUsageRequest(
    @Json(name = "code") val code: String,
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "used_contacts") val usedContacts: Int,
    @Json(name = "total_contacts") val totalContacts: Int,
    @Json(name = "plan_type") val planType: String = "SUBSCRIBED"
)

@JsonClass(generateAdapter = true)
data class SubscriptionReviewRequest(
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "requested_code") val requestedCode: String = "",
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "local_ip") val localIp: String,
    @Json(name = "device_model") val deviceModel: String,
    @Json(name = "location_info") val locationInfo: String,
    @Json(name = "saved_contacts_count") val savedContactsCount: Int = 0,
    @Json(name = "notes") val notes: String = ""
)

@JsonClass(generateAdapter = true)
data class ClientSyncItem(
    @Json(name = "phone") val phone: String,
    @Json(name = "original_phone") val originalPhone: String,
    @Json(name = "name") val name: String,
    @Json(name = "source") val source: String,
    @Json(name = "created_at") val createdAt: Long,
    @Json(name = "last_activity") val lastActivity: Long,
    @Json(name = "interaction_count") val interactionCount: Int,
    @Json(name = "is_synced_to_phone") val isSyncedToPhone: Boolean,
    @Json(name = "is_deleted") val isDeleted: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ClientSyncBatchRequest(
    @Json(name = "user_id") val userId: String,
    @Json(name = "device_id") val deviceId: String = "",
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "plan_type") val planType: String = "FREE",
    @Json(name = "clients") val clients: List<ClientSyncItem>
)

@JsonClass(generateAdapter = true)
data class SubscriptionApiResponse(
    @Json(name = "valid") val valid: Boolean = false,
    @Json(name = "is_subscribed") val isSubscribed: Boolean = true,
    @Json(name = "is_active") val isActive: Boolean = true,
    @Json(name = "is_trial") val isTrial: Boolean = false,
    @Json(name = "status") val status: String = "",
    @Json(name = "message") val message: String = "",
    @Json(name = "code") val code: String = "",
    @Json(name = "plan_label") val planLabel: String = "",
    @Json(name = "plan_type") val planType: String? = null,
    @Json(name = "duration_minutes") val durationMinutes: Long = 0L,
    @Json(name = "is_lifetime") val isLifetime: Boolean = false,
    @Json(name = "max_contacts") val maxContacts: Int? = null,
    @Json(name = "used_contacts") val usedContacts: Int = 0,
    @Json(name = "free_contacts_used") val freeContactsUsed: Int = 0,
    @Json(name = "free_contacts_limit") val freeContactsLimit: Int? = null,
    @Json(name = "free_tier_exhausted") val freeTierExhausted: Boolean = false,
    @Json(name = "activated_at") val activatedAt: Long? = null,
    @Json(name = "expires_at") val expiresAt: Long? = null,
    @Json(name = "bound_user_id") val boundUserId: String? = null,
    @Json(name = "bound_ip") val boundIp: String? = null,
    @Json(name = "device_id") val deviceId: String? = null
)

interface SubscriptionApiService {

    @POST("api/subscription/connect")
    suspend fun connectAndSyncUser(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: SubscriptionConnectRequest
    ): Response<SubscriptionApiResponse>

    @POST("api/subscription/activate")
    suspend fun activateCode(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: SubscriptionActivateRequest
    ): Response<SubscriptionApiResponse>

    @POST("api/subscription/verify")
    suspend fun verifySession(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: SubscriptionVerifyRequest
    ): Response<SubscriptionApiResponse>

    @POST("api/subscription/usage")
    suspend fun reportUsage(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: SubscriptionUsageRequest
    ): Response<SubscriptionApiResponse>

    @POST("api/subscription/review-request")
    suspend fun submitReviewRequest(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: SubscriptionReviewRequest
    ): Response<SubscriptionApiResponse>

    @POST("api/clients/sync")
    suspend fun syncClients(
        @Header("Authorization") authorization: String?,
        @Header("X-API-Key") apiKey: String?,
        @Body request: ClientSyncBatchRequest
    ): Response<SubscriptionApiResponse>

    companion object {
        @Volatile
        private var cachedKey: String? = null

        @Volatile
        private var cachedService: SubscriptionApiService? = null

        fun extractApiKey(databaseUrl: String): String? {
            return ServerEndpointConfig.parse(databaseUrl)?.authToken
        }

        fun create(databaseUrl: String): SubscriptionApiService? {
            val trimmed = databaseUrl.trim()
            synchronized(this) {
                if (cachedKey == trimmed && cachedService != null) {
                    return cachedService
                }
            }

            val invalidReason = ServerEndpointConfig.isPlaceholderOrInvalidScheme(trimmed)
            if (invalidReason != null) {
                return null
            }

            val endpoint = ServerEndpointConfig.parse(trimmed) ?: return null

            return try {
                // Inject extracted server token on every request (headers + original query param if present)
                val authInterceptor = Interceptor { chain ->
                    val original = chain.request()
                    val builder = original.newBuilder()

                    val token = endpoint.authToken
                    val paramName = endpoint.authParamName
                    if (!token.isNullOrBlank()) {
                        if (original.header("Authorization").isNullOrBlank()) {
                            builder.header("Authorization", "Bearer $token")
                        }
                        if (original.header("X-API-Key").isNullOrBlank()) {
                            builder.header("X-API-Key", token)
                        }
                        if (original.header("X-Mobile-API").isNullOrBlank()) {
                            builder.header("X-Mobile-API", token)
                        }
                        if (!paramName.isNullOrBlank() && original.url.queryParameter(paramName) == null) {
                            val newUrl = original.url.newBuilder()
                                .addQueryParameter(paramName, token)
                                .build()
                            builder.url(newUrl)
                        }
                    }

                    chain.proceed(builder.build())
                }

                // Generous timeouts to support Render cold-start wakeups without blocking UI
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .addInterceptor(authInterceptor)
                    .build()

                val service = Retrofit.Builder()
                    .baseUrl(endpoint.baseUrl)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create())
                    .build()
                    .create(SubscriptionApiService::class.java)

                synchronized(this) {
                    cachedKey = trimmed
                    cachedService = service
                }

                service
            } catch (_: Exception) {
                null
            }
        }
    }
}
