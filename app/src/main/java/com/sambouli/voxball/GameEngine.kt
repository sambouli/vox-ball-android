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

enum class Phase {
    HINTS,
    CALIB_READY,
    CALIB_COUNTDOWN,
    CALIB_RECORD,
    CALIB_DONE,
    ROUND_START,
    SERVE,
    PLAY,
    POINT,
    PAUSED
}

/** What the pause overlay must return to, since pausing is allowed in several phases. */
enum class Stage { SERVE, PLAY, POINT, ROUND_START, CALIB_DONE }

object Tuning {
    const val BALLS_PER_LEVEL = 5
    const val CHARGE_GAIN = 2.6f          // full charge in ~0.4 s of voice
    const val CHARGE_DECAY = 1.9f
    const val CHARGE_TO_BOUNCE = 0.34f    // below this the racket is a ghost
    const val PADDLE_SPEED = 1.05f        // x world height per second
    const val BASE_SPEED = 0.34f          // ball speed at level 1, x world height per second
    const val SPEED_STEP = 0.055f
    const val SPEED_CAP = 1.5f
    const val BOUNCE_GAIN = 1.02f         // a returned ball only drifts faster
    const val CALIB_PAUSE = 1.6f          // after J1, "hand over" screen before J2 even starts
    const val ROUND_PAUSE = 2.4f          // rules recap before the first ball
    const val CALIB_COUNTDOWN = 3f
    const val CALIB_RECORD = 2.5f
    const val CALIB_RETRY_PAUSE = 2.2f
    const val SERVE_DELAY = 1.2f
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

    /** True once the current capture cycle has finished and we are showing its result. */
    var calibAwaitingConfirm = false
        private set

    /** Result of the last capture cycle: enough voiced frames or not. */
    var calibOk = false
        private set
    var calibCountdownLeft = 0f
        private set
    var calibRecordLeft = 0f
        private set
    var serveTo = 0
    var activeSeat = 0
        private set
    var banner = ""
    var bannerTimer = 0f
    var pointTimer = 0f
    var phaseTimer = 0f

    /** Direction search bias: a ball the receiver lets through repeats the same side,
     *  unless it comes from the side that was already served to, in which case it flips. */
    private var lastPassDir = 0

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
            startCalibration()
        }
    }

    /** Sends the ball to the given side (0 = towards J1 on the left). */
    fun serveToSide(dir: Int) {
        serveTo = if (dir < 0) 0 else 1
        activeSeat = serveTo
        beginServe()
    }

    // ---------------------------------------------------------- calibration

    private fun startCalibration() {
        calibSeat = 0
        activeSeat = 0
        lastPassDir = 0
        calibAwaitingConfirm = false
        calibOk = false
        calibCountdownLeft = Tuning.CALIB_COUNTDOWN
        calibRecordLeft = 0f
        // the player being measured owns the microphone, whoever is "active" for the ball
        phase = Phase.CALIB_READY
        resetSamples()
    }

    /** The seated player is ready: start a fresh 3-2-1 countdown. */
    fun beginCalibCountdown() {
        if (phase != Phase.CALIB_READY && phase != Phase.CALIB_COUNTDOWN) return
        resetSamples()
        calibAwaitingConfirm = false
        calibOk = false
        calibCountdownLeft = Tuning.CALIB_COUNTDOWN
        calibRecordLeft = 0f
        phase = Phase.CALIB_COUNTDOWN
    }

    /** Called by the UI "skip" control: keeps the default baseline for this player. */
    fun skipCalibSeat() {
        val stage = phase
        if (stage != Phase.CALIB_READY && stage != Phase.CALIB_COUNTDOWN &&
            stage != Phase.CALIB_RECORD && stage != Phase.CALIB_DONE
        ) return
        resetSamples()
        pads[calibSeat].baseline = Tuning.DEFAULT_BASELINE
        calibAwaitingConfirm = false
        calibOk = false
        if (calibSeat == 0) {
            // skipping J1 is still a hand-over: J2 gets his own screen after the pause
            calibSeat = 1
            activeSeat = 1
            phase = Phase.CALIB_DONE
            phaseTimer = Tuning.CALIB_PAUSE
        } else {
            finishCalibSeat()
        }
    }

    private fun resetSamples() {
        samples.clear()
        calibSamples = 0
        calibProgress = 0f
    }

    /**
     * Called once the player has read the verdict of his take. A usable take hands over to
     * the next player (with a deliberate pause); a silent one just retries this player.
     */
    fun confirmCalibResult() {
        if (phase != Phase.CALIB_RECORD || !calibAwaitingConfirm) return
        val ok = calibOk
        calibAwaitingConfirm = false
        calibOk = false
        if (!ok) {
            resetSamples()
            calibCountdownLeft = Tuning.CALIB_COUNTDOWN
            phase = Phase.CALIB_READY
            return
        }
        if (calibSeat == 0) {
            // J1 recorded: hand over through a visible pause before J2's screen, so the
            // two voices are never captured in the same breath.
            calibSeat = 1
            activeSeat = 1
            phase = Phase.CALIB_DONE
            phaseTimer = Tuning.CALIB_PAUSE
            resetSamples()
        } else {
            finishCalibSeat()
        }
    }

    private fun finishCalibSeat() {
        resetSamples()
        calibOk = false
        calibAwaitingConfirm = false
        phase = Phase.ROUND_START
        phaseTimer = Tuning.ROUND_PAUSE
    }

    /** Continue after the "À joueur 2" screen: J2 gets his own ready screen and countdown. */
    fun continueAfterCalibPause() {
        if (phase != Phase.CALIB_DONE) return
        resetSamples()
        calibOk = false
        calibAwaitingConfirm = false
        calibCountdownLeft = Tuning.CALIB_COUNTDOWN
        calibRecordLeft = 0f
        phase = Phase.CALIB_READY
    }

    fun startRound() {
        if (phase != Phase.ROUND_START) return
        score = intArrayOf(0, 0)
        level = 1
        ballsPlayed = 0
        rally = 0
        lastPassDir = 0
        banner = ""
        bannerTimer = 0f
        serveTo = random.nextInt(2)
        activeSeat = serveTo
        beginServe()
    }

    // -------------------------------------------------------------- update

    fun update(dtRaw: Float, voice: VoiceFrame) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        micLevel = voice.level
        micF0 = voice.f0
        when (phase) {
            Phase.HINTS, Phase.PAUSED -> Unit
            Phase.CALIB_READY -> updatePaddles(dt, voice, liveSeat = calibSeat)
            Phase.CALIB_COUNTDOWN -> {
                updatePaddles(dt, voice, liveSeat = calibSeat)
                calibCountdownLeft -= dt
                if (calibCountdownLeft <= 0f) {
                    calibCountdownLeft = 0f
                    calibRecordLeft = Tuning.CALIB_RECORD
                    resetSamples()
                    phase = Phase.CALIB_RECORD
                }
            }
            Phase.CALIB_RECORD -> {
                if (calibAwaitingConfirm) {
                    updatePaddles(dt, VoiceFrame())
                    // stay long enough for the player to read the verdict, then retry
                    phaseTimer -= dt
                    if (phaseTimer <= 0f) {
                        confirmCalibResult()
                    }
                    return
                }
                collectCalibration(voice)
                updatePaddles(dt, voice, liveSeat = calibSeat)
                calibRecordLeft = max(0f, calibRecordLeft - dt)
                if (calibRecordLeft <= 0f) {
                    calibAwaitingConfirm = true
                    phaseTimer = Tuning.CALIB_RETRY_PAUSE
                    val enough = calibSamples >= Tuning.CALIB_FALLBACK_SAMPLES
                    if (enough) {
                        pads[calibSeat].baseline = median(samples).coerceIn(70f, 420f)
                        calibOk = true
                    } else {
                        calibOk = false
                    }
                }
            }
            Phase.CALIB_DONE -> {
                updatePaddles(dt, VoiceFrame())
                phaseTimer -= dt
                if (phaseTimer <= 0f) continueAfterCalibPause()
            }
            Phase.ROUND_START -> {
                updatePaddles(dt, VoiceFrame())
                phaseTimer -= dt
                if (phaseTimer <= 0f) startRound()
            }
            Phase.SERVE -> {
                // the flight is already simulated during the serve delay: that is what
                // keeps activeSeat identical to "the side the ball is flying towards"
                updatePaddles(dt, voice)
                updateBall(dt, judgePoint = false)
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
                if (pointTimer <= 0f) pickServe()
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
            val span = Tuning.CALIB_RECORD * 21f
            calibProgress = (samples.size / span).coerceIn(0f, 1f)
        }
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        val n = s.size
        if (n == 0) return Tuning.DEFAULT_BASELINE
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
    }

    private fun updatePaddles(dt: Float, voice: VoiceFrame, liveSeat: Int = activeSeat) {
        for (p in pads) {
            val active = p.seat == liveSeat
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

    private fun updateBall(dt: Float, judgePoint: Boolean = true) {
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

        if (!judgePoint) {
            // serve delay: clamp only, the point cannot be lost before the serve window ends
            bx = bx.coerceIn(-ballR * 2.9f, w + ballR * 2.9f)
            return
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
            val speed = min(hypot(bvx, bvy) * Tuning.BOUNCE_GAIN, h * Tuning.SPEED_CAP)
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
            lastPassDir = 0
            onBounce?.invoke()
        } else {
            p.passFlash = 1f
            lastPassDir = dirX.toInt()
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
        // Strict alternation: the next ball goes to the side that was NOT just attacked.
        // Without this, a player who misses twice in a row gets served at forever.
        val receiver = if (bx < w / 2f) 0 else 1
        serveTo = 1 - receiver
        activeSeat = serveTo
        bvx = 0f
        bvy = 0f
        phase = Phase.POINT
        pointTimer = 1.5f
        onPoint?.invoke()
    }

    /** Serves towards the seat decided by [endPoint]: the two sides strictly alternate. */
    private fun pickServe() {
        serveToSide(if (serveTo == 0) -1 else 1)
    }

    private fun beginServe() {
        bx = w / 2f
        by = h / 2f
        activeSeat = serveTo
        val speed = serveSpeed()
        val ang = (random.nextFloat() - 0.5f) * 0.50f
        bvx = if (serveTo == 0) -speed * cos(ang) else speed * cos(ang)
        bvy = speed * sin(ang)
        serveTimerLeft = Tuning.SERVE_DELAY
        phase = Phase.SERVE
    }

    // ---------------------------------------------------------------- pause

    fun togglePause() {
        phase = when (phase) {
            Phase.PLAY, Phase.SERVE, Phase.POINT, Phase.ROUND_START, Phase.CALIB_DONE -> {
                pausedFrom = phase
                Phase.PAUSED
            }
            Phase.PAUSED -> pausedFrom
            else -> phase
        }
    }

    /** What to redraw behind the pause overlay. */
    val resumeStage: Stage
        get() = when (phase) {
            Phase.PAUSED -> when (pausedFrom) {
                Phase.ROUND_START -> Stage.ROUND_START
                Phase.CALIB_DONE -> Stage.CALIB_DONE
                else -> if (pausedFrom == Phase.SERVE) Stage.SERVE else Stage.PLAY
            }
            Phase.ROUND_START -> Stage.ROUND_START
            Phase.CALIB_DONE -> Stage.CALIB_DONE
            Phase.SERVE -> Stage.SERVE
            Phase.PLAY -> Stage.PLAY
            Phase.POINT -> Stage.POINT
            else -> Stage.SERVE
        }

    /** Restart the current point from the centre, used when coming back from a pause. */
    fun restartCurrentPoint() {
        if (phase == Phase.PAUSED) phase = Phase.PLAY
        beginServe()
    }

    fun isRunning(): Boolean =
        phase == Phase.PLAY || phase == Phase.SERVE || phase == Phase.POINT
}
