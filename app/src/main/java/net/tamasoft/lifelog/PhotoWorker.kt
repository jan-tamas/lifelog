package net.tamasoft.lifelog

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tamasoft.lifelog.core.Http
import net.tamasoft.lifelog.core.Reply
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * New camera photographs, on Wi-Fi (an unmetered network) only, to the
 * receiver's photo route, ORIGINAL bytes: Android strips a photo's location
 * from what an app reads unless the app asks for the original and holds the
 * photo-location permission, and the photo router reads the time and place a
 * photo was taken from exactly that data.
 *
 * Only photos added after the module was turned on, oldest first; the mark
 * moves past each one the receiver accepted, so nothing is sent twice and an
 * interrupted run carries on where it stopped.
 *
 * A photo deleted on the phone must not stay on the server, which is what
 * Syncthing's mirror of the camera folder did. So each run first tells the
 * receiver which of the photos it holds have left the camera folder (deleted,
 * in the trash, or moved out), and a photo taken back out of the trash is
 * sent again.
 */
class PhotoWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val prefs = Prefs(c)
        val cfg = prefs.config ?: return@withContext Result.success()
        if (!prefs.on(Module.PHOTOS)) return@withContext Result.success()
        val token = cfg.tokens["photos"] ?: return@withContext Result.success()
        if (!Perms.photos(c)) { prefs.err(Module.PHOTOS, "Photos, or their locations, may not be read."); return@withContext Result.success() }
        val cm = c.getSystemService(ConnectivityManager::class.java)
        if (cm.isActiveNetworkMetered) return@withContext Result.success()

        val held = Held(File(c.filesDir, "photos-sent"))
        var sent = 0
        var removed = 0

        // null when this photo is done with (sent, or refused for good); otherwise what this run returns.
        fun upload(id: Long, name: String, modified: Long, added: Long, mark: Boolean): Result? {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            // The receiver takes plain names only, and Android cannot put other
            // characters in a header: such a photo stays on the phone.
            if (ext !in IMAGE_TYPES || !SAFE_NAME.matches(name)) { held.put(id, name, null); if (mark) held.mark(prefs, added); return null }
            val uri = MediaStore.setRequireOriginal(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
            val r = try {
                c.contentResolver.openInputStream(uri)?.use { input ->
                    Http.postStream("${cfg.url}/photos/$token", input, IMAGE_TYPES.getValue(ext),
                        mapOf("X-Filename" to name, "X-Modified" to modified.toString()))
                }
            } catch (e: java.io.IOException) { null }
            if (r == null) { prefs.err(Module.PHOTOS, "Could not open $name."); return Result.retry() }
            if (!r.ok && !r.refused) {
                prefs.err(Module.PHOTOS, "The receiver answered ${r.code}: ${r.body.take(200)}")
                return if (r.blocked) Result.success() else Result.retry()
            }
            if (r.refused) prefs.err(Module.PHOTOS, "The receiver refused $name: ${r.body.take(200)}") else sent++
            held.put(id, name, if (r.ok) fileOf(r) else null)
            if (mark) held.mark(prefs, added)
            return null
        }

        // What left the camera folder, by id AND by name: Android can renumber
        // its photos (a media rescan), and a renumbered photo has not gone.
        // An empty or unreadable folder says nothing: storage may not be ready.
        val camera = camera(c)
        if (camera != null && camera.isNotEmpty()) {
            val names = camera.values.toHashSet()
            val gone = held.entries().filter { (i, e) -> !e.gone && e.file != null && i !in camera && e.name !in names }
            for (chunk in gone.chunked(200)) {
                val body = JSONObject().put("files", JSONArray(chunk.map { it.second.file })).toString()
                val r = Http.post("${cfg.url}/photos/$token/deleted", body.toByteArray(), "application/json")
                when {
                    r.ok -> { chunk.forEach { held.gone(it.first) }; removed += chunk.size }
                    r.refused -> { prefs.err(Module.PHOTOS, "The receiver refused to delete ${chunk.size} photo(s): ${r.body.take(200)}"); chunk.forEach { held.gone(it.first) } }
                    r.blocked -> { prefs.err(Module.PHOTOS, "The receiver answered ${r.code} to a deletion: ${r.body.take(200)}"); break }
                    else -> { prefs.err(Module.PHOTOS, "Could not reach the receiver (${r.code}): ${r.body.take(200)}"); return@withContext Result.retry() }
                }
            }
            // Back from the trash: the same photo, so it goes again.
            for ((id, _) in held.entries().filter { (i, e) -> e.gone && i in camera }) {
                val (name, taken) = info(c, id) ?: continue
                upload(id, name, taken, 0, mark = false)?.let { return@withContext it }
            }
        }

        // A camera can publish a photo after a later one, with an earlier time,
        // so each run looks an hour behind the mark and skips what it already sent.
        val mark = prefs.long(Prefs.PHOTOS_MARK_ADDED)
        val sel = "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED)
        var tried = 0
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel,
            arrayOf((mark - 3600).toString(), CAMERA),
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC")?.use { cur ->
            while (cur.moveToNext() && tried < 100) {
                if (c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) break
                val id = cur.getLong(0)
                if (held.has(id)) continue
                val name = cur.getString(1) ?: "$id.jpg"
                val modified = if (!cur.isNull(3)) cur.getLong(3) else cur.getLong(4) * 1000
                tried++
                upload(id, name, modified, cur.getLong(2), mark = true)?.let { return@withContext it }
            }
        }
        if (sent > 0 || removed > 0) prefs.ok(Module.PHOTOS, listOfNotNull(
            if (sent > 0) "sent $sent photo(s)" else null,
            if (removed > 0) "removed $removed deleted on the phone" else null)
            .joinToString(", ").replaceFirstChar { it.uppercase() })
        Result.success()
    }

    /** {id: name} of every photo in the camera folder now, the trash left out; null if it cannot be read. */
    private fun camera(c: Context): Map<Long, String>? {
        val out = HashMap<Long, String>()
        val cur = c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf(CAMERA), null) ?: return null
        cur.use { while (it.moveToNext()) out[it.getLong(0)] = it.getString(1) ?: "" }
        return out
    }

    /** (name, moment taken in ms) of one photo, or null. */
    private fun info(c: Context, id: Long): Pair<String, Long>? =
        c.contentResolver.query(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_MODIFIED),
            null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null
            else (cur.getString(0) ?: "$id.jpg") to (if (!cur.isNull(1)) cur.getLong(1) else cur.getLong(2) * 1000)
        }

    /** The receiver's name for what it holds, from its answer: "lifelog/PXL_….jpg". */
    private fun fileOf(r: Reply): String? = runCatching { JSONObject(r.body).optString("file").ifEmpty { null } }.getOrNull()

    /**
     * Every photo this phone sent: its id, its name here, the receiver's name
     * for its copy, and whether it has since gone from the phone. One line per
     * photo, newest last, the last [KEEP] kept. 0.1.0 wrote bare ids: those
     * photos' copies are not known, so they are never deleted from here.
     */
    private class Held(private val f: File) {
        class Entry(val name: String?, val file: String?, val gone: Boolean)

        private val map = LinkedHashMap<Long, Entry>()

        init {
            if (f.exists()) f.readLines().forEach { line ->
                val p = line.split('\t')
                val id = p[0].toLongOrNull() ?: return@forEach
                map[id] = Entry(p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null }, p.getOrNull(3) == "gone")
            }
        }

        fun has(id: Long) = id in map
        fun entries() = map.entries.map { it.key to it.value }

        fun put(id: Long, name: String?, file: String?) {
            map.remove(id); map[id] = Entry(name, file, false); save()
        }

        fun gone(id: Long) {
            val e = map[id] ?: return
            map[id] = Entry(e.name, e.file, true); save()
        }

        /** Move the photos mark past one more accepted photo. */
        fun mark(prefs: Prefs, added: Long) {
            if (added > prefs.long(Prefs.PHOTOS_MARK_ADDED)) prefs.setLong(Prefs.PHOTOS_MARK_ADDED, added)
        }

        private fun save() {
            while (map.size > KEEP) map.remove(map.keys.first())
            f.writeText(map.entries.joinToString("") {
                "${it.key}\t${it.value.name ?: ""}\t${it.value.file ?: ""}\t${if (it.value.gone) "gone" else ""}\n"
            })
        }

        companion object { const val KEEP = 5000 }
    }

    companion object {
        /** What the photo router reads; anything else stays on the phone. */
        val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$")

        val IMAGE_TYPES = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "heic" to "image/heic", "webp" to "image/webp")

        const val CAMERA = "DCIM/Camera/%"
    }
}
