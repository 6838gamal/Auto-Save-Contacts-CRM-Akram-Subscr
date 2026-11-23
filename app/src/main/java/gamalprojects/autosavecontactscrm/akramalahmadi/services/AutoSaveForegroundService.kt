package gamalprojects.autosavecontactscrm.akramalahmadi.services

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.database.AppDatabase
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper
import gamalprojects.autosavecontactscrm.akramalahmadi.receivers.ServiceRestartReceiver
import gamalprojects.autosavecontactscrm.akramalahmadi.repositories.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AutoSaveForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: ContactsRepository
    @Volatile
    private var latestSavedCount: Int = 0
    @Volatile
    private var isExplicitStop: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d("AutoSaveForegroundService", "Service onCreate")
        isRunning = true
        isExplicitStop = false

        NotificationHelper.createNotificationChannels(this)
        ensureForegroundNotification(latestSavedCount, "رصد مستمر نشط بالخلفية")

        val db = AppDatabase.getDatabase(this)
        repository = ContactsRepository(db)

        // Dynamically update the sticky notification count
        serviceScope.launch {
            repository.contactsCount.collect { count ->
                latestSavedCount = count
                val updatedNotification = NotificationHelper.getForegroundServiceNotification(
                    this@AutoSaveForegroundService,
                    count,
                    "رصد مستمر نشط بالخلفية"
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NotificationHelper.NOTIFICATION_ID_FOREGROUND, updatedNotification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AutoSaveForegroundService", "Service onStartCommand action=${intent?.action}")

        if (intent?.action == ACTION_STOP_SERVICE) {
            isExplicitStop = true
            isRunning = false
            setMonitoringEnabled(this, false)
            cancelRestartAlarm(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        isExplicitStop = false
        isRunning = true
        setMonitoringEnabled(this, true)
        ensureForegroundNotification(latestSavedCount, "رصد مستمر نشط بالخلفية")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w("AutoSaveForegroundService", "App task removed from recents; keeping monitoring notification alive.")
        if (isMonitoringEnabled(applicationContext)) {
            ensureForegroundNotification(latestSavedCount, "رصد مستمر نشط بالخلفية")
            scheduleRestartAlarm(applicationContext, delayMillis = 600L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        Log.d("AutoSaveForegroundService", "Service onDestroy explicitStop=$isExplicitStop")
        if (!isExplicitStop && isMonitoringEnabled(applicationContext)) {
            scheduleRestartAlarm(applicationContext, delayMillis = 800L)
            try {
                sendBroadcast(
                    Intent(applicationContext, ServiceRestartReceiver::class.java).apply {
                        action = ACTION_RESTART_MONITORING
                    }
                )
            } catch (e: Exception) {
                Log.e("AutoSaveForegroundService", "Failed to send restart broadcast: ${e.message}")
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun ensureForegroundNotification(count: Int, statusText: String) {
        val notification = NotificationHelper.getForegroundServiceNotification(
            this,
            count,
            statusText
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                notification
            )
        }
    }

    companion object {
        const val ACTION_STOP_SERVICE = "gamalprojects.autosavecontactscrm.akramalahmadi.services.ACTION_STOP_SERVICE"
        const val ACTION_RESTART_MONITORING = "gamalprojects.autosavecontactscrm.akramalahmadi.ACTION_RESTART_MONITORING"
        private const val PREFS_NAME = "service_monitoring_prefs"
        private const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        private const val RESTART_REQUEST_CODE = 9042

        @Volatile
        var isRunning: Boolean = false
            private set

        fun isMonitoringEnabled(context: Context): Boolean {
            return context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MONITORING_ENABLED, false)
        }

        fun setMonitoringEnabled(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_MONITORING_ENABLED, enabled)
                .apply()
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            setMonitoringEnabled(appContext, true)
            cancelRestartAlarm(appContext)
            startServiceInternal(appContext)
        }

        fun startIfEnabled(context: Context) {
            val appContext = context.applicationContext
            if (isMonitoringEnabled(appContext) && !isRunning) {
                startServiceInternal(appContext)
            }
        }

        private fun startServiceInternal(appContext: Context) {
            try {
                NotificationHelper.createNotificationChannels(appContext)
                val intent = Intent(appContext, AutoSaveForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("ForegroundService", "Cannot start foreground service due to OS restrictions: ${e.message}", e)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            setMonitoringEnabled(appContext, false)
            cancelRestartAlarm(appContext)
            try {
                val intent = Intent(appContext, AutoSaveForegroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                appContext.startService(intent)
            } catch (e: Exception) {
                Log.e("ForegroundService", "Cannot stop foreground service: ${e.message}", e)
            }
        }

        internal fun scheduleRestartAlarm(context: Context, delayMillis: Long = 600L) {
            try {
                val appContext = context.applicationContext
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val restartIntent = Intent(appContext, ServiceRestartReceiver::class.java).apply {
                    action = ACTION_RESTART_MONITORING
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    RESTART_REQUEST_CODE,
                    restartIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val triggerAt = SystemClock.elapsedRealtime() + delayMillis
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e("AutoSaveForegroundService", "Unable to schedule restart alarm: ${e.message}")
            }
        }

        private fun cancelRestartAlarm(context: Context) {
            try {
                val appContext = context.applicationContext
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val restartIntent = Intent(appContext, ServiceRestartReceiver::class.java).apply {
                    action = ACTION_RESTART_MONITORING
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    RESTART_REQUEST_CODE,
                    restartIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                    pendingIntent.cancel()
                }
            } catch (_: Exception) {
            }
        }
    }
}

