package com.brainrot.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val SECOND_MS = 1_000L
const val MINUTE_MS = 60 * SECOND_MS
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS

/** Local calendar day as an ISO `yyyy-MM-dd` key; daily counters roll over when this changes. */
object DayKey {
    fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun startOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun parse(key: String): LocalDate? = runCatching { LocalDate.parse(key) }.getOrNull()
}

/** "2h 05m", "12m", "0m" — compact duration for the bubble, widget and notification. */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0) / MINUTE_MS
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}
