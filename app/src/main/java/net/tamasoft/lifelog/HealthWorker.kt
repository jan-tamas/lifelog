package net.tamasoft.lifelog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tamasoft.lifelog.core.HealthJson
import net.tamasoft.lifelog.core.Http
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Health Connect, every twelve hours by default, to the receiver's health
 * route, through Health Connect's change feed: everything written or rewritten
 * since the last accepted send, whatever moment it describes. A time window
 * cannot do this, because Health Connect filters a window by a record's START
 * and the watch writes late: a night's sleep arrives hours after it began, so
 * "since the last send" never contains it.
 *
 * The feed starts from a token. With none yet, or when the allowed kinds
 * changed, or when Health Connect let the token expire, a new one is taken
 * FIRST and the records are then read by time, from three days back on the
 * first send and two days before the last good send after that, in windows of
 * at most a day. The receiver keys records by their moment, so a record sent
 * twice, or by HC Webhook as well, is one.
 *
 * Nothing is read and nothing moves while no kind is allowed: a send before
 * the owner has answered Health Connect would otherwise count as done.
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
        val closed = !MainActivity.shown
        var records = 0
        var sends = 0

        // null when the receiver took it; otherwise what this run returns.
        fun send(data: Map<String, List<JSONObject>>): Result? {
            val n = data.values.sumOf { it.size }
            if (n == 0) return null
            val body = HealthJson.payload(data, "lifelog ${BuildConfig.VERSION_NAME}", Instant.now())
            val r = Http.post("${cfg.url}/hc/$token", body.toByteArray(), "application/json", timeoutMs = 300_000)
            if (!r.ok) {
                prefs.err(Module.HEALTH, "The receiver answered ${r.code}: ${r.body.take(200)}")
                return if (r.refused || r.blocked) Result.success() else Result.retry()
            }
            records += n; sends++
            return null
        }

        try {
            val kinds = Health.readable(c)
            if (kinds.isEmpty()) {
                prefs.err(Module.HEALTH, "Lifelog may not read any health data yet: allow it in Health Connect (“Allow” above).")
                return@withContext Result.success()
            }
            val key = kinds.sorted().joinToString(",")
            var feed = prefs.string(Prefs.HEALTH_CHANGES)?.takeIf { prefs.string(Prefs.HEALTH_CHANGES_KINDS) == key }
            while (feed != null) {
                val page = Health.changes(c, feed)
                if (page.expired) { feed = null; break }
                send(page.records)?.let { return@withContext it }
                feed = page.next
                prefs.setString(Prefs.HEALTH_CHANGES, feed)
                if (!page.more) break
            }
            if (feed == null) {
                val fresh = Health.changesToken(c, kinds)
                val mark = prefs.long(Prefs.HEALTH_MARK)
                var from = if (mark == 0L) now.minus(Duration.ofDays(3)) else Instant.ofEpochMilli(mark).minus(Duration.ofDays(2))
                while (from < now) {
                    val to = minOf(from.plus(Duration.ofDays(1)), now)
                    send(Health.read(c, from, to))?.let { return@withContext it }
                    from = to
                }
                prefs.setString(Prefs.HEALTH_CHANGES, fresh)
                prefs.setString(Prefs.HEALTH_CHANGES_KINDS, key)
            }
        } catch (e: CancellationException) {
            throw e                                     // Android stopped the job; it runs again by itself
        } catch (e: SecurityException) {
            prefs.err(Module.HEALTH, "Health Connect refused to be read: allow Lifelog to read in the background. (${e.message})")
            return@withContext Result.success()
        } catch (e: Exception) {
            // The token and the mark stay where they are, so this is read again.
            prefs.err(Module.HEALTH, "Health Connect could not be read just now (${e.javaClass.simpleName}: ${e.message}); trying again.")
            return@withContext Result.retry()
        }
        prefs.setLong(Prefs.HEALTH_MARK, now.toEpochMilli())
        if (sends == 0) prefs.idle(Module.HEALTH)
        else prefs.ok(Module.HEALTH, "Sent $records record(s) in $sends send(s)" + if (closed) ", with the app closed" else "")
        Result.success()
    }
}
