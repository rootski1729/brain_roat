package com.brainrot.pet.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.brainrot.core.ShortVideoApp
import com.brainrot.core.ShortVideoApps
import com.brainrot.pet.AppGraph
import com.brainrot.pet.BrainLog

/**
 * Counts reels and reports app switches. It reads only view ids, visibility and bounds of the
 * short-video players listed in [ShortVideoApps]; it never reads text, never stores screen content,
 * and only performs HOME/BACK when the user taps a button on the block screen.
 */
class ReelAccessibilityService : AccessibilityService() {

    private val runtime get() = AppGraph.runtime
    private val activityCache = HashMap<String, Boolean>()
    private val lastSurfaceCheckAt = HashMap<String, Long>()
    private val bounds = Rect()
    private var lastErrorLogAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        runtime.onAccessibilityConnected(this)
        BrainService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        runtime.markAccessibilityEvent(now)
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    onWindowState(pkg, e.className?.toString(), e.windowId, now)
                    checkSurface(pkg, now, force = true)
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkSurface(pkg, now, force = false)
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScroll(e, pkg, now)
                else -> Unit // Only the three types in accessibility_service_config.xml are subscribed.
            }
        } catch (ex: RuntimeException) {
            // Another app's node tree can vanish mid-read. Never crash; log at most once a minute.
            if (now - lastErrorLogAt > 60_000) {
                lastErrorLogAt = now
                BrainLog.w("a11y_event_failed", ex, "pkg" to pkg, "type" to e.eventType)
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        runtime.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    /**
     * Only full app windows change the foreground app. Keyboards, System UI, toasts and our own
     * overlay windows also fire window-state events and would otherwise lift a block. The window
     * type is authoritative; PackageManager is only a fallback, because Android 11+ package
     * visibility hides apps without a launcher icon (e.g. a separate in-call UI package).
     */
    private fun onWindowState(pkg: String, className: String?, windowId: Int, now: Long) {
        if (pkg == packageName && className?.startsWith(OWN_UI_PREFIX) != true) return
        val isAppWindow = windowType(windowId)?.let { it == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: (className != null && isActivity(pkg, className))
        if (isAppWindow) runtime.setForeground(pkg, now)
    }

    private fun windowType(windowId: Int): Int? =
        if (windowId < 0) null else windows.firstOrNull { it.id == windowId }?.type

    private fun isActivity(pkg: String, className: String): Boolean {
        val key = "$pkg/$className"
        activityCache[key]?.let { return it }
        val result = try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(ComponentName(pkg, className), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (activityCache.size > MAX_CACHE) activityCache.clear()
        activityCache[key] = result
        return result
    }

    private fun checkSurface(pkg: String, now: Long, force: Boolean) {
        val app = ShortVideoApps.find(pkg) ?: return
        if (!AppGraph.store.data.value.settings.reelCountingEnabled) {
            // Switched off mid-session: drop the surface so reel limits stop applying too.
            if (runtime.reelCounter.isInSurface(pkg)) {
                runtime.reelCounter.onSurfaceChanged(pkg, false, now)
                runtime.setInShortVideo(pkg, false)
            }
            return
        }
        if (!force && now - (lastSurfaceCheckAt[pkg] ?: 0L) < SURFACE_CHECK_MS) return
        lastSurfaceCheckAt[pkg] = now
        // Our block screen or another app may be the active window; only judge the app itself.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val visible = app.wholeAppIsSurface || hasFullScreenSurface(root, pkg, app)
        val counted = runtime.reelCounter.onSurfaceChanged(pkg, visible, now)
        runtime.setInShortVideo(pkg, visible)
        if (counted) runtime.onReel(pkg, now)
    }

    private fun hasFullScreenSurface(root: AccessibilityNodeInfo, pkg: String, app: ShortVideoApp): Boolean {
        val minHeight = resources.displayMetrics.heightPixels / 2
        // Patched forks keep the original resource package, so try it too.
        val resourcePackages = listOf(pkg, app.packageNames.first()).distinct()
        for (name in app.surfaceIds) {
            for (resPkg in resourcePackages) {
                for (node in root.findAccessibilityNodeInfosByViewId("$resPkg:id/$name")) {
                    if (!node.isVisibleToUser) continue
                    node.getBoundsInScreen(bounds)
                    if (bounds.height() >= minHeight) return true
                }
            }
        }
        return false
    }

    private fun onScroll(e: AccessibilityEvent, pkg: String, now: Long) {
        val app = ShortVideoApps.find(pkg) ?: return
        if (!AppGraph.store.data.value.settings.reelCountingEnabled) return
        if (!runtime.reelCounter.isInSurface(pkg)) {
            // Throttled: normal feed scrolling must not trigger a tree search per scroll event.
            checkSurface(pkg, now, force = false)
            if (!runtime.reelCounter.isInSurface(pkg)) return
        }
        val source = e.source ?: return
        if (!isReelPager(source, app)) return
        val position = if (e.itemCount > 0 && e.fromIndex >= 0) e.fromIndex else null
        if (runtime.reelCounter.onScroll(pkg, position, now)) runtime.onReel(pkg, now)
    }

    /**
     * The scrolled node is the reel pager if it (or one of its first ancestors: ViewPager2's inner
     * RecyclerView has no id) has a known pager id, or if it is a scrollable view covering almost the
     * whole screen. The size rule keeps counting alive when an app renames its ids.
     */
    private fun isReelPager(source: AccessibilityNodeInfo, app: ShortVideoApp): Boolean {
        val metrics = resources.displayMetrics
        var node: AccessibilityNodeInfo? = source
        var depth = 0
        while (node != null && depth <= MAX_PARENT_DEPTH) {
            if (ShortVideoApps.matchesId(node.viewIdResourceName, app.pagerIds)) return true
            if (node.isScrollable) {
                node.getBoundsInScreen(bounds)
                if (bounds.height() >= metrics.heightPixels * 0.75f && bounds.width() >= metrics.widthPixels * 0.9f) return true
            }
            node = node.parent
            depth++
        }
        return false
    }

    companion object {
        private const val SURFACE_CHECK_MS = 600L
        private const val MAX_PARENT_DEPTH = 4
        private const val MAX_CACHE = 500
        private const val OWN_UI_PREFIX = "com.brainrot.pet.ui."
    }
}
