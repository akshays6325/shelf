package com.personal.shelf

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppEntry(
    val id: String,
    val name: String,
    val packageName: String,
    val description: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val iconUrl: String?,
    val changelog: String,
    val updatedAt: String,
)

object Catalog {
    private val RAW = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""")

    /** Downloads and parses the catalog, caching it for offline use. */
    suspend fun fetch(ctx: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val url = Prefs.feedUrl(ctx)
        val text = fetchText(url)
        val apps = parse(text, url)
        Prefs.setCachedFeed(ctx, text, url)
        apps
    }

    fun cached(ctx: Context): List<AppEntry> =
        Prefs.cachedFeed(ctx)?.let { (t, base) -> runCatching { parse(t, base) }.getOrNull() } ?: emptyList()

    private fun fetchText(url: String): String {
        // raw.githubusercontent.com is cached for ~5 minutes; the contents API is not,
        // so a fresh release shows up immediately. Fall back to raw if the API is rate limited.
        RAW.matchEntire(url)?.let { m ->
            val (owner, repo, ref, path) = m.destructured
            runCatching {
                return get("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$ref",
                    mapOf("Accept" to "application/vnd.github.raw"))
            }
        }
        val sep = if (url.contains('?')) '&' else '?'
        return get("$url${sep}t=${System.currentTimeMillis() / 60000}", emptyMap())
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.useCaches = false
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun parse(text: String, base: String): List<AppEntry> {
        val arr = JSONObject(text).getJSONArray("apps")
        fun abs(u: String?) = u?.takeIf { it.isNotBlank() }?.let { URL(URL(base), it).toString() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                packageName = o.getString("packageName"),
                description = o.optString("description"),
                versionCode = o.getLong("versionCode"),
                versionName = o.optString("versionName"),
                apkUrl = abs(o.getString("apkUrl"))!!,
                sha256 = o.optString("sha256"),
                size = o.optLong("size"),
                iconUrl = abs(o.optString("iconUrl")),
                changelog = o.optString("changelog"),
                updatedAt = o.optString("updatedAt"),
            )
        }
    }
}

object Packages {
    data class Installed(val versionCode: Long, val versionName: String?)

    fun installed(ctx: Context, pkg: String): Installed? = try {
        val pi = ctx.packageManager.getPackageInfo(pkg, 0)
        Installed(if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong(), pi.versionName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** True when Shelf is the installer of record, which lets Android 12+ update it without a prompt. */
    fun installedByUs(ctx: Context, pkg: String): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
        installer == ctx.packageName || pkg == ctx.packageName
    }.getOrDefault(false)

    fun hasUpdate(ctx: Context, app: AppEntry): Boolean =
        installed(ctx, app.packageName)?.let { app.versionCode > it.versionCode } ?: false
}
