package com.sambouli.voxball

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class PitchTest {

    private fun tone(hz: Float, rate: Int, n: Int, amp: Float = 8000f): ShortArray {
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = (amp * sin(2.0 * PI * hz * i / rate)).toInt().toShort()
        }
        return out
    }

    private fun voiceLike(f0: Float, rate: Int, n: Int, amp: Float = 9000f): ShortArray {
        // a decaying harmonic stack, closer to a real voice than a pure sine
        val out = ShortArray(n)
        val harmonics = floatArrayOf(1f, 0.6f, 0.35f, 0.2f)
        for (i in 0 until n) {
            var v = 0.0
            for ((k, g) in harmonics.withIndex()) {
                v += g * sin(2.0 * PI * f0 * (k + 1) * i / rate)
            }
            out[i] = (amp / harmonics.size * v).toInt().toShort()
        }
        return out
    }

    @Test
    fun detectsPureTones() {
        val rate = 44100
        for (hz in floatArrayOf(98f, 147f, 220f, 330f, 440f)) {
            val r = Pitch.detect(tone(hz, rate, 2048), 2048, rate)
            assertEquals("f0 for ${hz}Hz", hz.toDouble(), r.f0.toDouble(), hz * 0.02)
            assertTrue("clarity must be high for a sine at ${hz}Hz", r.clarity > 0.9f)
        }
    }

    @Test
    fun detectsHarmonicVoices() {
        val rate = 44100
        for (hz in floatArrayOf(110f, 165f, 200f, 260f)) {
            val r = Pitch.detect(voiceLike(hz, rate, 2048), 2048, rate)
            assertEquals("f0 for ${hz}Hz", hz.toDouble(), r.f0.toDouble(), hz * 0.03)
        }
    }

    @Test
    fun silenceAndNoiseAreNotVoiced() {
        val rate = 44100
        assertEquals(0f, Pitch.detect(ShortArray(2048), 2048, rate).f0, 0.0001f)
        val rnd = Random(3)
        val noise = ShortArray(2048) { (rnd.nextFloat() * 2f - 1f).times(6000).toInt().toShort() }
        val r = Pitch.detect(noise, 2048, rate)
        assertTrue("noise must not be pitched", r.clarity < 0.5f)
    }

    @Test
    fun mapsToTheRequestedRange() {
        val rate = 48000
        val low = Pitch.detect(tone(85f, rate, 2048), 2048, rate, minHz = 80f, maxHz = 450f)
        assertEquals(85.0, low.f0.toDouble(), 4.0)
        val high = Pitch.detect(tone(430f, rate, 2048), 2048, rate, minHz = 80f, maxHz = 450f)
        assertEquals(430.0, high.f0.toDouble(), 12.0)
    }
}
