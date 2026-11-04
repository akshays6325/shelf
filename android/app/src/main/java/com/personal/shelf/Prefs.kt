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

    /** True the first time it's called for this "package:versionCode": limits failure notices to one per version. */
    fun firstTime(ctx: Context, key: String): Boolean {
        val pkg = key.substringBefore(':')
        if (p(ctx).getString("failed:$pkg", null) == key) return false
        p(ctx).edit().putString("failed:$pkg", key).apply()
        return true
    }

    fun reviewsUrl(ctx: Context): String = p(ctx).getString("reviews", null) ?: BuildConfig.REVIEWS_URL
    fun setReviewsUrl(ctx: Context, url: String?) = p(ctx).edit().putString("reviews", url?.trim()).apply()

    fun sessionToken(ctx: Context): String? = p(ctx).getString("session", null)
    fun setSessionToken(ctx: Context, t: String?) = p(ctx).edit().putString("session", t).apply()

    fun userJson(ctx: Context): String? = p(ctx).getString("user", null)
    fun setUserJson(ctx: Context, s: String?) = p(ctx).edit().putString("user", s).apply()

    fun cachedRatings(ctx: Context): String? = p(ctx).getString("ratings", null)
    fun setCachedRatings(ctx: Context, s: String) = p(ctx).edit().putString("ratings", s).apply()
}
