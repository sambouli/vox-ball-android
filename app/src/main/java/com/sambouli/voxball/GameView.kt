package com.sambouli.voxball

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

object Palette {
    const val BG_TOP = 0xFF0A0B10.toInt()
    const val BG_BOTTOM = 0xFF181A23.toInt()
    const val TEXT = 0xFFF2F3F7.toInt()
    const val DIM = 0xFF7A7C88.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val GREY = 0xFF86889A.toInt()
    const val ACCENT = 0xFF5AC8FA.toInt()
    const val LINE = 0x1FFFFFFF
    const val SHADE = 0x33FFFFFF
}

/**
 * Renders the match and owns the game loop, the microphone analyser and the touch
 * handling. All gameplay lives in [GameEngine]; this class is only drawing + input.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    var onRequestPermission: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    private val engine = GameEngine()
    private val analyzer = VoiceAnalyzer()

    @Volatile
    private var micGranted = false
    private var micError: String? = null

    @Volatile
    private var running = false
    private var loopThread: Thread? = null

    @Volatile
    private var crashReport: String? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tfBold = Typeface.create("sans-serif", Typeface.BOLD)
    private val tfReg = Typeface.create("sans-serif", Typeface.NORMAL)
    private val tfCond = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val bgPaint = Paint()
    private var bgShader: Shader? = null
    private var bgW = 0f
    private var bgH = 0f

    private val pauseR = RectF()
    private val resumeR = RectF()
    private val quitR = RectF()
    private val grantR = RectF()
    private val skipR = RectF()
    private val micRetryR = RectF()

    private lateinit var dash: DashPathEffect
    private var t = 0f

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (t: Throwable) {
        null
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        engine.onBounce = { buzz(9, 70) }
        engine.onPass = { buzz(20, 140) }
        engine.onPoint = { buzz(45, 200) }
        dash = DashPathEffect(floatArrayOf(dp(7f), dp(7f)), 0f)
        crashReport = readCrashReport()
    }

    private fun readCrashReport(): String? = try {
        val f = context.getFileStreamPath(VoxBallApp.CRASH_FILE)
        if (f.exists()) f.readText().take(2400) else null
    } catch (_: Throwable) {
        null
    }

    private fun clearCrashReport() {
        crashReport = null
        try {
            context.getFileStreamPath(VoxBallApp.CRASH_FILE).delete()
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------ lifecycle

    fun setMicGranted(granted: Boolean) {
        micGranted = granted
        if (granted) startVoice()
    }

    fun startVoice() {
        if (!micGranted) return
        if (analyzer.start()) {
            micError = null
        } else {
            micError = analyzer.error ?: "micro indisponible"
        }
    }

    fun pauseGame() {
        pauseRequested = false
        stopLoop()
        analyzer.stop()
    }

    /** Pause requested by the game loop, on the render thread, never from the UI thread. */
    @Volatile
    private var pauseRequested = false

    fun autoPauseIfRunning() {
        if (engine.isRunning() || engine.phase == Phase.ROUND_START || engine.phase == Phase.CALIB_DONE) {
            pauseRequested = true
        }
    }

    /**
     * Leaving the pause overlay. If the microphone stalled in the meantime (the analyser
     * thread died, or it was never restarted), restart it; if the voice is still missing
     * after a moment, send the point back to the centre instead of letting it be lost.
     */
    private fun onResumeClicked() {
        engine.togglePause()
        startVoice()
        postDelayed({
            if (engine.isRunning() && !analyzer.running) {
                startVoice()
                if (!analyzer.running) engine.restartCurrentPoint()
            }
        }, 600)
    }

    fun resumeGame() {
        if (holder.surface?.isValid == true) startLoop()
        startVoice()
    }

    private fun startLoop() {
        if (loopThread?.isAlive == true) return
        running = true
        loopThread = Thread(this, "vox-render").apply { start() }
    }

    private fun stopLoop() {
        running = false
        loopThread?.join(700)
        loopThread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine.resize(width.toFloat(), height.toFloat())
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.resize(width.toFloat(), height.toFloat())
        bgShader = null
        layoutRects(width.toFloat(), height.toFloat())
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.1f) dt = 0.1f
            t += dt

            val voice = analyzer.frame
            if (pauseRequested && engine.isRunning()) {
                pauseRequested = false
                engine.togglePause()
            }
            engine.update(dt, voice)

            val c = try {
                holder.lockCanvas()
            } catch (t: Throwable) {
                null
            }
            if (c != null) {
                try {
                    drawFrame(c, voice)
                } catch (t: Throwable) {
                    // never let a draw error kill the loop
                } finally {
                    try {
                        holder.unlockCanvasAndPost(c)
                    } catch (_: Throwable) {
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - now) / 1_000_000L
            val sleep = 16L - elapsedMs
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- input

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_DOWN) return true
        if (crashReport != null) {
            clearCrashReport()
            return true
        }
        if (!micGranted) {
            onRequestPermission?.invoke()
            return true
        }
        val x = e.x
        val y = e.y
        when (engine.phase) {
            Phase.HINTS -> engine.advanceHint()
            Phase.CALIB_READY, Phase.CALIB_COUNTDOWN -> when {
                skipR.contains(x, y) -> engine.skipCalibSeat()
                else -> engine.beginCalibCountdown()
            }
            Phase.CALIB_RECORD -> {
                if (skipR.contains(x, y) || engine.calibAwaitingConfirm) engine.confirmCalibResult()
            }
            Phase.CALIB_DONE -> engine.continueAfterCalibPause()
            Phase.ROUND_START -> engine.startRound()
            Phase.PAUSED -> when {
                resumeR.contains(x, y) -> onResumeClicked()
                quitR.contains(x, y) -> onExit?.invoke()
            }
            else -> when {
                micRetryR.contains(x, y) -> startVoice()
                pauseR.contains(x, y) -> engine.togglePause()
                else -> Unit
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // --------------------------------------------------------------- layout

    private fun layoutRects(w: Float, h: Float) {
        val s = h * 0.045f
        pauseR.set(w - h * 0.10f, h * 0.03f, w - h * 0.03f, h * 0.03f + h * 0.07f)
        skipR.set(w * 0.66f, h * 0.045f, w * 0.95f, h * 0.045f + h * 0.075f)
        resumeR.set(w / 2f - w * 0.16f, h * 0.46f, w / 2f + w * 0.16f, h * 0.46f + h * 0.13f)
        quitR.set(w / 2f - w * 0.16f, h * 0.63f, w / 2f + w * 0.16f, h * 0.63f + h * 0.13f)
        grantR.set(w / 2f - w * 0.18f, h * 0.66f, w / 2f + w * 0.18f, h * 0.66f + h * 0.12f)
        if (s <= 0f) return
    }

    // --------------------------------------------------------------- drawing

    private fun drawFrame(c: Canvas, voice: VoiceFrame) {
        drawBackground(c)
        val crash = crashReport
        if (crash != null) {
            drawCrash(c, crash)
            return
        }
        when (engine.phase) {
            Phase.HINTS -> {
                drawHints(c)
                drawMicMeter(c, voice, height * 0.92f)
            }
            Phase.CALIB_READY, Phase.CALIB_COUNTDOWN, Phase.CALIB_RECORD -> {
                drawArena(c, showBall = false)
                drawCalibration(c, voice)
            }
            Phase.PAUSED -> {
                drawArena(c, showBall = true)
                drawPause(c)
            }
            Phase.CALIB_DONE -> {
                drawArena(c, showBall = false)
                drawCalibDone(c)
            }
            Phase.ROUND_START -> {
                drawArena(c, showBall = false)
                drawRoundStart(c)
            }
            else -> {
                drawArena(c, showBall = true)
                drawBanner(c)
            }
        }
        if (!micGranted) drawPermission(c)
    }

    private fun drawBackground(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (bgShader == null || bgW != w || bgH != h) {
            bgShader = LinearGradient(
                0f, 0f, 0f, h,
                Palette.BG_TOP, Palette.BG_BOTTOM, Shader.TileMode.CLAMP
            )
            bgW = w
            bgH = h
        }
        bgPaint.shader = bgShader
        c.drawRect(0f, 0f, w, h, bgPaint)
    }

    private fun drawArena(c: Canvas, showBall: Boolean) {
        val w = width.toFloat()
        val h = height.toFloat()
        val e = engine

        // centre line
        stroke.color = Palette.LINE
        stroke.strokeWidth = dp(2f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(h * 0.02f, h * 0.02f), 0f)
        c.drawLine(w / 2f, h * 0.02f, w / 2f, h * 0.98f, stroke)
        stroke.pathEffect = null

        drawPaddle(c, 0)
        drawPaddle(c, 1)

        if (showBall && e.phase != Phase.HINTS && (e.bx != 0f || e.by != 0f)) {
            fill.color = 0x33FFFFFF
            c.drawCircle(e.bx, e.by, e.ballR * 2.1f, fill)
            fill.color = 0x55FFFFFF
            c.drawCircle(e.bx, e.by, e.ballR * 1.5f, fill)
            fill.color = Color.WHITE
            c.drawCircle(e.bx, e.by, e.ballR, fill)
        }

        // scores
        val scoreY = h * 0.155f
        text(c, e.score[0].toString(), w * 0.26f, scoreY, h * 0.10f, Palette.TEXT, bold = true)
        text(c, e.score[1].toString(), w * 0.74f, scoreY, h * 0.10f, Palette.TEXT, bold = true)
        text(c, "J1", w * 0.26f, scoreY + h * 0.05f, h * 0.028f, Palette.DIM, bold = true)
        text(c, "J2", w * 0.74f, scoreY + h * 0.05f, h * 0.028f, Palette.DIM, bold = true)

        text(c, Content.level(e.level), w / 2f, h * 0.075f, h * 0.032f, Palette.DIM, bold = true)

        // whose turn it is
        if (e.isRunning()) {
            val seat = e.activeSeat
            val cx = if (seat == 0) w * 0.12f else w * 0.88f
            chevron(c, cx, h * 0.30f, h * 0.035f, if (seat == 0) -1f else 1f)
            text(
                c, Content.yourTurn(seat), cx, h * 0.245f, h * 0.026f,
                Palette.RED, bold = true
            )
        }

        if (e.phase == Phase.SERVE) {
            val pulse = 0.55f + 0.45f * kotlin.math.sin(t * 6f)
            tp.alpha = (pulse * 255).toInt()
            text(c, Content.SERVE, w / 2f, h * 0.52f, h * 0.05f, Palette.TEXT, bold = true)
            tp.alpha = 255
        }

        drawMicMeter(c, analyzer.frame, h * 0.985f)

        // microphone health: a stalled capture looks exactly like two silent players, so
        // say it out loud instead of leaving the game unplayable with no explanation.
        if (!analyzer.running) {
            text(
                c, "⚠ MICRO À L'ARRÊT — touchez ici pour le relancer",
                w / 2f, h * 0.93f, h * 0.030f, Palette.RED, bold = true
            )
            micRetryR.set(w * 0.2f, h * 0.90f, w * 0.8f, h * 0.95f)
        } else {
            micRetryR.setEmpty()
        }

        // pause button
        text(c, "II", pauseR.centerX(), pauseR.centerY() + h * 0.022f, h * 0.042f, Palette.DIM, bold = true)
    }

    private fun drawPaddle(c: Canvas, seat: Int) {
        val e = engine
        val p = e.pads[seat]
        val x = if (seat == 0) e.margin else e.w - e.margin - e.padW
        val top = p.y - p.half
        val rect = RectF(x, top, x + e.padW, top + p.half * 2f)
        val r = e.padW / 2f
        val active = e.isRunning() && seat == e.activeSeat

        val alpha = if (active) 255 else 70
        val solid = p.charge >= Tuning.CHARGE_TO_BOUNCE

        // outline (dashed when the racket is empty: the ball would go through)
        stroke.color = Palette.GREY
        stroke.alpha = alpha
        stroke.strokeWidth = e.padW * 0.16f
        stroke.pathEffect = if (solid) null else dash
        c.drawRoundRect(rect, r, r, stroke)
        stroke.pathEffect = null

        // fill = amount of voice in the racket
        val fillH = p.half * 2f * p.charge
        if (fillH > 2f) {
            val fr = RectF(rect.left, p.y - fillH / 2f, rect.right, p.y + fillH / 2f)
            fill.color = if (p.voiced) Palette.RED else Palette.GREY
            fill.alpha = alpha
            c.drawRoundRect(fr, r, r, fill)
            fill.alpha = 255
        }

        // pass-through flash
        if (p.passFlash > 0f) {
            stroke.color = Palette.RED
            stroke.alpha = (p.passFlash * 200f).toInt().coerceIn(0, 255)
            stroke.strokeWidth = e.padW * 0.5f
            c.drawRoundRect(
                RectF(rect.left - e.padW, rect.top, rect.right + e.padW, rect.bottom),
                r, r, stroke
            )
            stroke.alpha = 255
        }

        // pitch indicator: where the voice pushes, relative to the racket centre
        if (active && p.voiced) {
            val cx = if (seat == 0) rect.left - e.padW * 1.4f else rect.right + e.padW * 1.4f
            val my = p.y - p.move * p.half * 0.9f
            stroke.color = Palette.RED
            stroke.strokeWidth = dp(2f)
            stroke.alpha = 120
            c.drawLine(cx, p.y - p.half, cx, p.y + p.half, stroke)
            stroke.alpha = 255
            val arrow = Path()
            val sz = e.padW * 0.8f
            val dir = if (p.move >= 0f) -1f else 1f
            arrow.moveTo(cx - sz, my - dir * sz)
            arrow.lineTo(cx, my)
            arrow.lineTo(cx + sz, my - dir * sz)
            c.drawPath(arrow, stroke)
        }
    }

    private fun drawBanner(c: Canvas) {
        val e = engine
        if (e.bannerTimer <= 0f || e.banner.isEmpty()) return
        val a = min(1f, e.bannerTimer / 0.4f)
        tp.alpha = (a * 255).toInt()
        text(c, e.banner, width / 2f, height * 0.42f, height * 0.075f, Palette.RED, bold = true)
        tp.alpha = 255
    }

    private fun drawHints(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val hint = Content.hints[engine.hint]

        text(c, Content.TITLE, w / 2f, h * 0.16f, h * 0.062f, Palette.TEXT, bold = true)
        text(c, Content.SUBTITLE, w / 2f, h * 0.215f, h * 0.024f, Palette.DIM, bold = false)
        text(c, hint.tag, w / 2f, h * 0.31f, h * 0.028f, Palette.RED, bold = true)

        val lines = wrapText(hint.body, tfReg, h * 0.040f, w * 0.74f)
        var y = h * 0.42f
        for (line in lines) {
            text(c, line, w / 2f, y, h * 0.040f, Palette.TEXT)
            y += h * 0.058f
        }

        // page dots
        val n = Content.hints.size
        val gap = h * 0.026f
        val startX = w / 2f - (n - 1) * gap / 2f
        for (i in 0 until n) {
            fill.color = if (i == engine.hint) Palette.RED else Palette.SHADE
            c.drawCircle(startX + i * gap, h * 0.80f, h * 0.006f, fill)
        }

        val pulse = 0.5f + 0.5f * kotlin.math.sin(t * 3.2f)
        tp.alpha = (pulse * 255).toInt()
        text(c, Content.TAP, w / 2f, h * 0.875f, h * 0.028f, Palette.TEXT, bold = true)
        tp.alpha = 255
    }

    private fun drawCalibration(c: Canvas, voice: VoiceFrame) {
        val w = width.toFloat()
        val h = height.toFloat()
        val e = engine

        val scrim = Paint()
        scrim.color = 0xCC000000.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        text(c, Content.calibTitle(e.calibSeat), w / 2f, h * 0.20f, h * 0.052f, Palette.TEXT, bold = true)

        when (e.phase) {
            Phase.CALIB_READY -> {
                text(c, "le téléphone est à vous, seul.", w / 2f, h * 0.30f, h * 0.034f, Palette.DIM)
                text(
                    c, "Fredonnez « mmmm » à votre hauteur naturelle pendant 2 secondes.",
                    w / 2f, h * 0.40f, h * 0.038f, Palette.TEXT
                )
                text(c, "TOUCHEZ POUR COMMENCER", w / 2f, h * 0.55f, h * 0.044f, Palette.RED, bold = true)
                text(c, "un décompte 3-2-1 vous laisse le temps de respirer", w / 2f, h * 0.63f, h * 0.026f, Palette.DIM)
            }
            Phase.CALIB_COUNTDOWN -> {
                val n = kotlin.math.ceil(e.calibCountdownLeft).toInt().coerceAtLeast(1)
                val frac = e.calibCountdownLeft - kotlin.math.floor(e.calibCountdownLeft)
                val size = h * (0.20f + 0.06f * frac)
                text(c, n.toString(), w / 2f, h * 0.52f, size, Palette.RED, bold = true)
                text(c, "respirez…", w / 2f, h * 0.66f, h * 0.034f, Palette.DIM)
            }
            Phase.CALIB_RECORD -> {
                if (e.calibAwaitingConfirm) {
                    text(
                        c,
                        if (e.calibOk) "VOIX ENREGISTRÉE" else "RIEN ENTENDU",
                        w / 2f, h * 0.40f, h * 0.055f,
                        if (e.calibOk) Palette.RED else Palette.ACCENT, bold = true
                    )
                    text(
                        c,
                        if (e.calibOk) "merci — on passe à la suite" else "fredonnez plus fort, on recommence",
                        w / 2f, h * 0.50f, h * 0.034f, Palette.TEXT
                    )
                    text(c, "TOUCHEZ POUR CONTINUER", w / 2f, h * 0.62f, h * 0.028f, Palette.DIM, bold = true)
                } else {
                    // recording: progress ring of the 2.5 s window
                    val r = h * 0.13f
                    stroke.color = 0x33FFFFFF
                    stroke.strokeWidth = h * 0.014f
                    stroke.pathEffect = null
                    c.drawCircle(w / 2f, h * 0.42f, r, stroke)
                    val done = 1f - (e.calibRecordLeft / Tuning.CALIB_RECORD).coerceIn(0f, 1f)
                    stroke.color = if (voice.voiced) Palette.RED else Palette.GREY
                    val oval = RectF(w / 2f - r, h * 0.42f - r, w / 2f + r, h * 0.42f + r)
                    c.drawArc(oval, -90f, 360f * done, false, stroke)
                    if (voice.voiced) {
                        fill.color = 0x33E5484D
                        c.drawCircle(w / 2f, h * 0.42f, r * 0.8f, fill)
                    }
                    text(c, "CHANTez", w / 2f, h * 0.44f, h * 0.040f, Palette.TEXT, bold = true)
                    val f0 = if (voice.voiced) String.format("%.0f Hz", voice.f0) else "—"
                    text(c, f0, w / 2f, h * 0.63f, h * 0.036f, Palette.ACCENT, bold = true)
                }
            }
            else -> Unit
        }

        // mic level
        val mw = w * 0.30f
        val mx = w / 2f - mw / 2f
        val my = h * 0.74f
        val mh = h * 0.014f
        fill.color = 0x22FFFFFF
        c.drawRoundRect(RectF(mx, my, mx + mw, my + mh), mh / 2f, mh / 2f, fill)
        fill.color = if (voice.voiced) Palette.RED else Palette.GREY
        val lvl = voice.level.coerceIn(0f, 1f)
        if (lvl > 0.01f) {
            c.drawRoundRect(RectF(mx, my, mx + mw * lvl, my + mh), mh / 2f, mh / 2f, fill)
        }

        if (e.phase != Phase.CALIB_DONE) {
            stroke.color = Palette.DIM
            stroke.strokeWidth = dp(1.5f)
            c.drawRoundRect(skipR, dp(6f), dp(6f), stroke)
            text(c, Content.SKIP, skipR.centerX(), skipR.centerY() + h * 0.011f, h * 0.024f, Palette.DIM)
        }
    }

    private fun drawPause(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scrim = Paint()
        scrim.color = 0xE6000000.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        text(c, Content.PAUSE, w / 2f, h * 0.34f, h * 0.075f, Palette.TEXT, bold = true)

        fill.color = Palette.RED
        c.drawRoundRect(resumeR, h * 0.02f, h * 0.02f, fill)
        text(c, Content.RESUME, resumeR.centerX(), resumeR.centerY() + h * 0.018f, h * 0.045f, Palette.TEXT, bold = true)

        stroke.color = Palette.DIM
        stroke.strokeWidth = dp(2f)
        c.drawRoundRect(quitR, h * 0.02f, h * 0.02f, stroke)
        text(c, Content.QUIT, quitR.centerX(), quitR.centerY() + h * 0.016f, h * 0.040f, Palette.DIM)
    }

    private fun drawCalibDone(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scrim = Paint()
        scrim.color = 0xF0000000.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        val a = 0.6f + 0.4f * kotlin.math.sin(t * 5f)
        tp.alpha = (a * 255).toInt()
        text(c, "JOUEUR 1 : C'EST FAIT", w / 2f, h * 0.34f, h * 0.040f, Palette.DIM, bold = true)
        tp.alpha = 255
        text(c, "À JOUEUR 2", w / 2f, h * 0.46f, h * 0.075f, Palette.TEXT, bold = true)
        text(
            c, "passez le téléphone, puis fredonnez « mmmm »", w / 2f, h * 0.55f, h * 0.034f, Palette.DIM
        )

        val left = engine.phaseTimer
        text(
            c, "dans ${left.toInt() + 1}", w / 2f, h * 0.66f, h * 0.032f, Palette.RED, bold = true
        )
        text(c, Content.TAP, w / 2f, h * 0.86f, h * 0.028f, Palette.TEXT, bold = true)
    }

    private fun drawRoundStart(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scrim = Paint()
        scrim.color = 0xE6000000.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        text(c, "PRÊTS ?", w / 2f, h * 0.24f, h * 0.070f, Palette.TEXT, bold = true)

        val rules = listOf(
            "VOIX AIGUË → la raquette monte",
            "VOIX GRAVE → la raquette descend",
            "PARLEZ pour remplir la raquette en ROUGE",
            "Silence = raquette grise, la balle passe"
        )
        var y = h * 0.38f
        for (r in rules) {
            text(c, r, w / 2f, y, h * 0.038f, Palette.TEXT)
            y += h * 0.065f
        }
        text(
            c, "J1 est la raquette de GAUCHE, J2 celle de DROITE",
            w / 2f, y + h * 0.02f, h * 0.030f, Palette.RED, bold = true
        )

        val left = engine.phaseTimer
        text(
            c, "départ dans ${left.toInt() + 1}", w / 2f, h * 0.80f, h * 0.034f, Palette.ACCENT, bold = true
        )
        text(c, Content.TAP, w / 2f, h * 0.90f, h * 0.028f, Palette.DIM, bold = true)
    }

    private fun drawCrash(c: Canvas, report: String) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scrim = Paint()
        scrim.color = 0xFF140A0A.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        text(c, "DERNIER CRASH", w / 2f, h * 0.11f, h * 0.042f, Palette.RED, bold = true)
        text(
            c, "touchez l'écran pour effacer — la trace est aussi dans logcat",
            w / 2f, h * 0.165f, h * 0.024f, Palette.DIM
        )

        val size = h * 0.023f
        var y = h * 0.24f
        for (line in report.split('\n')) {
            for (part in wrapText(line, tfReg, size, w * 0.94f)) {
                if (y > h * 0.96f) return
                text(c, part, w * 0.03f, y, size, Palette.TEXT, align = Paint.Align.LEFT)
                y += size * 1.3f
            }
        }
    }

    private fun drawPermission(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val scrim = Paint()
        scrim.color = 0xF0000000.toInt()
        c.drawRect(0f, 0f, w, h, scrim)

        text(c, Content.MIC_NEEDED, w / 2f, h * 0.32f, h * 0.050f, Palette.RED, bold = true)
        val lines = wrapText(Content.MIC_BODY, tfReg, h * 0.034f, w * 0.62f)
        var y = h * 0.44f
        for (line in lines) {
            text(c, line, w / 2f, y, h * 0.034f, Palette.TEXT)
            y += h * 0.048f
        }
        if (micError != null) {
            text(c, "(${micError})", w / 2f, y + h * 0.03f, h * 0.022f, Palette.DIM)
        }

        fill.color = Palette.RED
        c.drawRoundRect(grantR, h * 0.02f, h * 0.02f, fill)
        text(c, Content.MIC_GRANT, grantR.centerX(), grantR.centerY() + h * 0.015f, h * 0.036f, Palette.TEXT, bold = true)
    }

    private fun drawMicMeter(c: Canvas, voice: VoiceFrame, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val bw = w * 0.16f
        val bh = h * 0.010f
        val x = w / 2f - bw / 2f
        fill.color = 0x22000000
        c.drawRoundRect(RectF(x, y - bh, x + bw, y - bh / 2f), bh, bh, fill)
        val lvl = voice.level.coerceIn(0f, 1f)
        fill.color = if (voice.voiced) Palette.RED else Palette.GREY
        if (lvl > 0.01f) {
            c.drawRoundRect(
                RectF(x, y - bh, x + bw * lvl, y - bh / 2f), bh, bh, fill
            )
        }
    }

    private fun chevron(c: Canvas, cx: Float, cy: Float, size: Float, dir: Float) {
        stroke.color = Palette.RED
        stroke.strokeWidth = dp(3f)
        stroke.strokeCap = Paint.Cap.ROUND
        val path = Path()
        path.moveTo(cx - dir * size, cy - size)
        path.lineTo(cx + dir * size * 0.6f, cy)
        path.lineTo(cx - dir * size, cy + size)
        c.drawPath(path, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    // ---------------------------------------------------------------- utils

    private fun text(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        tp.textSize = size
        tp.color = color
        tp.typeface = if (bold) tfCond else tfReg
        tp.textAlign = align
        c.drawText(s, x, y, tp)
    }

    private fun wrapText(s: String, face: Typeface, size: Float, maxW: Float): List<String> {
        tp.typeface = face
        tp.textSize = size
        val out = ArrayList<String>()
        for (para in s.split('\n')) {
            if (para.isBlank()) {
                out.add("")
                continue
            }
            val words = para.split(' ')
            var line = StringBuilder()
            for (word in words) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (tp.measureText(cand) <= maxW || line.isEmpty()) {
                    line = StringBuilder(cand)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun buzz(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Throwable) {
        }
    }
}
