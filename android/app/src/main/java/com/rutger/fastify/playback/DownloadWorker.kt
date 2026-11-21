package com.rutger.fastify.playback

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rutger.fastify.container
import com.rutger.fastify.diag.AppLog

/**
 * Keeps the process alive while [com.rutger.fastify.data.Downloads] works and the app isn't visible
 * (e.g. resuming after boot), where Android 15+ forbids starting the dataSync [DownloadService].
 * A job runs up to ~10 min; whatever is left resumes on the next app start or worker run.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.auth.isLoggedIn) return Result.success()
        val downloads = c.downloads
        // Process started for this job (e.g. after a reboot): load pins and resume.
        downloads.start()
        downloads.awaitIdle()
        return Result.success()
    }

    companion object {
        private const val NAME = "fastify-downloads"

        fun enqueue(context: Context) {
            AppLog.d("Downloads", "downloads continue in a worker (app not visible)")
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
