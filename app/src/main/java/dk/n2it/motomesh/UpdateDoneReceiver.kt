package dk.n2it.motomesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class UpdateDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = "mm_upd"
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(NotificationChannel(ch, "Moto Mesh updates", NotificationManager.IMPORTANCE_HIGH))
            val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
            val pi = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Moto Mesh updated to " + v)
                .setContentText("Tap to open the new version")
                .setContentIntent(pi).setAutoCancel(true).build()
            nm.notify(4711, n)
        } catch (_: Exception) {}
    }
}
