package net.tamasoft.lifelog

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Puts the phone in the state the switches describe: a periodic job per module
 * that is on, none for one that is off, and the status notification while any
 * module is on. Called after every switch, at boot and after an update.
 */
object Scheduler {
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val unmetered = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

    fun apply(c: Context) {
        val prefs = Prefs(c)
        val wm = WorkManager.getInstance(c)
        fun periodic(name: String, on: Boolean, minutes: Long, constraints: Constraints,
                     cls: Class<out androidx.work.ListenableWorker>) {
            if (!on) { wm.cancelUniqueWork(name); return }
            val req = androidx.work.PeriodicWorkRequest.Builder(cls, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        periodic("usage", prefs.on(Module.USAGE), 30, connected, UsageWorker::class.java)
        periodic("health", prefs.on(Module.HEALTH), prefs.long(Prefs.HEALTH_HOURS, 12) * 60, connected,
            HealthWorker::class.java)
        periodic("photos", prefs.on(Module.PHOTOS), 15, unmetered, PhotoWorker::class.java)
        periodic("location-upload", prefs.on(Module.LOCATION), 60, connected, LocationUploadWorker::class.java)

        // Location readings and regions follow the switch at once, whether or
        // not the service is running: a stopped service must not leave them on.
        Location.apply(c)
        val service = Intent(c, LifelogService::class.java)
        if (prefs.anyOn()) {
            runCatching { ContextCompat.startForegroundService(c, service) }
        } else {
            c.stopService(service)
        }
    }

    /** Run a module's job now, as the "Send now" button does. */
    fun now(c: Context, m: Module) {
        val cls = when (m) {
            Module.USAGE -> UsageWorker::class.java
            Module.HEALTH -> HealthWorker::class.java
            Module.PHOTOS -> PhotoWorker::class.java
            Module.LOCATION -> LocationUploadWorker::class.java
        }
        val req = androidx.work.OneTimeWorkRequest.Builder(cls)
            .setConstraints(if (m == Module.PHOTOS) unmetered else connected)
            .addTag(NOW).addTag("$NOW-${m.key}").build()
        // KEEP: pressed again while a send waits or runs, it carries on rather than starting over.
        WorkManager.getInstance(c).enqueueUniqueWork("now-${m.key}", ExistingWorkPolicy.KEEP, req)
    }

    const val NOW = "now"

    /** After a new location message: send it as soon as there is a network. */
    fun locationQueued(c: Context) {
        val req = OneTimeWorkRequestBuilder<LocationUploadWorker>().setConstraints(connected).build()
        WorkManager.getInstance(c).enqueueUniqueWork("location-now", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}
