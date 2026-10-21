package com.sambouli.voxball

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** The "is somebody actually speaking?" decision, tested without a microphone. */
class VoiceGateTest {

    private fun voice(f0: Float, rate: Int, n: Int, amp: Float): ShortArray {
        val out = ShortArray(n)
        val harmonics = floatArrayOf(1f, 0.6f, 0.35f)
        for (i in 0 until n) {
            var v = 0.0
            for ((k, g) in harmonics.withIndex()) {
                v += g * sin(2.0 * PI * f0 * (k + 1) * i / rate)
            }
            out[i] = (amp * v / harmonics.size).toInt().toShort()
        }
        return out
    }

    @Test
    fun aSungNoteIsVoiced() {
        val rate = 44100
        val frame = VoiceAnalyzer.analyse(voice(200f, rate, 2048, 12000f), 2048, rate)
        assertTrue("should be voiced", frame.voiced)
        assertTrue("clarity", frame.clarity > 0.6f)
        assertTrue("pitch", frame.f0 > 180f && frame.f0 < 220f)
        assertTrue("energy", frame.level > 0.5f)
    }

    @Test
    fun silenceIsNotVoiced() {
        val rate = 44100
        val frame = VoiceAnalyzer.analyse(ShortArray(2048), 2048, rate)
        assertFalse(frame.voiced)
        assertTrue(frame.f0 == 0f)
    }

    @Test
    fun aWhisperHasNoPitch() {
        // breath noise: loud enough, but no periodic structure
        val rate = 44100
        val rnd = java.util.Random(11)
        val buf = ShortArray(2048) { ((rnd.nextFloat() * 2f - 1f) * 9000f).toInt().toShort() }
        val frame = VoiceAnalyzer.analyse(buf, 2048, rate)
        assertFalse("noise must not pass as voice", frame.voiced)
        assertTrue("but it is loud", frame.level > 0.3f)
    }
}
