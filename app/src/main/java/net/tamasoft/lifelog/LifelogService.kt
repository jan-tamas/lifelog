package net.tamasoft.lifelog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * The permanent notice. While any module is on, this notification says what
 * this phone sends and to which server; it is never hidden, and if it is
 * swiped away it comes straight back. It is also what keeps location readings
 * coming while the phone sleeps. No module on, no notice, no service.
 */
class LifelogService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val n = notice(this, prefs)
        val location = prefs.on(Module.LOCATION) && Perms.fineLocation(this)
        val type = when {
            location -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, ID, n, type)
        } catch (e: Exception) {
            // Started from the background without location in use: keep the notice.
            val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            runCatching { ServiceCompat.startForeground(this, ID, n, fallback) }
        }
        // A start must always reach startForeground, even one that turns out to
        // be unwanted, or Android ends the app for it.
        if (!prefs.anyOn()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Location.apply(this)
        return START_STICKY
    }

    companion object {
        const val ID = 1
        const val CHANNEL = "status"

        fun channel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "What this phone sends",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Always shown while Lifelog sends anything, naming what and where."
                setShowBadge(false)
            })
        }

        fun summary(prefs: Prefs): String {
            val parts = mutableListOf<String>()
            if (prefs.on(Module.HEALTH)) parts += "health"
            if (prefs.on(Module.LOCATION)) parts += "location"
            if (prefs.on(Module.USAGE)) {
                val sub = UsagePart.entries.filter { prefs.part(it) }.map { it.title.lowercase() }
                parts += if (sub.isEmpty()) "app use" else "app use (+ ${sub.joinToString(", ")})"
            }
            if (prefs.on(Module.PHOTOS)) parts += "photos"
            return parts.joinToString(", ")
        }

        fun notice(c: Context, prefs: Prefs): Notification {
            channel(c)
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val back = PendingIntent.getBroadcast(c, 0, Intent(c, NoticeReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE)
            val host = prefs.config?.host ?: "?"
            val what = summary(prefs)
            return NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notice)
                .setContentTitle("This phone sends its $what to $host")
                .setContentText("Only to that server. Open Lifelog to change it.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "This phone sends its $what to $host, and nowhere else. Open Lifelog to see or change what is sent."))
                .setOngoing(true)
                .setContentIntent(open)
                .setDeleteIntent(back)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

/** The notice was swiped away: put it back. Posting needs no service start,
 *  which Android may refuse from here. */
class NoticeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val prefs = Prefs(c)
        if (!prefs.anyOn() || !Perms.notifications(c)) return
        try {
            androidx.core.app.NotificationManagerCompat.from(c).notify(LifelogService.ID, LifelogService.notice(c, prefs))
        } catch (_: SecurityException) {
        }
    }
}

/** After a restart or an update, everything comes back as the switches say. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Scheduler.apply(c)
        }
    }
}
