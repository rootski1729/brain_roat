package com.brainrot.pet.runtime

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import com.brainrot.core.AppData
import com.brainrot.core.Breach
import com.brainrot.core.BrainEngine
import com.brainrot.core.BrainState
import com.brainrot.core.BrainVoice
import com.brainrot.core.CareResult
import com.brainrot.core.DayKey
import com.brainrot.core.Food
import com.brainrot.core.LimitConfig
import com.brainrot.core.LimitProgress
import com.brainrot.core.ShortVideoApps
import com.brainrot.core.SpeechTopic
import com.brainrot.core.UnlockPolicy
import com.brainrot.core.UsageSnapshot
import com.brainrot.pet.BrainLog
import com.brainrot.pet.data.AppCatalog
import com.brainrot.pet.data.BrainStore
import com.brainrot.pet.data.UsageReader
import com.brainrot.pet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** Live, non-persisted state shared by the services, the overlay and the UI. */
data class LiveState(
    val screenOn: Boolean = true,
    val sessionStartedAt: Long = System.currentTimeMillis(),
    val foreground: String? = null,
    val inShortVideo: Boolean = false,
    /** Our own activity is on screen; the bubble hides because the big brain is already visible. */
    val appInForeground: Boolean = false,
    val appMs: Map<String, Long> = emptyMap(),
    val totalScreenMs: Long = 0,
    val usageUpdatedAt: Long = 0,
    val accessibilityConnected: Boolean = false,
    val lastAccessibilityEventAt: Long = 0,
    val serviceRunning: Boolean = false,
    val lastTickAt: Long = 0,
    val breach: Breach? = null,
) {
    /**
     * Today's usage. Time read before midnight (or never) is unknown, not yesterday's total, so a
     * fresh day's limits are never judged with yesterday's numbers.
     */
    fun snapshot(data: AppData): UsageSnapshot {
        val current = usageUpdatedAt > 0 && DayKey.of(usageUpdatedAt) == data.today.day
        return if (current) UsageSnapshot(appMs, totalScreenMs, data.today.reelsByApp) else UsageSnapshot(reelsByApp = data.today.reelsByApp)
    }
}

sealed interface BrainEvent {
    data class ReelCounted(val packageName: String, val totalToday: Int) : BrainEvent
    data class Say(val topic: SpeechTopic, val detail: String? = null) : BrainEvent
}

/**
 * Coordinates everything that changes the brain: reel counts from the accessibility service,
 * usage refreshes, care actions from the app or the floating panel, and unlocks.
 */
class BrainRuntime(
    private val context: Context,
    val store: BrainStore,
    private val usage: UsageReader,
    val catalog: AppCatalog,
) {
    val reelTracker = com.brainrot.core.ReelTracker()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _events = MutableSharedFlow<BrainEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<BrainEvent> = _events.asSharedFlow()

    private val _unlockRequest = MutableStateFlow<Breach?>(null)

    /** Set when the user taps "Unlock" on the block screen; the app shows the mindful-wait dialog. */
    val unlockRequest: StateFlow<Breach?> = _unlockRequest.asStateFlow()

    private val pendingReels = AtomicInteger()
    private val refreshLock = Mutex()
    @Volatile private var accessibility: WeakReference<AccessibilityService>? = null

    // --- Reels and foreground (accessibility service, main thread) ---

    fun onReel(packageName: String, now: Long) {
        store.update { it.copy(today = it.today.withReel(packageName)) }
        pendingReels.incrementAndGet()
        val total = store.data.value.today.totalReels
        _events.tryEmit(BrainEvent.ReelCounted(packageName, total))
        BrainLog.d("reel_counted", "pkg" to packageName, "total_today" to total, "at" to now)
    }

    /** Reels counted since the last engine tick. */
    fun drainPendingReels(): Int = pendingReels.getAndSet(0)

    fun setForeground(packageName: String?, now: Long) {
        val previous = _live.value.foreground
        if (previous == packageName) return
        if (previous != null && ShortVideoApps.find(previous) != null) {
            reelTracker.onSurfaceChanged(previous, false, now, null)
        }
        _live.update { it.copy(foreground = packageName, inShortVideo = packageName != null && reelTracker.isInSurface(packageName)) }
    }

    fun setInShortVideo(packageName: String, visible: Boolean) {
        _live.update { if (it.foreground == packageName && it.inShortVideo != visible) it.copy(inShortVideo = visible) else it }
    }

    fun setAppInForeground(visible: Boolean) = _live.update { it.copy(appInForeground = visible) }

    fun setScreenOn(on: Boolean, now: Long) = _live.update {
        if (on) it.copy(screenOn = true, sessionStartedAt = now) else it.copy(screenOn = false, inShortVideo = false)
    }

    fun setBreach(breach: Breach?) = _live.update { if (it.breach == breach) it else it.copy(breach = breach) }

    fun markServiceRunning(running: Boolean) = _live.update { it.copy(serviceRunning = running) }

    fun markTick(now: Long) = _live.update { it.copy(lastTickAt = now) }

    // --- Accessibility service lifecycle ---

    fun onAccessibilityConnected(service: AccessibilityService) {
        accessibility = WeakReference(service)
        _live.update { it.copy(accessibilityConnected = true) }
        BrainLog.i("accessibility_connected")
    }

    fun onAccessibilityDisconnected() {
        accessibility = null
        reelTracker.hideAll(System.currentTimeMillis())
        _live.update { it.copy(accessibilityConnected = false, inShortVideo = false) }
        BrainLog.w("accessibility_disconnected")
    }

    /** Cheap heartbeat: only touches the StateFlow every few seconds. */
    fun markAccessibilityEvent(now: Long) {
        if (now - _live.value.lastAccessibilityEventAt >= 5_000) {
            _live.update { it.copy(lastAccessibilityEventAt = now) }
        }
    }

    fun performGlobalAction(action: Int): Boolean = accessibility?.get()?.performGlobalAction(action) == true

    /** Leave the blocked app. Uses the accessibility HOME action if available, else a HOME intent. */
    fun goHome() {
        if (performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            BrainLog.e("go_home_failed", e)
        }
    }

    fun openApp(unlockFor: Breach? = null) {
        if (unlockFor != null) _unlockRequest.value = unlockFor
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        } catch (e: RuntimeException) {
            BrainLog.e("open_app_failed", e)
        }
    }

    // --- Usage ---

    /** Re-reads today's usage. Safe to call from any coroutine; does the I/O on [Dispatchers.Default]. */
    suspend fun refreshUsage(now: Long = System.currentTimeMillis()) = refreshLock.withLock {
        // Tick, screen-on and the activity can all ask at once; one query at a time, never older over newer.
        if (_live.value.usageUpdatedAt > now) return@withLock
        val summary = withContext(Dispatchers.Default) { usage.today(now, catalog.notScreenTime) }
        _live.update { it.copy(appMs = summary.appMs, totalScreenMs = summary.totalMs, usageUpdatedAt = now) }
        store.update { if (it.today.screenMs == summary.totalMs) it else it.copy(today = it.today.copy(screenMs = summary.totalMs)) }
    }

    // --- Care actions (app UI and floating panel) ---

    fun feed(food: Food, now: Long = System.currentTimeMillis()): CareResult =
        care(now, water = false) { BrainEngine.feed(it, food, now) }

    fun water(now: Long = System.currentTimeMillis()): CareResult =
        care(now, water = true) { BrainEngine.water(it, now) }

    private fun care(now: Long, water: Boolean, action: (BrainState) -> CareResult): CareResult {
        var result: CareResult = CareResult.Full
        store.update { data ->
            val r = action(data.brain)
            result = r
            if (r is CareResult.Accepted) {
                val today = if (water) data.today.copy(waters = data.today.waters + 1) else data.today.copy(feeds = data.today.feeds + 1)
                data.copy(brain = r.state, today = today)
            } else {
                data
            }
        }
        _events.tryEmit(BrainEvent.Say(BrainVoice.careTopic(result, water)))
        BrainLog.i("care", "kind" to if (water) "water" else "feed", "result" to result.javaClass.simpleName, "at" to now)
        return result
    }

    fun say(topic: SpeechTopic, detail: String? = null) {
        _events.tryEmit(BrainEvent.Say(topic, detail))
    }

    // --- Limits ---

    /** Grants today's allowance for [breach]; the caller has already made the user wait. */
    fun clearUnlockRequest() {
        _unlockRequest.value = null
    }

    fun unlock(breach: Breach) {
        _unlockRequest.value = null
        store.update { it.copy(today = UnlockPolicy.grant(it.today, breach)) }
        BrainLog.i("unlock_granted", "kind" to breach.kind, "pkg" to breach.packageName, "unlocks_today" to store.data.value.today.unlocks)
    }

    fun saveLimits(limits: LimitConfig, countsAsUnlock: Boolean) {
        val clean = limits.sanitized()
        store.update {
            val today = if (countsAsUnlock) it.today.copy(unlocks = it.today.unlocks + 1) else it.today
            it.copy(limits = clean, today = today)
        }
        BrainLog.i("limits_saved", "apps" to clean.apps.size, "reels" to clean.dailyReels, "screen_min" to clean.dailyScreenMinutes, "enforce" to clean.enforce, "loosened" to countsAsUnlock)
    }

    fun markNudged(progress: LimitProgress) {
        store.update { it.copy(today = it.today.copy(nudged = it.today.nudged + progress.id)) }
    }

    fun hideBubbleFor(ms: Long) {
        val until = System.currentTimeMillis() + ms
        store.update { it.copy(settings = it.settings.copy(bubbleHiddenUntil = until)) }
    }
}
