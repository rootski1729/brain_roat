package com.brainrot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AppDataTest {

    @Test
    fun `round trip and forward compatibility`() {
        val data = AppData(
            brain = BrainState(rot = 42.0),
            limits = LimitConfig(dailyReels = 30, apps = listOf(AppLimit("com.instagram.android", "Instagram", dailyReels = 10))),
            today = DayStats(day = "2026-10-22").withReel("com.instagram.android").withReel("com.instagram.android"),
        )
        val decoded = AppDataCodec.decode(AppDataCodec.encode(data))
        assertEquals(data, decoded)
        assertEquals(2, decoded.today.totalReels)

        val newer = """{"schema":2,"brain":{"rot":10,"futureField":1},"unknown":{"x":1}}"""
        assertEquals(10.0, AppDataCodec.decode(newer).brain.rot, 1e-9)
    }

    @Test
    fun `decode sanitizes tampered values`() {
        val tampered = """{"brain":{"rot":1000,"energy":-4},"settings":{"brainName":"  \u0007${"x".repeat(50)}","bubbleSizeDp":5000},
            "today":{"day":"2026-10-22","reelsByApp":{"a":-3}},"history":[{"day":"garbage"},{"day":"2026-10-20"},{"day":"2026-10-21"}]}"""
        val d = AppDataCodec.decode(tampered)
        assertEquals(100.0, d.brain.rot, 1e-9)
        assertEquals(0.0, d.brain.energy, 1e-9)
        assertEquals(Settings.MAX_NAME_LENGTH, d.settings.brainName.length)
        assertTrue(d.settings.brainName.all { it == 'x' })
        assertEquals(Settings.MAX_BUBBLE_DP, d.settings.bubbleSizeDp)
        assertEquals(0, d.today.reelsByApp["a"])
        assertEquals(listOf("2026-10-21", "2026-10-20"), d.history.map { it.day })
    }

    @Test
    fun `day rollover archives today and resets grants, keeps the brain`() {
        val data = AppData(
            brain = BrainState(rot = 70.0),
            today = DayStats(day = "2026-10-21", unlocks = 3, extraMinutesByKey = mapOf(TOTAL_KEY to 20)),
            history = (1..40).map { DayStats(day = "2026-09-%02d".format(it % 30 + 1)) },
        )
        val rolled = data.rolledTo("2026-10-22")
        assertEquals(DayStats(day = "2026-10-22"), rolled.today)
        assertEquals("2026-10-21", rolled.history.first().day)
        assertEquals(3, rolled.history.first().unlocks)
        assertEquals(AppData.MAX_HISTORY_DAYS, rolled.history.size)
        assertEquals(70.0, rolled.brain.rot, 1e-9)
        assertTrue(rolled === rolled.rolledTo("2026-10-22"))
        assertEquals("2026-10-22", AppData().rolledTo("2026-10-22").today.day)
    }

    @Test
    fun `day keys follow the local zone`() {
        val utc = ZoneId.of("UTC")
        val kolkata = ZoneId.of("Asia/Kolkata")
        val t = Instant.parse("2026-10-21T20:00:00Z").toEpochMilli()
        assertEquals("2026-10-21", DayKey.of(t, utc))
        assertEquals("2026-10-22", DayKey.of(t, kolkata))
        assertEquals(t - 20 * HOUR_MS, DayKey.startOfDay(t, utc))
        assertEquals("2h 05m", formatDuration(125 * MINUTE_MS))
        assertEquals("0m", formatDuration(-1))
    }
}
