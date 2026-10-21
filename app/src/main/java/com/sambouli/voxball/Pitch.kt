package com.sambouli.voxball

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Normalised-autocorrelation pitch detector, pure Kotlin so it can be unit tested.
 *
 * The signal is de-meaned, then for every lag in the useful voice range we compute
 * r(lag) = sum(x[i] * x[i+lag]) / sqrt(sum x[i]^2 * sum x[i+lag]^2), which is 1 for a
 * perfectly periodic signal. The best lag is refined by parabolic interpolation, and a
 * small guard prefers the shortest near-best lag to avoid the classic octave-down error.
 */
object Pitch {

    data class Result(val f0: Float, val clarity: Float)

    val NONE = Result(0f, 0f)

    fun detect(
        buf: ShortArray,
        n: Int,
        sampleRate: Int,
        minHz: Float = 80f,
        maxHz: Float = 450f
    ): Result {
        if (sampleRate <= 0 || n < 256) return NONE
        var sum = 0.0
        for (i in 0 until n) sum += buf[i]
        val mean = sum / n
        val x = FloatArray(n)
        var energy = 0.0
        for (i in 0 until n) {
            val v = buf[i] - mean
            x[i] = v.toFloat()
            energy += v * v
        }
        if (energy / n < 1e-3) return NONE // essentially silence
        return detectCentered(x, sampleRate, minHz, maxHz)
    }

    fun detectCentered(
        x: FloatArray,
        sampleRate: Int,
        minHz: Float = 80f,
        maxHz: Float = 450f
    ): Result {
        val n = x.size
        val minLag = (sampleRate / maxHz).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / minHz).toInt()
        val window = n - maxLag
        if (window < 200 || maxLag <= minLag + 2) return NONE

        val count = maxLag - minLag + 1
        val corr = FloatArray(count)
        var best = 0f
        var bestIdx = -1
        for (lag in minLag..maxLag) {
            var s = 0f
            var e1 = 0f
            var e2 = 0f
            var i = 0
            while (i < window) {
                val a = x[i]
                val b = x[i + lag]
                s += a * b
                e1 += a * a
                e2 += b * b
                i++
            }
            val denom = sqrt(e1 * e2)
            val r = if (denom <= 1e-6f) 0f else s / denom
            corr[lag - minLag] = r
            if (r > best) {
                best = r
                bestIdx = lag - minLag
            }
        }
        if (bestIdx < 0 || best < 0.5f) return Result(0f, best)

        // Octave guard. The strongest lag is often a multiple of the real period (the
        // classic octave-down error), so repeatedly try the true submultiples: a nearby
        // lag is never accepted, which is what kept pure tones off-pitch before.
        var chosen = bestIdx
        val bestLag = minLag + bestIdx
        var chosenLag = bestLag
        var rounds = 0
        while (rounds++ < 4) {
            var moved = false
            for (div in intArrayOf(2, 3)) {
                val target = chosenLag / div
                if (target < minLag) continue
                var local = target - minLag
                val lo = maxOf(0, local - 2)
                val hi = minOf(count - 1, local + 2)
                for (k in lo..hi) if (corr[k] > corr[local]) local = k
                if (corr[local] >= best * 0.90f) {
                    chosen = local
                    chosenLag = local + minLag
                    moved = true
                    break
                }
            }
            if (!moved) break
        }
        val clarity = corr[chosen]

        val y0 = corr[chosen]
        val ym = if (chosen > 0) corr[chosen - 1] else y0
        val yp = if (chosen < count - 1) corr[chosen + 1] else y0
        val denom = 2f * (ym - 2f * y0 + yp)
        val shift = if (abs(denom) > 1e-6f) (ym - yp) / denom else 0f
        val lag = minLag + chosen + shift.coerceIn(-0.5f, 0.5f)
        if (lag <= 0f) return Result(0f, clarity)
        return Result(sampleRate / lag, clarity)
    }
}
