package gamalprojects.autosavecontactscrm.akramalahmadi

import android.app.Application
import gamalprojects.autosavecontactscrm.akramalahmadi.network.ApiDebugLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiDebugLogger.init(this)
    }
}
