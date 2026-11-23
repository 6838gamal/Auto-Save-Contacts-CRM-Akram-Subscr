package gamalprojects.autosavecontactscrm.akramalahmadi

import android.app.Application
import gamalprojects.autosavecontactscrm.akramalahmadi.notifications.NotificationHelper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
    }
}

