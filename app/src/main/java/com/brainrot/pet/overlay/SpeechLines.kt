package com.brainrot.pet.overlay

import android.content.Context
import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import com.brainrot.core.Breach
import com.brainrot.core.BreachKind
import com.brainrot.core.SpeechTopic
import com.brainrot.core.Stage
import com.brainrot.core.formatDuration
import com.brainrot.pet.AppGraph
import com.brainrot.pet.R

/** Maps platform-neutral topics and stages to localized resources. */
object SpeechLines {

    fun pick(res: Resources, topic: SpeechTopic): String {
        val lines = res.getStringArray(arrayFor(topic))
        return if (lines.isEmpty()) "" else lines.random()
    }

    @ArrayRes
    fun arrayFor(topic: SpeechTopic): Int = when (topic) {
        SpeechTopic.HUNGRY -> R.array.speech_hungry
        SpeechTopic.THIRSTY -> R.array.speech_thirsty
        SpeechTopic.TIRED -> R.array.speech_tired
        SpeechTopic.OVERSTIMULATED -> R.array.speech_overstimulated
        SpeechTopic.NEAR_LIMIT -> R.array.speech_near_limit
        SpeechTopic.FRESH -> R.array.speech_fresh
        SpeechTopic.FOGGY -> R.array.speech_foggy
        SpeechTopic.RESTLESS -> R.array.speech_restless
        SpeechTopic.FRIED -> R.array.speech_fried
        SpeechTopic.ROTTEN -> R.array.speech_rotten
        SpeechTopic.FED -> R.array.speech_fed
        SpeechTopic.WATERED -> R.array.speech_watered
        SpeechTopic.FULL -> R.array.speech_full
        SpeechTopic.TOO_SOON -> R.array.speech_too_soon
        SpeechTopic.BLOCKED -> R.array.speech_blocked
        SpeechTopic.WELCOME_BACK_RESTED -> R.array.speech_rested
    }

    @StringRes
    fun stageLabel(stage: Stage): Int = when (stage) {
        Stage.FRESH -> R.string.stage_fresh
        Stage.FOGGY -> R.string.stage_foggy
        Stage.RESTLESS -> R.string.stage_restless
        Stage.FRIED -> R.string.stage_fried
        Stage.ROTTEN -> R.string.stage_rotten
    }

    /** "You've used Instagram for 1h 02m today. Your limit is 1h 00m." */
    fun describe(context: Context, breach: Breach): String {
        val app = breach.packageName?.let { AppGraph.catalog.label(it) } ?: ""
        return when (breach.kind) {
            BreachKind.APP_TIME -> context.getString(R.string.block_reason_app_time, app, formatDuration(breach.used), formatDuration(breach.limit))
            BreachKind.APP_REELS -> context.resources.getQuantityString(R.plurals.block_reason_app_reels, breach.used.toInt(), breach.used.toInt(), app, breach.limit.toInt())
            BreachKind.TOTAL_REELS -> context.resources.getQuantityString(R.plurals.block_reason_total_reels, breach.used.toInt(), breach.used.toInt(), breach.limit.toInt())
            BreachKind.TOTAL_SCREEN_TIME -> context.getString(R.string.block_reason_total_screen, formatDuration(breach.used), formatDuration(breach.limit))
        }
    }

    @StringRes
    fun breachLabel(kind: BreachKind): Int = when (kind) {
        BreachKind.APP_TIME -> R.string.limit_kind_app_time
        BreachKind.APP_REELS -> R.string.limit_kind_app_reels
        BreachKind.TOTAL_REELS -> R.string.limit_kind_total_reels
        BreachKind.TOTAL_SCREEN_TIME -> R.string.limit_kind_total_screen
    }
}
