package net.tamasoft.lifelog.core

import org.json.JSONObject
import java.time.ZoneId

/** One event from Android's usage history, reduced to what is needed here. */
data class UsageEvent(val ts: Long, val type: Int, val pkg: String)

/** A stretch of time one app was in front. [end] is null while it still is. */
data class Session(val pkg: String, val start: Long, val end: Long?)

/** A page seen in a browser: when the watcher first saw it, and what it was. */
data class Visit(val ts: Long, val browser: String, val site: String, val title: String,
                 val video: String?)

/** A stretch of a video playing (or paused) in one of the listed video apps. */
data class MediaSegment(val start: Long, val end: Long, val pkg: String, val title: String,
                        val artist: String, val state: String)

/**
 * App sessions and unlocks from Android's usage history, as ActivityWatch
 * built them: a session is one app in front, from the moment it resumed to
 * the moment it paused, the screen went off or the keyguard came up. A pause
 * followed within a second by a resume of the same app is one session (an
 * activity handing over to another of the same app).
 */
object Usage {
    // android.app.usage.UsageEvents.Event constants, copied so this runs on the JVM.
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val ACTIVITY_STOPPED = 23
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val DEVICE_SHUTDOWN = 26

    private const val MERGE_MS = 1_000L

    fun sessions(events: List<UsageEvent>): List<Session> {
        val out = mutableListOf<Session>()
        var cur: String? = null
        var start = 0L
        var open = false
        var closedAt = 0L
        fun emit(end: Long) {
            val p = cur ?: return
            if (end > start) out.add(Session(p, start, end))
            cur = null
            open = false
        }
        for (e in events.sortedBy { it.ts }) {
            when (e.type) {
                ACTIVITY_RESUMED -> {
                    if (cur == e.pkg && !open && e.ts - closedAt <= MERGE_MS) {
                        open = true                      // the same app, one session
                    } else if (cur == e.pkg && open) {
                        // a second activity of the app in front: nothing changes
                    } else {
                        if (cur != null) emit(if (open) e.ts else closedAt)
                        cur = e.pkg; start = e.ts; open = true
                    }
                }
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> if (cur == e.pkg && open) {
                    open = false; closedAt = e.ts
                }
                SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN, DEVICE_SHUTDOWN ->
                    if (cur != null) emit(if (open) e.ts else closedAt)
            }
            if (cur != null && !open && e.ts - closedAt > MERGE_MS) emit(closedAt)
        }
        if (cur != null) {
            if (open) out.add(Session(cur!!, start, null)) else emit(closedAt)
        }
        return out
    }

    fun unlocks(events: List<UsageEvent>): List<Long> =
        events.filter { it.type == KEYGUARD_HIDDEN }.map { it.ts }.sorted()

    /** The part of [start, end) inside [from, to), or null. */
    fun clip(start: Long, end: Long, from: Long, to: Long): Pair<Long, Long>? {
        val s = maxOf(start, from)
        val e = minOf(end, to)
        return if (e > s) s to e else null
    }

    /**
     * How long each page was looked at: until the next page, or until its
     * browser left the front, whichever came first. The watcher only ever sees
     * the browsers, by design, so the usage history says when one was left.
     * Consecutive sightings of the same page in one session are one visit.
     */
    fun resolveVisits(visits: List<Visit>, sessions: List<Session>, now: Long):
            List<Triple<Visit, Long, Long>> {
        val sorted = visits.sortedBy { it.ts }
        val out = mutableListOf<Triple<Visit, Long, Long>>()
        for ((i, v) in sorted.withIndex()) {
            val s = sessions.firstOrNull {
                it.pkg == v.browser && it.start <= v.ts + MERGE_MS && v.ts < (it.end ?: now)
            }
            val sessionEnd = s?.let { it.end ?: now } ?: v.ts
            val next = sorted.getOrNull(i + 1)?.ts ?: Long.MAX_VALUE
            val end = minOf(sessionEnd, next).coerceAtLeast(v.ts)
            val last = out.lastOrNull()
            if (last != null && last.first.sameAs(v) && last.third >= v.ts - MERGE_MS) {
                out[out.size - 1] = Triple(last.first, last.second, maxOf(last.third, end))
            } else {
                out.add(Triple(v, v.ts, end))
            }
        }
        return out
    }

    private fun Visit.sameAs(o: Visit) =
        browser == o.browser && site == o.site && title == o.title && video == o.video

    fun appEvent(pkg: String, label: String, start: Long, end: Long): JSONObject = JSONObject()
        .put("src", "app").put("ts", Times.utcMillis(start)).put("dur", Times.seconds(end - start))
        .put("app", label).put("package", pkg)

    fun unlockEvent(ts: Long): JSONObject = JSONObject().put("src", "unlock").put("ts", Times.utcMillis(ts))

    fun webEvent(v: Visit, start: Long, end: Long): JSONObject {
        val o = JSONObject().put("src", "web").put("ts", Times.utcMillis(start))
            .put("dur", Times.seconds(end - start)).put("site", v.site).put("title", v.title)
            .put("browser", v.browser)
        if (!v.video.isNullOrEmpty()) o.put("video", v.video)
        return o
    }

    fun mediaEvent(m: MediaSegment, start: Long, end: Long): JSONObject = JSONObject()
        .put("src", "media").put("ts", Times.utcMillis(start)).put("dur", Times.seconds(end - start))
        .put("package", m.pkg).put("title", m.title).put("artist", m.artist).put("state", m.state)

    /**
     * Everything for one day, clipped to its midnights as ActivityWatch clipped
     * its events, in time order. Sessions still open are left for a later run:
     * the receiver files a finished session, not one still growing.
     */
    fun dayEvents(day: String, zone: ZoneId, sessions: List<Session>, labels: (String) -> String,
                  unlocks: List<Long>, visits: List<Triple<Visit, Long, Long>>,
                  media: List<MediaSegment>, calls: List<Pair<Long, JSONObject>>,
                  sms: List<Pair<Long, JSONObject>>): List<JSONObject> {
        val from = Times.dayStart(day, zone)
        val to = Times.dayEnd(day, zone)
        val out = mutableListOf<Pair<Long, JSONObject>>()
        for (s in sessions) {
            val end = s.end ?: continue
            clip(s.start, end, from, to)?.let { (a, b) -> out.add(a to appEvent(s.pkg, labels(s.pkg), a, b)) }
        }
        unlocks.filter { it in from until to }.forEach { out.add(it to unlockEvent(it)) }
        for ((v, a0, b0) in visits) {
            if (a0 == b0) { if (a0 in from until to) out.add(a0 to webEvent(v, a0, b0)); continue }
            clip(a0, b0, from, to)?.let { (a, b) -> out.add(a to webEvent(v, a, b)) }
        }
        for (m in media) {
            if (m.start == m.end) { if (m.start in from until to) out.add(m.start to mediaEvent(m, m.start, m.end)); continue }
            clip(m.start, m.end, from, to)?.let { (a, b) -> out.add(a to mediaEvent(m, a, b)) }
        }
        calls.filter { it.first in from until to }.forEach { out.add(it) }
        sms.filter { it.first in from until to }.forEach { out.add(it) }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** One line of the push: {"day", "events"}. */
    fun snapshot(day: String, events: List<JSONObject>): String {
        val arr = org.json.JSONArray()
        events.forEach { arr.put(it) }
        return JSONObject().put("day", day).put("events", arr).toString()
    }

    fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Which days to send: today and yesterday always (they are still filling),
     * and any older day of the last [windowDays] whose snapshot changed since the
     * receiver last accepted it.
     */
    fun daysToSend(today: String, built: Map<String, String>,
                   sent: Map<String, String>, windowDays: Long = 14): List<String> {
        val floor = Times.plusDays(today, -windowDays)
        val yesterday = Times.plusDays(today, -1)
        return built.keys.filter { it >= floor && it <= today }.sorted().filter { d ->
            d == today || d == yesterday || sent[d] != Usage.md5(built.getValue(d))
        }
    }
}
