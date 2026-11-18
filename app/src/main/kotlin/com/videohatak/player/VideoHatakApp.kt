package com.videohatak.player

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VideoHatakApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashDiagnosticsHandler()
    }

    private fun installCrashDiagnosticsHandler() {
        runCatching {
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    val logFile = File(filesDir, CRASH_LOG_FILENAME)
                    if (logFile.exists() && logFile.length() > MAX_CRASH_LOG_BYTES) {
                        logFile.writeText("", Charsets.UTF_8)
                    }
                    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    val sw = StringWriter()
                    PrintWriter(sw).use { pw ->
                        pw.println("=== [$timestamp] thread=${thread.name} ===")
                        throwable.printStackTrace(pw)
                    }
                    val entry = sw.toString().take(16 * 1024)
                    logFile.appendText("$entry\n", Charsets.UTF_8)
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    companion object {
        private const val CRASH_LOG_FILENAME = "crash_diagnostics.log"
        private const val MAX_CRASH_LOG_BYTES = 64 * 1024L
    }
}
