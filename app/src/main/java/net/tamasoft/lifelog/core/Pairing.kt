package net.tamasoft.lifelog.core

import org.json.JSONObject
import java.net.URI
import java.util.Base64

/**
 * What the receiver's pairing page hands this phone: where to send, and one
 * token per route. Nothing about a person is in the app itself; it all
 * arrives here, from the owner's own server, on the owner's own phone.
 *
 * A module the server offers no token for cannot be turned on.
 */
data class PairConfig(
    val url: String,
    val tz: String,
    val tokens: Map<String, String>,
    val tid: String,
    val topic: String,
    val regions: List<Region>,
    val videoApps: Set<String>,
) {
    val host: String get() = URI(url).host ?: url

    fun offers(module: String) = !tokens[module].isNullOrEmpty()

    fun toJson(): String {
        val t = JSONObject()
        tokens.forEach { (k, v) -> t.put(k, v) }
        val regs = org.json.JSONArray()
        regions.forEach {
            regs.put(JSONObject().put("desc", it.desc).put("lat", it.lat).put("lon", it.lon)
                .put("rad", it.rad).put("tst", it.tst))
        }
        val apps = org.json.JSONArray()
        videoApps.sorted().forEach { apps.put(it) }
        return JSONObject().put("v", 1).put("url", url).put("tz", tz).put("tokens", t)
            .put("location", JSONObject().put("tid", tid).put("topic", topic).put("regions", regs))
            .put("video_apps", apps).toString()
    }

    companion object {
        const val SCHEME_PREFIX = "lifelog://pair?c="

        /** A pairing link, the code inside it, or the JSON itself. */
        fun parse(input: String): PairConfig {
            var s = input.trim()
            if (s.startsWith("lifelog://", ignoreCase = true)) {
                s = s.substringAfter("c=", "").substringBefore("&")
            }
            val json = if (s.startsWith("{")) s else
                String(Base64.getUrlDecoder().decode(s.trimEnd('=')), Charsets.UTF_8)
            return fromJson(json)
        }

        fun fromJson(json: String): PairConfig {
            val o = JSONObject(json)
            require(o.optInt("v") == 1) { "not a Lifelog pairing code" }
            val url = o.getString("url").trimEnd('/')
            require(url.startsWith("https://")) { "the server's address must be https" }
            val t = o.getJSONObject("tokens")
            val tokens = t.keys().asSequence().associateWith { t.getString(it) }
            val loc = o.optJSONObject("location") ?: JSONObject()
            val regs = loc.optJSONArray("regions")
            val regions = (0 until (regs?.length() ?: 0)).map {
                val r = regs!!.getJSONObject(it)
                Region(r.getString("desc"), r.getDouble("lat"), r.getDouble("lon"),
                    r.optInt("rad", 100), r.optLong("tst", 0))
            }
            val apps = o.optJSONArray("video_apps")
            val videoApps = (0 until (apps?.length() ?: 0)).map { apps!!.getString(it) }.toSet()
            return PairConfig(url, o.optString("tz", "UTC"), tokens, loc.optString("tid", "LL"),
                loc.optString("topic", "owntracks/lifelog/phone"), regions, videoApps)
        }
    }
}
