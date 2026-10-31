package com.rutger.fastify.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.rutger.fastify.container

/** Receives PackageInstaller session results for [Updater]. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        context.container.updater.onInstallResult(
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
            interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, false),
            versionCode = intent.getIntExtra(EXTRA_VERSION, -1),
        )
    }

    companion object {
        const val EXTRA_INTERACTIVE = "interactive"
        const val EXTRA_VERSION = "versionCode"
    }
}
