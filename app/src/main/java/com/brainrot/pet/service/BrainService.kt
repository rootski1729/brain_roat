package com.brainrot.pet.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brainrot.core.ActivitySlice
import com.brainrot.core.AppData
import com.brainrot.core.BrainEngine
import com.brainrot.core.BrainTuning
import com.brainrot.core.DayKey
import com.brainrot.core.LimitEvaluator
import com.brainrot.core.LimitProgress
import com.brainrot.core.MINUTE_MS
import com.brainrot.core.SECOND_MS
import com.brainrot.core.SpeechTopic
import com.brainrot.core.Stage
import com.brainrot.core.formatDuration
import com.brainrot.pet.AppGraph
import com.brainrot.pet.BrainLog
import com.brainrot.pet.R
import com.brainrot.pet.data.ForegroundPoller
import com.brainrot.pet.data.Permissions
import com.brainrot.pet.overlay.OverlayController
import com.brainrot.pet.overlay.SpeechLines
import com.brainrot.pet.runtime.LiveState
import com.brainrot.pet.widget.BrainWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The pet's heartbeat. A foreground service so Android keeps it alive while other apps are in
 * front. It
 *  - advances the brain engine every [TICK_MS] while the screen is on, and credits screen-off time as rest;
 *  - refreshes today's usage from UsageStats;
 *  - evaluates limits whenever the foreground app, usage or settings change, and shows the block guard;
 *  - drives the overlay, the status notification, nudges and the widget.
 */
class BrainService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = AppGraph.runtime
    private val store get() = AppGraph.store
    private val tuning = BrainTuning()

    private lateinit var overlay: OverlayController
    private lateinit var poller: ForegroundPoller
    private var screenOn = true
    private var receiverRegistered = false
    private var lastBreachId: String? = null
    private var lastStatus: String? = null
    private var lastWidgetAt = 0L
    private var lastWidgetStage: Stage? = null
    private var lastFallbackAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff(now)
                Intent.ACTION_SCREEN_ON -> onScreenOn(now)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!startAsForeground()) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        poller = ForegroundPoller(AppGraph.usage)
        overlay = OverlayController(this, runtime, scope) { evaluate() }
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        runtime.setScreenOn(screenOn, now)
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        runtime.markServiceRunning(true)

        scope.launch {
            catchUp()
            refreshUsage()
            tickLoop()
        }
        scope.launch { foregroundLoop() }
        scope.launch {
            combine(store.data, runtime.live) { d, l -> d to l }.collect { (d, l) -> onState(d, l) }
        }
        scope.launch { runtime.events.collect { overlay.onEvent(it) } }
        BrainLog.i("service_started", "screen_on" to screenOn, "overlay" to overlay.canDraw(), "accessibility" to runtime.live.value.accessibilityConnected)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        val now = System.currentTimeMillis()
        if (::overlay.isInitialized) {
            if (screenOn) account(now, wasScreenOn = true)
            overlay.destroy()
        }
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        runtime.setBreach(null)
        runtime.markServiceRunning(false)
        scope.cancel()
        AppGraph.appScope.launch { store.flush() }
        BrainLog.i("service_stopped")
        super.onDestroy()
    }

    private fun startAsForeground(): Boolean = try {
        val notification = Notifications.status(this, getString(R.string.app_name), getString(R.string.status_starting))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ID_STATUS, notification, type)
        true
    } catch (e: Exception) {
        BrainLog.e("service_foreground_failed", e)
        false
    }

    // --- Brain accounting -----------------------------------------------------------------------

    /**
     * Credits the gap since the process last ran (killed by the system, reboot). Screen-on time in
     * the gap comes from UsageStats; the rest is treated as one rest period.
     */
    private suspend fun catchUp() {
        val now = System.currentTimeMillis()
        val from = store.data.value.accountedUntil
        if (from <= 0 || now <= from || now - from < CATCH_UP_MIN_GAP_MS || !Permissions.hasUsageAccess(this)) {
            store.update { it.copy(accountedUntil = now) }
            return
        }
        val gap = now - from
        val onMs = withContext(Dispatchers.Default) {
            AppGraph.usage.between(from, now, runtime.catalog.notScreenTime).totalMs
        }.coerceIn(0, gap)
        val offMs = gap - onMs
        store.update { d ->
            // A screen on/off event may have accounted this gap while UsageStats was being read.
            if (d.accountedUntil != from) return@update d
            val rested = BrainEngine.advance(d.brain, ActivitySlice(screenOffMs = offMs))
            d.copy(
                brain = BrainEngine.advance(rested, ActivitySlice(screenOnMs = onMs)),
                accountedUntil = now,
                today = if (offMs >= tuning.minRestMs) d.today.copy(restMs = d.today.restMs + offMs) else d.today,
            )
        }
        BrainLog.i("catch_up", "gap_min" to gap / MINUTE_MS, "on_min" to onMs / MINUTE_MS)
    }

    private fun account(now: Long, wasScreenOn: Boolean) {
        val shortMs = runtime.reelCounter.drainSurfaceMs(now)
        val reels = runtime.drainPendingReels()
        store.update { d ->
            val elapsed = now - d.accountedUntil
            val slice = when {
                d.accountedUntil <= 0 || elapsed <= 0 -> ActivitySlice(newReels = reels)
                // Ticks arrive every TICK_MS while the screen is on; anything longer is a clock jump, not use.
                wasScreenOn -> ActivitySlice(screenOnMs = elapsed.coerceAtMost(MAX_ON_SLICE_MS), shortVideoMs = shortMs, newReels = reels)
                else -> ActivitySlice(screenOffMs = elapsed, newReels = reels)
            }
            val restMs = if (slice.screenOffMs >= tuning.minRestMs) slice.screenOffMs else 0L
            d.copy(
                brain = BrainEngine.advance(d.brain, slice),
                accountedUntil = now,
                today = d.today.copy(shortVideoMs = d.today.shortVideoMs + shortMs, restMs = d.today.restMs + restMs),
            )
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            if (screenOn) tick(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    private suspend fun tick(now: Long) {
        account(now, wasScreenOn = true)
        val usageAt = runtime.live.value.usageUpdatedAt
        // After midnight the cached usage belongs to yesterday; never judge today's limits with it.
        if (now - usageAt >= USAGE_REFRESH_MS || DayKey.of(usageAt) != DayKey.of(now)) refreshUsage()
        runtime.markTick(now)
        checkNudges()
        updateStatus()
        updateWidget(now, force = false)
    }

    private suspend fun refreshUsage() {
        if (!Permissions.hasUsageAccess(this)) return
        try {
            runtime.refreshUsage()
        } catch (e: RuntimeException) {
            BrainLog.w("usage_refresh_failed", e)
        }
    }

    private suspend fun foregroundLoop() {
        while (currentCoroutineContext().isActive) {
            val live = runtime.live.value
            // The accessibility service reports app switches instantly; polling is only the fallback.
            if (screenOn && !live.accessibilityConnected && !store.data.value.limits.isEmpty && Permissions.hasUsageAccess(this)) {
                val now = System.currentTimeMillis()
                val fg = withContext(Dispatchers.Default) { poller.poll(now) }
                runtime.setForeground(fg, now)
            }
            delay(FOREGROUND_POLL_MS)
        }
    }

    // --- Screen on / off ------------------------------------------------------------------------

    private fun onScreenOff(now: Long) {
        account(now, wasScreenOn = true)
        screenOn = false
        runtime.reelCounter.hideAll(now)
        runtime.setScreenOn(false, now)
        overlay.onScreenOff()
        updateWidget(now, force = true)
        scope.launch { store.flush() }
    }

    private fun onScreenOn(now: Long) {
        val offMs = now - store.data.value.accountedUntil
        account(now, wasScreenOn = false)
        screenOn = true
        runtime.setScreenOn(true, now)
        if (offMs >= WELCOME_BACK_AFTER_MS) runtime.say(SpeechTopic.WELCOME_BACK_RESTED)
        scope.launch { refreshUsage() }
    }

    // --- Limits ---------------------------------------------------------------------------------

    private fun evaluate() = onState(store.data.value, runtime.live.value)

    private fun onState(d: AppData, l: LiveState) {
        val now = System.currentTimeMillis()
        enforce(d, l, now)
        overlay.render(d, runtime.live.value, now)
    }

    private fun enforce(d: AppData, l: LiveState, now: Long) {
        val breach = if (l.screenOn) {
            LimitEvaluator.evaluate(d.limits, d.today, l.snapshot(d), l.foreground, l.inShortVideo, runtime.catalog.neverBlock)
        } else {
            null
        }
        runtime.setBreach(breach)
        if (breach == null) {
            if (overlay.isBlockVisible) overlay.hideBlock()
            lastBreachId = null
            return
        }
        val id = "${breach.kind}:${breach.packageName}"
        val newBreach = id != lastBreachId
        if (newBreach) {
            lastBreachId = id
            store.update { it.copy(today = it.today.copy(blocks = it.today.blocks + 1)) }
            BrainLog.i("limit_block", "kind" to breach.kind, "pkg" to breach.packageName, "used" to breach.used, "limit" to breach.limit)
        }
        if (!overlay.showBlock(breach, d, now) && now - lastFallbackAt >= FALLBACK_INTERVAL_MS) {
            // No overlay window possible: try to leave the app (reliable only via the accessibility
            // HOME action; a background HOME intent may be ignored), and explain once per breach.
            lastFallbackAt = now
            runtime.goHome()
            if (newBreach) {
                Notifications.nudge(this, getString(R.string.block_title, d.settings.brainName), overlay.describe(breach))
                BrainLog.w("limit_block_no_overlay", null, "kind" to breach.kind, "pkg" to breach.packageName, "accessibility" to l.accessibilityConnected)
            }
        }
    }

    private fun checkNudges() {
        val d = store.data.value
        val l = runtime.live.value
        val pending = LimitEvaluator.pendingNudges(d.limits, d.today, l.snapshot(d)).firstOrNull() ?: return
        runtime.markNudged(pending)
        val detail = getString(R.string.nudge_detail, nudgeLabel(pending), (pending.fraction * 100).roundToInt())
        if (overlay.isBubbleVisible) {
            runtime.say(SpeechTopic.NEAR_LIMIT, detail)
        } else {
            Notifications.nudge(this, getString(R.string.nudge_title, d.settings.brainName), detail)
        }
        BrainLog.i("limit_nudge", "id" to pending.id, "pct" to (pending.fraction * 100).roundToInt())
    }

    private fun nudgeLabel(p: LimitProgress): String =
        p.label?.let { getString(R.string.nudge_label_app, getString(SpeechLines.breachLabel(p.kind)), it) }
            ?: getString(SpeechLines.breachLabel(p.kind))

    // --- Status surfaces ------------------------------------------------------------------------

    private fun updateStatus() {
        val d = store.data.value
        val l = runtime.live.value
        val mood = BrainEngine.mood(d.brain, System.currentTimeMillis() - l.sessionStartedAt)
        val title = getString(R.string.status_title, d.settings.brainName, getString(SpeechLines.stageLabel(mood.stage)))
        val text = getString(R.string.status_text, d.today.totalReels, formatDuration(l.totalScreenMs), mood.health)
        val key = "$title|$text"
        if (key == lastStatus) return
        lastStatus = key
        Notifications.updateStatus(this, title, text)
    }

    private fun updateWidget(now: Long, force: Boolean) {
        val stage = Stage.of(store.data.value.brain.rot)
        if (!force && stage == lastWidgetStage && now - lastWidgetAt < WIDGET_REFRESH_MS) return
        lastWidgetStage = stage
        lastWidgetAt = now
        BrainWidget.updateAll(this)
    }

    companion object {
        private const val TICK_MS = 15 * SECOND_MS
        private const val MAX_ON_SLICE_MS = 3 * TICK_MS
        private const val USAGE_REFRESH_MS = 30 * SECOND_MS
        private const val FOREGROUND_POLL_MS = 1_500L
        private const val CATCH_UP_MIN_GAP_MS = 2 * MINUTE_MS
        private const val WELCOME_BACK_AFTER_MS = 30 * MINUTE_MS
        private const val WIDGET_REFRESH_MS = 10 * MINUTE_MS
        private const val FALLBACK_INTERVAL_MS = 3 * SECOND_MS

        /** Starts the service if there is anything for it to do. Safe to call repeatedly. */
        fun start(context: Context) {
            if (!Permissions.hasUsageAccess(context) && !Settings.canDrawOverlays(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, BrainService::class.java))
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) or IllegalStateException when
                // started from the background; the next app open or boot will try again.
                BrainLog.w("service_start_denied", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BrainService::class.java))
        }
    }
}
