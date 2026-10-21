package com.sambouli.voxball

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Launches the real activity and draws every screen, on the JVM.
 *
 * There is no emulator on the build machine, so this is the closest thing to starting the
 * app: it catches crashes in onCreate, in the layout pass and in every branch of the
 * renderer (intro, calibration, serve, play, pause, permission).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameViewSmokeTest {

    private var activity: MainActivity? = null

    private fun launch(w: Int, h: Int): GameView {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity = a
        val root = a.window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val view = root.getChildAt(0) as GameView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, w, h)
        view.layout(0, 0, w, h)
        view.surfaceChanged(view.holder, 0, w, h)
        return view
    }

    private fun engineOf(view: GameView): GameEngine {
        val f = GameView::class.java.getDeclaredField("engine")
        f.isAccessible = true
        return f.get(view) as GameEngine
    }

    private fun draw(view: GameView, frame: VoiceFrame = VoiceFrame()) {
        val m = GameView::class.java.getDeclaredMethod(
            "drawFrame", Canvas::class.java, VoiceFrame::class.java
        )
        m.isAccessible = true
        m.invoke(view, Canvas(), frame)
    }

    private fun tap(view: GameView) {
        val t = System.currentTimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        view.onTouchEvent(down)
        down.recycle()
    }

    @After
    fun tearDown() {
        activity?.let {
            try {
                it.gameView.pauseGame()
            } catch (_: Throwable) {
            }
            it.finish()
        }
        activity = null
    }

    @Test
    fun drawsAStoredCrashReportThenDismissesIt() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(app.filesDir, VoxBallApp.CRASH_FILE)
            .writeText("java.lang.NullPointerException: boom\n  at com.foo.Bar.baz(Bar.kt:1)\n")
        val view = launch(1920, 1200)
        draw(view)
        tap(view)
        draw(view)
    }

    @Test
    fun drawsPermissionScreenBeforeTheMicrophoneIsGranted() {
        val view = launch(1920, 1200)
        draw(view)
        draw(view)
    }

    @Test
    fun drawsEveryIntroPageAndTheCalibrationScreen() {
        val view = launch(1920, 1200)
        view.setMicGranted(true)
        val engine = engineOf(view)
        var guard = 0
        while (engine.phase == Phase.HINTS && guard++ < 20) {
            draw(view)
            tap(view)
        }
        assertNotNull(engine.phase)
        draw(view)
        draw(view, VoiceFrame(voiced = true, f0 = 220f, level = 0.9f, clarity = 0.95f))
    }

    @Test
    fun drawsServePlayAndPause() {
        val view = launch(2560, 1600) // tablet geometry
        view.setMicGranted(true)
        val engine = engineOf(view)
        engine.skipCalibSeat()
        engine.skipCalibSeat()
        draw(view) // serve
        engine.update(0.05f, VoiceFrame())
        engine.update(0.05f, VoiceFrame(voiced = true, f0 = 250f, level = 0.9f, clarity = 0.9f))
        draw(view, VoiceFrame(voiced = true, f0 = 250f, level = 0.9f, clarity = 0.9f))
        engine.togglePause()
        draw(view) // pause overlay
        tap(view) // no-op click somewhere harmless
    }

    @Test
    fun survivesAnEntireMatchAtAnyAspectRatio() {
        for ((w, h) in listOf(1920 to 1080, 2560 to 1600, 2800 to 1752, 1280 to 720, 2400 to 1080)) {
            val view = launch(w, h)
            view.setMicGranted(true)
            val engine = engineOf(view)
            engine.skipCalibSeat()
            engine.skipCalibSeat()
            repeat(400) {
                engine.update(1f / 60f, if (it % 3 == 0) VoiceFrame(voiced = true, f0 = 210f, level = 0.8f, clarity = 0.9f) else VoiceFrame())
                if (engine.phase == Phase.CALIBRATION) {
                    engine.skipCalibSeat()
                    engine.skipCalibSeat()
                }
                draw(view)
            }
            activity?.gameView?.pauseGame()
            activity?.finish()
            activity = null
        }
    }
}
