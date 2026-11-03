package net.tamasoft.lifelog

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.tamasoft.lifelog.core.FileQueue
import net.tamasoft.lifelog.core.Fix
import net.tamasoft.lifelog.core.Http
import net.tamasoft.lifelog.core.OwnTracks
import java.io.File

/**
 * Location as OwnTracks did it on this phone: a reading every fifteen minutes,
 * and an enter or leave message for each region in the pairing code (Home),
 * posted one message per request to the receiver's OwnTracks route.
 * Readings are queued on the phone first, so being offline delays them only.
 */
object Location {
    const val INTERVAL_MS = 15 * 60_000L

    fun queue(c: Context) = FileQueue(File(c.filesDir, "location-queue"))

    private fun updatesIntent(c: Context) = PendingIntent.getBroadcast(c, 1,
        Intent(c, LocationReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    private fun fenceIntent(c: Context) = PendingIntent.getBroadcast(c, 2,
        Intent(c, GeofenceReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

    /** Never throws: it runs from receivers and the boot, where an exception ends the app. */
    fun apply(c: Context) {
        try { applyNow(c) } catch (e: Exception) { Prefs(c).err(Module.LOCATION, "Location could not be set up: ${e.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun applyNow(c: Context) {
        val prefs = Prefs(c)
        val fused = LocationServices.getFusedLocationProviderClient(c)
        val fences = LocationServices.getGeofencingClient(c)
        val on = prefs.on(Module.LOCATION) && Perms.fineLocation(c)
        if (!on) {
            fused.removeLocationUpdates(updatesIntent(c))
            fences.removeGeofences(fenceIntent(c))
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 3).build()
        runCatching { fused.requestLocationUpdates(req, updatesIntent(c)) }
            .onFailure { prefs.err(Module.LOCATION, "Location updates could not start: ${it.message}") }
        val regions = prefs.config?.regions.orEmpty()
        if (regions.isNotEmpty() && Perms.backgroundLocation(c)) {
            val g = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(regions.map {
                Geofence.Builder().setRequestId(it.desc).setCircularRegion(it.lat, it.lon, it.rad.toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
            }).build()
            runCatching { fences.addGeofences(g, fenceIntent(c)) }
                .onFailure { prefs.err(Module.LOCATION, "Regions could not be watched: ${it.message}") }
        }
    }

    fun fix(l: android.location.Location) = Fix(l.time, l.latitude, l.longitude,
        if (l.hasAccuracy()) l.accuracy else null, if (l.hasAltitude()) l.altitude else null,
        if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
        if (l.hasSpeed()) l.speed else null, if (l.hasBearing()) l.bearing else null)

    fun battery(c: Context): Pair<Int?, Int> {
        val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        return pct to OwnTracks.batteryStatus(i.getIntExtra(BatteryManager.EXTRA_STATUS, 1))
    }

    fun conn(c: Context): String {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "o"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "w"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "m"
            else -> "w"
        }
    }
}

class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return
        if (!prefs.on(Module.LOCATION)) return
        val result = LocationResult.extractResult(intent) ?: return
        val (batt, bs) = Location.battery(c)
        val conn = Location.conn(c)
        val q = Location.queue(c)
        for (l in result.locations) {
            q.add(OwnTracks.location(Location.fix(l), cfg.tid, cfg.topic, batt, bs, conn, cfg.regions, OwnTracks.newId()).toString())
        }
        Scheduler.locationQueued(c)
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return
        if (!prefs.on(Module.LOCATION)) return
        val e = GeofencingEvent.fromIntent(intent) ?: return
        if (e.hasError()) return
        val enter = when (e.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val l = e.triggeringLocation ?: return
        val q = Location.queue(c)
        // The receiver drops a repeat by (type, tracker, second), so two regions
        // crossed in one event are a second apart.
        for ((i, g) in e.triggeringGeofences.orEmpty().withIndex()) {
            val region = cfg.regions.firstOrNull { it.desc == g.requestId } ?: continue
            val f = Location.fix(l).let { it.copy(timeMs = it.timeMs + i * 1000L) }
            q.add(OwnTracks.transition(f, cfg.tid, cfg.topic, region, enter, OwnTracks.newId()).toString())
        }
        Scheduler.locationQueued(c)
    }
}

/** Sends the queued location messages, oldest first, one per request. */
class LocationUploadWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return@withContext Result.success()
        val token = cfg.tokens["location"] ?: return@withContext Result.success()
        // Android drops every region when location is switched off on the phone;
        // adding them again with the same names replaces them, so this runs hourly.
        if (prefs.on(Module.LOCATION)) Location.apply(c)
        UPLOAD.withLock { upload(c, prefs, cfg.url, token) }
    }

    private fun upload(c: Context, prefs: Prefs, url: String, token: String): Result {
        val q = Location.queue(c)
        var sent = 0
        while (true) {
            val batch = q.pending()
            if (batch.isEmpty()) break
            for (f in batch) {
                val body = runCatching { f.readBytes() }.getOrNull() ?: continue      // already sent by another run
                val r = Http.post("$url/owntracks/$token", body, "application/json", timeoutMs = 30_000)
                when {
                    r.ok -> { q.done(f); sent++ }
                    r.refused -> { q.refuse(f); prefs.err(Module.LOCATION, "The receiver refused a message: ${r.body.take(200)}") }
                    r.blocked -> { prefs.err(Module.LOCATION, "The receiver answered ${r.code}: ${r.body.take(200)}"); return Result.success() }
                    else -> { prefs.err(Module.LOCATION, "Could not reach the receiver (${r.code}); ${q.size()} waiting."); return Result.retry() }
                }
            }
        }
        if (sent > 0) prefs.ok(Module.LOCATION, "Sent $sent message(s)")
        return Result.success()
    }

    companion object {
        /** The periodic run and the one after each reading must not drain the queue together. */
        val UPLOAD = kotlinx.coroutines.sync.Mutex()
    }
}
