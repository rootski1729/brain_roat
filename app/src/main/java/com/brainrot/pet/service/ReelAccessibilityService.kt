package com.brainrot.pet.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.brainrot.core.NodeSnap
import com.brainrot.core.RailDetector
import com.brainrot.core.ReelFingerprints
import com.brainrot.core.ShortVideoApp
import com.brainrot.core.ShortVideoApps
import com.brainrot.pet.AppGraph
import com.brainrot.pet.BrainLog

/**
 * Counts reels and reports app switches. Inside the short-video players listed in [ShortVideoApps]
 * it reads view ids, bounds and the current reel's author/caption text; that text is hashed into
 * anonymous tokens in memory at once and never stored or sent. Outside those players it only
 * looks at which app window is in front. HOME/BACK happen only on block-screen buttons.
 */
class ReelAccessibilityService : AccessibilityService() {

    private val runtime get() = AppGraph.runtime
    private val activityCache = HashMap<String, Boolean>()
    private val lastSurfaceCheckAt = HashMap<String, Long>()
    private val bounds = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private val pendingCheck = HashSet<String>()
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
        handler.removeCallbacksAndMessages(null)
        pendingCheck.clear()
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
        val tracker = runtime.reelTracker
        if (!AppGraph.store.data.value.settings.reelCountingEnabled) {
            // Switched off mid-session: drop the surface so reel limits stop applying too.
            if (tracker.isInSurface(pkg)) {
                tracker.onSurfaceChanged(pkg, false, now, null)
                runtime.setInShortVideo(pkg, false)
            }
            return
        }
        val sinceLast = now - (lastSurfaceCheckAt[pkg] ?: 0L)
        if (!force && sinceLast < SURFACE_CHECK_MS) {
            // Throttled, but never drop the last change: re-check once when the window ends,
            // otherwise a reel that loads after the final event of a burst would be missed.
            if (pendingCheck.add(pkg)) {
                handler.postDelayed({
                    pendingCheck.remove(pkg)
                    checkSurface(pkg, System.currentTimeMillis(), force = false)
                }, SURFACE_CHECK_MS - sinceLast)
            }
            return
        }
        lastSurfaceCheckAt[pkg] = now
        // Our block screen or another app may be the active window; only judge the app itself.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val (visible, tokens) = inspect(root, pkg, app)
        val counted = if (visible && tracker.isInSurface(pkg)) {
            tracker.onContentChanged(pkg, now, tokens)
        } else {
            tracker.onSurfaceChanged(pkg, visible, now, tokens)
        }
        runtime.setInShortVideo(pkg, visible)
        if (counted) runtime.onReel(pkg, now)
    }

    private fun hasFullScreenSurface(root: AccessibilityNodeInfo, pkg: String, app: ShortVideoApp): Boolean {
        val minHeight = resources.displayMetrics.heightPixels / 2
        for (name in app.surfaceIds) {
            for (resPkg in resourcePackages(pkg, app)) {
                for (node in root.findAccessibilityNodeInfosByViewId("$resPkg:id/$name")) {
                    if (!node.isVisibleToUser) continue
                    node.getBoundsInScreen(bounds)
                    if (bounds.height() >= minHeight) return true
                }
            }
        }
        return false
    }

    /** Is the reel player on screen, and which reel is it (token hashes, null if unreadable)? */
    private fun inspect(root: AccessibilityNodeInfo, pkg: String, app: ShortVideoApp): Pair<Boolean, Set<Int>?> {
        if (app.layoutDetected) {
            val metrics = resources.displayMetrics
            val result = RailDetector.detect(snapshot(root), metrics.widthPixels, metrics.heightPixels)
            return result.isReel to result.tokens
        }
        val visible = app.wholeAppIsSurface || hasFullScreenSurface(root, pkg, app)
        return visible to (if (visible) currentReelTokens(root, pkg, app) else null)
    }

    /** Visible nodes as plain snapshots for layout detection, breadth-first and bounded. */
    private fun snapshot(root: AccessibilityNodeInfo): List<NodeSnap> {
        val out = ArrayList<NodeSnap>(256)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_LAYOUT_NODES) {
            val n = queue.removeFirst()
            visited++
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(bounds)
                out += NodeSnap(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    n.text?.toString(), n.contentDescription?.toString(), n.className?.toString(),
                )
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    /** Patched forks keep the original resource package, so try it too. */
    private fun resourcePackages(pkg: String, app: ShortVideoApp) = listOf(pkg, app.packageNames.first()).distinct()

    private fun onScroll(e: AccessibilityEvent, pkg: String, now: Long) {
        val app = ShortVideoApps.find(pkg) ?: return
        if (!AppGraph.store.data.value.settings.reelCountingEnabled) return
        val tracker = runtime.reelTracker
        if (!tracker.isInSurface(pkg)) {
            // Throttled: normal feed scrolling must not trigger a tree search per scroll event.
            checkSurface(pkg, now, force = false)
            if (!tracker.isInSurface(pkg)) return
        }
        val source = e.source ?: return
        if (!isReelPager(source, app)) return
        val position = if (e.itemCount > 0 && e.fromIndex >= 0) e.fromIndex else null
        val root = rootInActiveWindow
        val tokens = if (root != null && root.packageName?.toString() == pkg) inspect(root, pkg, app).second else null
        if (tracker.onScroll(pkg, position, now, tokens)) runtime.onReel(pkg, now)
    }

    /**
     * Identity of the reel on screen, as token hashes of its author/caption/title (see
     * ReelFingerprints). The text only lives in a local buffer for the duration of this call.
     * Returns null when the app shows too little text, and counting falls back to swipes.
     */
    private fun currentReelTokens(root: AccessibilityNodeInfo, pkg: String, app: ShortVideoApp): Set<Int>? {
        val text = StringBuilder()
        for (name in app.captionIds) {
            mostVisible(resourcePackages(pkg, app).flatMap { root.findAccessibilityNodeInfosByViewId("$it:id/$name") })
                ?.let { collectText(it, text) }
        }
        if (text.isEmpty()) {
            // No caption ids for this app (or they were renamed): read the most visible pager page.
            val pager = app.pagerIds.firstNotNullOfOrNull { name ->
                resourcePackages(pkg, app).firstNotNullOfOrNull { mostVisible(root.findAccessibilityNodeInfosByViewId("$it:id/$name")) }
            } ?: return null
            val pages = (0 until pager.childCount.coerceAtMost(MAX_PAGES)).mapNotNull { pager.getChild(it) }
            mostVisible(pages)?.let { collectText(it, text) }
        }
        val tokens = ReelFingerprints.tokenize(text)
        return tokens.takeIf { it.size >= ReelFingerprints.MIN_TOKENS }
    }

    /** The node covering the most screen; during a swipe two pages are partly visible. */
    private fun mostVisible(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        for (node in nodes) {
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(bounds)
            val area = bounds.width().toLong() * bounds.height()
            if (area > bestArea) {
                bestArea = area
                best = node
            }
        }
        return best
    }

    /** Breadth-first text and content descriptions under [node], bounded in nodes and characters. */
    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TEXT_NODES && out.length < MAX_TEXT_CHARS) {
            val n = queue.removeFirst()
            visited++
            n.text?.let { out.append(it).append(' ') }
            n.contentDescription?.let { out.append(it).append(' ') }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
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
        private const val SURFACE_CHECK_MS = 400L
        private const val MAX_PAGES = 8
        private const val MAX_TEXT_NODES = 60
        private const val MAX_TEXT_CHARS = 2_000
        private const val MAX_LAYOUT_NODES = 700
        private const val MAX_PARENT_DEPTH = 4
        private const val MAX_CACHE = 500
        private const val OWN_UI_PREFIX = "com.brainrot.pet.ui."
    }
}
