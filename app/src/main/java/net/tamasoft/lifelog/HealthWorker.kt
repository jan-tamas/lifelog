package net.tamasoft.lifelog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tamasoft.lifelog.core.HealthJson
import net.tamasoft.lifelog.core.Http
import java.time.Duration
import java.time.Instant

/**
 * Health Connect, every twelve hours by default, to the receiver's health
 * route: everything since the last accepted send, with two hours of overlap
 * (the receiver keys records by their moment, so a record sent twice is one),
 * in windows of at most a day, so no single send grows past a few megabytes.
 * The first send reaches back three days.
 */
class HealthWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return@withContext Result.success()
        if (!prefs.on(Module.HEALTH)) return@withContext Result.success()
        val token = cfg.tokens["health"] ?: return@withContext Result.success()
        if (!Health.available(c)) { prefs.err(Module.HEALTH, "Health Connect is not available on this phone."); return@withContext Result.success() }
        val now = Instant.now()
        val mark = prefs.long(Prefs.HEALTH_MARK)
        var from = if (mark == 0L) now.minus(Duration.ofDays(3)) else Instant.ofEpochMilli(mark).minus(Duration.ofHours(2))
        var records = 0
        var sends = 0
        while (from < now) {
            val to = minOf(from.plus(Duration.ofDays(1)), now)
            val data = try { Health.read(c, from, to) } catch (e: SecurityException) {
                prefs.err(Module.HEALTH, "Health Connect refused to be read: allow Lifelog to read in the background. (${e.message})")
                return@withContext Result.success()
            } catch (e: Exception) {
                // The mark stays where it is, so this window is read again.
                prefs.err(Module.HEALTH, "Health Connect could not be read just now (${e.javaClass.simpleName}: ${e.message}); trying again.")
                return@withContext Result.retry()
            }
            val n = data.values.sumOf { it.size }
            if (n > 0) {
                val body = HealthJson.payload(data, "lifelog ${BuildConfig.VERSION_NAME}", Instant.now())
                val r = Http.post("${cfg.url}/hc/$token", body.toByteArray(), "application/json", timeoutMs = 300_000)
                if (!r.ok) {
                    prefs.err(Module.HEALTH, "The receiver answered ${r.code}: ${r.body.take(200)}")
                    return@withContext if (r.refused || r.blocked) Result.success() else Result.retry()
                }
                records += n; sends++
            }
            prefs.setLong(Prefs.HEALTH_MARK, to.toEpochMilli())
            from = to
        }
        prefs.ok(Module.HEALTH, if (sends == 0) "Nothing new" else "Sent $records record(s) in $sends send(s)")
        Result.success()
    }
}
