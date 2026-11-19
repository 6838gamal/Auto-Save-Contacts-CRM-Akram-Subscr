package gamalprojects.autosavecontactscrm.akramalahmadi.managers

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UserNetworkSnapshot(
    val userId: String,
    val androidId: String,
    val installUuid: String,
    val publicIp: String,
    val localIp: String,
    val locationInfo: String,
    val deviceModel: String,
    val osVersion: String,
    val networkType: String,
    val capturedAt: Long = System.currentTimeMillis()
)

object UserIdentityManager {

    private const val PREFS_NAME = "subscription_identity_prefs"
    private const val KEY_INSTALL_UUID = "install_uuid"
    private const val KEY_LAST_PUBLIC_IP = "last_public_ip"
    private const val KEY_LAST_LOCATION = "last_location"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2000, TimeUnit.MILLISECONDS)
            .build()
    }

    fun hasInternetConnectivity(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    @SuppressLint("HardwareIds")
    fun getAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() } ?: "android_id_unavailable"
        } catch (_: Exception) {
            "android_id_unavailable"
        }
    }

    fun getOrCreateInstallUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_UUID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_UUID, created).apply()
        return created
    }

    /**
     * Produces a deterministic, collision-resistant user identifier (USR-XXXXXXXXXX)
     * combining ANDROID_ID and the persisted installation UUID.
     */
    fun getStableUserId(context: Context): String {
        val androidId = getAndroidId(context)
        val installUuid = getOrCreateInstallUuid(context)
        val raw = "$androidId|$installUuid|${context.packageName}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.take(5).joinToString("") { "%02X".format(it) }
        return "USR-$hex"
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }.ifBlank { "Android Device" }
    }

    fun getOsVersion(): String {
        return "Android ${Build.VERSION.RELEASE ?: "?"} (SDK ${Build.VERSION.SDK_INT})"
    }

    fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "غير معروف"
            val network = cm.activeNetwork ?: return "غير متصل"
            val caps = cm.getNetworkCapabilities(network) ?: return "غير متصل"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الهاتف"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "شبكة سلكية"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "متصل"
            }
        } catch (_: Exception) {
            "غير معروف"
        }
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var fallbackIpv6: String? = null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (addr is Inet4Address) {
                        return host
                    } else if (fallbackIpv6 == null) {
                        fallbackIpv6 = host.substringBefore('%')
                    }
                }
            }
            fallbackIpv6 ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun getCachedSnapshot(context: Context): UserNetworkSnapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localIp = getLocalIpAddress()
        val cachedIp = prefs.getString(KEY_LAST_PUBLIC_IP, null)?.takeIf { it.isNotBlank() } ?: localIp
        val cachedLoc = prefs.getString(KEY_LAST_LOCATION, null).orEmpty()
        return UserNetworkSnapshot(
            userId = getStableUserId(context),
            androidId = getAndroidId(context),
            installUuid = getOrCreateInstallUuid(context),
            publicIp = cachedIp,
            localIp = localIp,
            locationInfo = cachedLoc,
            deviceModel = getDeviceModel(),
            osVersion = getOsVersion(),
            networkType = getNetworkType(context)
        )
    }

    /**
     * Fetches the user's real public IP address and geolocation/ISP metadata via HTTPS.
     * Falls back to secondary IP endpoint and finally local interface IP if offline.
     */
    suspend fun fetchPublicNetworkInfo(context: Context): UserNetworkSnapshot = withContext(Dispatchers.IO) {
        val userId = getStableUserId(context)
        val androidId = getAndroidId(context)
        val installUuid = getOrCreateInstallUuid(context)
        val localIp = getLocalIpAddress()
        val deviceModel = getDeviceModel()
        val osVersion = getOsVersion()
        val networkType = getNetworkType(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        var publicIp = ""
        var locationInfo = ""

        if (hasInternetConnectivity(context)) {
            withTimeoutOrNull(2500L) {
                // Primary: ipwho.is (returns IP, country, city, ISP)
                try {
                    val req = Request.Builder().url("https://ipwho.is/").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                val json = JSONObject(body)
                                val success = json.optBoolean("success", true)
                                val ip = json.optString("ip", "")
                                if (success && ip.isNotBlank()) {
                                    publicIp = ip
                                    val country = json.optString("country", "")
                                    val city = json.optString("city", "")
                                    val isp = json.optJSONObject("connection")?.optString("isp", "").orEmpty()
                                    locationInfo = listOf(city, country, isp)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" • ")
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Fallback below
                }

                // Fallback 1: api.ipify.org
                if (publicIp.isBlank()) {
                    try {
                        val req = Request.Builder().url("https://api.ipify.org?format=json").get().build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                val ip = JSONObject(body).optString("ip", "")
                                if (ip.isNotBlank()) {
                                    publicIp = ip
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Fallback below
                    }
                }

                // Fallback 2: api64.ipify.org
                if (publicIp.isBlank()) {
                    try {
                        val req = Request.Builder().url("https://api64.ipify.org?format=json").get().build()
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val body = resp.body?.string().orEmpty()
                                val ip = JSONObject(body).optString("ip", "")
                                if (ip.isNotBlank()) {
                                    publicIp = ip
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Offline or blocked
                    }
                }
            }
        }

        if (publicIp.isNotBlank()) {
            prefs.edit()
                .putString(KEY_LAST_PUBLIC_IP, publicIp)
                .putString(KEY_LAST_LOCATION, locationInfo)
                .apply()
        } else {
            val cached = prefs.getString(KEY_LAST_PUBLIC_IP, null)
            publicIp = if (!cached.isNullOrBlank()) cached else localIp
            locationInfo = prefs.getString(KEY_LAST_LOCATION, null).orEmpty()
        }

        UserNetworkSnapshot(
            userId = userId,
            androidId = androidId,
            installUuid = installUuid,
            publicIp = publicIp,
            localIp = localIp,
            locationInfo = locationInfo,
            deviceModel = deviceModel,
            osVersion = osVersion,
            networkType = networkType
        )
    }
}
