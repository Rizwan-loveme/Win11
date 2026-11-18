package com.srizwan.win11

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny file-backed logger.
 *
 * Logcat alone is useless without a computer/adb, so every line written here
 * also gets appended to a plain-text file inside the app's own storage
 * (filesDir — no special permission needed). [DebugLogActivity] reads that
 * file so the log can be viewed, copied, or shared straight from the phone.
 *
 * Every function is best-effort and never throws: logging must not be able
 * to crash the app it is trying to help debug.
 */
object Logger {
    private const val FILE_NAME = "rizwans2_debug_log.txt"
    private const val MAX_BYTES = 512 * 1024L // trim once the file passes this size
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var logFile: File? = null

    /** Call once, as early as possible (Application.attachBaseContext). Safe to call again. */
    fun init(ctx: Context) {
        if (logFile != null) return
        try {
            logFile = File(ctx.filesDir, FILE_NAME).also {
                if (!it.exists()) it.createNewFile()
            }
            write("I", "Logger", "──── log opened ────", null)
        } catch (e: Throwable) {
            Log.e("Logger", "init failed", e)
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = write("E", tag, msg, tr)

    fun logFilePath(): String = logFile?.absolutePath ?: "(logger not initialized yet)"

    fun readAll(): String = try {
        logFile?.takeIf { it.exists() }?.readText()?.ifBlank { "(log is empty)" }
            ?: "(no log file yet)"
    } catch (e: Throwable) {
        "(could not read log file: ${e.message})"
    }

    fun clear() {
        try { logFile?.writeText("") } catch (e: Throwable) { /* best effort */ }
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, tr)
            "W" -> Log.w(tag, msg, tr)
            "I" -> Log.i(tag, msg)
            else -> Log.d(tag, msg)
        }
        val f = logFile ?: return
        try {
            val line = buildString {
                append(fmt.format(Date())).append(" [").append(level).append("] ")
                append(tag).append(": ").append(msg).append('\n')
                if (tr != null) append(Log.getStackTraceString(tr)).append('\n')
            }
            f.appendText(line)
            if (f.length() > MAX_BYTES) {
                // Keep only the newer half so the file never grows without bound.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2))
            }
        } catch (e: Throwable) {
            // best effort; logging must never crash the app
        }
    }
}
