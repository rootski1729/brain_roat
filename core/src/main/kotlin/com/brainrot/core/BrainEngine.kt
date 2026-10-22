package com.brainrot.core

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Persistent vital signs of the brain pet. Every meter is 0..100.
 *
 * `rot` is the headline number: it rises with reels and screen time and only really falls
 * when the phone is put down. Food and water help a little, but rest is the cure.
 */
@Serializable
data class BrainState(
    val rot: Double = 0.0,
    val nourishment: Double = 80.0,
    val hydration: Double = 80.0,
    val energy: Double = 100.0,
    val lastFedAt: Long = 0L,
    val lastWateredAt: Long = 0L,
) {
    fun clamped(): BrainState = copy(
        rot = rot.clampMeter(),
        nourishment = nourishment.clampMeter(),
        hydration = hydration.clampMeter(),
        energy = energy.clampMeter(),
        lastFedAt = lastFedAt.coerceAtLeast(0),
        lastWateredAt = lastWateredAt.coerceAtLeast(0),
    )
}

internal fun Double.clampMeter(): Double = if (isNaN()) 0.0 else coerceIn(0.0, 100.0)

/** Visible rot stages; each one changes colour, face, jitter and how much the brain wanders. */
enum class Stage(val minRot: Double) {
    FRESH(0.0),
    FOGGY(20.0),
    RESTLESS(40.0),
    FRIED(60.0),
    ROTTEN(80.0);

    companion object {
        fun of(rot: Double): Stage {
            val r = rot.clampMeter()
            return entries.last { r >= it.minRot }
        }
    }
}

enum class Need { HUNGRY, THIRSTY, TIRED, OVERSTIMULATED }

/** Derived, non-persistent view of the brain used for rendering and speech. */
data class BrainMood(
    val stage: Stage,
    /** 0..1: drives jitter amplitude, wander frequency and animation speed. */
    val restlessness: Double,
    /** 0..100 overall wellbeing shown in the app. */
    val health: Int,
    val needs: Set<Need>,
)

/** What happened since the previous engine update. */
data class ActivitySlice(
    val screenOnMs: Long = 0,
    /** Part of [screenOnMs] spent inside a Shorts/Reels surface. */
    val shortVideoMs: Long = 0,
    /** One continuous screen-off period. Only periods >= [BrainTuning.minRestMs] count as rest. */
    val screenOffMs: Long = 0,
    val newReels: Int = 0,
)

enum class Food(val nourishment: Double, val rotRelief: Double) {
    WALNUT(nourishment = 35.0, rotRelief = 1.0),
    BLUEBERRIES(nourishment = 20.0, rotRelief = 3.0),
    SALMON(nourishment = 45.0, rotRelief = 2.0),
}

sealed interface CareResult {
    data class Accepted(val state: BrainState) : CareResult
    data class TooSoon(val retryInMs: Long) : CareResult
    data object Full : CareResult
}

/** All rates are per minute unless noted. Kept in one place so balancing is a one-file change. */
data class BrainTuning(
    val rotPerReel: Double = 0.6,
    val rotPerScreenMinute: Double = 0.12,
    val rotPerShortVideoMinute: Double = 0.45,
    val rotRecoveryPerRestMinute: Double = 0.35,
    val minRestMs: Long = 2 * MINUTE_MS,
    val nourishmentDecayPerMinute: Double = 0.2,
    val hydrationDecayPerMinute: Double = 0.25,
    /** Needs drain slower while the phone is down (sleep / rest). */
    val restNeedDecayFactor: Double = 0.4,
    val energyDrainPerScreenMinute: Double = 0.15,
    val energyRecoveryPerRestMinute: Double = 0.5,
    /** Rot builds faster when the brain is starving or dehydrated. */
    val neglectRotMultiplier: Double = 1.25,
    val neglectThreshold: Double = 20.0,
    val needThreshold: Double = 30.0,
    val tiredThreshold: Double = 25.0,
    val overstimulatedSessionMs: Long = 45 * MINUTE_MS,
    val feedCooldownMs: Long = 30 * MINUTE_MS,
    val waterCooldownMs: Long = 10 * MINUTE_MS,
    val waterAmount: Double = 30.0,
    val waterRotRelief: Double = 1.5,
    val fullThreshold: Double = 90.0,
    /** A single slice longer than this is clamped (clock jumps, restored backups). */
    val maxSliceMs: Long = DAY_MS,
)

object BrainEngine {

    fun advance(state: BrainState, slice: ActivitySlice, tuning: BrainTuning = BrainTuning()): BrainState {
        val onMs = slice.screenOnMs.coerceIn(0, tuning.maxSliceMs)
        val onMin = onMs.toDouble() / MINUTE_MS
        val shortMin = slice.shortVideoMs.coerceIn(0, onMs).toDouble() / MINUTE_MS
        val offMs = slice.screenOffMs.coerceIn(0, tuning.maxSliceMs)
        val restMin = if (offMs >= tuning.minRestMs) offMs.toDouble() / MINUTE_MS else 0.0
        // Short screen-off blips (checking a notification) are neither use nor rest.
        val blipMin = if (offMs < tuning.minRestMs) offMs.toDouble() / MINUTE_MS else 0.0
        val reels = slice.newReels.coerceAtLeast(0)

        val neglected = state.nourishment < tuning.neglectThreshold || state.hydration < tuning.neglectThreshold
        val multiplier = if (neglected) tuning.neglectRotMultiplier else 1.0
        val rotGain = (onMin * tuning.rotPerScreenMinute +
            shortMin * tuning.rotPerShortVideoMinute +
            reels * tuning.rotPerReel) * multiplier
        val needMinutes = onMin + blipMin + restMin * tuning.restNeedDecayFactor

        return state.copy(
            rot = state.rot + rotGain - restMin * tuning.rotRecoveryPerRestMinute,
            nourishment = state.nourishment - needMinutes * tuning.nourishmentDecayPerMinute,
            hydration = state.hydration - needMinutes * tuning.hydrationDecayPerMinute,
            energy = state.energy - onMin * tuning.energyDrainPerScreenMinute +
                restMin * tuning.energyRecoveryPerRestMinute,
        ).clamped()
    }

    fun feed(state: BrainState, food: Food, now: Long, tuning: BrainTuning = BrainTuning()): CareResult {
        cooldownRemaining(state.lastFedAt, now, tuning.feedCooldownMs)?.let { return CareResult.TooSoon(it) }
        if (state.nourishment >= tuning.fullThreshold) return CareResult.Full
        return CareResult.Accepted(
            state.copy(
                nourishment = state.nourishment + food.nourishment,
                rot = state.rot - food.rotRelief,
                lastFedAt = now,
            ).clamped(),
        )
    }

    fun water(state: BrainState, now: Long, tuning: BrainTuning = BrainTuning()): CareResult {
        cooldownRemaining(state.lastWateredAt, now, tuning.waterCooldownMs)?.let { return CareResult.TooSoon(it) }
        if (state.hydration >= tuning.fullThreshold) return CareResult.Full
        return CareResult.Accepted(
            state.copy(
                hydration = state.hydration + tuning.waterAmount,
                rot = state.rot - tuning.waterRotRelief,
                lastWateredAt = now,
            ).clamped(),
        )
    }

    fun mood(state: BrainState, sessionMs: Long, tuning: BrainTuning = BrainTuning()): BrainMood {
        val s = state.clamped()
        val sessionFactor = min(sessionMs.coerceAtLeast(0).toDouble() / HOUR_MS, 1.0)
        val restlessness = (s.rot / 100.0 * 0.7 + sessionFactor * 0.2 +
            (if (s.energy < tuning.tiredThreshold) 0.1 else 0.0)).coerceIn(0.0, 1.0)
        val damage = s.rot * 0.55 + (100 - s.nourishment) * 0.15 +
            (100 - s.hydration) * 0.15 + (100 - s.energy) * 0.15
        val needs = buildSet {
            if (s.nourishment < tuning.needThreshold) add(Need.HUNGRY)
            if (s.hydration < tuning.needThreshold) add(Need.THIRSTY)
            if (s.energy < tuning.tiredThreshold) add(Need.TIRED)
            if (sessionMs >= tuning.overstimulatedSessionMs) add(Need.OVERSTIMULATED)
        }
        return BrainMood(
            stage = Stage.of(s.rot),
            restlessness = restlessness,
            health = (100 - damage).roundToInt().coerceIn(0, 100),
            needs = needs,
        )
    }

    /** Null when the action is allowed. A clock that moved backwards never locks the user out. */
    private fun cooldownRemaining(lastAt: Long, now: Long, cooldownMs: Long): Long? {
        if (lastAt <= 0) return null
        val remaining = lastAt + cooldownMs - now
        return if (remaining in 1..cooldownMs) remaining else null
    }
}

/** What the brain says in its speech bubble; the Android layer maps topics to localized lines. */
enum class SpeechTopic {
    HUNGRY, THIRSTY, TIRED, OVERSTIMULATED, NEAR_LIMIT,
    FRESH, FOGGY, RESTLESS, FRIED, ROTTEN,
    FED, WATERED, FULL, TOO_SOON, BLOCKED, WELCOME_BACK_RESTED,
}

object BrainVoice {
    /** Needs win over stage chatter, and the most urgent need wins over the others. */
    fun ambientTopic(mood: BrainMood): SpeechTopic = when {
        Need.OVERSTIMULATED in mood.needs && mood.stage >= Stage.RESTLESS -> SpeechTopic.OVERSTIMULATED
        Need.THIRSTY in mood.needs -> SpeechTopic.THIRSTY
        Need.HUNGRY in mood.needs -> SpeechTopic.HUNGRY
        Need.TIRED in mood.needs -> SpeechTopic.TIRED
        Need.OVERSTIMULATED in mood.needs -> SpeechTopic.OVERSTIMULATED
        else -> when (mood.stage) {
            Stage.FRESH -> SpeechTopic.FRESH
            Stage.FOGGY -> SpeechTopic.FOGGY
            Stage.RESTLESS -> SpeechTopic.RESTLESS
            Stage.FRIED -> SpeechTopic.FRIED
            Stage.ROTTEN -> SpeechTopic.ROTTEN
        }
    }

    fun careTopic(result: CareResult, water: Boolean): SpeechTopic = when (result) {
        is CareResult.Accepted -> if (water) SpeechTopic.WATERED else SpeechTopic.FED
        is CareResult.TooSoon -> SpeechTopic.TOO_SOON
        CareResult.Full -> SpeechTopic.FULL
    }
}
