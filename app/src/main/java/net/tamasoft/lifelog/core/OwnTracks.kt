package net.tamasoft.lifelog.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A place with a name and a radius, from the pairing code: OwnTracks calls it a waypoint. */
data class Region(val desc: String, val lat: Double, val lon: Double, val rad: Int, val tst: Long)

/** One location reading, reduced to what the message carries. */
data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val acc: Float?,
               val alt: Double?, val vac: Float?, val speedMps: Float?, val bearing: Float?)

/**
 * Messages in OwnTracks' own JSON (owntracks.org/booklet/tech/json), so the
 * receiver's location route, which OwnTracks posts to today, files them
 * unchanged: `location` for a reading, `transition` for entering or leaving a
 * region. The battery and network fields are kept because the location page
 * measures the phone's battery drain from them.
 */
object OwnTracks {
    fun location(f: Fix, tid: String, topic: String, battery: Int?, batteryStatus: Int,
                 conn: String, regions: List<Region>, id: String): JSONObject {
        val o = JSONObject()
            .put("_type", "location")
            .put("_id", id)
            .put("tid", tid)
            .put("topic", topic)
            .put("tst", f.timeMs / 1000)
            .put("created_at", System.currentTimeMillis() / 1000)
            .put("lat", f.lat)
            .put("lon", f.lon)
            .put("m", 1)
            .put("source", "fused")
            .put("conn", conn)
            .put("bs", batteryStatus)
        f.acc?.let { o.put("acc", it.roundToInt()) }
        f.alt?.let { o.put("alt", it.roundToInt()) }
        f.vac?.let { o.put("vac", it.roundToInt()) }
        f.speedMps?.let { o.put("vel", (it * 3.6f).roundToInt()) }
        f.bearing?.let { o.put("cog", it.roundToInt()) }
        battery?.let { o.put("batt", it) }
        val inside = JSONArray()
        regions.filter { metres(f.lat, f.lon, it.lat, it.lon) <= it.rad }.forEach { inside.put(it.desc) }
        if (inside.length() > 0) o.put("inregions", inside)
        return o
    }

    fun transition(f: Fix, tid: String, topic: String, region: Region, enter: Boolean,
                   id: String): JSONObject {
        val o = JSONObject()
            .put("_type", "transition")
            .put("_id", id)
            .put("tid", tid)
            .put("topic", "$topic/event")
            .put("event", if (enter) "enter" else "leave")
            .put("desc", region.desc)
            .put("t", "c")
            .put("tst", f.timeMs / 1000)
            .put("wtst", region.tst)
            .put("lat", f.lat)
            .put("lon", f.lon)
        f.acc?.let { o.put("acc", it.roundToInt()) }
        return o
    }

    /** OwnTracks' battery status: 0 unknown, 1 unplugged, 2 charging, 3 full. */
    fun batteryStatus(androidStatus: Int): Int = when (androidStatus) {
        2 -> 2          // BatteryManager.BATTERY_STATUS_CHARGING
        5 -> 3          // BATTERY_STATUS_FULL
        3, 4 -> 1       // DISCHARGING, NOT_CHARGING
        else -> 0
    }

    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun newId(): String = (1..8).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")
}
