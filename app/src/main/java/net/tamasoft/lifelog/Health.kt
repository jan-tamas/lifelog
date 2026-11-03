package net.tamasoft.lifelog

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import net.tamasoft.lifelog.core.HealthJson
import net.tamasoft.lifelog.core.Meta
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KClass

/**
 * Reads Health Connect and writes HC Webhook's JSON (core/HealthJson.kt), the
 * shape the receiver's health route already takes. Reads only; writes nothing.
 * A kind the owner did not allow is simply not read.
 */
@OptIn(ExperimentalMindfulnessSessionApi::class)
object Health {
    val KINDS: Map<String, KClass<out Record>> = linkedMapOf(
        "steps" to StepsRecord::class,
        "distance" to DistanceRecord::class,
        "heart_rate" to HeartRateRecord::class,
        "heart_rate_variability" to HeartRateVariabilityRmssdRecord::class,
        "resting_heart_rate" to RestingHeartRateRecord::class,
        "respiratory_rate" to RespiratoryRateRecord::class,
        "sleep" to SleepSessionRecord::class,
        "exercise" to ExerciseSessionRecord::class,
        "total_calories" to TotalCaloriesBurnedRecord::class,
        "active_calories" to ActiveCaloriesBurnedRecord::class,
        "weight" to WeightRecord::class,
        "oxygen_saturation" to OxygenSaturationRecord::class,
        "skin_temperature" to SkinTemperatureRecord::class,
        "mindfulness" to MindfulnessSessionRecord::class,
    )

    val PERMISSIONS: Set<String> = KINDS.values.map { HealthPermission.getReadPermission(it) }.toSet() +
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND +
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    fun available(c: Context) = HealthConnectClient.getSdkStatus(c) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(c: Context): Set<String> =
        if (!available(c)) emptySet() else HealthConnectClient.getOrCreate(c).permissionController.getGrantedPermissions()

    private fun meta(m: Metadata, zone: ZoneOffset? = null, start: ZoneOffset? = null, end: ZoneOffset? = null) = Meta(
        origin = m.dataOrigin.packageName, method = m.recordingMethod, id = m.id,
        clientRecordId = m.clientRecordId, clientRecordVersion = m.clientRecordVersion,
        lastModified = m.lastModifiedTime, zoneOffset = zone?.totalSeconds,
        startZoneOffset = start?.totalSeconds, endZoneOffset = end?.totalSeconds,
        manufacturer = m.device?.manufacturer, model = m.device?.model, deviceType = m.device?.type)

    private suspend fun <T : Record> all(client: HealthConnectClient, cls: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var page: String? = null
        do {
            val resp = client.readRecords(ReadRecordsRequest(cls, TimeRangeFilter.between(from, to),
                pageSize = 5000, pageToken = page))
            out.addAll(resp.records)
            page = resp.pageToken
        } while (!page.isNullOrEmpty())
        return out
    }

    private fun mindfulnessType(t: Int): String = when (t) {
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION -> "meditation"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING -> "breathing"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MUSIC -> "music"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MOVEMENT -> "movement"
        MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_UNGUIDED -> "unguided"
        else -> "unknown"
    }

    /** Every allowed kind over [from, to), as {kind: [record, ...]}. */
    suspend fun read(c: Context, from: Instant, to: Instant): Map<String, List<JSONObject>> {
        val client = HealthConnectClient.getOrCreate(c)
        val granted = client.permissionController.getGrantedPermissions()
        val out = LinkedHashMap<String, List<JSONObject>>()
        for ((kind, cls) in KINDS) {
            if (HealthPermission.getReadPermission(cls) !in granted) continue
            // "Unsupported record type" is the one error that means "never on this
            // phone". Anything else (a rate limit, Health Connect updating, a sync
            // in progress) is passing, and goes up so the window is tried again.
            val recs = try { all(client, cls, from, to) } catch (e: IllegalArgumentException) { continue }
            out[kind] = recs.flatMap { r -> json(client, granted, r) }
        }
        return out
    }

    private suspend fun json(client: HealthConnectClient, granted: Set<String>, r: Record): List<JSONObject> = when (r) {
        is StepsRecord -> listOf(HealthJson.interval("count", r.count, r.startTime, r.endTime, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is DistanceRecord -> listOf(HealthJson.interval("meters", r.distance.inMeters, r.startTime, r.endTime, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is TotalCaloriesBurnedRecord -> listOf(HealthJson.interval("calories", r.energy.inKilocalories, r.startTime, r.endTime, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is ActiveCaloriesBurnedRecord -> listOf(HealthJson.interval("calories", r.energy.inKilocalories, r.startTime, r.endTime, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is HeartRateRecord -> r.samples.map { HealthJson.sample("bpm", it.beatsPerMinute, it.time, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)) }
        is HeartRateVariabilityRmssdRecord -> listOf(HealthJson.sample("rmssd_millis", r.heartRateVariabilityMillis, r.time, meta(r.metadata, r.zoneOffset)))
        is RestingHeartRateRecord -> listOf(HealthJson.sample("bpm", r.beatsPerMinute, r.time, meta(r.metadata, r.zoneOffset)))
        is RespiratoryRateRecord -> listOf(HealthJson.sample("rate", r.rate, r.time, meta(r.metadata, r.zoneOffset)))
        is WeightRecord -> listOf(HealthJson.sample("kilograms", r.weight.inKilograms, r.time, meta(r.metadata, r.zoneOffset)))
        is OxygenSaturationRecord -> listOf(HealthJson.sample("percentage", r.percentage.value, r.time, meta(r.metadata, r.zoneOffset)))
        is SleepSessionRecord -> listOf(HealthJson.sleep(r.startTime, r.endTime,
            r.stages.map { HealthJson.Stage(it.stage, it.startTime, it.endTime) }, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is SkinTemperatureRecord -> r.deltas.map {
            HealthJson.skinTemperature(it.time, it.delta.inCelsius, r.baseline?.inCelsius, r.measurementLocation,
                meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset))
        }
        is MindfulnessSessionRecord -> listOf(HealthJson.mindfulness(mindfulnessType(r.mindfulnessSessionType),
            r.title, r.notes, r.startTime, r.endTime, meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        is ExerciseSessionRecord -> {
            var meters: Double? = null
            var steps: Long? = null
            if (HealthPermission.getReadPermission(DistanceRecord::class) in granted &&
                HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                runCatching {
                    val a = client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL, StepsRecord.COUNT_TOTAL),
                        TimeRangeFilter.between(r.startTime, r.endTime)))
                    meters = a[DistanceRecord.DISTANCE_TOTAL]?.inMeters
                    steps = a[StepsRecord.COUNT_TOTAL]
                }
            }
            listOf(HealthJson.exercise(r.exerciseType, r.title, r.startTime, r.endTime, meters, steps,
                meta(r.metadata, null, r.startZoneOffset, r.endZoneOffset)))
        }
        else -> emptyList()
    }
}
