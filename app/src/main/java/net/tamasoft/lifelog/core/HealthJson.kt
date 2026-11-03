package net.tamasoft.lifelog.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** A Health Connect record's provenance, as HC Webhook writes it under "metadata". */
data class Meta(val origin: String, val method: Int, val id: String = "",
                val clientRecordId: String? = null, val clientRecordVersion: Long = 0,
                val lastModified: Instant? = null, val zoneOffset: Int? = null,
                val startZoneOffset: Int? = null, val endZoneOffset: Int? = null,
                val manufacturer: String? = null, val model: String? = null,
                val deviceType: Int? = null)

/**
 * Health records in HC Webhook's JSON, the shape the receiver's health ingest
 * reads today (wiki: health-ingest, "Observed payload schema"). One builder per
 * record kind, taking plain values, so the shape is tested without Health
 * Connect and the phone-side reading stays a thin layer over these.
 *
 * Times are written in UTC (`Instant.toString()`), HC Webhook's original style.
 */
object HealthJson {
    fun t(i: Instant): String = i.toString()

    fun method(m: Int): String = when (m) {
        1 -> "actively_recorded"
        2 -> "automatically_recorded"
        3 -> "manual_entry"
        else -> "unknown"
    }

    fun meta(m: Meta): JSONObject {
        val o = JSONObject().put("data_origin", m.origin).put("recording_method", method(m.method))
        if (m.id.isNotEmpty()) o.put("id", m.id)
        m.clientRecordId?.let { o.put("client_record_id", it) }
        if (m.clientRecordVersion != 0L) o.put("client_record_version", m.clientRecordVersion)
        m.lastModified?.let { o.put("last_modified_time", t(it)) }
        m.zoneOffset?.let { o.put("zone_offset_seconds", it) }
        m.startZoneOffset?.let { o.put("start_zone_offset_seconds", it) }
        m.endZoneOffset?.let { o.put("end_zone_offset_seconds", it) }
        if (m.manufacturer != null || m.model != null || m.deviceType != null) {
            val d = JSONObject()
            m.manufacturer?.let { d.put("manufacturer", it) }
            m.model?.let { d.put("model", it) }
            m.deviceType?.let { d.put("type", it) }
            o.put("device", d)
        }
        return o
    }

    fun interval(valueKey: String, value: Number, start: Instant, end: Instant, m: Meta): JSONObject =
        JSONObject().put(valueKey, value).put("start_time", t(start)).put("end_time", t(end)).put("metadata", meta(m))

    fun sample(valueKey: String, value: Number, time: Instant, m: Meta): JSONObject =
        JSONObject().put(valueKey, value).put("time", t(time)).put("metadata", meta(m))

    data class Stage(val stage: Int, val start: Instant, val end: Instant)

    fun sleep(start: Instant, end: Instant, stages: List<Stage>, m: Meta): JSONObject {
        val arr = JSONArray()
        stages.forEach {
            arr.put(JSONObject().put("stage", it.stage.toString()).put("start_time", t(it.start))
                .put("end_time", t(it.end)).put("duration_seconds", it.end.epochSecond - it.start.epochSecond))
        }
        return JSONObject().put("session_end_time", t(end))
            .put("duration_seconds", end.epochSecond - start.epochSecond)
            .put("stages", arr).put("metadata", meta(m))
    }

    fun exercise(type: Int, title: String?, start: Instant, end: Instant, distanceMeters: Double?,
                 steps: Long?, m: Meta): JSONObject {
        val o = JSONObject().put("type", type.toString())
        if (!title.isNullOrBlank()) o.put("title", title)
        o.put("start_time", t(start)).put("end_time", t(end))
            .put("duration_seconds", end.epochSecond - start.epochSecond)
        distanceMeters?.let { o.put("distance_meters", it) }
        steps?.let { o.put("steps", it) }
        if (distanceMeters != null && steps != null && steps > 0) o.put("stride_length_m", distanceMeters / steps)
        return o.put("metadata", meta(m))
    }

    fun skinTemperature(time: Instant, deltaCelsius: Double, baselineCelsius: Double?,
                        location: Int, m: Meta): JSONObject {
        val o = JSONObject().put("time", t(time)).put("delta_celsius", deltaCelsius)
        baselineCelsius?.let { o.put("baseline_celsius", it) }
        return o.put("measurement_location", location).put("metadata", meta(m))
    }

    fun mindfulness(sessionType: String, title: String?, notes: String?, start: Instant, end: Instant,
                    m: Meta): JSONObject {
        val o = JSONObject().put("session_type", sessionType)
        title?.let { o.put("title", it) }
        notes?.let { o.put("notes", it) }
        return o.put("start_time", t(start)).put("end_time", t(end))
            .put("duration_seconds", end.epochSecond - start.epochSecond).put("metadata", meta(m))
    }

    /** The envelope: when it was sent, by what, and one array per record kind. */
    fun payload(records: Map<String, List<JSONObject>>, appVersion: String, now: Instant): String {
        val o = JSONObject().put("timestamp", t(now)).put("app_version", appVersion)
            .put("timezone", "UTC").put("sender", "lifelog")
        for ((k, v) in records.toSortedMap()) {
            if (v.isEmpty()) continue
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            o.put(k, arr)
        }
        return o.toString()
    }
}
