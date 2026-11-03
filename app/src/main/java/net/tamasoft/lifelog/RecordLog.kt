package net.tamasoft.lifelog

import android.content.Context
import net.tamasoft.lifelog.core.MediaSegment
import net.tamasoft.lifelog.core.Visit
import org.json.JSONObject
import java.io.File

/**
 * What the two watchers saw, kept on the phone until the usage job turns it
 * into days: pages from the browser watcher, video segments from the media
 * watcher. One line each, appended; nothing older than three weeks is kept.
 */
class RecordLog(c: Context) {
    private val dir = File(c.filesDir, "usage").apply { mkdirs() }
    private val web = File(dir, "web.jsonl")
    private val media = File(dir, "media.jsonl")

    fun visit(v: Visit) = synchronized(LOCK) { append(web, JSONObject().put("ts", v.ts).put("browser", v.browser)
        .put("site", v.site).put("title", v.title).apply { v.video?.let { put("video", it) } }) }

    fun segment(m: MediaSegment) = synchronized(LOCK) { append(media, JSONObject().put("start", m.start).put("end", m.end)
        .put("pkg", m.pkg).put("title", m.title).put("artist", m.artist).put("state", m.state)) }

    fun visits(since: Long): List<Visit> = lines(web).mapNotNull { o ->
        val ts = o.optLong("ts")
        if (ts < since) null else Visit(ts, o.getString("browser"), o.getString("site"),
            o.optString("title"), o.optString("video").ifEmpty { null })
    }

    fun segments(since: Long): List<MediaSegment> = lines(media).mapNotNull { o ->
        val end = o.optLong("end")
        if (end < since) null else MediaSegment(o.getLong("start"), end, o.getString("pkg"),
            o.optString("title"), o.optString("artist"), o.optString("state"))
    }

    fun prune(before: Long) = synchronized(LOCK) {
        for ((f, key) in listOf(web to "ts", media to "end")) {
            val keep = lines(f).filter { it.optLong(key) >= before }
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(keep.joinToString("") { it.toString() + "\n" })
            tmp.renameTo(f)
        }
    }

    private fun append(f: File, o: JSONObject) = f.appendText(o.toString() + "\n")

    private fun lines(f: File): List<JSONObject> = synchronized(LOCK) {
        if (!f.exists()) emptyList() else f.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    companion object {
        /** The watchers and the usage job each make their own RecordLog: one lock for all. */
        private val LOCK = Any()
    }
}
