package com.brainrot.pet.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.brainrot.core.AppData
import com.brainrot.core.Breach
import com.brainrot.core.BrainEngine
import com.brainrot.core.BrainMood
import com.brainrot.core.BrainVoice
import com.brainrot.core.Food
import com.brainrot.core.HOUR_MS
import com.brainrot.core.MINUTE_MS
import com.brainrot.core.SpeechTopic
import com.brainrot.core.Stage
import com.brainrot.core.formatDuration
import com.brainrot.pet.BrainLog
import com.brainrot.pet.R
import com.brainrot.pet.runtime.BrainEvent
import com.brainrot.pet.runtime.BrainRuntime
import com.brainrot.pet.runtime.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Owns every overlay window: the floating brain bubble, its care panel, speech bubbles and the
 * full-screen block guard. Main thread only; driven by [com.brainrot.pet.service.BrainService].
 *
 * Window rules that matter on Android 12+: the bubble and panel are touchable and small; the
 * speech bubble is not touchable, so it is drawn at alpha 0.8, the maximum the system allows for
 * touches to pass through an untrusted overlay.
 */
class OverlayController(
    context: Context,
    private val runtime: BrainRuntime,
    private val scope: CoroutineScope,
    /** Called when a temporary block suppression ends, so the service re-evaluates limits. */
    private val onReevaluate: () -> Unit,
) {
    /** On Android 11+ overlays should come from a window context so metrics follow rotation. */
    private val windowContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
    private val ctx: Context = ContextThemeWrapper(windowContext, R.style.Theme_BrainRot)
    private val wm: WindowManager = windowContext.getSystemService(WindowManager::class.java)
    private val res = ctx.resources
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var bubble: BrainView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleSizePx = 0
    private var panel: PanelViews? = null
    private var panelHiddenAt = 0L
    private var speech: TextView? = null
    private var speechHideJob: Job? = null
    private var block: BlockScreenView? = null
    private var blockSuppressedUntil = 0L
    private var roamJob: Job? = null
    private var throwJob: Job? = null
    private var hud: TextView? = null
    private var moveAnimator: ValueAnimator? = null
    private var dragging = false
    private var lastAmbientAt = System.currentTimeMillis()
    private var windowErrorLogged = false

    private var data: AppData? = null
    private var live: LiveState? = null
    private var mood: BrainMood? = null

    val isBubbleVisible: Boolean get() = bubble != null
    val isBlockVisible: Boolean get() = block != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    // --- State -> windows -----------------------------------------------------------------------

    fun render(d: AppData, l: LiveState, now: Long) {
        data = d
        live = l
        val m = BrainEngine.mood(d.brain, now - l.sessionStartedAt)
        mood = m

        val wantBubble = l.screenOn && d.settings.overlayEnabled && now >= d.settings.bubbleHiddenUntil &&
            !l.appInForeground && block == null && canDraw()
        if (wantBubble) ensureBubble(d.settings.bubbleSizeDp) else removeBubble()

        val look = BrainLook.of(d.brain, m)
        bubble?.let {
            it.look = look
            // A calm brain barely moves; a frantic one needs smooth frames for its jitter.
            it.frameIntervalMs = when {
                m.restlessness > 0.5 -> 33L
                m.restlessness > 0.2 -> 50L
                else -> 80L
            }
            it.contentDescription = res.getString(R.string.bubble_cd, d.settings.brainName, res.getString(SpeechLines.stageLabel(m.stage)))
            it.badge = badgeText(d.today.totalReels)
        }
        renderHud(d, l, m)
        panel?.bind(d, l, m)
        block?.let { bindBlock(it, l.breach ?: return@let, d, m) }

        // Calm brains take an occasional stroll; restless ones pace; fried ones get in the way.
        val shouldRoam = bubble != null && d.settings.roamingEnabled
        if (shouldRoam && roamJob == null) startRoaming() else if (!shouldRoam) stopRoaming()

        // Calm brains chat every ~6 minutes, frazzled ones every ~2.
        val ambientEvery = (6 * MINUTE_MS - (4 * MINUTE_MS * m.restlessness)).toLong()
        if (bubble != null && panel == null && d.settings.speechEnabled && now - lastAmbientAt >= ambientEvery) {
            lastAmbientAt = now
            say(BrainVoice.ambientTopic(m), null)
        }
    }

    fun onEvent(event: BrainEvent) {
        val settings = data?.settings ?: return
        when (event) {
            is BrainEvent.ReelCounted -> {
                bubble?.react(Reaction.FLINCH)
                bubble?.badge = badgeText(event.totalToday)
                bubble?.bumpBadge()
                hud?.let { bump(it) }
                if (settings.speechEnabled && event.totalToday > 0 && event.totalToday % REEL_MILESTONE == 0) {
                    showSpeech(res.getQuantityString(R.plurals.speech_reel_milestone, event.totalToday, event.totalToday))
                }
            }
            is BrainEvent.Say -> {
                when (event.topic) {
                    SpeechTopic.FED -> bubble?.react(Reaction.EAT)
                    SpeechTopic.WATERED -> bubble?.react(Reaction.DRINK)
                    SpeechTopic.WELCOME_BACK_RESTED -> bubble?.react(Reaction.HAPPY)
                    else -> Unit
                }
                if (settings.speechEnabled) say(event.topic, event.detail)
            }
        }
    }

    fun onScreenOff() {
        throwJob?.cancel()
        removeHud()
        moveAnimator?.cancel()
        hidePanel()
        hideSpeech()
    }

    fun destroy() {
        removeHud()
        stopRoaming()
        removeBubble()
        hideBlock()
    }

    // --- Block screen ---------------------------------------------------------------------------

    /** Returns false if the guard could not be shown (no overlay permission or window error). */
    fun showBlock(breach: Breach, d: AppData, now: Long): Boolean {
        if (now < blockSuppressedUntil) return true
        if (!canDraw()) return false
        val m = mood ?: BrainEngine.mood(d.brain, 0)
        block?.let {
            bindBlock(it, breach, d, m)
            return true
        }
        removeBubble()
        val view = BlockScreenView(
            ctx,
            onHome = { runtime.goHome() },
            onUnlock = { runtime.openApp(unlockFor = runtime.live.value.breach) },
            onLeaveShorts = { leaveShorts() },
        )
        bindBlock(view, breach, d, m)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (!addWindow(view, params)) return false
        block = view
        return true
    }

    fun hideBlock() {
        block?.let { removeWindow(it) }
        block = null
    }

    private fun bindBlock(view: BlockScreenView, breach: Breach, d: AppData, m: BrainMood) {
        view.bind(
            look = BrainLook.of(d.brain, m, blocking = true),
            titleText = res.getString(R.string.block_title, d.settings.brainName),
            reasonText = describe(breach),
            lineText = SpeechLines.pick(res, SpeechTopic.BLOCKED),
            canLeaveShorts = breach.isReels && runtime.live.value.accessibilityConnected,
        )
    }

    /** Reel limits only cover the Shorts/Reels player: step back out of it instead of leaving the app. */
    private fun leaveShorts() {
        val now = System.currentTimeMillis()
        hideBlock()
        blockSuppressedUntil = now + BLOCK_SUPPRESS_MS
        scope.launch {
            delay(150)
            runtime.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(BLOCK_SUPPRESS_MS)
            onReevaluate()
        }
    }

    fun describe(breach: Breach): String = SpeechLines.describe(ctx, breach)

    // --- Bubble ---------------------------------------------------------------------------------

    private fun ensureBubble(sizeDp: Int) {
        val sizePx = dp(sizeDp)
        if (bubble != null && sizePx == bubbleSizePx) return
        removeBubble()
        val view = BrainView(ctx)
        view.setOnTouchListener(BubbleTouch())
        // Taps (and TalkBack double-taps) arrive as clicks; the touch listener only handles drags.
        view.setOnClickListener {
            view.react(Reaction.HAPPY)
            togglePanel()
        }
        view.setOnLongClickListener {
            runtime.openApp()
            true
        }
        val screen = screenSize()
        val (minY, maxY) = yRange(screen, sizePx)
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.getBoolean(KEY_RIGHT, true)) screen.x - sizePx else 0
            y = (prefs.getFloat(KEY_Y, 0.35f) * screen.y).roundToInt().coerceIn(minY, maxY)
        }
        if (addWindow(view, params)) {
            bubble = view
            bubbleParams = params
            bubbleSizePx = sizePx
        }
    }

    private fun removeBubble() {
        throwJob?.cancel()
        throwJob = null
        stopRoaming()
        moveAnimator?.cancel()
        hidePanel()
        hideSpeech()
        bubble?.let {
            // A removed window may never get ACTION_UP/CANCEL: reset drag state and drop the long-press.
            it.handler?.removeCallbacksAndMessages(null)
            removeWindow(it)
        }
        dragging = false
        bubble = null
        bubbleParams = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class BubbleTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        private var longPressed = false
        private var velocity: VelocityTracker? = null
        private val longPress = Runnable {
            if (!moved) {
                longPressed = true
                runtime.openApp()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            val brain = bubble ?: return false
            // The window moves with the finger, so track velocity in screen coordinates.
            velocity?.let { tracker ->
                val screenEvent = MotionEvent.obtain(e)
                screenEvent.setLocation(e.rawX, e.rawY)
                tracker.addMovement(screenEvent)
                screenEvent.recycle()
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moveAnimator?.cancel()
                    throwJob?.cancel()
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain()
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    longPressed = false
                    dragging = true
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        v.removeCallbacks(longPress)
                        hidePanel()
                        hideSpeech()
                        brain.react(Reaction.GRABBED)
                    }
                    if (moved) {
                        val screen = screenSize()
                        val (minY, maxY) = yRange(screen, bubbleSizePx)
                        p.x = (startX + dx).roundToInt().coerceIn(0, screen.x - bubbleSizePx)
                        p.y = (startY + dy).roundToInt().coerceIn(minY, maxY)
                        updateWindow(brain, p)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    dragging = false
                    val tracker = velocity
                    velocity = null
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    if (moved) {
                        brain.clearReaction()
                        if (e.actionMasked == MotionEvent.ACTION_UP && hypot(vx, vy) > dp(FLING_MIN_DP)) throwBubble(vx, vy) else snapToEdge()
                    } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                    }
                }
            }
            return true
        }
    }

    private fun snapToEdge() {
        val p = bubbleParams ?: return
        val maxX = screenSize().x - bubbleSizePx
        scope.launch {
            animateTo(if (p.x + bubbleSizePx / 2 > (maxX + bubbleSizePx) / 2) maxX else 0, p.y, 220L, walk = false)
            savePosition()
        }
    }

    private fun savePosition() {
        val p = bubbleParams ?: return
        val screen = screenSize()
        prefs.edit()
            .putBoolean(KEY_RIGHT, p.x > (screen.x - bubbleSizePx) / 2)
            .putFloat(KEY_Y, p.y.toFloat() / screen.y.coerceAtLeast(1))
            .apply()
    }

    // --- Roaming: restless brains do not sit still ----------------------------------------------

    private fun startRoaming() {
        roamJob = scope.launch {
            while (isActive) {
                val m0 = mood
                val calm = m0 == null || m0.stage < Stage.RESTLESS
                delay(if (calm) 70_000 + Random.nextLong(0, 50_000) else (40_000 - 32_000 * m0!!.restlessness).toLong() + Random.nextLong(0, 6_000))
                val m = mood ?: continue
                if (dragging || panel != null || bubble == null) continue
                roamOnce(m)
            }
        }
    }

    private fun stopRoaming() {
        roamJob?.cancel()
        roamJob = null
    }

    private suspend fun roamOnce(m: BrainMood) {
        val p = bubbleParams ?: return
        val screen = screenSize()
        val maxX = screen.x - bubbleSizePx
        val (minY, maxY) = yRange(screen, bubbleSizePx)
        val duration = (2_600 - 1_600 * m.restlessness).toLong()
        hideSpeech()
        if (m.stage < Stage.RESTLESS) {
            // A calm stroll: a short walk up or down its own edge.
            val step = (screen.y * (0.08f + Random.nextFloat() * 0.15f)).roundToInt() * (if (Random.nextBoolean()) 1 else -1)
            animateTo(p.x, (p.y + step).coerceIn(minY, maxY), 2_200, walk = true)
        } else if (m.stage >= Stage.FRIED && Random.nextFloat() < 0.6f) {
            // Fried brains wander into the middle of the screen, linger in the way, then wander back.
            val ty = Random.nextInt(minY, maxY + 1)
            animateTo(Random.nextInt(maxX / 4, maxX * 3 / 4 + 1), ty, duration, walk = true)
            if (Random.nextFloat() < 0.5f) say(BrainVoice.ambientTopic(m), null)
            delay(4_000 + Random.nextLong(0, 3_000))
            if (dragging || bubble == null) return
            animateTo(if (Random.nextBoolean()) 0 else maxX, ty, duration, walk = true)
        } else {
            // Restless brains pace along the edge and sometimes cross to the other side.
            val tx = if (Random.nextFloat() < 0.3f) (if (p.x < maxX / 2) maxX else 0) else p.x
            animateTo(tx, Random.nextInt(minY, maxY + 1), duration, walk = true)
        }
        savePosition()
    }

    private suspend fun animateTo(tx: Int, ty: Int, durationMs: Long, walk: Boolean) {
        val view = bubble ?: return
        val p = bubbleParams ?: return
        val sx = p.x
        val sy = p.y
        suspendCancellableCoroutine { cont ->
            view.walking = walk
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = durationMs
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { a ->
                    if (bubble !== view) return@addUpdateListener
                    val f = a.animatedValue as Float
                    p.x = (sx + (tx - sx) * f).roundToInt()
                    p.y = (sy + (ty - sy) * f).roundToInt()
                    updateWindow(view, p)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.walking = false
                        if (cont.isActive) cont.resume(Unit)
                    }
                })
            }
            moveAnimator?.cancel()
            moveAnimator = anim
            cont.invokeOnCancellation { anim.cancel() }
            anim.start()
        }
    }

    // --- Throwing: fling the brain and it bounces off the screen edges --------------------------

    private fun throwBubble(vx0: Float, vy0: Float) {
        val view = bubble ?: return
        val p = bubbleParams ?: return
        moveAnimator?.cancel()
        throwJob?.cancel()
        if (data?.settings?.speechEnabled == true && Random.nextFloat() < 0.5f) {
            showSpeech(res.getStringArray(R.array.speech_thrown).random())
            hideSpeechSoon()
        }
        throwJob = scope.launch {
            val cap = dp(4_000).toFloat()
            var vx = vx0.coerceIn(-cap, cap)
            var vy = vy0.coerceIn(-cap, cap)
            var x = p.x.toFloat()
            var y = p.y.toFloat()
            val screen = screenSize()
            val maxX = (screen.x - bubbleSizePx).toFloat()
            val (minY, maxYInt) = yRange(screen, bubbleSizePx)
            val maxY = maxYInt.toFloat()
            val stopSpeed = dp(140).toFloat()
            var last = SystemClock.uptimeMillis()
            view.walking = false
            while (isActive && hypot(vx, vy) > stopSpeed && bubble === view) {
                delay(16)
                val now = SystemClock.uptimeMillis()
                val dt = ((now - last) / 1000f).coerceIn(0.001f, 0.05f)
                last = now
                x += vx * dt
                y += vy * dt
                // Air friction: loses ~80% of its speed per second.
                val drag = Math.pow(0.2, dt.toDouble()).toFloat()
                vx *= drag
                vy *= drag
                if (x < 0f || x > maxX) { x = x.coerceIn(0f, maxX); vx = -vx * 0.65f; view.react(Reaction.FLINCH) }
                if (y < minY || y > maxY) { y = y.coerceIn(minY.toFloat(), maxY); vy = -vy * 0.65f; view.react(Reaction.FLINCH) }
                p.x = x.roundToInt()
                p.y = y.roundToInt()
                updateWindow(view, p)
            }
            if (bubble === view) snapToEdge()
        }
    }

    // --- Live counter pill at the top while a Shorts/Reels player is open ----------------------

    private fun renderHud(d: AppData, l: LiveState, m: BrainMood) {
        val want = l.screenOn && l.inShortVideo && d.settings.liveCounterEnabled && block == null && canDraw()
        if (!want) {
            removeHud()
            return
        }
        val view = hud ?: createHud() ?: return
        val reels = d.today.totalReels
        view.text = res.getQuantityString(R.plurals.hud_text, reels, reels, res.getString(SpeechLines.stageLabel(m.stage)))
        (view.background as? GradientDrawable)?.setColor(hudColor(m.stage))
    }

    private fun createHud(): TextView? {
        val view = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(hudColor(Stage.FRESH), 20)
            setPadding(dp(14), dp(6), dp(14), dp(6))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            // Touches pass through to the app below; Android 12+ allows that only at alpha <= 0.8.
            alpha = 0.8f
        }
        if (!addWindow(view, params)) return null
        hud = view
        return view
    }

    private fun removeHud() {
        hud?.let { removeWindow(it) }
        hud = null
    }

    private fun bump(view: View) {
        view.animate().cancel()
        view.scaleX = 1.25f
        view.scaleY = 1.25f
        view.animate().scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun hudColor(stage: Stage): Int = when (stage) {
        Stage.FRESH -> 0xFF2E7D5B.toInt()
        Stage.FOGGY -> 0xFF5B6B7A.toInt()
        Stage.RESTLESS -> 0xFFB7791F.toInt()
        Stage.FRIED -> 0xFFC2410C.toInt()
        Stage.ROTTEN -> 0xFF9F1239.toInt()
    }

    private fun badgeText(reels: Int): String = if (reels > 999) "999+" else reels.toString()

    private fun hideSpeechSoon() {
        speechHideJob?.cancel()
        speechHideJob = scope.launch {
            delay(1_500)
            hideSpeech()
        }
    }

    // --- Care panel -----------------------------------------------------------------------------

    private fun togglePanel() {
        if (panel != null) {
            hidePanel()
        } else if (System.currentTimeMillis() - panelHiddenAt > 300) {
            // A tap on the bubble also counts as "outside" for the open panel; don't instantly reopen.
            showPanel()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val d = data ?: return
        val l = live ?: return
        val m = mood ?: return
        hideSpeech()
        val views = PanelViews()
        views.bind(d, l, m)
        val params = besideBubble(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ) ?: return
        views.root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hidePanel()
            false
        }
        if (addWindow(views.root, params)) panel = views
    }

    private fun hidePanel() {
        panel?.let {
            removeWindow(it.root)
            panelHiddenAt = System.currentTimeMillis()
        }
        panel = null
    }

    private inner class PanelViews {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFF7FA.toInt(), 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumWidth = dp(232)
        }
        private val title = label(16f, bold = true)
        private val subtitle = label(12f)
        private val rot = Meter(R.string.meter_rot, 0xFFB86B7A.toInt())
        private val food = Meter(R.string.meter_food, 0xFFC08A4A.toInt())
        private val water = Meter(R.string.meter_water, 0xFF3D9BE0.toInt())
        private val energy = Meter(R.string.meter_energy, 0xFF8A6FD6.toInt())
        private val stats = label(12f)

        init {
            root.addView(title)
            root.addView(subtitle)
            listOf(rot, food, water, energy).forEach { root.addView(it.row) }
            root.addView(stats, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            root.addView(row(
                action(R.string.action_feed) {
                    runtime.feed(Food.entries.random())
                    hidePanel()
                },
                action(R.string.action_water) {
                    runtime.water()
                    hidePanel()
                },
            ))
            root.addView(row(
                action(R.string.action_hide_hour) {
                    runtime.hideBubbleFor(HOUR_MS)
                    hidePanel()
                },
                action(R.string.action_open_app) {
                    runtime.openApp()
                    hidePanel()
                },
            ))
        }

        fun bind(d: AppData, l: LiveState, m: BrainMood) {
            title.text = d.settings.brainName
            subtitle.text = res.getString(R.string.panel_subtitle, res.getString(SpeechLines.stageLabel(m.stage)), m.health)
            rot.set(d.brain.rot)
            food.set(d.brain.nourishment)
            water.set(d.brain.hydration)
            energy.set(d.brain.energy)
            stats.text = res.getQuantityString(R.plurals.panel_stats, d.today.totalReels, d.today.totalReels, formatDuration(l.totalScreenMs))
        }

        private fun row(vararg buttons: Button) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f).apply { topMargin = dp(6); marginEnd = dp(4) }) }
        }

        private fun action(textRes: Int, onClick: () -> Unit) = Button(ctx).apply {
            setText(textRes)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(INK)
            background = rounded(0xFFFFDDE7.toInt(), 14)
            minHeight = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { onClick() }
        }
    }

    private inner class Meter(labelRes: Int, color: Int) {
        private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(color)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(12f).apply { setText(labelRes) }, LinearLayout.LayoutParams(dp(78), WRAP))
            addView(bar, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        fun set(value: Double) {
            bar.progress = value.roundToInt().coerceIn(0, 100)
        }
    }

    // --- Speech ---------------------------------------------------------------------------------

    private fun say(topic: SpeechTopic, detail: String?) {
        val line = SpeechLines.pick(res, topic)
        showSpeech(if (detail.isNullOrBlank()) line else "$line\n$detail")
    }

    private fun showSpeech(text: String) {
        if (bubble == null || panel != null || text.isBlank()) return
        hideSpeech()
        val view = TextView(ctx).apply {
            this.text = text
            setTextColor(INK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = dp(220)
            background = rounded(0xFFFFFFFF.toInt(), 14).apply { setStroke(dp(1), 0xFFE7B3C3.toInt()) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val params = besideBubble(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ) ?: return
        params.alpha = 0.8f
        if (!addWindow(view, params)) return
        speech = view
        speechHideJob = scope.launch {
            delay(SPEECH_MS)
            hideSpeech()
        }
    }

    private fun hideSpeech() {
        speechHideJob?.cancel()
        speechHideJob = null
        speech?.let { removeWindow(it) }
        speech = null
    }

    // --- Window plumbing ------------------------------------------------------------------------

    private fun besideBubble(flags: Int): WindowManager.LayoutParams? {
        val b = bubbleParams ?: return null
        val screen = screenSize()
        val onLeft = b.x + bubbleSizePx / 2 < screen.x / 2
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (onLeft) {
                gravity = Gravity.TOP or Gravity.START
                x = b.x + bubbleSizePx + dp(4)
            } else {
                gravity = Gravity.TOP or Gravity.END
                x = screen.x - b.x + dp(4)
            }
            y = b.y.coerceAtLeast(0)
        }
    }

    private fun addWindow(view: View, params: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, params)
        true
    } catch (e: RuntimeException) {
        // BadTokenException / SecurityException when the overlay permission was just revoked.
        if (!windowErrorLogged) {
            windowErrorLogged = true
            BrainLog.e("overlay_add_failed", e, "can_draw" to canDraw())
        }
        false
    }

    private fun updateWindow(view: View, params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            // View was removed meanwhile (screen off, block shown); nothing to move.
        }
    }

    private fun removeWindow(view: View) {
        try {
            wm.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Already detached.
        }
    }

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            return Point(b.width(), b.height())
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Point(dm.widthPixels, dm.heightPixels)
    }

    private fun yRange(screen: Point, sizePx: Int): Pair<Int, Int> {
        val minY = dp(32)
        return minY to (screen.y - sizePx - dp(64)).coerceAtLeast(minY)
    }

    private fun label(sizeSp: Float, bold: Boolean = false) = TextView(ctx).apply {
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(v: Int): Int = (v * res.displayMetrics.density).roundToInt()

    companion object {
        private const val PREFS = "overlay"
        private const val KEY_RIGHT = "bubble_right"
        private const val KEY_Y = "bubble_y"
        private const val SPEECH_MS = 4_500L
        private const val BLOCK_SUPPRESS_MS = 1_500L
        private const val REEL_MILESTONE = 10
        private const val FLING_MIN_DP = 900
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val INK = 0xFF2B1B2E.toInt()
    }
}
