package com.brainrot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainEngineTest {
    private val tuning = BrainTuning()

    @Test
    fun `reels and short video time rot the brain faster than plain screen time`() {
        val start = BrainState()
        val plain = BrainEngine.advance(start, ActivitySlice(screenOnMs = 30 * MINUTE_MS))
        val reels = BrainEngine.advance(start, ActivitySlice(screenOnMs = 30 * MINUTE_MS, shortVideoMs = 30 * MINUTE_MS, newReels = 60))
        assertEquals(30 * tuning.rotPerScreenMinute, plain.rot, 1e-9)
        assertTrue("reels ${reels.rot} should beat plain ${plain.rot}", reels.rot > plain.rot * 5)
        assertEquals(Stage.ROTTEN, Stage.of(BrainEngine.advance(start, ActivitySlice(screenOnMs = 40 * MINUTE_MS, shortVideoMs = 40 * MINUTE_MS, newReels = 100)).rot))
    }

    @Test
    fun `a full night of rest heals a rotten brain and restores energy`() {
        val rotten = BrainState(rot = 95.0, energy = 10.0)
        val slept = BrainEngine.advance(rotten, ActivitySlice(screenOffMs = 8 * HOUR_MS))
        assertEquals(0.0, slept.rot, 1e-9)
        assertEquals(100.0, slept.energy, 1e-9)
        assertTrue("needs drain slower but still drain overnight", slept.nourishment < rotten.nourishment)
    }

    @Test
    fun `screen-off blips shorter than minimum rest do not heal`() {
        val state = BrainState(rot = 50.0)
        val blip = BrainEngine.advance(state, ActivitySlice(screenOffMs = tuning.minRestMs - 1))
        assertEquals(50.0, blip.rot, 1e-9)
        val rest = BrainEngine.advance(state, ActivitySlice(screenOffMs = tuning.minRestMs))
        assertTrue(rest.rot < 50.0)
    }

    @Test
    fun `neglected brain rots faster`() {
        val slice = ActivitySlice(screenOnMs = 60 * MINUTE_MS)
        val cared = BrainEngine.advance(BrainState(), slice)
        val starving = BrainEngine.advance(BrainState(nourishment = 5.0), slice)
        assertEquals(cared.rot * tuning.neglectRotMultiplier, starving.rot, 1e-9)
    }

    @Test
    fun `hostile inputs are clamped`() {
        val s = BrainEngine.advance(
            BrainState(rot = Double.NaN, nourishment = 500.0),
            ActivitySlice(screenOnMs = -5, shortVideoMs = Long.MAX_VALUE, screenOffMs = Long.MAX_VALUE, newReels = -3),
        )
        assertTrue(s.rot in 0.0..100.0 && s.nourishment in 0.0..100.0 && s.energy in 0.0..100.0)
        assertEquals(Stage.FRESH, Stage.of(Double.NaN))
        assertEquals(Stage.ROTTEN, Stage.of(1e9))
    }

    @Test
    fun `feeding respects cooldown and fullness`() {
        val now = 10 * HOUR_MS
        val hungry = BrainState(nourishment = 20.0, rot = 10.0)
        val fed = BrainEngine.feed(hungry, Food.WALNUT, now) as CareResult.Accepted
        assertEquals(55.0, fed.state.nourishment, 1e-9)
        assertEquals(9.0, fed.state.rot, 1e-9)

        val again = BrainEngine.feed(fed.state.copy(nourishment = 40.0), Food.WALNUT, now + MINUTE_MS)
        assertEquals(CareResult.TooSoon(tuning.feedCooldownMs - MINUTE_MS), again)

        val full = BrainEngine.feed(BrainState(nourishment = 95.0), Food.WALNUT, now)
        assertEquals(CareResult.Full, full)
    }

    @Test
    fun `a clock moved backwards never locks care actions`() {
        val state = BrainState(hydration = 10.0, lastWateredAt = 50 * HOUR_MS)
        assertTrue(BrainEngine.water(state, now = 1 * HOUR_MS) is CareResult.Accepted)
    }

    @Test
    fun `mood reports needs and restlessness`() {
        val calm = BrainEngine.mood(BrainState(), sessionMs = 0)
        assertEquals(Stage.FRESH, calm.stage)
        assertTrue(calm.needs.isEmpty())
        assertTrue(calm.health > 80)

        val wreck = BrainEngine.mood(BrainState(rot = 90.0, nourishment = 5.0, hydration = 5.0, energy = 5.0), sessionMs = 2 * HOUR_MS)
        assertEquals(setOf(Need.HUNGRY, Need.THIRSTY, Need.TIRED, Need.OVERSTIMULATED), wreck.needs)
        assertTrue(wreck.restlessness > 0.9)
        assertTrue(wreck.health < 20)
        assertEquals(SpeechTopic.OVERSTIMULATED, BrainVoice.ambientTopic(wreck))
        assertEquals(SpeechTopic.THIRSTY, BrainVoice.ambientTopic(wreck.copy(stage = Stage.FRESH)))
    }
}
