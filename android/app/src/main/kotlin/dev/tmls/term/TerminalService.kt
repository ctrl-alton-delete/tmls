package dev.tmls.term

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.tmls.MainActivity
import dev.tmls.R
import dev.tmls.TmlsApp

/**
 * Keeps the process in the foreground while tabs are open (spec ARCH-5), so Android does not kill
 * the ssh connections behind them when the app is in the background. The tabs themselves live in
 * [SessionManager]; this only holds the notification. Type `specialUse`: `dataSync` is limited to
 * 6 hours a day on Android 15+, and tabs stay open all day.
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            (application as TmlsApp).sessions.closeAll()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (count <= 0) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(count), type)
        // after a process death the tabs are gone; nothing to restart (NF-5 restores them in M3)
        return START_NOT_STICKY
    }

    private fun notification(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_sessions), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val closeAll = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_CLOSE_ALL), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(resources.getQuantityString(R.plurals.sessions_open, count, count))
            .setContentText(getString(R.string.sessions_open_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.detach_all), closeAll)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "sessions"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "count"
        private const val ACTION_CLOSE_ALL = "dev.tmls.CLOSE_ALL"

        /** Starts, updates or stops the service for [count] open tabs. Call from the foreground. */
        fun update(context: Context, count: Int) {
            val intent = Intent(context, TerminalService::class.java).putExtra(EXTRA_COUNT, count)
            if (count > 0) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                // stopping: harmless to miss when the service is not running
                runCatching { context.startService(intent) }
            }
        }
    }
}
