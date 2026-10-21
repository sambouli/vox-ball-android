package com.sambouli.voxball

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One analysed microphone frame. */
data class VoiceFrame(
    val voiced: Boolean = false,
    val f0: Float = 0f,
    val level: Float = 0f,   // 0..1, loudness above the tracked noise floor
    val clarity: Float = 0f  // 0..1, normalised autocorrelation peak
)

/**
 * Captures the microphone and reports pitch + loudness about 20 times per second.
 *
 * Sample rates are probed in order because not every device can initialise a 22 kHz
 * capture; the lag range is derived from the negotiated rate, so the pitch readout is
 * the same whatever gets picked. The pitch maths itself lives in [Pitch] (pure Kotlin).
 */
class VoiceAnalyzer {

    @Volatile
    var frame: VoiceFrame = VoiceFrame()
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var sampleRate: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    private var frameSize = 2048
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var floorDb = -62f

    fun start(): Boolean {
        if (running) return true
        var lastError = ""
        for ((rate, size) in CANDIDATES) {
            val minBuf = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                lastError = "rate $rate: getMinBufferSize() = $minBuf"
                continue
            }
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    max(minBuf, size * 4)
                )
            } catch (t: Throwable) {
                lastError = "rate $rate: ${t.message ?: t.javaClass.simpleName}"
                continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "rate $rate: non initialisé"
                rec.release()
                continue
            }
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                lastError = "rate $rate: ${t.message ?: t.javaClass.simpleName}"
                rec.release()
                continue
            }
            record = rec
            sampleRate = rate
            frameSize = size
            running = true
            error = null
            thread = Thread({ loop(rec) }, "vox-voice").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return true
        }
        error = lastError.ifEmpty { "aucune configuration audio disponible" }
        return false
    }

    fun stop() {
        running = false
        val rec = record
        record = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        thread?.join(400)
        thread = null
        try {
            rec?.release()
        } catch (_: Throwable) {
        }
        frame = VoiceFrame()
    }

    private fun loop(rec: AudioRecord) {
        val buf = ShortArray(frameSize)
        while (running) {
            val n = try {
                rec.read(buf, 0, frameSize)
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                -1
            }
            if (n <= 0) {
                if (n < 0) break
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                }
                continue
            }
            try {
                val (analysed, newFloor) = analyseWith(buf, n, sampleRate, floorDb)
                floorDb = newFloor
                frame = analysed
            } catch (t: Throwable) {
                Log.w("VoxBall", "analyse failed", t)
            }
        }
    }

    companion object {
        /**
         * (sample rate, frame size) tried in order. 2048 samples at 44.1 kHz is 46 ms,
         * which holds two periods of an 80 Hz voice, the lowest pitch we care about.
         */
        val CANDIDATES = listOf(
            44100 to 2048,
            48000 to 2048,
            22050 to 1024,
            16000 to 1024
        )

        /** Pure analysis of one PCM frame: loudness gate plus pitch. Testable without Android. */
        fun analyse(buf: ShortArray, n: Int, sampleRate: Int): VoiceFrame =
            analyseWith(buf, n, sampleRate, -62f).first

        private fun analyseWith(
            buf: ShortArray,
            n: Int,
            sampleRate: Int,
            floor: Float
        ): Pair<VoiceFrame, Float> {
            var sum = 0.0
            for (i in 0 until n) sum += buf[i]
            val mean = sum / n
            var energy = 0.0
            for (i in 0 until n) {
                val v = buf[i] - mean
                energy += v * v
            }
            val rms = sqrt(energy / n)
            val db = (20.0 * log10(max(rms, 1e-6) / 32768.0)).toFloat()

            // Leaky minimum tracker: follows the room noise, rises slowly so a loud
            // burst cannot permanently desensitise the gate.
            val floorDb = if (db < floor) floor * 0.85f + db * 0.15f else min(floor + 0.08f, -38f)
            val gate = max(floorDb + 9f, -52f)
            val level = ((db - gate) / 22f).coerceIn(0f, 1f)

            val pitch = Pitch.detect(buf, n, sampleRate)
            val voiced = pitch.clarity > 0.55f && db > gate && pitch.f0 > 60f
            val frame = VoiceFrame(
                voiced = voiced,
                f0 = if (voiced) pitch.f0 else 0f,
                level = level,
                clarity = pitch.clarity
            )
            return frame to floorDb
        }
    }
}
