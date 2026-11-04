package com.personal.shelf

import android.content.Context

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    fun feedUrl(ctx: Context): String = p(ctx).getString("feed", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.FEED_URL
    fun setFeedUrl(ctx: Context, url: String?) = p(ctx).edit().putString("feed", url?.trim()).apply()

    fun autoUpdate(ctx: Context) = p(ctx).getBoolean("auto", true)
    fun setAutoUpdate(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("auto", on).apply()

    fun lastChecked(ctx: Context) = p(ctx).getLong("checked", 0L)
    fun setLastChecked(ctx: Context, t: Long) = p(ctx).edit().putLong("checked", t).apply()

    fun cachedFeed(ctx: Context): Pair<String, String>? {
        val text = p(ctx).getString("feedText", null) ?: return null
        return text to (p(ctx).getString("feedBase", null) ?: feedUrl(ctx))
    }
    fun setCachedFeed(ctx: Context, text: String, base: String) =
        p(ctx).edit().putString("feedText", text).putString("feedBase", base).apply()

    /** The set of "package:versionCode" we last notified about, so each update is announced once. */
    fun notified(ctx: Context): Set<String> = p(ctx).getStringSet("notified", emptySet()) ?: emptySet()
    fun setNotified(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("notified", s).apply()
}
