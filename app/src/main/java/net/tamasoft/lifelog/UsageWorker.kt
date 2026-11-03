package net.tamasoft.lifelog

import android.Manifest
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tamasoft.lifelog.core.Http
import net.tamasoft.lifelog.core.Times
import net.tamasoft.lifelog.core.Usage
import java.io.File

/**
 * Phone use, every half hour, to the receiver's phone-usage route, in the form
 * the Termux script used: one line per day, {"day", "events"}, today and
 * yesterday on every run and any older day of the last week that changed. The
 * receiver files each day as a delta, so a day is only ever sent whole.
 *
 * Every push says it is from this app (`X-Sender: lifelog`), and the receiver
 * keeps one store per sender and shows one sender's view of each day. So this
 * app and the Termux script it replaces can both push for as long as the owner
 * likes: two senders' views of one day no longer undo each other, and a day
 * this app saw only in part does not replace the other's whole one.
 *
 * A day is frozen on the phone once it is two days old: Android keeps its
 * usage history for a few days only, and a day rebuilt after that would come
 * back emptier than it was sent.
 */
class UsageWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { run() }

    private fun run(): Result {
        val c = applicationContext
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return Result.success()
        if (!prefs.on(Module.USAGE)) return Result.success()
        val token = cfg.tokens["usage"] ?: return Result.success()
        val zone = prefs.zone
        val now = System.currentTimeMillis()
        val today = Times.day(now, zone)
        prefs.note(Module.USAGE, null)                 // 0.1.0 left "Sending starts on …" here
        if (!Perms.usageAccess(c)) return fail(prefs, "Usage access is off, so nothing was sent.")
        if (prefs.part(UsagePart.CALLS) && !Perms.granted(c, Manifest.permission.READ_CALL_LOG))
            return fail(prefs, "Calls are on but the call history may not be read, so nothing was sent.")
        if (prefs.part(UsagePart.SMS) && !Perms.granted(c, Manifest.permission.READ_SMS))
            return fail(prefs, "Text messages are on but may not be read, so nothing was sent.")

        val dir = File(c.filesDir, "usage/days").apply { mkdirs() }
        val yesterday = Times.plusDays(today, -1)
        val fresh = (0L..6L).map { Times.plusDays(today, -it) }
            .filter { it >= yesterday || !File(dir, "$it.json").exists() }
            .sorted()
        if (fresh.isNotEmpty()) {
            val from = Times.dayStart(fresh.first(), zone) - 2 * 3600_000L
            val events = UsageSources.events(c, from, now)
            val sessions = Usage.sessions(events)
            val unlocks = Usage.unlocks(events)
            val log = RecordLog(c)
            val visits = if (prefs.part(UsagePart.WEB)) Usage.resolveVisits(log.visits(from), sessions, now) else emptyList()
            val media = if (prefs.part(UsagePart.VIDEOS)) log.segments(from) else emptyList()
            val calls = try {
                if (prefs.part(UsagePart.CALLS)) UsageSources.calls(c, from, now, zone) else emptyList()
            } catch (e: Exception) { return fail(prefs, "The call history could not be read (${e.message}).") }
            val sms = try {
                if (prefs.part(UsagePart.SMS)) UsageSources.sms(c, from, now, zone) else emptyList()
            } catch (e: Exception) { return fail(prefs, "The SMS could not be read (${e.message}).") }
            for (d in fresh) {
                val line = Usage.snapshot(d, Usage.dayEvents(d, zone, sessions, { UsageSources.label(c, it) },
                    unlocks, visits, media, calls, sms))
                val tmp = File(dir, ".$d.tmp"); tmp.writeText(line); tmp.renameTo(File(dir, "$d.json"))
            }
        }

        val built = dir.listFiles { f -> f.name.endsWith(".json") }!!.associate { it.name.removeSuffix(".json") to it.readText() }
        val sentFile = File(c.filesDir, "usage/sent")
        val sent = if (sentFile.exists()) sentFile.readLines().mapNotNull {
            it.split('\t').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }.toMap() else emptyMap()
        val days = Usage.daysToSend(today, built, sent)
        if (days.isEmpty()) { prefs.idle(Module.USAGE); return Result.success() }
        val body = days.joinToString("") { built.getValue(it) + "\n" }
        val r = Http.post("${cfg.url}/activitywatch/$token", body.toByteArray(), "application/x-ndjson",
            mapOf("X-Sender" to SENDER), timeoutMs = 900_000)
        return when {
            r.ok -> {
                val merged = sent.toMutableMap()
                days.forEach { merged[it] = Usage.md5(built.getValue(it)) }
                sentFile.writeText(merged.toSortedMap().entries.joinToString("") { "${it.key}\t${it.value}\n" })
                prefs.ok(Module.USAGE, "Sent ${days.size} day(s), ${days.first()} to ${days.last()}")
                tidy(c, dir, today)
                Result.success()
            }
            r.refused -> {
                // A refusal will not change by sending again: these days stay as
                // they are until something changes in them.
                val merged = sent.toMutableMap()
                days.forEach { merged[it] = Usage.md5(built.getValue(it)) }
                sentFile.writeText(merged.toSortedMap().entries.joinToString("") { "${it.key}\t${it.value}\n" })
                fail(prefs, "The receiver refused ${days.size} day(s) (${r.code}): ${r.body.take(300)}. " +
                    "If the receiver's rules changed, open its pairing page and pair again.")
            }
            r.blocked -> fail(prefs, "The receiver answered ${r.code}: ${r.body.take(300)}")
            else -> { prefs.err(Module.USAGE, "Could not reach the receiver (${r.code}): ${r.body.take(200)}"); Result.retry() }
        }
    }

    private fun fail(prefs: Prefs, why: String): Result { prefs.err(Module.USAGE, why); return Result.success() }

    private fun tidy(c: Context, dir: File, today: String) {
        val oldest = Times.plusDays(today, -16)
        dir.listFiles()?.filter { it.name.endsWith(".json") && it.name.removeSuffix(".json") < oldest }?.forEach { it.delete() }
        RecordLog(c).prune(System.currentTimeMillis() - 21L * 24 * 3600_000)
    }

    companion object {
        /** How the receiver tells this app's pushes from the Termux script's. */
        const val SENDER = "lifelog"
    }
}
