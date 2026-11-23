package gamalprojects.autosavecontactscrm.akramalahmadi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.services.AutoSaveForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (AutoSaveForegroundService.isMonitoringEnabled(context)) {
                Log.i("BootReceiver", "System event $action. Re-triggering CRM monitoring foreground service.")
                AutoSaveForegroundService.startIfEnabled(context)
            }
        }
    }
}

