package com.rutger.fastify.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rutger.fastify.container
import com.rutger.fastify.diag.AppLog

/** Periodic update check (6 h, unmetered, battery not low), also while Fastify is closed. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppLog.i("Update", "worker: " + applicationContext.container.updater.check())
        return Result.success()
    }

    companion object {
        const val NAME = "fastify-update-check"
    }
}
