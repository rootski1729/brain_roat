package com.brainrot.core

/**
 * Turns accessibility signals from a short-video app into "one more reel watched".
 *
 * Inputs are deliberately simple so the Android layer stays thin:
 *  - [onSurfaceChanged]: is a Shorts/Reels player on screen for this package?
 *  - [onScroll]: a vertical scroll event from that player, with the adapter position if the app
 *    reports one.
 *
 * Counting rules:
 *  - entering the surface counts the first reel, unless the user only left it briefly
 *    (comments sheet, share dialog) within [reEntryGraceMs];
 *  - with a position: count only when it exceeds the highest position seen in this session, so
 *    scrolling back to re-watch or a cancelled swipe never double counts;
 *  - without a position: one swipe produces a burst of scroll events, so count the first event
 *    after [burstGapMs] of silence, and never more than once per [minReelIntervalMs].
 *
 * It also accumulates time spent inside the surface for the brain engine.
 * Accessibility callbacks and the service tick both touch this object, hence @Synchronized.
 */
class ReelCounter(
    private val reEntryGraceMs: Long = 30 * SECOND_MS,
    private val burstGapMs: Long = 600,
    private val minReelIntervalMs: Long = 800,
) {
    private class Session(
        var visible: Boolean = false,
        var visibleSince: Long = 0,
        var hiddenAt: Long = Long.MIN_VALUE,
        var maxPosition: Int = -1,
        var lastScrollAt: Long = Long.MIN_VALUE,
        var lastCountAt: Long = Long.MIN_VALUE,
    )

    private val sessions = HashMap<String, Session>()
    private var pendingSurfaceMs = 0L

    /** Returns true when this transition counts as a new reel. */
    @Synchronized
    fun onSurfaceChanged(packageName: String, visible: Boolean, now: Long): Boolean {
        val s = sessions.getOrPut(packageName) { Session() }
        if (visible == s.visible) return false
        return if (visible) {
            s.visible = true
            s.visibleSince = now
            s.lastScrollAt = Long.MIN_VALUE
            val quickReturn = s.hiddenAt != Long.MIN_VALUE && now - s.hiddenAt in 0 until reEntryGraceMs
            if (quickReturn) {
                false
            } else {
                s.maxPosition = -1
                s.lastCountAt = now
                true
            }
        } else {
            s.visible = false
            s.hiddenAt = now
            pendingSurfaceMs += (now - s.visibleSince).coerceAtLeast(0)
            false
        }
    }

    /** Returns true when this scroll counts as a new reel. [position] is null if the app hides it. */
    @Synchronized
    fun onScroll(packageName: String, position: Int?, now: Long): Boolean {
        val s = sessions[packageName] ?: return false
        if (!s.visible) return false
        val previousScroll = s.lastScrollAt
        s.lastScrollAt = now

        if (position != null && position >= 0) {
            if (s.maxPosition < 0) {
                // First position we learn in this session belongs to the reel already counted on entry.
                s.maxPosition = position
                return false
            }
            if (position <= s.maxPosition) return false
            s.maxPosition = position
            s.lastCountAt = now
            return true
        }

        val newBurst = previousScroll == Long.MIN_VALUE || now - previousScroll >= burstGapMs
        val spaced = s.lastCountAt == Long.MIN_VALUE || now - s.lastCountAt >= minReelIntervalMs
        if (newBurst && spaced) {
            s.lastCountAt = now
            return true
        }
        return false
    }

    @Synchronized
    fun isInSurface(packageName: String): Boolean = sessions[packageName]?.visible == true

    @Synchronized
    fun anySurfaceVisible(): Boolean = sessions.values.any { it.visible }

    /** Hide every surface (screen off, service stopped). */
    @Synchronized
    fun hideAll(now: Long) {
        sessions.keys.toList().forEach { onSurfaceChanged(it, false, now) }
    }

    /** Time spent in short-video surfaces since the last call, including the ongoing session. */
    @Synchronized
    fun drainSurfaceMs(now: Long): Long {
        var total = pendingSurfaceMs
        pendingSurfaceMs = 0
        for (s in sessions.values) {
            if (s.visible) {
                total += (now - s.visibleSince).coerceAtLeast(0)
                s.visibleSince = now
            }
        }
        return total
    }
}
