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
import java.util.concurrent.TimeUnit

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
    @Json(name = "total_contacts") val totalContacts: Int
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

    companion object {
        fun create(baseUrl: String): SubscriptionApiService? {
            val normalized = baseUrl.trim().let {
                if (it.isNotBlank() && !it.endsWith("/")) "$it/" else it
            }
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                return null
            }
            if (normalized.contains("api.example.com", ignoreCase = true)) {
                return null
            }
            return try {
                val logging = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(8, TimeUnit.SECONDS)
                    .writeTimeout(8, TimeUnit.SECONDS)
                    .addInterceptor(logging)
                    .build()

                Retrofit.Builder()
                    .baseUrl(normalized)
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
