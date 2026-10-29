package com.rutger.fastify.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Logcat + a small rotating file, so logs from earlier runs (and crashes) survive restarts and
 * can be shared from the app. Rust logs go to logcat (tag fastify-core) and are added on share.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "fastify-log").apply { isDaemon = true } }
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    @Volatile private var file: File? = null

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Write synchronously: the process is about to die.
            append("E", "Crash", "uncaught on ${thread.name}: " + Log.getStackTraceString(e))
            previous?.uncaughtException(thread, e)
        }
        i("App", "---- process start ----")
    }

    val files: List<File>
        get() = file?.let { listOf(File(it.parentFile, "app.log.1"), it) }.orEmpty().filter { it.exists() }

    fun i(tag: String, msg: String) {
        Log.i("Fastify/$tag", msg)
        async("I", tag, msg)
    }

    fun w(tag: String, msg: String, e: Throwable? = null) {
        Log.w("Fastify/$tag", msg, e)
        async("W", tag, msg + (e?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    fun e(tag: String, msg: String, e: Throwable? = null) {
        Log.e("Fastify/$tag", msg, e)
        async("E", tag, msg + (e?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    private fun async(level: String, tag: String, msg: String) {
        val line = "${LocalDateTime.now().format(time)} $level/$tag: $msg"
        writer.execute { write(line) }
    }

    private fun append(level: String, tag: String, msg: String) =
        write("${LocalDateTime.now().format(time)} $level/$tag: $msg")

    @Synchronized
    private fun write(line: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "app.log.1"))
            f.appendText(line + "\n")
        }
    }
}
