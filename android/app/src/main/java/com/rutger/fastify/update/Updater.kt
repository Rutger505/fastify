package com.rutger.fastify.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import com.rutger.fastify.BuildConfig
import com.rutger.fastify.diag.AppLog
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** Downloaded and verified. Installs silently once the app is in the background and idle. */
    data class Ready(val release: Release, val silent: Boolean) : UpdateStatus
    data class Installing(val release: Release) : UpdateStatus
    /** Android wants a tap (first self-update, or install permission missing). */
    data class NeedsConfirmation(val release: Release) : UpdateStatus
    data class Failed(val message: String, val release: Release?) : UpdateStatus
}

/**
 * Self-update from the CDN: poll version.json, download + verify the APK, then install it with
 * PackageInstaller. Once Fastify is the installer of record (after the first confirmed update),
 * Android installs our updates without asking (USER_ACTION_NOT_REQUIRED). That kills the process,
 * so silent installs only happen in the background, not playing, after [IDLE_DELAY_MS].
 */
class Updater(
    private val app: Application,
    private val scope: CoroutineScope,
    http: () -> OkHttpClient,
    private val foreground: StateFlow<Boolean>,
    private val playing: StateFlow<Boolean>,
) {
    private val baseUrl = BuildConfig.UPDATE_URL.trimEnd('/')
    val enabled = baseUrl.isNotEmpty()

    private val http by lazy { http().newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private val dir = File(app.cacheDir, "updates")
    private val lock = Mutex()
    @Volatile private var lastCheck = 0L

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    fun start() {
        logVersionChange()
        if (!enabled) return
        scope.launch {
            while (true) {
                check()
                delay(CHECK_INTERVAL_MS)
            }
        }
        scope.launch {
            combine(_status, foreground, playing) { s, fg, p -> s is UpdateStatus.Ready && s.silent && !fg && !p }
                .distinctUntilChanged()
                .collectLatest { idle ->
                    if (!idle) return@collectLatest
                    delay(IDLE_DELAY_MS)
                    (_status.value as? UpdateStatus.Ready)?.let { install(it.release, interactive = false) }
                }
        }
    }

    /** App came to the foreground: re-check when the last check is stale. */
    fun onForeground() {
        if (enabled && System.currentTimeMillis() - lastCheck > FOREGROUND_CHECK_MS) scope.launch { check() }
    }

    /** Fetch version.json and download the APK when it is newer. Returns a one-line summary. */
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
                _status.value = UpdateStatus.UpToDate(release)
                return@withLock describe()
            }
            try {
                val file = download(release)
                AppLog.i("Update", "${release.versionName} (${release.versionCode}) ready: ${file.name}, silent=${canInstallSilently()}")
                _status.value = UpdateStatus.Ready(release, canInstallSilently())
            } catch (e: Exception) {
                AppLog.w("Update", "download ${release.versionCode} failed", e)
                _status.value = UpdateStatus.Failed("download: ${e.javaClass.simpleName}: ${e.message}", release)
            }
            describe()
        }
    }

    /** User tapped install: may show Android's confirmation, and restarts the app when done. */
    fun installNow() {
        val release = when (val s = _status.value) {
            is UpdateStatus.Ready -> s.release
            is UpdateStatus.NeedsConfirmation -> s.release
            is UpdateStatus.Failed -> s.release ?: return
            else -> return
        }
        scope.launch { install(release, interactive = true) }
    }

    fun describe(): String {
        val installer = runCatching { app.packageManager.getInstallSourceInfo(app.packageName) }.getOrNull()
        val current = "installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val state = when (val s = _status.value) {
            UpdateStatus.Idle -> "not checked yet"
            UpdateStatus.Checking -> "checking"
            is UpdateStatus.UpToDate -> "up to date, latest ${s.latest.versionName} (${s.latest.versionCode})"
            is UpdateStatus.Downloading -> "downloading ${s.release.versionCode}: ${s.bytes / 1_048_576}/${s.release.size / 1_048_576} MB"
            is UpdateStatus.Ready -> "${s.release.versionName} (${s.release.versionCode}) ready, silent=${s.silent}"
            is UpdateStatus.Installing -> "installing ${s.release.versionCode}"
            is UpdateStatus.NeedsConfirmation -> "${s.release.versionCode} needs a tap to install"
            is UpdateStatus.Failed -> "FAILED ${s.message}"
        }
        return "$current, $state; installer=${installer?.installingPackageName} " +
            "updateOwner=${installer?.updateOwnerPackageName} canRequestInstalls=${app.packageManager.canRequestPackageInstalls()}"
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

    private suspend fun install(r: Release, interactive: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = apkFile(r)
            if (!file.exists()) {
                _status.value = UpdateStatus.Failed("install: ${file.name} missing, re-check", r)
                return@withLock
            }
            if (!interactive && !canInstallSilently()) {
                _status.value = UpdateStatus.NeedsConfirmation(r)
                return@withLock
            }
            _status.value = UpdateStatus.Installing(r)
            AppLog.i("Update", "installing ${r.versionName} (${r.versionCode}), interactive=$interactive")
            try {
                val installer = app.packageManager.packageInstaller
                installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("fastify.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(app, UpdateReceiver::class.java)
                        .putExtra(UpdateReceiver.EXTRA_INTERACTIVE, interactive)
                        .putExtra(UpdateReceiver.EXTRA_VERSION, r.versionCode)
                    val pending = PendingIntent.getBroadcast(app, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    session.commit(pending.intentSender)
                }
            } catch (e: Exception) {
                AppLog.e("Update", "install ${r.versionCode} failed", e)
                _status.value = UpdateStatus.Failed("install: ${e.javaClass.simpleName}: ${e.message}", r)
            }
        }
    }

    /** Called by [UpdateReceiver] with the PackageInstaller result (success never arrives: we get killed). */
    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?, interactive: Boolean) {
        val release = (_status.value as? UpdateStatus.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                AppLog.i("Update", "Android asks for confirmation (interactive=$interactive)")
                if (interactive && confirm != null && foreground.value) {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else if (release != null) {
                    _status.value = UpdateStatus.NeedsConfirmation(release)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("Update", "installed")
            else -> {
                AppLog.e("Update", "install failed: status=$status $message")
                _status.value = UpdateStatus.Failed("install status=$status: $message", release)
            }
        }
    }

    private fun logVersionChange() {
        val prefs = app.getSharedPreferences("update", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getInt("versionCode", -1)
        if (previous != BuildConfig.VERSION_CODE) {
            AppLog.i("Update", "now running ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), was $previous")
            prefs.edit().putInt("versionCode", BuildConfig.VERSION_CODE).apply()
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val FOREGROUND_CHECK_MS = 30 * 60 * 1000L
        const val IDLE_DELAY_MS = 2 * 60 * 1000L
    }
}
