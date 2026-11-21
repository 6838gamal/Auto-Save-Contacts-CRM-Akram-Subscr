package gamalprojects.autosavecontactscrm.akramalahmadi.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts_table ORDER BY timestamp DESC")
    fun getAllContacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts_table ORDER BY timestamp DESC")
    suspend fun getAllContactsSync(): List<Contact>

    @Query("SELECT * FROM contacts_table WHERE phone = :phone LIMIT 1")
    suspend fun getContactByPhone(phone: String): Contact?

    @Query("SELECT COUNT(*) FROM contacts_table")
    fun getContactsCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM contacts_table")
    suspend fun getContactsCountSync(): Int

    @Query("SELECT * FROM contacts_table ORDER BY timestamp DESC LIMIT 1")
    fun getLatestContact(): Flow<Contact?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: Contact): Long

    @Update
    suspend fun updateContact(contact: Contact)

    @Delete
    suspend fun deleteContact(contact: Contact)

    @Query("DELETE FROM contacts_table")
    suspend fun deleteAllContacts()
}

@Dao
interface LogEntryDao {
    @Query("SELECT * FROM logs_table ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<LogEntry>>

    @Query("SELECT COUNT(*) FROM logs_table")
    suspend fun getLogsCountSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: LogEntry)

    @Query("DELETE FROM logs_table")
    suspend fun deleteAllLogs()
}

@Dao
interface PhoneEventDao {
    @Query("SELECT * FROM events_table ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<PhoneEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PhoneEvent)

    @Query("DELETE FROM events_table")
    suspend fun deleteAllEvents()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings_table WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): SettingEntity?

    @Query("SELECT * FROM settings_table WHERE `key` = :key LIMIT 1")
    fun getSettingFlow(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SettingEntity)
}

@Dao
interface SubscriptionCodeDao {
    @Query("SELECT * FROM subscription_codes_table ORDER BY createdAt DESC")
    fun getAllCodes(): Flow<List<SubscriptionCodeEntity>>

    @Query("SELECT * FROM subscription_codes_table WHERE code = :code LIMIT 1")
    suspend fun getCode(code: String): SubscriptionCodeEntity?

    @Query("SELECT * FROM subscription_codes_table WHERE code = :code LIMIT 1")
    fun observeCode(code: String): Flow<SubscriptionCodeEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCode(item: SubscriptionCodeEntity)

    @Delete
    suspend fun deleteCode(item: SubscriptionCodeEntity)
}

@Dao
interface UserDeviceProfileDao {
    @Query("SELECT * FROM user_device_profiles_table WHERE userId = :userId LIMIT 1")
    suspend fun getProfile(userId: String): UserDeviceProfileEntity?

    @Query("SELECT * FROM user_device_profiles_table ORDER BY lastSeenAt DESC")
    fun getAllProfiles(): Flow<List<UserDeviceProfileEntity>>

    @Query("SELECT * FROM user_device_profiles_table WHERE publicIp = :ip AND publicIp != '' AND publicIp != 'غير متاح (أوفلاين)' LIMIT 1")
    suspend fun getProfileByIp(ip: String): UserDeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: UserDeviceProfileEntity)
}

@Dao
interface SubscriptionAuditLogDao {
    @Query("SELECT * FROM subscription_audit_logs_table ORDER BY timestamp DESC LIMIT 100")
    fun getRecentLogs(): Flow<List<SubscriptionAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: SubscriptionAuditLogEntity)

    @Query("DELETE FROM subscription_audit_logs_table")
    suspend fun clearAllLogs()
}
