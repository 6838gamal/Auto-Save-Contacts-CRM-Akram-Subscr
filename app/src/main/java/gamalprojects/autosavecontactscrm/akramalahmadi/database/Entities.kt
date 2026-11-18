package gamalprojects.autosavecontactscrm.akramalahmadi.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "contacts_table",
    indices = [Index(value = ["phone"], unique = true)]
)
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phone: String, // Normalized phone number
    val originalPhone: String,
    val source: String, // "مكالمة", "رسالة SMS", "واتساب"
    val timestamp: Long = System.currentTimeMillis(),
    val lastActivity: Long = System.currentTimeMillis(),
    val interactionCount: Int = 1,
    val isSyncedToPhone: Boolean = false
)

@Entity(tableName = "logs_table")
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phone: String,
    val source: String,
    val status: String, // "تم الحفظ تلقائياً", "تم التجاهل (محفوظ مسبقاً)", "تحديث النشاط", "خطأ في الحفظ"
    val timestamp: Long = System.currentTimeMillis(),
    val details: String
)

@Entity(tableName = "events_table")
data class PhoneEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phone: String,
    val type: String, // "مكالمة واردة", "مكالمة صادرة", "رسالة SMS", "إشعار واتساب"
    val timestamp: Long = System.currentTimeMillis(),
    val content: String
)

@Entity(tableName = "settings_table")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String
)

@Entity(tableName = "subscription_codes_table")
data class SubscriptionCodeEntity(
    @PrimaryKey val code: String,
    val label: String = "",
    val durationMinutes: Long = 43200L, // Default 30 days
    val isLifetime: Boolean = false,
    val isSubscribed: Boolean = true,
    val isActive: Boolean = true,
    val isTrial: Boolean = false,
    val maxContacts: Int? = null, // null or <= 0 means unlimited
    val usedContacts: Int = 0,
    val enforceIpLock: Boolean = false,
    val preventDuplicatePerDevice: Boolean = true,
    val boundUserId: String? = null,
    val boundAndroidId: String? = null,
    val boundIpAddress: String? = null,
    val boundDeviceModel: String? = null,
    val activatedAt: Long? = null,
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceType: String = "POSTGRES_API"
)

@Entity(tableName = "user_device_profiles_table")
data class UserDeviceProfileEntity(
    @PrimaryKey val userId: String,
    val androidId: String,
    val installUuid: String,
    val publicIp: String,
    val localIp: String,
    val locationInfo: String = "",
    val deviceModel: String,
    val osVersion: String,
    val networkType: String,
    val activeCode: String? = null,
    val redeemedCodesHistory: String = "",
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "subscription_audit_logs_table")
data class SubscriptionAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val codeEntered: String,
    val userId: String,
    val ipAddress: String,
    val deviceModel: String,
    val status: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
