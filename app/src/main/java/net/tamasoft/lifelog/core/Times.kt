package net.tamasoft.lifelog.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The two time spellings the receiver already holds, so a record from this app
 * reads like one from the apps it replaces.
 *
 *  - [utcMillis]: what ActivityWatch wrote for app, unlock, web and media events,
 *    `2026-11-02T23:00:53.746Z`, always three decimals.
 *  - [localSeconds]: what the call and SMS history gave, the wall clock with the
 *    offset in force at that moment, `2026-11-03T00:45:53+01:00`.
 *
 * Days are calendar days in the receiver's time zone, from its pairing code.
 */
object Times {
    private val UTC_MS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_S: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    fun utcMillis(epochMs: Long): String = UTC_MS.format(Instant.ofEpochMilli(epochMs))

    fun localSeconds(epochMs: Long, zone: ZoneId): String =
        LOCAL_S.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun day(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun dayStart(day: String, zone: ZoneId): Long =
        LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayEnd(day: String, zone: ZoneId): Long =
        LocalDate.parse(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun plusDays(day: String, n: Long): String = LocalDate.parse(day).plusDays(n).toString()

    /** Seconds, rounded to milliseconds, as ActivityWatch gave durations. */
    fun seconds(ms: Long): Double = Math.round(ms.toDouble()) / 1000.0
}
