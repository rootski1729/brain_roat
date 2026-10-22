package com.brainrot.pet.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.brainrot.pet.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Full-screen guard shown over an app whose limit is used up. The brain bounces around the top of
 * the screen (poke it and it gets angrier), the reason is spelled out, and the only ways forward
 * are leaving the app or unlocking inside Brain Rot after a mindful wait. Back is captured.
 */
@SuppressLint("ViewConstructor")
class BlockScreenView(
    context: Context,
    private val onHome: () -> Unit,
    private val onUnlock: () -> Unit,
    private val onLeaveShorts: () -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private val brainSize = dp(170)
    private val brain = BrainView(context).apply {
        frameIntervalMs = 16
        contentDescription = context.getString(R.string.block_brain_cd)
    }
    private val title = text(24f, bold = true)
    private val reason = text(16f)
    private val line = text(15f).apply { setTypeface(typeface, Typeface.ITALIC) }
    private val leaveShorts = button(R.string.block_leave_shorts, primary = true) { onLeaveShorts() }

    private var posX = 0f
    private var posY = 0f
    private var vx = dp(150).toFloat()
    private var vy = dp(110).toFloat()
    private var lastFrameNanos = 0L
    private var speed = 1f

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            step(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setBackgroundColor(0xF2190F2E.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        addView(brain, LayoutParams(brainSize, brainSize))
        brain.setOnClickListener {
            brain.react(Reaction.ANGRY)
            line.text = resources.getStringArray(R.array.speech_poked).random()
            vx = -vx * 1.3f
            vy = -vy * 1.3f
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(40))
            addView(title)
            addView(reason, margins(top = 10))
            addView(line, margins(top = 14))
            addView(leaveShorts, margins(top = 24))
            addView(button(R.string.block_go_home, primary = true) { onHome() }, margins(top = 12))
            addView(button(R.string.block_unlock, primary = false) { onUnlock() }, margins(top = 12))
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    fun bind(look: BrainLook, titleText: String, reasonText: String, lineText: String, canLeaveShorts: Boolean) {
        brain.look = look
        speed = 1f + look.restlessness.toFloat()
        title.text = titleText
        reason.text = reasonText
        if (line.text.isNullOrEmpty()) line.text = lineText
        leaveShorts.visibility = if (canLeaveShorts) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onHome()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Bounce inside the upper part of the screen so the buttons stay reachable. */
    private fun step(frameTimeNanos: Long) {
        if (width == 0 || height == 0) return
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = frameTimeNanos
        val maxX = (width - brainSize).toFloat().coerceAtLeast(0f)
        val maxY = (height * 0.5f - brainSize).coerceAtLeast(0f)
        posX += vx * speed * dt
        posY += vy * speed * dt
        if (posX < 0f || posX > maxX) { vx = -vx; posX = posX.coerceIn(0f, maxX); wobble() }
        if (posY < 0f || posY > maxY) { vy = -vy; posY = posY.coerceIn(0f, maxY); wobble() }
        // Calm the speed back down after a poke.
        val cap = dp(260).toFloat()
        if (abs(vx) > cap) vx *= 0.98f
        if (abs(vy) > cap) vy *= 0.98f
        brain.translationX = posX
        brain.translationY = posY
        brain.walking = true
    }

    private fun wobble() {
        if (Random.nextFloat() < 0.3f) brain.react(Reaction.FLINCH)
    }

    private fun text(sizeSp: Float, bold: Boolean = false) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(textRes: Int, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
        setText(textRes)
        isAllCaps = false
        setTextColor(if (primary) 0xFF2B1B2E.toInt() else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            if (primary) setColor(0xFFFFA6C1.toInt()) else setStroke(dp(2), 0xFFFFA6C1.toInt())
        }
        minHeight = dp(52)
        setOnClickListener { onClick() }
    }

    private fun margins(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * density).roundToInt()
}
