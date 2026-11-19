package gamalprojects.autosavecontactscrm.akramalahmadi.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SubscriptionConnectRequest(
    @Json(name = "user_id") val userId: String,
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
    @Json(name = "public_ip") val publicIp: String,
    @Json(name = "used_contacts") val usedContacts: Int,
    @Json(name = "total_contacts") val totalContacts: Int,
    @Json(name = "plan_type") val planType: String = "SUBSCRIBED"
)

@JsonClass(generateAdapter = true)
data class SubscriptionReviewRequest(
    @Json(name = "user_id") val userId: String,
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
    @Json(name = "duration_minutes") val durationMinutes: Long = 0L,
    @Json(name = "is_lifetime") val isLifetime: Boolean = false,
    @Json(name = "max_contacts") val maxContacts: Int? = null,
    @Json(name = "used_contacts") val usedContacts: Int = 0,
    @Json(name = "activated_at") val activatedAt: Long? = null,
    @Json(name = "expires_at") val expiresAt: Long? = null,
    @Json(name = "bound_user_id") val boundUserId: String? = null,
    @Json(name = "bound_ip") val boundIp: String? = null
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
        fun extractApiKey(databaseUrl: String): String? {
            return try {
                val uri = URI(databaseUrl.trim())
                val query = uri.rawQuery ?: return null
                for (pair in query.split('&')) {
                    val idx = pair.indexOf('=')
                    if (idx > 0) {
                        val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                        if (k.equals("api_key", ignoreCase = true) || k.equals("apikey", ignoreCase = true) || k.equals("token", ignoreCase = true)) {
                            return URLDecoder.decode(pair.substring(idx + 1), "UTF-8").takeIf { it.isNotBlank() }
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        fun create(databaseUrl: String): SubscriptionApiService? {
            val trimmed = databaseUrl.trim()
            if (trimmed.isBlank() ||
                trimmed == "MY_POSTGRES_DATABASE_URL" ||
                trimmed.contains("api.example.com", ignoreCase = true)
            ) {
                return null
            }
            if (!trimmed.startsWith("http://", ignoreCase = true) &&
                !trimmed.startsWith("https://", ignoreCase = true)
            ) {
                return null
            }
            val cleanBase = trimmed.substringBefore('?').let {
                if (it.endsWith("/")) it else "$it/"
            }
            return try {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(6, TimeUnit.SECONDS)
                    .addInterceptor(logging)
                    .build()

                Retrofit.Builder()
                    .baseUrl(cleanBase)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create())
                    .build()
                    .create(SubscriptionApiService::class.java)
            } catch (_: Exception) {
                null
            }
        }
    }
}
