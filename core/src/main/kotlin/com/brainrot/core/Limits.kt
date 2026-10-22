package com.brainrot.core

import kotlinx.serialization.Serializable
import kotlin.math.min

/** Grant key used for the phone-wide (all apps) reel and screen-time limits. */
const val TOTAL_KEY = "*"

const val MAX_REEL_LIMIT = 5_000
const val MAX_MINUTES_LIMIT = 24 * 60

@Serializable
data class AppLimit(
    val packageName: String,
    val label: String = packageName,
    val dailyMinutes: Int? = null,
    /** Only meaningful for apps in [ShortVideoApps]; blocks the Shorts/Reels surface, not the whole app. */
    val dailyReels: Int? = null,
)

@Serializable
data class LimitConfig(
    val dailyReels: Int? = null,
    val dailyScreenMinutes: Int? = null,
    val apps: List<AppLimit> = emptyList(),
    /** Kill switch: when false, limits are tracked and shown but never block. */
    val enforce: Boolean = true,
) {
    /** Drops out-of-range values and duplicate/empty app entries instead of trusting stored input. */
    fun sanitized(): LimitConfig = copy(
        dailyReels = dailyReels.validOrNull(1..MAX_REEL_LIMIT),
        dailyScreenMinutes = dailyScreenMinutes.validOrNull(1..MAX_MINUTES_LIMIT),
        apps = apps
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .map {
                it.copy(
                    label = it.label.ifBlank { it.packageName }.take(60),
                    dailyMinutes = it.dailyMinutes.validOrNull(1..MAX_MINUTES_LIMIT),
                    dailyReels = it.dailyReels.validOrNull(1..MAX_REEL_LIMIT),
                )
            }
            .filter { it.dailyMinutes != null || it.dailyReels != null },
    )

    /**
     * True when [this] would let the user consume more than [previous]: a limit removed or raised,
     * or enforcement switched off. Loosening goes through the same cool-down as an unlock.
     */
    fun loosens(previous: LimitConfig): Boolean {
        if (previous.enforce && !enforce) return true
        if (isLooser(previous.dailyReels, dailyReels)) return true
        if (isLooser(previous.dailyScreenMinutes, dailyScreenMinutes)) return true
        return previous.apps.any { old ->
            val new = apps.firstOrNull { it.packageName == old.packageName }
            isLooser(old.dailyMinutes, new?.dailyMinutes) || isLooser(old.dailyReels, new?.dailyReels)
        }
    }

    fun limitFor(packageName: String): AppLimit? = apps.firstOrNull { it.packageName == packageName }

    val isEmpty: Boolean get() = dailyReels == null && dailyScreenMinutes == null && apps.isEmpty()

    private fun isLooser(old: Int?, new: Int?): Boolean = old != null && (new == null || new > old)
}

private fun Int?.validOrNull(range: IntRange): Int? = this?.takeIf { it in range }

/** Parses a limit typed by the user. Blank means "no limit"; only ASCII digits are accepted. */
sealed interface LimitInput {
    data object None : LimitInput
    data class Value(val value: Int) : LimitInput
    data object Invalid : LimitInput

    companion object {
        private val ASCII_DIGITS = Regex("^[0-9]{1,6}$")

        fun parse(text: String, range: IntRange): LimitInput {
            val t = text.trim()
            if (t.isEmpty()) return None
            if (!ASCII_DIGITS.matches(t)) return Invalid
            val v = t.toInt()
            return if (v in range) Value(v) else Invalid
        }
    }
}

/** Today's usage as measured by UsageStats (time) and the accessibility service (reels). */
data class UsageSnapshot(
    val appMs: Map<String, Long> = emptyMap(),
    val totalScreenMs: Long = 0,
    val reelsByApp: Map<String, Int> = emptyMap(),
) {
    val totalReels: Int get() = reelsByApp.values.sum()
}

enum class BreachKind { APP_TIME, APP_REELS, TOTAL_REELS, TOTAL_SCREEN_TIME }

/**
 * A limit that is currently exceeded. [used] and [limit] are reels for the *_REELS kinds and
 * milliseconds for the time kinds.
 */
data class Breach(
    val kind: BreachKind,
    val packageName: String?,
    val used: Long,
    val limit: Long,
) {
    val grantKey: String get() = if (kind == BreachKind.APP_TIME || kind == BreachKind.APP_REELS) packageName ?: TOTAL_KEY else TOTAL_KEY
    val isReels: Boolean get() = kind == BreachKind.APP_REELS || kind == BreachKind.TOTAL_REELS
}

data class LimitProgress(
    val kind: BreachKind,
    val packageName: String?,
    val label: String?,
    val used: Long,
    val limit: Long,
) {
    val fraction: Double get() = if (limit <= 0) 0.0 else used.toDouble() / limit

    /** Same rule as [LimitEvaluator.evaluate]: time is up at the limit, reels only after reel N. */
    val exceeded: Boolean get() = if (kind == BreachKind.APP_REELS || kind == BreachKind.TOTAL_REELS) used > limit else used >= limit

    fun toBreach() = Breach(kind, packageName, used, limit)
    /** Stable id for "already nudged today" bookkeeping. */
    val id: String get() = "${kind.name}:${packageName ?: TOTAL_KEY}"
}

object LimitEvaluator {
    const val NUDGE_FRACTION = 0.8

    /**
     * The breach that should block [foreground] right now, or null.
     *
     * Reel limits only block while a Shorts/Reels surface is on screen, so a user can still watch
     * a normal video in the same app; time limits block the whole app; the phone-wide screen-time
     * limit blocks everything except [exempt] (launcher, dialer, settings, this app).
     */
    fun evaluate(
        config: LimitConfig,
        today: DayStats,
        usage: UsageSnapshot,
        foreground: String?,
        inShortVideo: Boolean,
        exempt: Set<String>,
    ): Breach? {
        if (!config.enforce || foreground.isNullOrBlank() || foreground in exempt) return null
        val app = config.limitFor(foreground)

        app?.dailyMinutes?.let { minutes ->
            val limitMs = (minutes + today.extraMinutes(foreground)) * MINUTE_MS
            val used = usage.appMs[foreground] ?: 0L
            if (used >= limitMs) return Breach(BreachKind.APP_TIME, foreground, used, limitMs)
        }
        if (inShortVideo) {
            // A reel limit of N lets the user finish reel N; reel N+1 is blocked.
            app?.dailyReels?.let { reels ->
                val limit = (reels + today.extraReels(foreground)).toLong()
                val used = (usage.reelsByApp[foreground] ?: 0).toLong()
                if (used > limit) return Breach(BreachKind.APP_REELS, foreground, used, limit)
            }
            config.dailyReels?.let { reels ->
                val limit = (reels + today.extraReels(TOTAL_KEY)).toLong()
                val used = usage.totalReels.toLong()
                if (used > limit) return Breach(BreachKind.TOTAL_REELS, foreground, used, limit)
            }
        }
        config.dailyScreenMinutes?.let { minutes ->
            val limitMs = (minutes + today.extraMinutes(TOTAL_KEY)) * MINUTE_MS
            if (usage.totalScreenMs >= limitMs) {
                return Breach(BreachKind.TOTAL_SCREEN_TIME, foreground, usage.totalScreenMs, limitMs)
            }
        }
        return null
    }

    /** Every configured limit with today's usage, most-used first. */
    fun progress(config: LimitConfig, today: DayStats, usage: UsageSnapshot): List<LimitProgress> = buildList {
        config.dailyReels?.let {
            add(LimitProgress(BreachKind.TOTAL_REELS, null, null, usage.totalReels.toLong(), (it + today.extraReels(TOTAL_KEY)).toLong()))
        }
        config.dailyScreenMinutes?.let {
            add(LimitProgress(BreachKind.TOTAL_SCREEN_TIME, null, null, usage.totalScreenMs, (it + today.extraMinutes(TOTAL_KEY)) * MINUTE_MS))
        }
        for (app in config.apps) {
            app.dailyMinutes?.let {
                add(LimitProgress(BreachKind.APP_TIME, app.packageName, app.label, usage.appMs[app.packageName] ?: 0L, (it + today.extraMinutes(app.packageName)) * MINUTE_MS))
            }
            app.dailyReels?.let {
                add(LimitProgress(BreachKind.APP_REELS, app.packageName, app.label, (usage.reelsByApp[app.packageName] ?: 0).toLong(), (it + today.extraReels(app.packageName)).toLong()))
            }
        }
    }.sortedByDescending { it.fraction }

    /** Limits that crossed [NUDGE_FRACTION] and have not been announced yet today. */
    fun pendingNudges(config: LimitConfig, today: DayStats, usage: UsageSnapshot): List<LimitProgress> =
        progress(config, today, usage).filter { it.fraction >= NUDGE_FRACTION && !it.exceeded && it.id !in today.nudged }
}

/**
 * In-app unlocks: each one buys a small allowance for the rest of the day, and each one costs a
 * longer mindful wait than the last (15 s, 30 s, 60 s ... capped at 5 min).
 */
object UnlockPolicy {
    const val BASE_WAIT_SECONDS = 15
    const val MAX_WAIT_SECONDS = 300
    const val EXTRA_MINUTES = 10
    const val EXTRA_REELS = 5

    fun waitSeconds(unlocksToday: Int): Int {
        val n = unlocksToday.coerceIn(0, 10)
        return min(BASE_WAIT_SECONDS shl n, MAX_WAIT_SECONDS)
    }

    fun grant(today: DayStats, breach: Breach): DayStats {
        val key = breach.grantKey
        return if (breach.isReels) {
            today.copy(extraReelsByKey = today.extraReelsByKey + (key to today.extraReels(key) + EXTRA_REELS), unlocks = today.unlocks + 1)
        } else {
            today.copy(extraMinutesByKey = today.extraMinutesByKey + (key to today.extraMinutes(key) + EXTRA_MINUTES), unlocks = today.unlocks + 1)
        }
    }
}
