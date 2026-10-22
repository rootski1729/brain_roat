package com.brainrot.core

import com.brainrot.core.UsageEventType.BACKGROUND
import com.brainrot.core.UsageEventType.FOREGROUND
import com.brainrot.core.UsageEventType.SCREEN_OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageAggregatorTest {
    private val day = 1_000 * HOUR_MS
    private fun at(min: Long) = day + min * MINUTE_MS
    private fun fg(min: Long, pkg: String, cls: String = "Main") = UsageEventLite(at(min), FOREGROUND, pkg, cls)
    private fun bg(min: Long, pkg: String, cls: String? = "Main") = UsageEventLite(at(min), BACKGROUND, pkg, cls)

    @Test
    fun `pairs resume and pause per app`() {
        val s = UsageAggregator.summarize(
            listOf(fg(0, "a"), bg(10, "a"), fg(10, "b"), bg(25, "b"), fg(30, "a"), bg(35, "a")),
            windowStart = day, now = at(60),
        )
        assertEquals(mapOf("a" to 15 * MINUTE_MS, "b" to 15 * MINUTE_MS), s.appMs)
        assertEquals(30 * MINUTE_MS, s.totalMs)
        assertNull(s.foreground)
    }

    @Test
    fun `app open across midnight is counted from midnight, open app counted to now`() {
        val s = UsageAggregator.summarize(
            listOf(fg(-30, "night"), bg(20, "night"), fg(50, "live")),
            windowStart = day, now = at(60),
        )
        assertEquals(20 * MINUTE_MS, s.appMs["night"])
        assertEquals(10 * MINUTE_MS, s.appMs["live"])
        assertEquals("live", s.foreground)
    }

    @Test
    fun `screen off closes everything even without pause events`() {
        val s = UsageAggregator.summarize(
            listOf(fg(0, "a"), UsageEventLite(at(5), SCREEN_OFF)),
            windowStart = day, now = at(60),
        )
        assertEquals(5 * MINUTE_MS, s.appMs["a"])
        assertNull(s.foreground)
    }

    @Test
    fun `split screen is not double counted in total and excluded apps are ignored`() {
        val s = UsageAggregator.summarize(
            listOf(fg(0, "a"), fg(0, "b"), bg(10, "a"), bg(10, "b"), fg(10, "launcher"), bg(20, "launcher")),
            windowStart = day, now = at(60), excluded = setOf("launcher"),
        )
        assertEquals(10 * MINUTE_MS, s.appMs["a"])
        assertEquals(10 * MINUTE_MS, s.appMs["b"])
        assertEquals(10 * MINUTE_MS, s.totalMs)
        assertNull(s.appMs["launcher"])
    }

    @Test
    fun `activity switch inside one app and mismatched pause class`() {
        val s = UsageAggregator.summarize(
            listOf(fg(0, "a", "One"), bg(5, "a", "One"), fg(5, "a", "Two"), bg(12, "a", "Weird")),
            windowStart = day, now = at(60),
        )
        assertEquals(12 * MINUTE_MS, s.appMs["a"])
    }

    @Test
    fun `out of order and future events are handled`() {
        val s = UsageAggregator.summarize(
            listOf(bg(10, "a"), fg(0, "a"), fg(120, "future")),
            windowStart = day, now = at(60),
        )
        assertEquals(mapOf("a" to 10 * MINUTE_MS), s.appMs)
        assertEquals(UsageSummary(emptyMap(), 0, null), UsageAggregator.summarize(listOf(fg(0, "a")), day, day))
    }
}
