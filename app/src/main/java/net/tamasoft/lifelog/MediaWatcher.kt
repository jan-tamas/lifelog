package net.tamasoft.lifelog

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import net.tamasoft.lifelog.core.MediaSegment

/**
 * The video watcher. Android grants a notification listener the right to see
 * which media sessions are playing; this one reads no notification at all. It
 * follows only the video apps on the receiver's list (the receiver refuses any
 * other), and keeps, per stretch of playing or pausing: the title, the channel
 * and the state. Music players are never followed.
 */
class MediaWatcher : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val tracked = HashMap<MediaSession.Token, Tracker>()
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { update(it ?: emptyList()) }

    override fun onListenerConnected() {
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val me = ComponentName(this, MediaWatcher::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(listener, me, handler)
            update(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        tracked.values.toList().forEach { it.stop() }
        tracked.clear()
        runCatching { msm?.removeOnActiveSessionsChangedListener(listener) }
    }

    private fun update(controllers: List<MediaController>) {
        val apps = Prefs(this).config?.videoApps ?: emptySet()
        val keep = controllers.filter { it.packageName in apps }
        for (token in tracked.keys.toList()) {
            if (keep.none { it.sessionToken == token }) tracked.remove(token)?.stop()
        }
        for (c in keep) if (c.sessionToken !in tracked) tracked[c.sessionToken] = Tracker(c).also { it.start() }
    }

    private inner class Tracker(val c: MediaController) : MediaController.Callback() {
        var since = 0L
        var title = ""
        var artist = ""
        var state: String? = null

        fun start() { c.registerCallback(this, handler); snapshot() }
        fun stop() { runCatching { c.unregisterCallback(this) }; close(System.currentTimeMillis()); state = null }

        override fun onPlaybackStateChanged(s: PlaybackState?) = snapshot()
        override fun onMetadataChanged(m: MediaMetadata?) = snapshot()
        override fun onSessionDestroyed() { stop(); tracked.remove(c.sessionToken) }

        fun snapshot() {
            val md = c.metadata
            val t = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val a = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
            val st = when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_STOPPED -> "stopped"
                PlaybackState.STATE_BUFFERING -> "buffering"
                else -> null
            }
            if (t == title && a == artist && st == state) return
            val now = System.currentTimeMillis()
            close(now)
            title = t; artist = a; state = st; since = now
        }

        fun close(now: Long) {
            val s = state ?: return
            if (title.isEmpty() && artist.isEmpty()) return
            val prefs = Prefs(this@MediaWatcher)
            if (!prefs.on(Module.USAGE) || !prefs.part(UsagePart.VIDEOS)) return
            RecordLog(this@MediaWatcher).segment(MediaSegment(since, now, c.packageName, title, artist, s))
        }
    }
}
