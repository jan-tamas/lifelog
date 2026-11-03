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

        // A camera can publish a photo after a later one, with an earlier time,
        // so each run looks an hour behind the mark and skips what it already sent.
        val mark = prefs.long(Prefs.PHOTOS_MARK_ADDED)
        val sentFile = java.io.File(c.filesDir, "photos-sent")
        val sentIds = if (sentFile.exists()) sentFile.readLines().mapNotNull { it.toLongOrNull() }.toMutableList() else mutableListOf()
        val seen = sentIds.toHashSet()
        var newMark = mark
        val sel = "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.Images.Media.IS_PENDING} = 0"
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED)
        var sent = 0
        fun remember(id: Long, added: Long) {
            sentIds.add(id); seen.add(id)
            if (added > newMark) newMark = added
            sentFile.writeText(sentIds.takeLast(2000).joinToString("") { "$it\n" })
            prefs.setLong(Prefs.PHOTOS_MARK_ADDED, newMark)
        }
        c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel,
            arrayOf((mark - 3600).toString(), "DCIM/Camera/%"),
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC")?.use { cur ->
            while (cur.moveToNext() && sent < 100) {
                if (c.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered) break
                val id = cur.getLong(0)
                if (id in seen) continue
                val name = cur.getString(1) ?: "$id.jpg"
                val added = cur.getLong(2)
                val modified = if (!cur.isNull(3)) cur.getLong(3) else cur.getLong(4) * 1000
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                // The receiver takes plain names only, and Android cannot put other
                // characters in a header: such a photo stays on the phone.
                if (ext !in IMAGE_TYPES || !SAFE_NAME.matches(name)) { remember(id, added); continue }
                val uri = MediaStore.setRequireOriginal(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                val r = try {
                    c.contentResolver.openInputStream(uri)?.use { input ->
                        Http.postStream("${cfg.url}/photos/$token", input, IMAGE_TYPES.getValue(ext),
                            mapOf("X-Filename" to name, "X-Modified" to modified.toString()))
                    }
                } catch (e: java.io.IOException) { null }
                if (r == null) { prefs.err(Module.PHOTOS, "Could not open $name."); return@withContext Result.retry() }
                if (!r.ok && !r.refused) {
                    prefs.err(Module.PHOTOS, "The receiver answered ${r.code}: ${r.body.take(200)}")
                    return@withContext if (r.blocked) Result.success() else Result.retry()
                }
                if (r.refused) prefs.err(Module.PHOTOS, "The receiver refused $name: ${r.body.take(200)}") else sent++
                remember(id, added)
            }
        }
        if (sent > 0) prefs.ok(Module.PHOTOS, "Sent $sent photo(s)")
        Result.success()
    }

    companion object {
        /** What the photo router reads; anything else stays on the phone. */
        val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$")

        val IMAGE_TYPES = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "heic" to "image/heic", "webp" to "image/webp")
    }
}
