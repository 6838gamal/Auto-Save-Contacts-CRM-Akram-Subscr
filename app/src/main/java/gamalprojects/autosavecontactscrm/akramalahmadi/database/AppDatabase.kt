package gamalprojects.autosavecontactscrm.akramalahmadi.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Contact::class,
        LogEntry::class,
        PhoneEvent::class,
        SettingEntity::class,
        SubscriptionCodeEntity::class,
        UserDeviceProfileEntity::class,
        SubscriptionAuditLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
    abstract fun logEntryDao(): LogEntryDao
    abstract fun phoneEventDao(): PhoneEventDao
    abstract fun settingDao(): SettingDao
    abstract fun subscriptionCodeDao(): SubscriptionCodeDao
    abstract fun userDeviceProfileDao(): UserDeviceProfileDao
    abstract fun subscriptionAuditLogDao(): SubscriptionAuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscription_codes_table` (
                        `code` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `isLifetime` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `enforceIpLock` INTEGER NOT NULL,
                        `preventDuplicatePerDevice` INTEGER NOT NULL,
                        `boundUserId` TEXT,
                        `boundAndroidId` TEXT,
                        `boundIpAddress` TEXT,
                        `boundDeviceModel` TEXT,
                        `activatedAt` INTEGER,
                        `expiresAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        PRIMARY KEY(`code`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_device_profiles_table` (
                        `userId` TEXT NOT NULL,
                        `androidId` TEXT NOT NULL,
                        `installUuid` TEXT NOT NULL,
                        `publicIp` TEXT NOT NULL,
                        `localIp` TEXT NOT NULL,
                        `locationInfo` TEXT NOT NULL,
                        `deviceModel` TEXT NOT NULL,
                        `osVersion` TEXT NOT NULL,
                        `networkType` TEXT NOT NULL,
                        `activeCode` TEXT,
                        `redeemedCodesHistory` TEXT NOT NULL,
                        `firstSeenAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `subscription_audit_logs_table` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `codeEntered` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `ipAddress` TEXT NOT NULL,
                        `deviceModel` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `message` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `subscription_codes_table` ADD COLUMN `isSubscribed` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `subscription_codes_table` ADD COLUMN `isTrial` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `subscription_codes_table` ADD COLUMN `maxContacts` INTEGER")
                db.execSQL("ALTER TABLE `subscription_codes_table` ADD COLUMN `usedContacts` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autosave_contacts_crm_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
