package com.sambouli.voxball

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class MainActivity : Activity() {

    internal lateinit var gameView: GameView

    /** False until we already resumed once; a silent cold start must not auto-pause the game. */
    private var restoredOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gameView = GameView(this)
        gameView.onRequestPermission = { askMic() }
        gameView.onExit = { finish() }
        setContentView(gameView)
        goImmersive()

        if (hasMic()) {
            gameView.setMicGranted(true)
        } else {
            askMic()
        }
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askMic() {
        if (!hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            gameView.setMicGranted(granted)
        }
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        gameView.resumeGame()
        // COMING BACK from the app switcher: the game was still running in memory while
        // the microphone was dead, so the ball may have moved for nobody. Freeze it
        // instead of losing a point in the dark.
        if (restoredOnce) {
            gameView.autoPauseIfRunning()
        }
        restoredOnce = true
    }

    override fun onPause() {
        gameView.pauseGame()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun goImmersive() {
        // Touching the insets controller before the DecorView exists throws
        // NullPointerException inside Window (mDecor == null), so force it first.
        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    companion object {
        private const val REQ_MIC = 41
    }
}
