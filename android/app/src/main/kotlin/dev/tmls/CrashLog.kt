package dev.tmls

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the last crash's stack trace in `files/crash/last.txt` so a crash on a phone without adb
 * can be reported: the next launch shows it with a Copy button ([MainActivity]).
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "crash/last.txt")

    /** Chains in front of the default handler (which still kills the process as usual). */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(
            buildString {
                appendLine("tmls ${info.versionName} (${BuildConfig.COMMIT}) crashed at ${Instant.now()}")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, thread ${thread.name}")
                appendLine()
                append(trace)
            },
        )
    }

    /** The unseen crash report, if any. */
    fun pending(context: Context): String? = file(context).takeIf { it.isFile }?.readText()

    /** Keeps the report (as `seen.txt`) but stops showing it. */
    fun dismiss(context: Context) {
        val f = file(context)
        f.renameTo(File(f.parentFile, "seen.txt"))
    }
}
