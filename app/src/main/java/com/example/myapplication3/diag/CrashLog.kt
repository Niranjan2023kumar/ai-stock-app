package com.example.myapplication3.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P0 #8 — Local crash log (E6-friendly, no cloud).
 *
 * On an uncaught exception we append an IST timestamp + thread name + full stack
 * trace to filesDir/crashlog.txt, then re-throw to the previously installed
 * handler so the OS still shows/records the crash and the process exits normally.
 *
 * Every operation is runCatching-guarded: the crash handler must NEVER itself
 * crash (that would mask the real exception), and it must always chain to the
 * previous handler so default crash behavior/exit still happens.
 */
object CrashLog {

    private const val FILE_NAME = "crashlog.txt"

    // Rotate once the file grows past ~64KB; keep the last ~32KB so recent
    // crashes survive but the file can never grow without bound on a small device.
    private const val ROTATE_AT_BYTES = 64 * 1024
    private const val KEEP_BYTES = 32 * 1024

    @Volatile
    private var installed = false

    /**
     * Install the crash handler. Safe to call multiple times (only the first
     * call takes effect). Chains to whatever handler was set before us.
     *
     * This is just a handler assignment — no I/O — so it is safe to call as the
     * very first thing in Application.onCreate, before Hilt.
     */
    fun install(context: Context) {
        runCatching {
            if (installed) return
            installed = true

            // Capture the app filesDir now so the handler never needs a live
            // Context reference (avoids leaks and null-context surprises).
            val appContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                // Best-effort write; must not throw.
                runCatching { append(appContext, thread, throwable) }
                // Always chain so normal crash behavior/exit still happens.
                runCatching {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        // No previous handler: preserve normal crash semantics.
                        throw throwable
                    }
                }
            }
        }
    }

    /** Returns the crash log contents, or a friendly placeholder if empty/unreadable. */
    fun recentCrashes(context: Context): String {
        return runCatching {
            val file = logFile(context)
            if (file.exists() && file.length() > 0L) file.readText() else "No crashes recorded."
        }.getOrDefault("No crashes recorded.")
    }

    /** Deletes the crash log. Safe to call anytime. */
    fun clear(context: Context) {
        runCatching {
            val file = logFile(context)
            if (file.exists()) file.delete()
        }
    }

    private fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun append(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile(context)

        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val entry = buildString {
            append("==== CRASH ")
            append(nowIst())
            append(" ====\n")
            append("Thread: ")
            append(thread.name)
            append(" (id=")
            append(thread.id)
            append(")\n")
            append(stack)
            if (!stack.endsWith("\n")) append("\n")
            append("\n")
        }

        file.appendText(entry)

        // Rotate if we've grown too large: keep only the tail so recent crashes remain.
        runCatching {
            if (file.length() > ROTATE_AT_BYTES) rotate(file)
        }
    }

    private fun rotate(file: File) {
        val bytes = file.readBytes()
        if (bytes.size <= KEEP_BYTES) return
        var start = bytes.size - KEEP_BYTES
        // Advance to the start of the next line so we don't keep a half-truncated entry.
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        if (start < bytes.size) start++ // skip the newline itself
        val tail = bytes.copyOfRange(start.coerceAtMost(bytes.size), bytes.size)
        val header = "==== crashlog.txt trimmed at ${nowIst()} (kept last ~${KEEP_BYTES / 1024}KB) ====\n"
        file.writeBytes(header.toByteArray(Charsets.UTF_8) + tail)
    }

    private fun nowIst(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'IST'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return fmt.format(Date())
    }
}
