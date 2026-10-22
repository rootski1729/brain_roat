package com.brainrot.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Counters for one local day. Unlock grants and nudges live here so they expire at midnight. */
@Serializable
data class DayStats(
    val day: String,
    val reelsByApp: Map<String, Int> = emptyMap(),
    /** Latest screen-time reading for the day (UsageStats is the source of truth). */
    val screenMs: Long = 0,
    val shortVideoMs: Long = 0,
    val restMs: Long = 0,
    val blocks: Int = 0,
    val unlocks: Int = 0,
    val feeds: Int = 0,
    val waters: Int = 0,
    val extraMinutesByKey: Map<String, Int> = emptyMap(),
    val extraReelsByKey: Map<String, Int> = emptyMap(),
    val nudged: Set<String> = emptySet(),
) {
    val totalReels: Int get() = reelsByApp.values.sum()

    fun extraMinutes(key: String): Int = extraMinutesByKey[key] ?: 0
    fun extraReels(key: String): Int = extraReelsByKey[key] ?: 0

    fun withReel(packageName: String): DayStats =
        copy(reelsByApp = reelsByApp + (packageName to (reelsByApp[packageName] ?: 0) + 1))

    fun sanitized(): DayStats = copy(
        reelsByApp = reelsByApp.filter { it.key.isNotBlank() }.mapValues { it.value.coerceAtLeast(0) },
        screenMs = screenMs.coerceIn(0, DAY_MS),
        shortVideoMs = shortVideoMs.coerceIn(0, DAY_MS),
        restMs = restMs.coerceIn(0, DAY_MS),
        blocks = blocks.coerceAtLeast(0),
        unlocks = unlocks.coerceAtLeast(0),
        feeds = feeds.coerceAtLeast(0),
        waters = waters.coerceAtLeast(0),
        extraMinutesByKey = extraMinutesByKey.mapValues { it.value.coerceIn(0, MAX_MINUTES_LIMIT) },
        extraReelsByKey = extraReelsByKey.mapValues { it.value.coerceIn(0, MAX_REEL_LIMIT) },
    )
}

@Serializable
data class Settings(
    val brainName: String = DEFAULT_NAME,
    val overlayEnabled: Boolean = true,
    /** Kill switch for the brain wandering around the screen when it gets restless. */
    val roamingEnabled: Boolean = true,
    val reelCountingEnabled: Boolean = true,
    val speechEnabled: Boolean = true,
    /** Live "🧠 N reels" pill at the top of the screen while a Shorts/Reels player is open. */
    val liveCounterEnabled: Boolean = true,
    val bubbleSizeDp: Int = 64,
    val onboardingDone: Boolean = false,
    /** The bubble is hidden until this time ("hide for 1 hour"). Blocking still works. */
    val bubbleHiddenUntil: Long = 0,
) {
    fun sanitized(): Settings = copy(
        brainName = cleanName(brainName),
        bubbleSizeDp = bubbleSizeDp.coerceIn(MIN_BUBBLE_DP, MAX_BUBBLE_DP),
        bubbleHiddenUntil = bubbleHiddenUntil.coerceAtLeast(0),
    )

    companion object {
        const val DEFAULT_NAME = "Brainy"
        const val MAX_NAME_LENGTH = 20
        const val MIN_BUBBLE_DP = 48
        const val MAX_BUBBLE_DP = 96

        fun cleanName(raw: String): String =
            raw.filter { !it.isISOControl() }.trim().take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME }
    }
}

@Serializable
data class AppData(
    val schema: Int = SCHEMA_VERSION,
    val brain: BrainState = BrainState(),
    val limits: LimitConfig = LimitConfig(),
    val settings: Settings = Settings(),
    val today: DayStats = DayStats(day = ""),
    /** Completed days, newest first, capped at [MAX_HISTORY_DAYS]. */
    val history: List<DayStats> = emptyList(),
    /** Wall-clock time up to which screen-on/off has been fed into the brain engine. */
    val accountedUntil: Long = 0,
) {
    /**
     * Archives [today] and starts a fresh day when [dayKey] differs. The brain itself carries over:
     * a night of sleep heals it through screen-off rest, not through the date changing.
     */
    fun rolledTo(dayKey: String): AppData {
        if (today.day == dayKey) return this
        if (today.day.isBlank()) return copy(today = DayStats(day = dayKey))
        val archived = (listOf(today) + history)
            .filter { it.day != dayKey }
            .distinctBy { it.day }
            .sortedByDescending { it.day }
            .take(MAX_HISTORY_DAYS)
        return copy(today = DayStats(day = dayKey), history = archived)
    }

    fun sanitized(): AppData = copy(
        schema = SCHEMA_VERSION,
        accountedUntil = accountedUntil.coerceAtLeast(0),
        brain = brain.clamped(),
        limits = limits.sanitized(),
        settings = settings.sanitized(),
        today = today.sanitized(),
        history = history
            .filter { DayKey.parse(it.day) != null }
            .map { it.sanitized() }
            .distinctBy { it.day }
            .sortedByDescending { it.day }
            .take(MAX_HISTORY_DAYS),
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_HISTORY_DAYS = 30
    }
}

/** On-disk format. Unknown keys are ignored so an older build can read a newer file after a rollback. */
object AppDataCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun encode(data: AppData): String = json.encodeToString(AppData.serializer(), data)

    /** Throws [kotlinx.serialization.SerializationException] or [IllegalArgumentException] on corrupt input. */
    fun decode(text: String): AppData = json.decodeFromString(AppData.serializer(), text).sanitized()
}
