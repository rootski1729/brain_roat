package com.brainrot.pet.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brainrot.core.DayKey
import com.brainrot.core.HOUR_MS
import com.brainrot.core.MINUTE_MS
import com.brainrot.core.UsageAggregator
import com.brainrot.core.UsageEventLite
import com.brainrot.core.UsageEventType
import com.brainrot.core.UsageSummary
import com.brainrot.pet.BrainLog

/** Thin adapter from [UsageStatsManager] events to the platform-neutral [UsageAggregator]. */
class UsageReader(context: Context) {
    private val usm = context.getSystemService(UsageStatsManager::class.java)

    /** Today's per-app foreground time. Looks back [LOOKBACK_MS] so apps open at midnight count. */
    fun today(now: Long, excluded: Set<String>): UsageSummary {
        val start = DayKey.startOfDay(now)
        return UsageAggregator.summarize(events(start - LOOKBACK_MS, now), start, now, excluded)
    }

    fun between(from: Long, to: Long, excluded: Set<String>): UsageSummary =
        UsageAggregator.summarize(events(from - LOOKBACK_MS, to), from, to, excluded)

    fun events(from: Long, to: Long): List<UsageEventLite> {
        if (usm == null || to <= from) return emptyList()
        val result = ArrayList<UsageEventLite>(256)
        try {
            val iterator = usm.queryEvents(from, to) ?: return emptyList()
            val event = UsageEvents.Event()
            while (iterator.hasNextEvent()) {
                iterator.getNextEvent(event)
                val type = map(event.eventType) ?: continue
                result += UsageEventLite(event.timeStamp, type, event.packageName, event.className)
            }
        } catch (e: SecurityException) {
            // Usage access revoked; callers treat empty usage as "unknown", the UI shows the missing permission.
            BrainLog.w("usage_query_denied", e)
        } catch (e: RuntimeException) {
            BrainLog.w("usage_query_failed", e)
        }
        return result
    }

    private fun map(type: Int): UsageEventType? = when (type) {
        ACTIVITY_RESUMED -> UsageEventType.FOREGROUND
        ACTIVITY_PAUSED, ACTIVITY_STOPPED -> UsageEventType.BACKGROUND
        SCREEN_INTERACTIVE -> UsageEventType.SCREEN_ON
        SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_OFF
        DEVICE_SHUTDOWN -> UsageEventType.SHUTDOWN
        else -> null
    }

    companion object {
        const val LOOKBACK_MS = 3 * HOUR_MS
        const val FOREGROUND_LOOKBACK_MS = 10 * MINUTE_MS

        // Raw values so minSdk 26 devices work; the named constants arrived in API 28/29 with the same values.
        private const val ACTIVITY_RESUMED = 1
        private const val ACTIVITY_PAUSED = 2
        private const val SCREEN_INTERACTIVE = 15
        private const val SCREEN_NON_INTERACTIVE = 16
        private const val ACTIVITY_STOPPED = 23
        private const val DEVICE_SHUTDOWN = 26
    }
}

/**
 * Foreground app from usage events, used only when the accessibility service is off. Keeps the
 * last known answer between polls so an app opened long ago is still known.
 */
class ForegroundPoller(private val reader: UsageReader) {
    private var queriedUntil = 0L
    private var current: String? = null

    fun poll(now: Long): String? {
        val from = if (queriedUntil == 0L) now - UsageReader.FOREGROUND_LOOKBACK_MS else queriedUntil - OVERLAP_MS
        for (event in reader.events(from, now)) {
            when (event.type) {
                UsageEventType.FOREGROUND -> current = event.packageName
                UsageEventType.SCREEN_OFF, UsageEventType.SHUTDOWN -> current = null
                else -> Unit
            }
        }
        queriedUntil = now
        return current
    }

    companion object {
        private const val OVERLAP_MS = 2_000L
    }
}
