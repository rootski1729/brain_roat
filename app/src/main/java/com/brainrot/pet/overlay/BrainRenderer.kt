package com.brainrot.pet.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.brainrot.core.BrainMood
import com.brainrot.core.BrainState
import com.brainrot.core.Need
import com.brainrot.core.Stage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class Reaction(val durationMs: Long) {
    NONE(0), FLINCH(350), EAT(1_600), DRINK(1_400), HAPPY(900), ANGRY(700), GRABBED(60_000)
}

/** Everything the renderer needs to know about the brain's condition. */
data class BrainLook(
    val rot: Double = 0.0,
    val restlessness: Double = 0.0,
    val needs: Set<Need> = emptySet(),
    /** Angry guard pose used on the block screen. */
    val blocking: Boolean = false,
) {
    val stage: Stage get() = Stage.of(rot)

    companion object {
        fun of(state: BrainState, mood: BrainMood, blocking: Boolean = false) =
            BrainLook(state.rot, mood.restlessness, mood.needs, blocking)
    }
}

/**
 * Draws the brain pet procedurally in a unit square, so one code path serves the 64 dp bubble,
 * the big in-app brain, the block screen and the widget bitmap. All paths are built once;
 * [draw] allocates nothing, because it runs every frame while the bubble is visible.
 */
class BrainRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val body = buildBody()
    private val gyri = buildGyri()
    private val tmp = Path()
    private val oval = RectF()
    private var pupilX = 0f
    private var pupilY = 0f

    fun draw(
        canvas: Canvas,
        size: Float,
        look: BrainLook,
        timeMs: Long,
        reaction: Reaction = Reaction.NONE,
        reactionProgress: Float = 0f,
        walking: Boolean = false,
    ) {
        val t = timeMs / 1000f
        val r = look.restlessness.toFloat().coerceIn(0f, 1f)
        val stage = look.stage
        val base = bodyColor(look.rot, look.blocking)
        val outline = shade(base, 0.6f)
        val pulse = sin(reactionProgress * PI.toFloat())

        val breathe = 1f + 0.025f * sin(t * 2f * PI.toFloat() / (3f - 1.6f * r))
        val jitterX = r * r * 0.03f * (sin(t * 37f) + 0.5f * sin(t * 23f))
        val jitterY = r * r * 0.018f * sin(t * 31f)
        val bob = if (walking) -abs(sin(t * 10f)) * 0.035f else sin(t * 2f) * 0.008f
        var sx = 1f + (breathe - 1f) * 0.5f
        var sy = breathe
        var lift = 0f
        when (reaction) {
            Reaction.FLINCH -> { sx *= 1f + 0.14f * pulse; sy *= 1f - 0.14f * pulse }
            Reaction.HAPPY -> lift = -0.07f * pulse
            Reaction.GRABBED -> { sx *= 0.94f; sy *= 1.08f }
            Reaction.ANGRY -> { sx *= 1f + 0.06f * pulse; sy *= 1f + 0.06f * pulse }
            else -> Unit
        }

        canvas.save()
        canvas.scale(size, size)
        canvas.translate(0.5f + jitterX, 0.55f + bob + jitterY + lift)
        canvas.scale(sx, sy)
        canvas.translate(-0.5f, -0.55f)

        drawLimbs(canvas, t, r, outline, walking, look.blocking || reaction == Reaction.GRABBED)
        fill.color = base
        canvas.drawPath(body, fill)
        stroke.color = outline
        stroke.strokeWidth = 0.022f
        canvas.drawPath(body, stroke)
        stroke.color = withAlpha(shade(base, 0.72f), 200)
        stroke.strokeWidth = 0.018f
        canvas.drawPath(gyri, stroke)
        if (look.rot >= 65) drawSpots(canvas, look.rot)
        drawFace(canvas, t, stage, look, reaction, reactionProgress, base)
        canvas.restore()

        canvas.save()
        canvas.scale(size, size)
        drawEffects(canvas, size, t, stage, look, reaction, reactionProgress)
        canvas.restore()
    }

    /** Static frame for the home-screen widget. */
    fun renderBitmap(sizePx: Int, look: BrainLook): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // 1.5 s in: eyes open, mid-breath.
        draw(Canvas(bitmap), sizePx.toFloat(), look, timeMs = 1_500)
        return bitmap
    }

    // --- Body -----------------------------------------------------------------------------------

    private fun drawLimbs(canvas: Canvas, t: Float, r: Float, color: Int, walking: Boolean, armsUp: Boolean) {
        stroke.color = color
        stroke.strokeWidth = 0.035f
        val flail = r * 0.06f * sin(t * 12f)
        if (armsUp) {
            canvas.drawLine(0.14f, 0.56f, 0.04f, 0.42f + flail, stroke)
            canvas.drawLine(0.86f, 0.56f, 0.96f, 0.42f - flail, stroke)
        } else {
            canvas.drawLine(0.13f, 0.58f, 0.05f, 0.67f + flail, stroke)
            canvas.drawLine(0.87f, 0.58f, 0.95f, 0.67f - flail, stroke)
        }
        fill.color = color
        val stepL = if (walking) maxOf(0f, sin(t * 10f)) * 0.035f else 0f
        val stepR = if (walking) maxOf(0f, -sin(t * 10f)) * 0.035f else 0f
        oval.set(0.33f, 0.80f - stepL, 0.45f, 0.87f - stepL)
        canvas.drawOval(oval, fill)
        oval.set(0.55f, 0.80f - stepR, 0.67f, 0.87f - stepR)
        canvas.drawOval(oval, fill)
    }

    private fun drawSpots(canvas: Canvas, rot: Double) {
        val alpha = (((rot - 65) / 35.0).coerceIn(0.0, 1.0) * 180).toInt()
        fill.color = withAlpha(0xFF6B5B2E.toInt(), alpha)
        canvas.drawCircle(0.30f, 0.30f, 0.030f, fill)
        canvas.drawCircle(0.68f, 0.27f, 0.024f, fill)
        canvas.drawCircle(0.76f, 0.47f, 0.028f, fill)
        canvas.drawCircle(0.22f, 0.50f, 0.020f, fill)
    }

    // --- Face -----------------------------------------------------------------------------------

    private fun drawFace(canvas: Canvas, t: Float, stage: Stage, look: BrainLook, reaction: Reaction, p: Float, base: Int) {
        val happy = reaction == Reaction.HAPPY || reaction == Reaction.EAT || reaction == Reaction.DRINK
        if ((stage == Stage.FRESH && !look.blocking) || happy) {
            fill.color = withAlpha(0xFFFF6F91.toInt(), 110)
            oval.set(0.29f, 0.61f, 0.37f, 0.66f); canvas.drawOval(oval, fill)
            oval.set(0.63f, 0.61f, 0.71f, 0.66f); canvas.drawOval(oval, fill)
        }

        val blinking = (t % 4f) < 0.12f && stage != Stage.ROTTEN && !look.blocking
        val leftR = 0.062f
        val rightR = if (stage == Stage.FRIED && !look.blocking) 0.078f else 0.062f
        updatePupils(t, stage, look.blocking)
        val px = pupilX
        val py = pupilY
        if (blinking || reaction == Reaction.FLINCH) {
            stroke.color = INK
            stroke.strokeWidth = 0.02f
            canvas.drawLine(0.36f, 0.55f, 0.46f, 0.55f, stroke)
            canvas.drawLine(0.54f, 0.55f, 0.64f, 0.55f, stroke)
        } else {
            drawEye(canvas, 0.41f, 0.55f, leftR, px, py, t, stage, look, base, mirrored = false)
            drawEye(canvas, 0.59f, 0.55f, rightR, px, py, t, stage, look, base, mirrored = true)
        }

        stroke.color = INK
        if (look.blocking || reaction == Reaction.ANGRY) {
            stroke.strokeWidth = 0.028f
            canvas.drawLine(0.33f, 0.45f, 0.46f, 0.49f, stroke)
            canvas.drawLine(0.67f, 0.45f, 0.54f, 0.49f, stroke)
        } else if (stage == Stage.FRIED) {
            stroke.strokeWidth = 0.018f
            canvas.drawLine(0.53f, 0.44f, 0.66f, 0.42f, stroke)
        }
        drawMouth(canvas, t, stage, look, reaction, p)
    }

    private fun updatePupils(t: Float, stage: Stage, blocking: Boolean) {
        when {
            blocking -> { pupilX = 0f; pupilY = 0.01f }
            stage >= Stage.RESTLESS -> {
                // Darting gaze: jump to a new pseudo-random target several times a second.
                val k = floor(t * (if (stage == Stage.RESTLESS) 3f else 5f))
                pupilX = (hash(k) - 0.5f) * 0.05f
                pupilY = (hash(k + 17f) - 0.5f) * 0.03f
            }
            else -> { pupilX = sin(t * 0.7f) * 0.02f; pupilY = cos(t * 0.5f) * 0.01f }
        }
    }

    private fun drawEye(
        canvas: Canvas, cx: Float, cy: Float, radius: Float, px: Float, py: Float,
        t: Float, stage: Stage, look: BrainLook, base: Int, mirrored: Boolean,
    ) {
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, fill)
        if (stage == Stage.ROTTEN && !look.blocking) {
            drawSpiral(canvas, cx, cy, radius * 0.85f, t * (if (mirrored) -4f else 4f))
        } else {
            fill.color = INK
            val pupil = if (stage == Stage.FRIED && mirrored) radius * 0.32f else radius * 0.5f
            canvas.drawCircle(cx + px, cy + py, pupil, fill)
            fill.color = Color.WHITE
            canvas.drawCircle(cx + px - pupil * 0.35f, cy + py - pupil * 0.35f, pupil * 0.3f, fill)
        }
        val droopy = stage == Stage.FOGGY || Need.TIRED in look.needs
        if (droopy && !look.blocking) {
            canvas.save()
            tmp.reset()
            tmp.addCircle(cx, cy, radius, Path.Direction.CW)
            canvas.clipPath(tmp)
            fill.color = base
            canvas.drawRect(cx - radius, cy - radius, cx + radius, cy - radius * 0.1f, fill)
            canvas.restore()
            stroke.color = INK
            stroke.strokeWidth = 0.014f
            canvas.drawLine(cx - radius, cy - radius * 0.1f, cx + radius, cy - radius * 0.1f, stroke)
        }
        stroke.color = INK
        stroke.strokeWidth = 0.012f
        canvas.drawCircle(cx, cy, radius, stroke)
    }

    private fun drawSpiral(canvas: Canvas, cx: Float, cy: Float, radius: Float, angle: Float) {
        tmp.reset()
        val turns = 2.5f
        val steps = 28
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = angle + f * turns * 2f * PI.toFloat()
            val x = cx + cos(a) * radius * f
            val y = cy + sin(a) * radius * f
            if (i == 0) tmp.moveTo(x, y) else tmp.lineTo(x, y)
        }
        stroke.color = INK
        stroke.strokeWidth = 0.011f
        canvas.drawPath(tmp, stroke)
    }

    private fun drawMouth(canvas: Canvas, t: Float, stage: Stage, look: BrainLook, reaction: Reaction, p: Float) {
        stroke.color = INK
        stroke.strokeWidth = 0.02f
        when {
            reaction == Reaction.EAT -> {
                val open = 0.012f + 0.03f * abs(sin(p * 6f * PI.toFloat()))
                fill.color = INK
                oval.set(0.46f, 0.665f - open, 0.54f, 0.665f + open); canvas.drawOval(oval, fill)
            }
            reaction == Reaction.DRINK -> {
                fill.color = INK
                canvas.drawCircle(0.5f, 0.67f, 0.018f, fill)
            }
            reaction == Reaction.HAPPY -> {
                oval.set(0.42f, 0.60f, 0.58f, 0.71f); canvas.drawArc(oval, 15f, 150f, false, stroke)
            }
            look.blocking -> {
                val open = 0.025f + 0.012f * sin(t * 9f)
                fill.color = INK
                oval.set(0.45f, 0.67f - open, 0.55f, 0.67f + open); canvas.drawOval(oval, fill)
            }
            stage == Stage.FRESH -> {
                oval.set(0.44f, 0.61f, 0.56f, 0.70f); canvas.drawArc(oval, 20f, 140f, false, stroke)
            }
            stage == Stage.FOGGY -> canvas.drawLine(0.46f, 0.675f, 0.54f, 0.668f, stroke)
            stage == Stage.RESTLESS -> {
                tmp.reset()
                val w = 0.008f * sin(t * 14f)
                tmp.moveTo(0.44f, 0.67f)
                tmp.lineTo(0.47f, 0.65f + w); tmp.lineTo(0.50f, 0.69f - w)
                tmp.lineTo(0.53f, 0.65f + w); tmp.lineTo(0.56f, 0.67f)
                canvas.drawPath(tmp, stroke)
            }
            stage == Stage.FRIED -> {
                oval.set(0.44f, 0.655f, 0.56f, 0.735f); canvas.drawArc(oval, 200f, 140f, false, stroke)
            }
            else -> {
                fill.color = INK
                oval.set(0.45f, 0.635f, 0.55f, 0.705f); canvas.drawOval(oval, fill)
                fill.color = 0xFFFF7A9A.toInt()
                oval.set(0.475f, 0.675f, 0.525f, 0.725f); canvas.drawOval(oval, fill)
                // Drool drop sliding down.
                val d = (t * 0.5f) % 1f
                fill.color = withAlpha(0xFF9AD7F0.toInt(), ((1f - d) * 255).toInt())
                canvas.drawCircle(0.54f, 0.71f + d * 0.08f, 0.012f, fill)
            }
        }
    }

    // --- Effects --------------------------------------------------------------------------------

    private fun drawEffects(canvas: Canvas, size: Float, t: Float, stage: Stage, look: BrainLook, reaction: Reaction, p: Float) {
        if (stage >= Stage.FRIED) {
            stroke.strokeWidth = 0.016f
            for (i in 0 until 3) {
                val phase = (t * 0.6f + i * 0.33f) % 1f
                stroke.color = withAlpha(0xFF9E9E9E.toInt(), ((1f - phase) * 200).toInt())
                val x = 0.35f + i * 0.15f
                val y = 0.13f - phase * 0.13f
                tmp.reset()
                tmp.moveTo(x, y + 0.06f)
                tmp.quadTo(x - 0.025f, y + 0.03f, x, y)
                tmp.quadTo(x + 0.025f, y - 0.03f, x, y - 0.06f)
                canvas.drawPath(tmp, stroke)
            }
        }
        if (stage == Stage.ROTTEN) {
            for (i in 0 until 3) {
                val a = t * (2f + i) + i * 2.1f
                val x = 0.5f + cos(a) * 0.44f
                val y = 0.45f + sin(a * 1.3f) * 0.3f
                fill.color = withAlpha(Color.WHITE, 170)
                oval.set(x - 0.02f, y - 0.03f, x + 0.005f, y - 0.005f); canvas.drawOval(oval, fill)
                fill.color = INK
                canvas.drawCircle(x, y, 0.013f, fill)
            }
        }
        if (stage == Stage.RESTLESS || Need.THIRSTY in look.needs) {
            val d = (t * 0.7f) % 1f
            fill.color = withAlpha(0xFF6EC6FF.toInt(), ((1f - d) * 230).toInt())
            val x = 0.82f
            val y = 0.28f + d * 0.14f
            canvas.drawCircle(x, y, 0.022f, fill)
            tmp.reset()
            tmp.moveTo(x - 0.021f, y - 0.006f); tmp.lineTo(x, y - 0.05f); tmp.lineTo(x + 0.021f, y - 0.006f); tmp.close()
            canvas.drawPath(tmp, fill)
        }
        if (Need.TIRED in look.needs) {
            val d = (t * 0.4f) % 1f
            drawText(canvas, size, "z", 0.80f + d * 0.06f, 0.20f - d * 0.12f, 0.10f + d * 0.05f, withAlpha(INK, ((1f - d) * 255).toInt()))
        }
        when (reaction) {
            Reaction.EAT -> drawText(canvas, size, "🌰", 0.88f - p * 0.36f, 0.72f, 0.16f * (1f - p * 0.7f), Color.BLACK)
            Reaction.DRINK -> drawText(canvas, size, "💧", 0.86f - p * 0.3f, 0.70f - p * 0.02f, 0.15f * (1f - p * 0.6f), Color.BLACK)
            Reaction.HAPPY -> {
                drawText(canvas, size, "✨", 0.15f, 0.20f - p * 0.08f, 0.13f, Color.BLACK)
                drawText(canvas, size, "✨", 0.86f, 0.26f - p * 0.06f, 0.11f, Color.BLACK)
            }
            Reaction.ANGRY -> drawText(canvas, size, "💢", 0.84f, 0.18f, 0.14f, Color.BLACK)
            else -> Unit
        }
    }

    /** Text in pixel space: tiny text sizes under a large canvas scale render blurry on some GPUs. */
    private fun drawText(canvas: Canvas, size: Float, s: String, ux: Float, uy: Float, usize: Float, color: Int) {
        canvas.save()
        canvas.scale(1f / size, 1f / size)
        text.color = color
        text.textSize = usize * size
        canvas.drawText(s, ux * size, uy * size, text)
        canvas.restore()
    }

    // --- Geometry and colour --------------------------------------------------------------------

    private fun buildBody(): Path {
        val lobes = arrayOf(
            floatArrayOf(0.30f, 0.40f, 0.19f), floatArrayOf(0.50f, 0.33f, 0.21f), floatArrayOf(0.70f, 0.40f, 0.19f),
            floatArrayOf(0.26f, 0.56f, 0.17f), floatArrayOf(0.50f, 0.58f, 0.21f), floatArrayOf(0.74f, 0.56f, 0.17f),
        )
        val result = Path()
        val circle = Path()
        for ((i, l) in lobes.withIndex()) {
            circle.reset()
            circle.addCircle(l[0], l[1], l[2], Path.Direction.CW)
            if (i == 0) result.set(circle) else result.op(circle, Path.Op.UNION)
        }
        return result
    }

    private fun buildGyri(): Path = Path().apply {
        // Longitudinal fissure.
        moveTo(0.50f, 0.13f); quadTo(0.46f, 0.21f, 0.50f, 0.28f); quadTo(0.54f, 0.34f, 0.50f, 0.40f)
        // Left hemisphere folds.
        moveTo(0.20f, 0.36f); quadTo(0.27f, 0.29f, 0.34f, 0.35f)
        moveTo(0.33f, 0.21f); quadTo(0.39f, 0.27f, 0.37f, 0.33f)
        moveTo(0.15f, 0.50f); quadTo(0.22f, 0.44f, 0.28f, 0.49f)
        moveTo(0.18f, 0.63f); quadTo(0.24f, 0.60f, 0.27f, 0.66f)
        // Right hemisphere folds (mirrored).
        moveTo(0.80f, 0.36f); quadTo(0.73f, 0.29f, 0.66f, 0.35f)
        moveTo(0.67f, 0.21f); quadTo(0.61f, 0.27f, 0.63f, 0.33f)
        moveTo(0.85f, 0.50f); quadTo(0.78f, 0.44f, 0.72f, 0.49f)
        moveTo(0.82f, 0.63f); quadTo(0.76f, 0.60f, 0.73f, 0.66f)
    }

    private fun bodyColor(rot: Double, blocking: Boolean): Int {
        val r = rot.toFloat().coerceIn(0f, 100f)
        var i = 0
        while (i < STOPS.size - 2 && r > STOPS[i + 1].first) i++
        val (r0, c0) = STOPS[i]
        val (r1, c1) = STOPS[i + 1]
        val c = lerpColor(c0, c1, ((r - r0) / (r1 - r0)).coerceIn(0f, 1f))
        return if (blocking) lerpColor(c, 0xFFFF6B6B.toInt(), 0.3f) else c
    }

    companion object {
        private val INK = 0xFF2B1B2E.toInt()

        /** Healthy pink -> pale -> overheated orange -> fried brown -> rotten green. */
        private val STOPS = listOf(
            0f to 0xFFFFA6C1.toInt(),
            25f to 0xFFF2B5C4.toInt(),
            45f to 0xFFF6B27A.toInt(),
            65f to 0xFFD49563.toInt(),
            85f to 0xFFA5B86E.toInt(),
            100f to 0xFF8E9F5A.toInt(),
        )

        fun lerpColor(a: Int, b: Int, f: Float): Int = Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt(),
        )

        fun shade(c: Int, f: Float): Int =
            Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

        fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

        private fun hash(x: Float): Float {
            val s = sin(x * 12.9898f) * 43758.547f
            return s - floor(s)
        }
    }
}
