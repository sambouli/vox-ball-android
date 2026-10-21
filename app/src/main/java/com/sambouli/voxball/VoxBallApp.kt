package com.sambouli.voxball

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Records the last uncaught exception next to the app's private files so the next launch
 * can show it on screen. The phone is the only device this runs on and there is no adb on
 * the build machine, so a screenshot of the trace is the fastest possible bug report.
 */
class VoxBallApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = buildString {
                    append(error.javaClass.name).append(": ").append(error.message).append('\n')
                    error.stackTrace.take(24).forEach { append("  at ").append(it).append('\n') }
                    append("thread=").append(thread.name).append('\n')
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                        .append(" api=").append(Build.VERSION.SDK_INT).append('\n')
                }
                File(filesDir, CRASH_FILE).writeText(report)
            } catch (_: Throwable) {
                // if the reporter itself fails, the default handler still runs
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.txt"
    }
}
