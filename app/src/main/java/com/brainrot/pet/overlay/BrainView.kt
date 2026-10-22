package com.brainrot.pet.overlay

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Animated brain. Redraws itself every [frameIntervalMs] only while it is actually visible, so a
 * hidden bubble or a screen-off phone costs no frames.
 */
class BrainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = BrainRenderer()
    private val bornAt = SystemClock.uptimeMillis()
    private var reaction = Reaction.NONE
    private var reactionStartedAt = 0L
    private var reactionDurationMs = 0L
    private var visibleToUser = false

    var look: BrainLook = BrainLook()
    var walking: Boolean = false

    /** 33 ms (30 fps) in the app; the always-on bubble uses a slower rate to save battery. */
    var frameIntervalMs: Long = 33L

    fun react(r: Reaction, durationMs: Long = r.durationMs) {
        reaction = r
        reactionStartedAt = SystemClock.uptimeMillis()
        reactionDurationMs = durationMs
    }

    fun clearReaction() {
        reaction = Reaction.NONE
    }

    // The overlay drives taps through a touch listener that calls performClick (accessibility).
    override fun performClick(): Boolean = super.performClick()

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        if (isVisible) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availW = width - paddingLeft - paddingRight
        val availH = height - paddingTop - paddingBottom
        val size = min(availW, availH).toFloat()
        if (size <= 0f) return

        val now = SystemClock.uptimeMillis()
        var progress = 0f
        if (reaction != Reaction.NONE) {
            val elapsed = now - reactionStartedAt
            if (elapsed >= reactionDurationMs) {
                reaction = Reaction.NONE
            } else {
                progress = elapsed.toFloat() / reactionDurationMs
            }
        }

        canvas.save()
        canvas.translate(paddingLeft + (availW - size) / 2f, paddingTop + (availH - size) / 2f)
        renderer.draw(canvas, size, look, now - bornAt, reaction, progress, walking)
        canvas.restore()

        if (visibleToUser && isAttachedToWindow) postInvalidateDelayed(frameIntervalMs)
    }
}
