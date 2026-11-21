package com.rutger.fastify.diag

import android.content.Context
import android.util.Log
import com.rutger.fastify.core.LogSink
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Logcat + log files, so logs from earlier runs (and crashes) survive restarts and can be shared
 * from the app. Policy in README "Logging": [d] is logcat only; [i]/[w]/[e] also go to the file.
 * Rust logs go to logcat (tag fastify-core) and, through [CoreSink], into the same file.
 *
 * - Stack traces: the first one per kind (tag + message + exception) per process in full, later
 *   ones as one line.
 * - repeated = true (and librespot's warnings): the first line within [REPEAT_WINDOW_MS] is
 *   written, further ones are counted and summed up as "xN more since <time>" when the window
 *   ends (or on [flush]). While the network is down they're only counted ([network]).
 * - Files: one per day (new one at [MAX_FILE_BYTES]), app-<start time>.log; kept max
 *   [MAX_AGE_MS] and [MAX_TOTAL_BYTES] in total, oldest deleted first. Small files, so deleting
 *   the oldest only loses a small chunk; bigger ones (old layout, earlier limits) keep their tail.
 */
object AppLog {
    private const val MAX_TOTAL_BYTES = 300 * 1024L
    private const val MAX_FILE_BYTES = 64 * 1024L
    private const val MAX_AGE_MS = 5 * 24 * 3600_000L
    private const val REPEAT_WINDOW_MS = 10 * 60_000L
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "fastify-log").apply { isDaemon = true } }
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val fileTime = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    private val fileName = Regex("""app-\d{8}-\d{6}-\d{3}\.log""")
    @Volatile private var dir: File? = null

    // Guarded by this (writer thread, or the crash handler).
    private var file: File? = null
    private var out: FileOutputStream? = null
    private var fileBytes = 0L
    private var fileDay: LocalDate? = null
    private val repeats = HashMap<String, Repeat>()
    private val traced = HashSet<String>()
    private var offlineSince: Long? = null
    private val offline = LinkedHashMap<String, Int>()

    private class Repeat(val start: Long, val label: String) {
        var count = 0
        var first = 0L
        var last = 0L
    }

    fun init(context: Context) {
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Write synchronously: the process is about to die.
            record("E", "Crash", "uncaught on ${thread.name}", e, repeated = false, at = System.currentTimeMillis())
            previous?.uncaughtException(thread, e)
        }
        writer.execute { open() }
        writer.scheduleWithFixedDelay({ sweep(System.currentTimeMillis(), all = false) }, 1, 1, TimeUnit.MINUTES)
        i("App", "---- process start ----")
        NetworkLog.start(context)
    }

    /** Every retained log file, oldest first. */
    val files: List<File>
        get() = dir?.listFiles { f -> fileName.matches(f.name) }.orEmpty().sortedBy { it.name }

    /** Logcat only (periodic, per-attempt, chatty). */
    fun d(tag: String, msg: String) {
        Log.d("Fastify/$tag", msg)
    }

    fun i(tag: String, msg: String) {
        Log.i("Fastify/$tag", msg)
        async("I", tag, msg, null, false)
    }

    /** repeated: this can fire in a loop (retries, watchers): aggregate, quiet while offline. */
    fun w(tag: String, msg: String, e: Throwable? = null, repeated: Boolean = false) {
        Log.w("Fastify/$tag", msg, e)
        async("W", tag, msg, e, repeated)
    }

    fun e(tag: String, msg: String, e: Throwable? = null, repeated: Boolean = false) {
        Log.e("Fastify/$tag", msg, e)
        async("E", tag, msg, e, repeated)
    }

    /**
     * Network state from [NetworkLog]: logs the change; while down, repeated errors are only
     * counted and summed up in the "network up" line.
     */
    fun network(online: Boolean, what: String) {
        Log.i("Fastify/Network", what)
        val at = System.currentTimeMillis()
        writer.execute { networkChanged(online, what, at) }
    }

    /** Writes pending repeat summaries and waits for queued lines (before Share logs). */
    fun flush() {
        runCatching { writer.submit { sweep(System.currentTimeMillis(), all = true) }.get(5, TimeUnit.SECONDS) }
    }

    /** Rust core lines: file only (the core writes logcat itself). Tag: core/<module>. */
    object CoreSink : LogSink {
        override fun log(level: String, target: String, message: String) {
            val tag = "core/" + target.removePrefix("fastify_core::")
            // librespot retries (AP resolve, dealer reconnect) on its own: aggregate its complaints.
            async(level, tag, message, null, repeated = level != "I" && target.startsWith("librespot"))
        }
    }

    private fun async(level: String, tag: String, msg: String, e: Throwable?, repeated: Boolean) {
        val at = System.currentTimeMillis()
        writer.execute { record(level, tag, msg, e, repeated, at) }
    }

    @Synchronized
    private fun record(level: String, tag: String, msg: String, e: Throwable?, repeated: Boolean, at: Long) {
        sweep(at, all = false)
        if (repeated) {
            val key = signature(tag, msg, e)
            if (offlineSince != null) {
                val label = "$level/$tag: ${msg.lineSequence().first()}"
                offline[label] = (offline[label] ?: 0) + 1
                return
            }
            val r = repeats[key]
            if (r != null) {
                if (r.count == 0) r.first = at
                r.count++
                r.last = at
                return
            }
            repeats[key] = Repeat(at, "$level/$tag: ${oneLine(msg, e)}")
        }
        write(at, "$level/$tag: $msg${trace(tag, msg, e)}")
    }

    /** Full trace the first time per kind in this process, then just the exception in one line. */
    private fun trace(tag: String, msg: String, e: Throwable?): String {
        e ?: return ""
        return if (traced.add(signature(tag, msg, e))) "\n" + e.stackTraceToString().trimEnd()
        else ": ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.first()} (trace logged earlier)"
    }

    private fun oneLine(msg: String, e: Throwable?) =
        msg.lineSequence().first() + (e?.let { ": ${it.javaClass.simpleName}: ${it.message?.lineSequence()?.first()}" } ?: "")

    /** Same message modulo numbers and ids. */
    private fun signature(tag: String, msg: String, e: Throwable?): String {
        val norm = { s: String? -> s.orEmpty().lineSequence().first().replace(ID, "*").replace(NUMBER, "#") }
        return "$tag|${norm(msg)}|${e?.javaClass?.name}|${norm(e?.message)}"
    }

    private val ID = Regex("""\b[0-9A-Za-z]{16,}\b""")
    private val NUMBER = Regex("""\d+""")

    /** Summaries for repeats whose window ended (all: also running ones, the window goes on). */
    @Synchronized
    private fun sweep(now: Long, all: Boolean) {
        val iter = repeats.values.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            val ended = now - r.start >= REPEAT_WINDOW_MS
            if (r.count > 0 && (ended || all)) {
                write(now, "${r.label} (x${r.count} more since ${clock(r.first)}, last ${clock(r.last)})")
                r.count = 0
            }
            if (ended) iter.remove()
        }
        if (all) out?.flush()
    }

    @Synchronized
    private fun networkChanged(online: Boolean, what: String, at: Long) {
        val since = offlineSince
        if (!online) {
            if (since == null) {
                sweep(at, all = true)
                repeats.clear()
                offlineSince = at
            }
            write(at, "I/Network: $what")
            return
        }
        if (since == null) {
            write(at, "I/Network: $what")
            return
        }
        val quiet = offline.values.sum()
        val top = offline.entries.sortedByDescending { it.value }.take(3).joinToString("; ") { "${it.value}x ${it.key}" }
        write(at, "I/Network: $what (was down ${(at - since) / 1000} s" + (if (quiet > 0) ", meanwhile $quiet repeated errors: $top" else "") + ")")
        offline.clear()
        offlineSince = null
        repeats.clear()
    }

    private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

    @Synchronized
    private fun write(at: Long, line: String) {
        val now = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()
        if (out == null || fileBytes >= MAX_FILE_BYTES || fileDay != now.toLocalDate()) rotate(now)
        val bytes = ("${now.format(time)} $line\n").toByteArray()
        runCatching {
            out?.write(bytes)
            fileBytes += bytes.size
        }
    }

    /** Continue today's newest file after a restart, unless it's full. */
    @Synchronized
    private fun open() {
        val d = dir ?: return
        // Old layout: app.log.1 + app.log; named by their last write, so they sort before new files.
        for (old in listOf("app.log.1", "app.log")) {
            val f = File(d, old).takeIf { it.exists() } ?: continue
            val at = Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).toLocalDateTime()
            f.renameTo(File(d, "app-${at.format(fileTime)}.log"))
        }
        val last = files.lastOrNull()
        val day = LocalDate.now()
        if (out == null && last != null && last.length() < MAX_FILE_BYTES &&
            Instant.ofEpochMilli(last.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate() == day
        ) {
            use(last, day)
        }
        prune()
    }

    @Synchronized
    private fun rotate(now: LocalDateTime) {
        val d = dir ?: return
        runCatching { out?.close() }
        use(File(d, "app-${now.format(fileTime)}.log"), now.toLocalDate())
        prune()
    }

    private fun use(f: File, day: LocalDate) {
        file = f
        fileDay = day
        fileBytes = f.length()
        out = runCatching { FileOutputStream(f, true) }.getOrNull()
    }

    /**
     * Older than [MAX_AGE_MS], then oversized files (imported old layout, earlier limits) cut to
     * their last [MAX_FILE_BYTES], then oldest first until the rest fits next to a full current
     * file, so all files stay within [MAX_TOTAL_BYTES].
     */
    private fun prune() {
        val old = files.filter { it != file }.toMutableList()
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        old.removeAll { f -> (f.lastModified() < cutoff).also { if (it) f.delete() } }
        old.filter { it.length() > MAX_FILE_BYTES }.forEach(::keepTail)
        var total = old.sumOf { it.length() }
        while (old.isNotEmpty() && total > MAX_TOTAL_BYTES - MAX_FILE_BYTES) {
            val f = old.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }

    /** Keeps the last [MAX_FILE_BYTES] of [f] from a line start; mtime stays (age pruning). */
    private fun keepTail(f: File) {
        val modified = f.lastModified()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        val ok = runCatching {
            RandomAccessFile(f, "r").use { src ->
                src.seek(src.length() - MAX_FILE_BYTES)
                src.readLine()
                FileOutputStream(tmp).use { dst ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                    }
                }
            }
            tmp.renameTo(f) && f.setLastModified(modified)
        }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            // Can't trim: drop it rather than break the total.
            f.delete()
        }
    }
}
