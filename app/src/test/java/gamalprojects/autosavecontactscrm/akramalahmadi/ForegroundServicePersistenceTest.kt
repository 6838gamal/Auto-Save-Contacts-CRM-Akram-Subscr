package gamalprojects.autosavecontactscrm.akramalahmadi

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper
import gamalprojects.autosavecontactscrm.akramalahmadi.receivers.ServiceRestartReceiver
import gamalprojects.autosavecontactscrm.akramalahmadi.services.AutoSaveForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForegroundServicePersistenceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AutoSaveForegroundService.setMonitoringEnabled(context, false)
    }

    @Test
    fun `foreground notification is ongoing and non-clearable`() {
        NotificationHelper.createNotificationChannels(context)
        val notification = NotificationHelper.getForegroundServiceNotification(
            context,
            savedCount = 12,
            statusText = "رصد مستمر نشط بالخلفية"
        )
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertTrue((notification.flags and Notification.FLAG_NO_CLEAR) != 0)
    }

    @Test
    fun `service remains enabled onTaskRemoved schedules restart alarm and only stops on explicit stop`() {
        val controller = Robolectric.buildService(AutoSaveForegroundService::class.java)
        val service = controller.create().get()

        val startResult = service.onStartCommand(Intent(context, AutoSaveForegroundService::class.java), 0, 1)
        assertEquals(Service.START_STICKY, startResult)
        assertTrue(AutoSaveForegroundService.isRunning)
        assertTrue(AutoSaveForegroundService.isMonitoringEnabled(context))

        val shadowService = Shadows.shadowOf(service)
        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(NotificationHelper.NOTIFICATION_ID_FOREGROUND, shadowService.lastForegroundNotificationId)

        // Swiping app from recents triggers onTaskRemoved -> schedules restart alarm & keeps monitoring enabled
        service.onTaskRemoved(Intent(Intent.ACTION_MAIN))
        assertTrue(AutoSaveForegroundService.isMonitoringEnabled(context))

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarmManager = Shadows.shadowOf(alarmManager)
        assertNotNull(shadowAlarmManager.nextScheduledAlarm)

        // Simulating ServiceRestartReceiver firing while service is down restores service
        val shadowApp = Shadows.shadowOf(context as android.app.Application)
        shadowApp.clearStartedServices()

        service.onDestroy()
        assertFalse(AutoSaveForegroundService.isRunning)
        assertTrue(AutoSaveForegroundService.isMonitoringEnabled(context))

        val restartReceiver = ServiceRestartReceiver()
        restartReceiver.onReceive(
            context,
            Intent(AutoSaveForegroundService.ACTION_RESTART_MONITORING)
        )
        val restartedIntent = shadowApp.nextStartedService
        assertNotNull(restartedIntent)
        assertEquals(AutoSaveForegroundService::class.java.name, restartedIntent!!.component?.className)

        // Explicit user stop clears monitoring flag and returns START_NOT_STICKY
        val stopController = Robolectric.buildService(AutoSaveForegroundService::class.java).create()
        val stopResult = stopController.get().onStartCommand(
            Intent(context, AutoSaveForegroundService::class.java).apply {
                action = AutoSaveForegroundService.ACTION_STOP_SERVICE
            },
            0,
            2
        )
        assertEquals(Service.START_NOT_STICKY, stopResult)
        assertFalse(AutoSaveForegroundService.isMonitoringEnabled(context))
        assertFalse(AutoSaveForegroundService.isRunning)

        shadowApp.clearStartedServices()
        restartReceiver.onReceive(
            context,
            Intent(AutoSaveForegroundService.ACTION_RESTART_MONITORING)
        )
        assertNull(shadowApp.nextStartedService)
    }
}
