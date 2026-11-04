package com.personal.shelf

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * One notification per app. It goes through "available" -> "updated" (or "failed") in place,
 * making a sound only when it first appears.
 */
object Notifier {
    private const val CH_UPDATES = "updates"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        // Left over from Shelf 1.3 and older
        nm.deleteNotificationChannel("installed")
        nm.cancel(1)
    }

    fun enabled(ctx: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun builder(ctx: Context) = NotificationCompat.Builder(ctx, CH_UPDATES)
        .setSmallIcon(R.drawable.ic_stat_shelf)
        .setContentIntent(openApp(ctx))
        .setOnlyAlertOnce(true)
        .setAutoCancel(true)

    @Suppress("MissingPermission")
    private fun post(ctx: Context, pkg: String, b: NotificationCompat.Builder) {
        if (enabled(ctx)) NotificationManagerCompat.from(ctx).notify(pkg.hashCode(), b.build())
    }

    fun clear(ctx: Context, pkg: String) = NotificationManagerCompat.from(ctx).cancel(pkg.hashCode())

    /** An installed app has a new version. [auto] means Shelf is installing it without asking. */
    fun available(ctx: Context, app: AppEntry, auto: Boolean) {
        val status = if (auto) "Installing automatically" else "Tap to open Shelf and update"
        val b = builder(ctx)
            .setContentTitle("${app.name} ${app.versionName} is available")
            .setContentText(status)
        if (app.changelog.isNotBlank()) {
            b.setStyle(NotificationCompat.BigTextStyle().bigText("${app.changelog}\n\n$status"))
        }
        post(ctx, app.packageName, b)
    }

    fun confirmInstall(ctx: Context, pkg: String, name: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, pkg.hashCode(), confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, pkg, builder(ctx)
            .setContentTitle("Update $name")
            .setContentText("Tap to finish installing")
            .setContentIntent(pi))
    }

    fun updated(ctx: Context, pkg: String, name: String, version: String) {
        post(ctx, pkg, builder(ctx)
            .setContentTitle("$name updated")
            .setContentText(if (version.isNotBlank()) "Now on $version" else "Update installed"))
    }

    /** A background update didn't install. Said once per version, though Shelf keeps retrying. */
    fun updateFailed(ctx: Context, pkg: String, name: String, version: String, versionCode: Long) {
        if (!Prefs.firstTime(ctx, "$pkg:$versionCode")) return
        post(ctx, pkg, builder(ctx)
            .setContentTitle("Couldn't update $name to $version")
            .setContentText("Shelf will try again. Tap to open it."))
    }
}
