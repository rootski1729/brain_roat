package com.brainrot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelFingerprintsTest {
    private fun t(s: String) = ReelFingerprints.tokenize(s)

    @Test
    fun `tokenize drops counts, chrome and short words and keeps no text`() {
        val tokens = t("@chef_ana Crispy garlic noodles 🍜 1.2K likes 340 comments Subscribe a")
        assertEquals(setOf("@chef_ana", "crispy", "garlic", "noodles").map { it.hashCode() }.toSet(), tokens)
    }

    @Test
    fun `same reel with changing counters is not counted twice, a new reel is`() {
        val p = ReelFingerprints()
        assertTrue(p.observe(t("@chef_ana crispy garlic noodles 1.2K")))
        assertFalse(p.observe(t("@chef_ana crispy garlic noodles 1.3K liked")))
        assertTrue(p.observe(t("@runner_joe morning run in the rain")))
    }

    @Test
    fun `going back to a recently seen reel does not count`() {
        val p = ReelFingerprints()
        p.observe(t("@a first reel caption here"))
        p.observe(t("@b second clip something else"))
        assertFalse(p.observe(t("@a first reel caption here")))
        assertTrue(p.observe(t("@c totally different video")))
    }

    @Test
    fun `reels from the same creator with different captions still count`() {
        val p = ReelFingerprints()
        assertTrue(p.observe(t("@chef_ana crispy garlic noodles recipe")))
        assertTrue(p.observe(t("@chef_ana spicy tomato soup tonight")))
    }

    @Test
    fun `too little text is ignored, seeding remembers without counting`() {
        val p = ReelFingerprints()
        assertFalse(p.observe(t("hi")))
        assertFalse(p.observe(t("@a quiet beach sunset"), countIfNew = false))
        assertFalse(p.observe(t("@a quiet beach sunset")))
    }
}

class ReelTrackerTest {
    private val yt = "com.google.android.youtube"
    private fun t(s: String) = ReelFingerprints.tokenize(s)

    @Test
    fun `fingerprint mode counts each new reel exactly once whatever the event mix`() {
        val r = ReelTracker()
        assertTrue(r.onSurfaceChanged(yt, true, 0, t("@a cat jumps over fence")))
        // Many content/scroll events for the same reel.
        assertFalse(r.onContentChanged(yt, 300, t("@a cat jumps over fence 12K")))
        assertFalse(r.onScroll(yt, 0, 400, null))
        // Swipe: mid-swipe events have no readable text, then the next reel.
        assertFalse(r.onScroll(yt, null, 5_000, null))
        assertTrue(r.onScroll(yt, null, 5_200, t("@b pasta in five minutes")))
        assertFalse(r.onContentChanged(yt, 5_600, t("@b pasta in five minutes")))
        assertTrue(r.onContentChanged(yt, 9_000, t("@c guitar solo cover")))
    }

    @Test
    fun `falls back to swipe counting when no text is readable`() {
        val r = ReelTracker()
        assertTrue(r.onSurfaceChanged(yt, true, 0, null))
        assertFalse(r.onScroll(yt, 0, 1_000, null))
        assertTrue(r.onScroll(yt, 1, 5_000, null))
        assertTrue(r.onScroll(yt, 2, 9_000, null))
    }

    @Test
    fun `text appearing right after a swipe-counted entry does not double count the first reel`() {
        val r = ReelTracker()
        assertTrue(r.onSurfaceChanged(yt, true, 0, null))
        assertFalse(r.onContentChanged(yt, 800, t("@a first reel loading caption")))
        assertTrue(r.onContentChanged(yt, 6_000, t("@b second reel caption here")))
    }

    @Test
    fun `leaving and quickly coming back to the same reel does not count`() {
        val r = ReelTracker()
        assertTrue(r.onSurfaceChanged(yt, true, 0, t("@a long video caption words")))
        assertFalse(r.onSurfaceChanged(yt, false, 3_000, null))
        assertFalse(r.onSurfaceChanged(yt, true, 8_000, t("@a long video caption words")))
        assertFalse(r.onContentChanged(yt, 1, null))
        r.hideAll(9_000)
        assertFalse(r.anySurfaceVisible())
    }
}
