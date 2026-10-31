package com.rutger.fastify.update

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rutger.fastify.BuildConfig
import com.rutger.fastify.diag.AppLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** CDN manifest written by android/scripts/publish next to the APK. */
@Serializable
data class Release(val versionCode: Int, val versionName: String, val apk: String, val sha256: String, val size: Long)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val latest: Release) : UpdateStatus
    data class Downloading(val release: Release, val bytes: Long) : UpdateStatus
    /** Downloaded and verified; not installable without a tap (first self-update, permission missing). */
    data class Ready(val release: Release) : UpdateStatus
    /** Committed with install constraints: Android installs it once Fastify isn't in use. */
    data class Scheduled(val release: Release) : UpdateStatus
    data class Installing(val release: Release) : UpdateStatus
    /** Android wants a tap (first self-update, Play Protect, or install permission missing). */
    data class NeedsConfirmation(val release: Release) : UpdateStatus
    data class Failed(val message: String, val release: Release?) : UpdateStatus
}

/**
 * Self-update from the CDN, the way app stores do it:
 * - Check: on app start, on foreground (at most every 30 min) and from [UpdateWorker] every 6 h
 *   (WorkManager, unmetered network, battery not low), also while the app is closed.
 * - Download + verify sha256.
 * - Install: when Fastify is the installer of record, commit the session with
 *   [PackageInstaller.InstallConstraints] (not foreground, not interacting, e.g. playing, not in a
 *   call). Android itself installs it at the right moment, without UI, even when our process is dead.
 *   Otherwise (first update) Home shows a card and a notification asks for the tap.
 */
class Updater(
    private val app: Application,
    private val scope: CoroutineScope,
    http: () -> OkHttpClient,
    private val foreground: StateFlow<Boolean>,
) {
    private val baseUrl = BuildConfig.UPDATE_URL.trimEnd('/')
    val enabled = baseUrl.isNotEmpty()

    private val http by lazy { http().newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(app.cacheDir, "updates")
    private val lock = Mutex()
    private val installer get() = app.packageManager.packageInstaller
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    @Volatile private var lastCheck = 0L

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    fun start() {
        logVersionChange()
        if (!enabled) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            UpdateWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(CHECK_INTERVAL_HOURS, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }

    /** App came to the foreground: re-check when the last check is stale. */
    fun onForeground() {
        if (enabled && System.currentTimeMillis() - lastCheck > FOREGROUND_CHECK_MS) scope.launch { check() }
    }

    /**
     * Fetch version.json; download, verify and schedule the APK when it is newer. Returns a
     * one-line summary. The worker only runs on unmetered networks; app-triggered checks download on any.
     */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext "disabled in this build (no UPDATE_URL)"
        lock.withLock {
            val busy = _status.value
            if (busy is UpdateStatus.Downloading || busy is UpdateStatus.Installing) return@withLock describe()
            lastCheck = System.currentTimeMillis()
            _status.value = UpdateStatus.Checking
            val release = try {
                fetchRelease()
            } catch (e: Exception) {
                AppLog.w("Update", "check failed", e)
                _status.value = UpdateStatus.Failed("check: ${e.javaClass.simpleName}: ${e.message}", null)
                return@withLock describe()
            }
            if (release.versionCode <= BuildConfig.VERSION_CODE) {
                dir.deleteRecursively()
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                _status.value = UpdateStatus.UpToDate(release)
                return@withLock describe()
            }
            if (scheduledSession(release) != null) {
                _status.value = UpdateStatus.Scheduled(release)
                return@withLock describe()
            }
            try {
                val file = download(release)
                val silent = canInstallSilently()
                AppLog.i("Update", "${release.versionName} (${release.versionCode}) ready: ${file.name}, silent=$silent")
                if (silent) commit(release, interactive = false) else {
                    _status.value = UpdateStatus.Ready(release)
                    if (!foreground.value) notifyNeedsTap(release, null)
                }
            } catch (e: Exception) {
                AppLog.w("Update", "download ${release.versionCode} failed", e)
                _status.value = UpdateStatus.Failed("download: ${e.javaClass.simpleName}: ${e.message}", release)
            }
            describe()
        }
    }

    /** User tapped install: installs right away (may show Android's confirmation) and closes the app. */
    fun installNow() {
        val release = when (val s = _status.value) {
            is UpdateStatus.Ready -> s.release
            is UpdateStatus.Scheduled -> s.release
            is UpdateStatus.NeedsConfirmation -> s.release
            is UpdateStatus.Failed -> s.release ?: return
            else -> return
        }
        scope.launch(Dispatchers.IO) { lock.withLock { commit(release, interactive = true) } }
    }

    fun describe(): String {
        val info = runCatching { app.packageManager.getInstallSourceInfo(app.packageName) }.getOrNull()
        val current = "installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val state = when (val s = _status.value) {
            UpdateStatus.Idle -> "not checked in this process"
            UpdateStatus.Checking -> "checking"
            is UpdateStatus.UpToDate -> "up to date, latest ${s.latest.versionName} (${s.latest.versionCode})"
            is UpdateStatus.Downloading -> "downloading ${s.release.versionCode}: ${s.bytes / 1_048_576}/${s.release.size / 1_048_576} MB"
            is UpdateStatus.Ready -> "${s.release.versionName} (${s.release.versionCode}) downloaded, needs a tap"
            is UpdateStatus.Scheduled -> "${s.release.versionName} (${s.release.versionCode}) scheduled, installs when Fastify is not in use"
            is UpdateStatus.Installing -> "installing ${s.release.versionCode}"
            is UpdateStatus.NeedsConfirmation -> "${s.release.versionCode} needs a tap to install"
            is UpdateStatus.Failed -> "FAILED ${s.message}"
        }
        val sessions = installer.mySessions.joinToString { "#${it.sessionId} committed=${it.isCommitted} staged=${it.isStaged}" }
        return "$current, $state; installer=${info?.installingPackageName} updateOwner=${info?.updateOwnerPackageName} " +
            "canRequestInstalls=${app.packageManager.canRequestPackageInstalls()} sessions=[$sessions]"
    }

    /** Silent updates need: install permission granted, and us as installer of record / update owner. */
    private fun canInstallSilently(): Boolean {
        val pm = app.packageManager
        if (!pm.canRequestPackageInstalls()) return false
        val info = runCatching { pm.getInstallSourceInfo(app.packageName) }.getOrNull() ?: return false
        return info.installingPackageName == app.packageName || info.updateOwnerPackageName == app.packageName
    }

    private fun fetchRelease(): Release {
        val req = Request.Builder().url("$baseUrl/version.json").header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200).replace('\n', ' ')}")
            return json.decodeFromString(Release.serializer(), body)
        }
    }

    private fun apkFile(r: Release) = File(dir, "fastify-${r.versionCode}-${r.sha256.take(12)}.apk")

    /** A committed session for [r] that is waiting for its install constraints. */
    private fun scheduledSession(r: Release) = installer.mySessions.firstOrNull {
        it.isCommitted && it.appPackageName == app.packageName && sessionVersion(it.sessionId) == r.versionCode
    }

    private fun sessionVersion(id: Int) = app.getSharedPreferences("update", android.content.Context.MODE_PRIVATE).getInt("session-$id", -1)

    /** Downloads to a .part file and only renames it after the sha256 matches: a final file is verified. */
    private fun download(r: Release): File {
        val target = apkFile(r)
        if (target.exists()) return target
        dir.deleteRecursively()
        dir.mkdirs()
        val part = File(dir, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        val req = Request.Builder().url("$baseUrl/${r.apk}").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for ${r.apk}")
            resp.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        total += n
                        if (total - reported > 512 * 1024) {
                            reported = total
                            _status.value = UpdateStatus.Downloading(r, total)
                        }
                    }
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != r.sha256.lowercase()) {
            part.delete()
            error("sha256 mismatch: got $sha, expected ${r.sha256} (${part.length()} bytes)")
        }
        if (!part.renameTo(target)) error("rename ${part.name} failed")
        return target
    }

    /**
     * Writes the APK into a fresh session. Interactive: commit now (user is waiting, app closes).
     * Otherwise: commit once Fastify is not in use; Android holds the session up to [CONSTRAINT_TIMEOUT_MS].
     * Caller holds [lock].
     */
    private fun commit(r: Release, interactive: Boolean) {
        val file = apkFile(r)
        if (!file.exists()) {
            _status.value = UpdateStatus.Failed("install: ${file.name} missing, re-check", r)
            return
        }
        AppLog.i("Update", "committing ${r.versionName} (${r.versionCode}), interactive=$interactive")
        try {
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            }
            val id = installer.createSession(params)
            app.getSharedPreferences("update", android.content.Context.MODE_PRIVATE).edit().putInt("session-$id", r.versionCode).apply()
            installer.openSession(id).use { session ->
                session.openWrite("fastify.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
            }
            val intent = Intent(app, UpdateReceiver::class.java)
                .putExtra(UpdateReceiver.EXTRA_INTERACTIVE, interactive)
                .putExtra(UpdateReceiver.EXTRA_VERSION, r.versionCode)
            val sender = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender
            if (interactive) {
                _status.value = UpdateStatus.Installing(r)
                installer.openSession(id).use { it.commit(sender) }
            } else {
                val constraints = PackageInstaller.InstallConstraints.Builder()
                    .setAppNotForegroundRequired()
                    .setAppNotInteractingRequired()
                    .setNotInCallRequired()
                    .build()
                installer.commitSessionAfterInstallConstraintsAreMet(id, sender, constraints, CONSTRAINT_TIMEOUT_MS)
                _status.value = UpdateStatus.Scheduled(r)
            }
        } catch (e: Exception) {
            AppLog.e("Update", "commit ${r.versionCode} failed", e)
            _status.value = UpdateStatus.Failed("install: ${e.javaClass.simpleName}: ${e.message}", r)
        }
    }

    /** Called by [UpdateReceiver] with the PackageInstaller result. Our process may be fresh here. */
    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?, interactive: Boolean, versionCode: Int) {
        val release = when (val s = _status.value) {
            is UpdateStatus.Installing -> s.release
            is UpdateStatus.Scheduled -> s.release
            is UpdateStatus.Ready -> s.release
            else -> null
        }
        val label = release?.versionName ?: "version $versionCode"
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.w("Update", "$label: Android asks for confirmation (interactive=$interactive): $message")
                if (interactive && confirm != null && foreground.value) {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    release?.let { _status.value = UpdateStatus.NeedsConfirmation(it) }
                    notifyNeedsTap(release, confirm, label)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("Update", "$label installed")
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                // Constraints weren't met in time (app in use all day): the next check schedules it again.
                AppLog.w("Update", "$label: install constraints not met within timeout")
                lastCheck = 0
                release?.let { _status.value = UpdateStatus.Ready(it) }
            }
            else -> {
                AppLog.e("Update", "$label install failed: status=$status $message")
                _status.value = UpdateStatus.Failed("install status=$status: $message", release)
            }
        }
    }

    /** Fallback when Android wants a tap: a notification, so it doesn't wait for Fastify to be opened. */
    private fun notifyNeedsTap(release: Release?, confirm: Intent?, label: String = release?.versionName ?: "update") {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val target = confirm ?: app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        val tap = PendingIntent.getActivity(app, 1, target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Fastify $label ready")
            .setContentText("Tap to install")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, n) }
    }

    private fun logVersionChange() {
        val prefs = app.getSharedPreferences("update", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getInt("versionCode", -1)
        if (previous != BuildConfig.VERSION_CODE) {
            AppLog.i("Update", "now running ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), was $previous")
            prefs.edit().clear().putInt("versionCode", BuildConfig.VERSION_CODE).apply()
            notifications.cancel(NOTIFICATION_ID)
        }
    }

    companion object {
        const val CHECK_INTERVAL_HOURS = 6L
        const val FOREGROUND_CHECK_MS = 30 * 60 * 1000L
        const val CONSTRAINT_TIMEOUT_MS = 24 * 60 * 60 * 1000L
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 7001
    }
}
