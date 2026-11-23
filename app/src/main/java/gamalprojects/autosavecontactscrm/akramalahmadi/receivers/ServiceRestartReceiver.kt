package gamalprojects.autosavecontactscrm.akramalahmadi.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import gamalprojects.autosavecontactscrm.akramalahmadi.services.AutoSaveForegroundService

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutoSaveForegroundService.ACTION_RESTART_MONITORING) {
            if (AutoSaveForegroundService.isMonitoringEnabled(context)) {
                Log.i("ServiceRestartReceiver", "Restarting AutoSaveForegroundService to keep monitoring active.")
                AutoSaveForegroundService.startIfEnabled(context)
            }
        }
    }
}
