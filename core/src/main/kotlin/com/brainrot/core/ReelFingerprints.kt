package com.brainrot.core

/**
 * Tells reels apart by the words on their overlay (author, caption, title) without keeping the
 * words: text is reduced to a set of token hashes immediately, and only the last [capacity] sets
 * per app are held in memory.
 *
 * Two observations are the same reel when their token sets overlap by at least [sameThreshold]
 * (Jaccard). That tolerates small UI changes (a caption expanding, a button label toggling)
 * while different reels, which differ in author and caption, fall well below it. A reel seen
 * recently never counts again, so swiping back to re-watch is free.
 */
class ReelFingerprints(
    private val capacity: Int = 50,
    private val sameThreshold: Double = 0.6,
) {
    private val recent = ArrayDeque<Set<Int>>()
    private var current: Set<Int>? = null

    /**
     * Records the reel now on screen. Returns true when it is a reel not seen recently and
     * [countIfNew] is set; with [countIfNew] false it only remembers it (already counted another way).
     */
    @Synchronized
    fun observe(tokens: Set<Int>, countIfNew: Boolean = true): Boolean {
        if (tokens.size < MIN_TOKENS) return false
        val cur = current
        if (cur != null && similarity(cur, tokens) >= sameThreshold) {
            // Same reel, possibly with more text loaded; keep the richer version.
            if (tokens.size > cur.size) replace(cur, tokens)
            return false
        }
        current = tokens
        val seen = recent.firstOrNull { similarity(it, tokens) >= sameThreshold }
        if (seen != null) {
            replace(seen, tokens)
            return false
        }
        recent.addLast(tokens)
        while (recent.size > capacity) recent.removeFirst()
        return countIfNew
    }

    @Synchronized
    fun clearCurrent() {
        current = null
    }

    private fun replace(old: Set<Int>, new: Set<Int>) {
        val i = recent.indexOf(old)
        if (i >= 0) recent[i] = new
        if (current === old) current = new
    }

    companion object {
        const val MIN_TOKENS = 2

        /** Generic player chrome that appears on every reel and says nothing about which one it is. */
        private val CHROME = setOf(
            "like", "likes", "liked", "dislike", "comment", "comments", "share", "send", "remix", "save",
            "subscribe", "subscribed", "follow", "following", "more", "less", "sound", "original", "audio",
            "reel", "reels", "shorts", "short", "views", "view", "sponsored", "ad", "see", "translation",
            "the", "and", "for", "you", "your", "this", "that", "with",
        )
        private val SPLIT = Regex("[^\\p{L}\\p{N}@#_]+")

        /**
         * Lower-cased words of at least 2 letters, minus anything containing a digit (like and view
         * counts change while you watch) and minus player chrome, hashed straight away.
         */
        fun tokenize(text: CharSequence): Set<Int> =
            text.toString().lowercase().split(SPLIT)
                .asSequence()
                .filter { it.length >= 2 && it.none(Char::isDigit) && it !in CHROME }
                .map { it.hashCode() }
                .toSet()

        fun similarity(a: Set<Int>, b: Set<Int>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val inter = a.count { it in b }
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}

/**
 * Combines the two ways of noticing "next reel": reel identity from on-screen text
 * ([ReelFingerprints], accurate, used whenever the app exposes text) and swipe signals
 * ([ReelCounter], position or scroll bursts, the fallback). One session uses one method at a
 * time, so a reel is never counted by both.
 */
class ReelTracker(
    private val counter: ReelCounter = ReelCounter(),
    /** Fingerprints arriving this soon after a swipe-counted entry belong to the reel already counted. */
    private val seedWindowMs: Long = 2_500,
) {
    private class Session {
        var fingerprintMode = false
        var entryCountedAt = Long.MIN_VALUE
    }

    private val sessions = HashMap<String, Session>()
    private val prints = HashMap<String, ReelFingerprints>()

    @Synchronized
    fun onSurfaceChanged(packageName: String, visible: Boolean, now: Long, tokens: Set<Int>?): Boolean {
        val wasVisible = counter.isInSurface(packageName)
        val entryCounted = counter.onSurfaceChanged(packageName, visible, now)
        if (!visible) {
            if (wasVisible) {
                sessions.remove(packageName)
                prints[packageName]?.clearCurrent()
            }
            return false
        }
        if (wasVisible) return identify(packageName, now, tokens)
        val session = Session().also { sessions[packageName] = it }
        if (usable(tokens)) {
            session.fingerprintMode = true
            return printsFor(packageName).observe(tokens!!)
        }
        if (entryCounted) session.entryCountedAt = now
        return entryCounted
    }

    /** Scroll inside the player. [tokens] describe the reel on screen after the scroll, if readable. */
    @Synchronized
    fun onScroll(packageName: String, position: Int?, now: Long, tokens: Set<Int>?): Boolean {
        if (!counter.isInSurface(packageName)) return false
        val session = sessions[packageName] ?: return false
        if (usable(tokens) || session.fingerprintMode) return identify(packageName, now, tokens)
        return counter.onScroll(packageName, position, now)
    }

    /** The player's content changed (a new reel finished loading, captions appeared). */
    @Synchronized
    fun onContentChanged(packageName: String, now: Long, tokens: Set<Int>?): Boolean {
        if (!counter.isInSurface(packageName)) return false
        return identify(packageName, now, tokens)
    }

    private fun identify(packageName: String, now: Long, tokens: Set<Int>?): Boolean {
        val session = sessions[packageName] ?: return false
        if (!usable(tokens)) return false
        val firstPrint = !session.fingerprintMode
        session.fingerprintMode = true
        val seedOnly = firstPrint && session.entryCountedAt != Long.MIN_VALUE && now - session.entryCountedAt in 0..seedWindowMs
        return printsFor(packageName).observe(tokens!!, countIfNew = !seedOnly)
    }

    private fun usable(tokens: Set<Int>?): Boolean = tokens != null && tokens.size >= ReelFingerprints.MIN_TOKENS

    private fun printsFor(packageName: String) = prints.getOrPut(packageName) { ReelFingerprints() }

    fun isInSurface(packageName: String): Boolean = counter.isInSurface(packageName)
    fun anySurfaceVisible(): Boolean = counter.anySurfaceVisible()
    fun drainSurfaceMs(now: Long): Long = counter.drainSurfaceMs(now)

    @Synchronized
    fun hideAll(now: Long) {
        sessions.keys.toList().forEach { onSurfaceChanged(it, false, now, null) }
        counter.hideAll(now)
    }
}
