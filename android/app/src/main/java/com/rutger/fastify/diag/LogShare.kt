package com.rutger.fastify.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import com.rutger.fastify.BuildConfig
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LogShare {
    /** Bundles the app log + this process's logcat (incl. Rust) and opens the share sheet. */
    suspend fun share(context: Context, extra: String? = null) {
        val file = withContext(Dispatchers.IO) { collect(context, extra) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Fastify logs")
            clipData = ClipData.newRawUri("Fastify logs", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share Fastify logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun collect(context: Context, extra: String?): File {
        val out = File(File(context.cacheDir, "shared").apply { mkdirs() }, "fastify-logs.txt")
        out.bufferedWriter().use { w ->
            w.appendLine("Fastify ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            w.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            w.appendLine("Collected: ${LocalDateTime.now()}")
            if (extra != null) {
                w.appendLine()
                w.appendLine("==== diagnostics ====")
                w.appendLine(extra)
            }
            for (f in AppLog.files) {
                w.appendLine()
                w.appendLine("==== ${f.name} ====")
                w.append(f.readText())
            }
            w.appendLine()
            w.appendLine("==== logcat (this process) ====")
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + Process.myPid())
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { w.appendLine(it) } }
            }.onFailure { w.appendLine("logcat failed: $it") }
        }
        return out
    }
}
