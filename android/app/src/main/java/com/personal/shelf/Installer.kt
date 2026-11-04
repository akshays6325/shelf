package com.personal.shelf

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(val progress: Float) : InstallState
    data object Installing : InstallState
    data class Failed(val message: String) : InstallState
}

object Installer {
    val states = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    /** Fires when a package finished installing, so screens re-read installed versions. */
    val changed = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun set(pkg: String, s: InstallState) = states.update { it + (pkg to s) }
    fun state(pkg: String): InstallState = states.value[pkg] ?: InstallState.Idle

    /** Downloads, verifies and hands the APK to the system installer. Returns false on failure. */
    suspend fun install(ctx: Context, app: AppEntry, background: Boolean = false): Boolean {
        val s = state(app.packageName)
        if (s is InstallState.Downloading || s is InstallState.Installing) return true
        return try {
            set(app.packageName, InstallState.Downloading(0f))
            val file = download(ctx, app)
            set(app.packageName, InstallState.Installing)
            commit(ctx, app, file, background)
            true
        } catch (e: Exception) {
            set(app.packageName, InstallState.Failed(e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private suspend fun download(ctx: Context, app: AppEntry): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "apk").apply { mkdirs() }
        val file = File(dir, "${app.id}-${app.versionCode}.apk")
        var url = URL(app.apkUrl)
        var c: HttpURLConnection
        // Follow redirects by hand: GitHub release assets redirect across hosts.
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                continue
            }
            if (code !in 200..299) error("Download failed (HTTP $code)")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: app.size
        val md = MessageDigest.getInstance("SHA-256")
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    md.update(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = (read * 100 / total).toInt().coerceAtMost(100)
                        if (pct != lastPct) {
                            lastPct = pct
                            set(app.packageName, InstallState.Downloading(pct / 100f))
                        }
                    }
                }
            }
        }
        c.disconnect()
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        if (app.sha256.isNotBlank() && !hash.equals(app.sha256, ignoreCase = true)) {
            file.delete()
            error("Checksum mismatch, the download was corrupted")
        }
        file
    }

    private suspend fun commit(ctx: Context, app: AppEntry, file: File, background: Boolean) = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
                .putExtra(InstallReceiver.EXTRA_PKG, app.packageName)
                .putExtra(InstallReceiver.EXTRA_NAME, app.name)
                .putExtra(InstallReceiver.EXTRA_VERSION, app.versionName)
                .putExtra(InstallReceiver.EXTRA_BACKGROUND, background)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        file.delete()
    }
}
