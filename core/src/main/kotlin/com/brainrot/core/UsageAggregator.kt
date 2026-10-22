package com.brainrot.core

/** Platform-neutral copy of the UsageEvents we care about (mapped in the Android layer). */
enum class UsageEventType { FOREGROUND, BACKGROUND, SCREEN_ON, SCREEN_OFF, SHUTDOWN }

data class UsageEventLite(
    val timeMs: Long,
    val type: UsageEventType,
    val packageName: String? = null,
    /** Activity class; lets multi-activity and split-screen apps pair resume/pause correctly. */
    val className: String? = null,
)

data class UsageSummary(
    val appMs: Map<String, Long>,
    /** Union of all app intervals, so split-screen time is not double counted. */
    val totalMs: Long,
    /** Most recently resumed app that is still open at the end of the window. */
    val foreground: String?,
)

/**
 * Rebuilds per-app foreground time from raw usage events.
 *
 * `queryUsageStats` buckets are known to disagree with Digital Wellbeing, so we pair
 * RESUMED/PAUSED (or STOPPED) events ourselves:
 *  - events from before [windowStart] are replayed so an app already open at midnight is counted
 *    from midnight, then every interval is clipped to [windowStart, now];
 *  - screen off and shutdown close every open activity (pauses are sometimes never delivered);
 *  - an activity still open at the end is counted up to [now];
 *  - [excluded] (launcher, System UI, this app) is ignored.
 */
object UsageAggregator {

    fun summarize(
        events: List<UsageEventLite>,
        windowStart: Long,
        now: Long,
        excluded: Set<String> = emptySet(),
    ): UsageSummary {
        if (now <= windowStart) return UsageSummary(emptyMap(), 0, null)
        val open = LinkedHashMap<Pair<String, String?>, Long>()
        val intervals = HashMap<String, MutableList<LongRange>>()

        fun close(key: Pair<String, String?>, at: Long) {
            val start = open.remove(key) ?: return
            val s = maxOf(start, windowStart)
            val e = minOf(at, now)
            if (e > s) intervals.getOrPut(key.first) { mutableListOf() }.add(s until e)
        }

        fun closeAll(at: Long) = open.keys.toList().forEach { close(it, at) }

        for (event in events.sortedBy { it.timeMs }) {
            if (event.timeMs > now) break
            val pkg = event.packageName
            when (event.type) {
                UsageEventType.FOREGROUND -> {
                    if (pkg.isNullOrBlank() || pkg in excluded) continue
                    val key = pkg to event.className
                    // A second RESUMED for the same activity without a PAUSED: keep the earlier start.
                    if (key !in open) open[key] = event.timeMs
                }
                UsageEventType.BACKGROUND -> {
                    if (pkg.isNullOrBlank()) continue
                    val exact = pkg to event.className
                    if (exact in open) {
                        close(exact, event.timeMs)
                    } else {
                        // No class or a class we never saw resumed (seen on some OEMs): close the package.
                        open.keys.filter { it.first == pkg }.forEach { close(it, event.timeMs) }
                    }
                }
                UsageEventType.SCREEN_OFF, UsageEventType.SHUTDOWN -> closeAll(event.timeMs)
                UsageEventType.SCREEN_ON -> Unit
            }
        }
        val foreground = open.entries.maxByOrNull { it.value }?.key?.first
        closeAll(now)

        val appMs = intervals.mapValues { (_, list) -> unionLength(list) }.filterValues { it > 0 }
        val total = unionLength(intervals.values.flatten())
        return UsageSummary(appMs, total, foreground)
    }

    private fun unionLength(ranges: List<LongRange>): Long {
        if (ranges.isEmpty()) return 0
        val sorted = ranges.sortedBy { it.first }
        var total = 0L
        var curStart = sorted[0].first
        var curEnd = sorted[0].last + 1
        for (r in sorted.drop(1)) {
            val end = r.last + 1
            if (r.first <= curEnd) {
                if (end > curEnd) curEnd = end
            } else {
                total += curEnd - curStart
                curStart = r.first
                curEnd = end
            }
        }
        return total + (curEnd - curStart)
    }
}
