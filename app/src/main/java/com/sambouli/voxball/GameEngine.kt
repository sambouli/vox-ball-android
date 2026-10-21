package com.sambouli.voxball

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * "La trahison des voix"
 *
 * A two-player pong where the rackets are driven by the human voice:
 *  - only the player the ball is travelling towards can act,
 *  - a higher pitch (relative to that player's own voice) moves the racket up,
 *    a lower pitch moves it down,
 *  - a racket only returns the ball while it is "filled" with voice; silence
 *    makes it transparent and the ball goes straight through.
 *
 * Pure Kotlin, no Android dependency, so the physics can be unit tested.
 */

enum class Phase { HINTS, CALIBRATION, SERVE, PLAY, POINT, PAUSED }

object Tuning {
    const val BALLS_PER_LEVEL = 5
    const val CHARGE_GAIN = 2.6f          // full charge in ~0.4 s of voice
    const val CHARGE_DECAY = 1.9f
    const val CHARGE_TO_BOUNCE = 0.34f    // below this the racket is a ghost
    const val PADDLE_SPEED = 1.05f        // x world height per second
    const val BASE_SPEED = 0.60f          // ball speed at level 1, x world height per second
    const val SPEED_STEP = 0.12f
    const val SPEED_CAP = 2.3f
    const val PITCH_DEAD_SEMIS = 1.1f     // +-1.1 semitone around own pitch = no movement
    const val PITCH_FULL_SEMIS = 5.0f     // 5 semitones away = full speed
    const val DEFAULT_BASELINE = 175f
    const val CALIB_TARGET_SAMPLES = 26
    const val CALIB_FALLBACK_SAMPLES = 8
}

class Racket(val seat: Int) {
    var y = 0f
    var half = 1f
    var charge = 0f
    var voiced = false
    var move = 0f       // -1..1, +1 = up
    var f0 = 0f
    var baseline = 0f
    var velocity = 0f   // px/s, +1 = up
    var passFlash = 0f
}

class GameEngine {

    var w = 1600f
        private set
    var h = 900f
        private set

    val pads = arrayOf(Racket(0), Racket(1))

    var bx = 0f
    var by = 0f
    var bvx = 0f
    var bvy = 0f

    var score = intArrayOf(0, 0)
    var level = 1
    var ballsPlayed = 0
    var rally = 0

    var phase = Phase.HINTS
        private set
    var hint = 0
        private set
    var calibSeat = 0
        private set
    var calibSamples = 0
        private set
    var calibProgress = 0f
        private set
    var serveTo = 0
    var activeSeat = 0
        private set
    var banner = ""
    var bannerTimer = 0f
    var pointTimer = 0f

    /** How loud the microphone hears the room (0..1), used by the calibration screen. */
    var micLevel = 0f
    var micF0 = 0f

    var random: Random = Random.Default

    var onBounce: (() -> Unit)? = null
    var onPass: (() -> Unit)? = null
    var onPoint: (() -> Unit)? = null

    private var samples = ArrayList<Float>()
    private var pausedFrom = Phase.PLAY
    private val ballRBase = 0.0135f

    val padW: Float get() = max(10f, w * 0.014f)
    val margin: Float get() = w * 0.045f
    val leftFace: Float get() = margin + padW
    val rightFace: Float get() = w - margin - padW
    val ballR: Float get() = max(6f, h * ballRBase)

    fun resize(width: Float, height: Float) {
        w = max(1f, width)
        h = max(1f, height)
        for (p in pads) {
            p.half = h * 0.105f
            if (p.y < p.half || p.y > h - p.half) p.y = h / 2f
        }
        if (phase == Phase.HINTS) {
            bx = w / 2f
            by = h / 2f
        }
    }

    fun speedMult(): Float = min(Tuning.SPEED_CAP, 1f + Tuning.SPEED_STEP * (level - 1))

    fun serveSpeed(): Float = h * Tuning.BASE_SPEED * speedMult()

    // ---------------------------------------------------------------- hints

    fun advanceHint() {
        if (phase != Phase.HINTS) return
        hint++
        if (hint >= Content.hints.size) {
            hint = Content.hints.size - 1
            phase = Phase.CALIBRATION
            calibSeat = 0
            activeSeat = 0
            samples.clear()
            calibSamples = 0
            calibProgress = 0f
        }
    }

    // ---------------------------------------------------------- calibration

    fun skipCalibSeat() {
        if (phase != Phase.CALIBRATION) return
        samples.clear()
        pads[calibSeat].baseline = Tuning.DEFAULT_BASELINE
        nextCalibSeat()
    }

    private fun nextCalibSeat() {
        samples.clear()
        calibSamples = 0
        calibProgress = 0f
        if (calibSeat == 0) {
            calibSeat = 1
            activeSeat = 1
        } else {
            startMatch()
        }
    }

    private fun startMatch() {
        score = intArrayOf(0, 0)
        level = 1
        ballsPlayed = 0
        rally = 0
        serveTo = random.nextInt(2)
        banner = ""
        bannerTimer = 0f
        phase = Phase.SERVE
        beginServe()
    }

    // -------------------------------------------------------------- update

    fun update(dtRaw: Float, voice: VoiceFrame) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        micLevel = voice.level
        micF0 = voice.f0
        when (phase) {
            Phase.HINTS, Phase.PAUSED -> Unit
            Phase.CALIBRATION -> {
                collectCalibration(voice)
                updatePaddles(dt, voice)
            }
            Phase.SERVE -> {
                updatePaddles(dt, voice)
                serveTimerLeft -= dt
                if (serveTimerLeft <= 0f) phase = Phase.PLAY
            }
            Phase.PLAY -> {
                updatePaddles(dt, voice)
                updateBall(dt)
            }
            Phase.POINT -> {
                updatePaddles(dt, voice)
                pointTimer -= dt
                if (pointTimer <= 0f) beginServe()
            }
        }
        if (bannerTimer > 0f) bannerTimer = max(0f, bannerTimer - dt)
    }

    var serveTimerLeft = 0f
        private set

    private fun collectCalibration(voice: VoiceFrame) {
        if (voice.voiced && voice.f0 > 60f && voice.f0 < 500f) {
            samples.add(voice.f0)
            calibSamples = samples.size
            calibProgress = (samples.size.toFloat() / Tuning.CALIB_TARGET_SAMPLES).coerceIn(0f, 1f)
            if (samples.size >= Tuning.CALIB_TARGET_SAMPLES) {
                pads[calibSeat].baseline = median(samples).coerceIn(70f, 420f)
                nextCalibSeat()
            }
        }
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val n = s.size
        if (n == 0) return Tuning.DEFAULT_BASELINE
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private fun updatePaddles(dt: Float, voice: VoiceFrame) {
        for (p in pads) {
            val active = p.seat == activeSeat
            p.voiced = active && voice.voiced
            if (p.voiced) {
                p.f0 = voice.f0
                p.move = pitchToMove(voice.f0, p.baseline)
            } else {
                p.f0 = 0f
                p.move = 0f
            }
            p.charge = if (p.voiced) {
                min(1f, p.charge + Tuning.CHARGE_GAIN * dt)
            } else {
                max(0f, p.charge - Tuning.CHARGE_DECAY * dt)
            }
            p.velocity = p.move * Tuning.PADDLE_SPEED * h
            p.y = (p.y - p.velocity * dt).coerceIn(p.half, h - p.half)
            if (p.passFlash > 0f) p.passFlash = max(0f, p.passFlash - dt * 2.2f)
        }
    }

    /**
     * Maps a pitch offset (in semitones from the player's own voice) to a movement
     * factor in -1..1. Positive = up. Dead zone in the middle so a steady voice keeps
     * the racket still.
     */
    fun pitchToMove(f0: Float, baseline: Float): Float {
        if (f0 <= 0f || baseline <= 0f) return 0f
        val semis = 12f * (ln(f0 / baseline) / ln(2f))
        val a = abs(semis)
        if (a <= Tuning.PITCH_DEAD_SEMIS) return 0f
        val mag = ((a - Tuning.PITCH_DEAD_SEMIS) / (Tuning.PITCH_FULL_SEMIS - Tuning.PITCH_DEAD_SEMIS))
            .coerceIn(0f, 1f)
        val shaped = mag * mag * (3f - 2f * mag) // smoothstep, finer control near the dead zone
        return if (semis > 0f) shaped else -shaped
    }

    private fun updateBall(dt: Float) {
        val prevX = bx
        bx += bvx * dt
        by += bvy * dt

        if (by - ballR < 0f) {
            by = ballR
            bvy = abs(bvy)
        } else if (by + ballR > h) {
            by = h - ballR
            bvy = -abs(bvy)
        }

        if (bvx < 0f && prevX - ballR >= leftFace && bx - ballR < leftFace) {
            hit(seat = 0, faceX = leftFace + ballR, dirX = 1f)
        } else if (bvx > 0f && prevX + ballR <= rightFace && bx + ballR > rightFace) {
            hit(seat = 1, faceX = rightFace - ballR, dirX = -1f)
        }

        if (bx < -ballR * 3f) {
            endPoint(scorer = 1)
        } else if (bx > w + ballR * 3f) {
            endPoint(scorer = 0)
        }
    }

    private fun hit(seat: Int, faceX: Float, dirX: Float) {
        val p = pads[seat]
        val contact = abs(by - p.y) <= p.half + ballR
        if (contact && p.charge >= Tuning.CHARGE_TO_BOUNCE) {
            val off = ((by - p.y) / p.half).coerceIn(-1f, 1f)
            val speed = min(hypot(bvx, bvy) * 1.045f, h * 2.6f)
            val ang = off * 0.82f
            bx = faceX
            bvx = dirX * speed * cos(ang)
            bvy = speed * sin(ang) + p.velocity * 0.16f
            val cur = hypot(bvx, bvy)
            if (cur > 1e-3f) {
                bvx = bvx / cur * speed
                bvy = bvy / cur * speed
            }
            rally++
            onBounce?.invoke()
        } else {
            p.passFlash = 1f
            onPass?.invoke()
        }
    }

    private fun endPoint(scorer: Int) {
        score[scorer]++
        ballsPlayed++
        val nextLevel = 1 + ballsPlayed / Tuning.BALLS_PER_LEVEL
        banner = if (nextLevel > level) {
            level = nextLevel
            "NIVEAU $level"
        } else {
            if (scorer == 0) "POINT J1" else "POINT J2"
        }
        bannerTimer = 1.5f
        serveTo = 1 - scorer
        activeSeat = serveTo
        bvx = 0f
        bvy = 0f
        phase = Phase.POINT
        pointTimer = 1.5f
        onPoint?.invoke()
    }

    private fun beginServe() {
        bx = w / 2f
        by = h / 2f
        activeSeat = serveTo
        val speed = serveSpeed()
        val ang = (random.nextFloat() - 0.5f) * 0.62f
        bvx = if (serveTo == 0) -speed * cos(ang) else speed * cos(ang)
        bvy = speed * sin(ang)
        serveTimerLeft = 0.9f
        phase = Phase.SERVE
    }

    // ---------------------------------------------------------------- pause

    fun togglePause() {
        phase = when (phase) {
            Phase.PLAY, Phase.SERVE, Phase.POINT -> {
                pausedFrom = phase
                Phase.PAUSED
            }
            Phase.PAUSED -> pausedFrom
            else -> phase
        }
    }

    fun isRunning(): Boolean = phase == Phase.PLAY || phase == Phase.SERVE || phase == Phase.POINT
}
