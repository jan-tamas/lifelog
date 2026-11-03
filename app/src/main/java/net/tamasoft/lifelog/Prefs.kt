package net.tamasoft.lifelog

import android.content.Context
import android.content.SharedPreferences
import net.tamasoft.lifelog.core.PairConfig
import java.time.ZoneId

/** The four modules. Each is OFF until the phone's owner turns it on. */
enum class Module(val key: String, val title: String) {
    HEALTH("health", "Health"),
    LOCATION("location", "Location"),
    USAGE("usage", "Phone use"),
    PHOTOS("photos", "Photos");
}

/** The parts of phone use, each its own switch, each off by default. */
enum class UsagePart(val key: String, val title: String) {
    WEB("web", "Websites"),
    VIDEOS("videos", "Videos"),
    CALLS("calls", "Calls"),
    SMS("sms", "Text messages");
}

class Prefs(c: Context) {
    private val p: SharedPreferences = c.getSharedPreferences("lifelog", Context.MODE_PRIVATE)

    var config: PairConfig?
        get() = p.getString("config", null)?.let { runCatching { PairConfig.fromJson(it) }.getOrNull() }
        set(v) = p.edit().putString("config", v?.toJson()).apply()

    val zone: ZoneId get() = runCatching { ZoneId.of(config?.tz ?: "UTC") }.getOrDefault(ZoneId.systemDefault())

    fun on(m: Module) = p.getBoolean("on.${m.key}", false) && config?.offers(m.key) == true
    fun setOn(m: Module, v: Boolean) = p.edit().putBoolean("on.${m.key}", v).apply()
    fun anyOn() = Module.entries.any { on(it) }

    fun part(u: UsagePart) = p.getBoolean("part.${u.key}", false)
    fun setPart(u: UsagePart, v: Boolean) = p.edit().putBoolean("part.${u.key}", v).apply()

    fun long(key: String, def: Long = 0L) = p.getLong(key, def)
    fun setLong(key: String, v: Long) = p.edit().putLong(key, v).apply()
    fun string(key: String): String? = p.getString(key, null)
    fun setString(key: String, v: String?) = p.edit().putString(key, v).apply()
    fun bool(key: String) = p.getBoolean(key, false)
    fun setBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    // What happened last, per module, for the status screen.
    fun ok(m: Module, what: String) = p.edit().putLong("ok.${m.key}", System.currentTimeMillis())
        .putString("okwhat.${m.key}", what).remove("err.${m.key}").apply()
    fun err(m: Module, why: String) = p.edit().putString("err.${m.key}", why)
        .putLong("errat.${m.key}", System.currentTimeMillis()).apply()
    /** A run that found nothing to send: not a send, so "Last sent" keeps its time. */
    fun idle(m: Module) = p.edit().putLong("idle.${m.key}", System.currentTimeMillis()).remove("err.${m.key}").apply()
    fun note(m: Module, what: String?) = p.edit().putString("note.${m.key}", what).apply()
    fun lastOk(m: Module) = p.getLong("ok.${m.key}", 0)
    fun lastOkWhat(m: Module) = p.getString("okwhat.${m.key}", null)
    fun lastErr(m: Module) = p.getString("err.${m.key}", null)
    fun lastErrAt(m: Module) = p.getLong("errat.${m.key}", 0)
    fun noteOf(m: Module) = p.getString("note.${m.key}", null)
    fun lastIdle(m: Module) = p.getLong("idle.${m.key}", 0)

    /** Calls [changed] on the main thread when a job writes what the status screen shows.
     *  Android keeps only a weak reference, so the caller holds the one returned. */
    fun watchStatus(changed: () -> Unit) = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k == null || STATUS.any { k.startsWith(it) }) changed()
    }.also { p.registerOnSharedPreferenceChangeListener(it) }
    fun unwatch(l: SharedPreferences.OnSharedPreferenceChangeListener) = p.unregisterOnSharedPreferenceChangeListener(l)

    /** Forget everything a server gave, and turn every module off. */
    fun unpair() = p.edit().clear().apply()

    companion object {
        const val HEALTH_MARK = "health.mark"
        const val HEALTH_CHANGES = "health.changesToken"
        const val HEALTH_CHANGES_KINDS = "health.changesKinds"
        const val HEALTH_HOURS = "health.hours"
        const val PHOTOS_MARK_ADDED = "photos.markAdded"
        const val PHOTOS_MARK_ID = "photos.markId"
        private val STATUS = listOf("ok.", "err.", "idle.", "note.")
    }
}
