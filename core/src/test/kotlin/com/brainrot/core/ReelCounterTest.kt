package com.brainrot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelCounterTest {
    private val ig = "com.instagram.android"

    @Test
    fun `entering the surface counts the first reel, brief exits do not recount`() {
        val c = ReelCounter()
        assertTrue(c.onSurfaceChanged(ig, true, 0))
        assertFalse("duplicate visible signal", c.onSurfaceChanged(ig, true, 100))
        assertFalse(c.onSurfaceChanged(ig, false, 5_000))
        assertFalse("back from comments within grace", c.onSurfaceChanged(ig, true, 10_000))
        c.onSurfaceChanged(ig, false, 20_000)
        assertTrue("new session after grace", c.onSurfaceChanged(ig, true, 60_000))
    }

    @Test
    fun `position based counting ignores re-watches and cancelled swipes`() {
        val c = ReelCounter()
        c.onSurfaceChanged(ig, true, 0)
        val counted = listOf(
            c.onScroll(ig, 0, 1_000), // learn starting position
            c.onScroll(ig, 1, 5_000), // swipe forward
            c.onScroll(ig, 1, 5_050), // same swipe, more events
            c.onScroll(ig, 0, 9_000), // back to re-watch
            c.onScroll(ig, 1, 12_000), // forward again to an already seen reel
            c.onScroll(ig, 2, 15_000), // genuinely new
        )
        assertEquals(listOf(false, true, false, false, false, true), counted)
    }

    @Test
    fun `burst based counting when the app hides positions`() {
        val c = ReelCounter(burstGapMs = 450, minReelIntervalMs = 700)
        c.onSurfaceChanged(ig, true, 0)
        // One swipe = burst of events 16ms apart.
        val swipe1 = (0 until 20).map { c.onScroll(ig, null, 3_000L + it * 16) }
        assertEquals(1, swipe1.count { it })
        val swipe2 = (0 until 20).map { c.onScroll(ig, null, 8_000L + it * 16) }
        assertEquals(1, swipe2.count { it })
        assertFalse("not in surface", ReelCounter().onScroll(ig, null, 1))
    }

    @Test
    fun `surface time accumulates across sessions and drains`() {
        val c = ReelCounter()
        c.onSurfaceChanged(ig, true, 0)
        c.onSurfaceChanged(ig, false, 10_000)
        c.onSurfaceChanged(ig, true, 100_000)
        assertEquals(15_000, c.drainSurfaceMs(105_000))
        assertEquals(5_000, c.drainSurfaceMs(110_000))
        c.hideAll(111_000)
        assertFalse(c.anySurfaceVisible())
        assertEquals(1_000, c.drainSurfaceMs(200_000))
        assertEquals(0, c.drainSurfaceMs(300_000))
    }
}

class ShortVideoAppsTest {
    @Test
    fun `registry lookup and id suffix matching`() {
        assertEquals(ShortVideoApps.INSTAGRAM, ShortVideoApps.find("com.instagram.android"))
        assertEquals(null, ShortVideoApps.find("com.whatsapp"))
        assertTrue(ShortVideoApps.matchesId("com.google.android.youtube:id/reel_recycler", ShortVideoApps.YOUTUBE.pagerIds))
        assertTrue(ShortVideoApps.matchesId("app.revanced.android.youtube:id/reel_recycler", ShortVideoApps.YOUTUBE.pagerIds))
        assertFalse(ShortVideoApps.matchesId("com.google.android.youtube:id/results", ShortVideoApps.YOUTUBE.pagerIds))
        assertFalse(ShortVideoApps.matchesId(null, ShortVideoApps.YOUTUBE.pagerIds))
    }
}

class DetectionHealthTest {
    @Test
    fun `flags short video apps with long use and no reels`() {
        val ig = "com.instagram.android"
        val yt = "com.google.android.youtube"
        val appMs = mapOf(ig to 25 * MINUTE_MS, yt to 30 * MINUTE_MS)
        assertEquals(listOf(ShortVideoApps.INSTAGRAM), DetectionHealth.suspects(appMs, emptyMap()))
        assertTrue(DetectionHealth.suspects(appMs, mapOf(ig to 3)).isEmpty())
        assertEquals(listOf(ShortVideoApps.YOUTUBE), DetectionHealth.suspects(mapOf(yt to 61 * MINUTE_MS), emptyMap()))
    }
}
