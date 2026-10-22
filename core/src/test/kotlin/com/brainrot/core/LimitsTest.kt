package com.brainrot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"
    private val exempt = setOf("com.android.launcher3", "com.brainrot.pet")
    private val today = DayStats(day = "2026-10-22")

    @Test
    fun `app time limit blocks the whole app once reached`() {
        val config = LimitConfig(apps = listOf(AppLimit(yt, dailyMinutes = 30)))
        val under = UsageSnapshot(appMs = mapOf(yt to 29 * MINUTE_MS))
        val over = UsageSnapshot(appMs = mapOf(yt to 30 * MINUTE_MS))
        assertNull(LimitEvaluator.evaluate(config, today, under, yt, inShortVideo = false, exempt = exempt))
        val breach = LimitEvaluator.evaluate(config, today, over, yt, inShortVideo = false, exempt = exempt)
        assertEquals(BreachKind.APP_TIME, breach?.kind)
        assertEquals(yt, breach?.grantKey)
    }

    @Test
    fun `reel limit lets the user finish reel N and blocks N+1, only inside the reels surface`() {
        val config = LimitConfig(dailyReels = 20)
        val atLimit = UsageSnapshot(reelsByApp = mapOf(yt to 12, ig to 8))
        val past = UsageSnapshot(reelsByApp = mapOf(yt to 12, ig to 9))
        assertNull(LimitEvaluator.evaluate(config, today, atLimit, ig, inShortVideo = true, exempt = exempt))
        assertEquals(BreachKind.TOTAL_REELS, LimitEvaluator.evaluate(config, today, past, ig, inShortVideo = true, exempt = exempt)?.kind)
        assertNull("long-form YouTube stays usable", LimitEvaluator.evaluate(config, today, past, yt, inShortVideo = false, exempt = exempt))
    }

    @Test
    fun `total screen limit blocks every app except exempt ones, and enforce=false disables blocking`() {
        val config = LimitConfig(dailyScreenMinutes = 60)
        val usage = UsageSnapshot(totalScreenMs = 61 * MINUTE_MS)
        assertEquals(BreachKind.TOTAL_SCREEN_TIME, LimitEvaluator.evaluate(config, today, usage, "com.whatsapp", false, exempt)?.kind)
        assertNull(LimitEvaluator.evaluate(config, today, usage, "com.android.launcher3", false, exempt))
        assertNull(LimitEvaluator.evaluate(config.copy(enforce = false), today, usage, "com.whatsapp", false, exempt))
        assertNull(LimitEvaluator.evaluate(config, today, usage, null, false, exempt))
    }

    @Test
    fun `unlock grants extend the right limit and waits double each time`() {
        val config = LimitConfig(apps = listOf(AppLimit(ig, dailyReels = 10)))
        val usage = UsageSnapshot(reelsByApp = mapOf(ig to 11))
        val breach = LimitEvaluator.evaluate(config, today, usage, ig, true, exempt)!!
        val granted = UnlockPolicy.grant(today, breach)
        assertEquals(UnlockPolicy.EXTRA_REELS, granted.extraReels(ig))
        assertEquals(1, granted.unlocks)
        assertNull(LimitEvaluator.evaluate(config, granted, usage, ig, true, exempt))

        assertEquals(listOf(15, 30, 60, 120, 240, 300, 300), (0..6).map(UnlockPolicy::waitSeconds))
        assertEquals(300, UnlockPolicy.waitSeconds(Int.MAX_VALUE))
        assertEquals(15, UnlockPolicy.waitSeconds(-1))
    }

    @Test
    fun `loosening detection`() {
        val base = LimitConfig(dailyReels = 50, apps = listOf(AppLimit(yt, dailyMinutes = 30)))
        assertFalse(base.loosens(base))
        assertFalse("tightening is free", base.copy(dailyReels = 40).loosens(base))
        assertFalse("adding a limit is free", base.copy(dailyScreenMinutes = 120).loosens(base))
        assertTrue(base.copy(dailyReels = 60).loosens(base))
        assertTrue(base.copy(dailyReels = null).loosens(base))
        assertTrue(base.copy(apps = emptyList()).loosens(base))
        assertTrue(base.copy(apps = listOf(AppLimit(yt, dailyMinutes = 45))).loosens(base))
        assertTrue(base.copy(enforce = false).loosens(base))
    }

    @Test
    fun `input parsing rejects non ascii digits, signs and out of range`() {
        assertEquals(LimitInput.None, LimitInput.parse("  ", 1..100))
        assertEquals(LimitInput.Value(42), LimitInput.parse(" 42 ", 1..100))
        assertEquals(LimitInput.Invalid, LimitInput.parse("٤٢", 1..100))
        assertEquals(LimitInput.Invalid, LimitInput.parse("-4", 1..100))
        assertEquals(LimitInput.Invalid, LimitInput.parse("0", 1..100))
        assertEquals(LimitInput.Invalid, LimitInput.parse("101", 1..100))
        assertEquals(LimitInput.Invalid, LimitInput.parse("99999999999", 1..100))
    }

    @Test
    fun `sanitize drops bad stored limits`() {
        val dirty = LimitConfig(
            dailyReels = 0,
            dailyScreenMinutes = 99_999,
            apps = listOf(AppLimit(""), AppLimit(yt, dailyMinutes = 10), AppLimit(yt, dailyMinutes = 99), AppLimit(ig)),
        ).sanitized()
        assertNull(dirty.dailyReels)
        assertNull(dirty.dailyScreenMinutes)
        assertEquals(listOf(AppLimit(yt, dailyMinutes = 10)), dirty.apps)
    }

    @Test
    fun `nudges fire once per limit when crossing 80 percent`() {
        val config = LimitConfig(dailyReels = 10, apps = listOf(AppLimit(yt, dailyMinutes = 100)))
        val usage = UsageSnapshot(appMs = mapOf(yt to 85 * MINUTE_MS), reelsByApp = mapOf(yt to 5))
        val nudges = LimitEvaluator.pendingNudges(config, today, usage)
        assertEquals(listOf("APP_TIME:$yt"), nudges.map { it.id })
        assertTrue(LimitEvaluator.pendingNudges(config, today.copy(nudged = setOf("APP_TIME:$yt")), usage).isEmpty())
    }
}

class LimitProgressTest {
    @Test
    fun `exceeded matches the evaluator rule for reels and time`() {
        assertFalse(LimitProgress(BreachKind.TOTAL_REELS, null, null, used = 10, limit = 10).exceeded)
        assertTrue(LimitProgress(BreachKind.TOTAL_REELS, null, null, used = 11, limit = 10).exceeded)
        assertTrue(LimitProgress(BreachKind.APP_TIME, "a", "A", used = 10, limit = 10).exceeded)
    }
}
