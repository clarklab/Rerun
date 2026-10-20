package team.superfun.rerun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Launches the player when the stick powers on. Android 10+ only allows this when the app holds
 * "display over other apps"; grant it once with:
 *   adb shell appops set team.superfun.rerun SYSTEM_ALERT_WINDOW allow
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("BootReceiver", "Boot (${intent.action}), starting player")
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Could not auto-start", e)
        }
    }
}
