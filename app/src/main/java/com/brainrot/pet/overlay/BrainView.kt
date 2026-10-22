package com.brainrot.pet.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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

    /** Small count bubble in the top-right corner (today's reels on the overlay); null hides it. */
    var badge: String? = null
    private var badgeBumpAt = 0L
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE5484D.toInt() }
    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /** Pops the badge (a new reel was counted). */
    fun bumpBadge() {
        badgeBumpAt = SystemClock.uptimeMillis()
    }
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
        badge?.let { drawBadge(canvas, it, size, now) }

        if (visibleToUser && isAttachedToWindow) postInvalidateDelayed(frameIntervalMs)
    }

    private fun drawBadge(canvas: Canvas, text: String, size: Float, now: Long) {
        val sinceBump = now - badgeBumpAt
        val pop = if (sinceBump in 0 until BUMP_MS) 1f + 0.35f * (1f - sinceBump.toFloat() / BUMP_MS) else 1f
        val radius = size * (if (text.length > 2) 0.24f else 0.2f) * pop
        val cx = width - paddingRight - radius
        val cy = paddingTop + radius
        canvas.drawCircle(cx, cy, radius, badgeFill)
        badgeStroke.strokeWidth = size * 0.025f
        canvas.drawCircle(cx, cy, radius, badgeStroke)
        badgeText.textSize = radius * (if (text.length > 2) 0.8f else 1.05f)
        canvas.drawText(text, cx, cy - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
    }

    private companion object {
        const val BUMP_MS = 300L
    }
}
