package com.brainrot.core

/**
 * Reel detection depends on other apps' view ids, which change without notice. When an app update
 * breaks it, counting silently stops; this is the signal that makes that visible in the app.
 */
object DetectionHealth {
    /** YouTube is also used for long videos, so it needs more unexplained time before we warn. */
    private val suspectAfterMs = mapOf(
        ShortVideoApps.YOUTUBE to 60 * MINUTE_MS,
    )
    private const val DEFAULT_SUSPECT_MS = 20 * MINUTE_MS

    /** Short-video apps used for a while today with zero reels counted. */
    fun suspects(appMs: Map<String, Long>, reelsByApp: Map<String, Int>): List<ShortVideoApp> =
        ShortVideoApps.all.filter { app ->
            val used = app.packageNames.sumOf { appMs[it] ?: 0L }
            val reels = app.packageNames.sumOf { reelsByApp[it] ?: 0 }
            reels == 0 && used >= (suspectAfterMs[app] ?: DEFAULT_SUSPECT_MS)
        }
}
