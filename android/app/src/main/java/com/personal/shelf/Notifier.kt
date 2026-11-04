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

object Notifier {
    private const val CH_UPDATES = "updates"
    private const val CH_INSTALLED = "installed"
    private const val ID_AVAILABLE = 1

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Available updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_INSTALLED, "Installed updates", NotificationManager.IMPORTANCE_LOW))
    }

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("MissingPermission")
    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        if (canPost(ctx)) NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    fun updatesAvailable(ctx: Context, apps: List<AppEntry>) {
        if (apps.isEmpty()) {
            NotificationManagerCompat.from(ctx).cancel(ID_AVAILABLE)
            return
        }
        val title = if (apps.size == 1) "${apps[0].name} ${apps[0].versionName} is available" else "${apps.size} updates available"
        post(ctx, ID_AVAILABLE, NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle(title)
            .setContentText(apps.joinToString(", ") { it.name })
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true))
    }

    fun confirmInstall(ctx: Context, pkg: String, name: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(ctx, pkg.hashCode(), confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(ctx, pkg.hashCode(), NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle("Update $name")
            .setContentText("Tap to finish installing")
            .setContentIntent(pi)
            .setAutoCancel(true))
    }

    fun clearConfirm(ctx: Context, pkg: String) = NotificationManagerCompat.from(ctx).cancel(pkg.hashCode())

    fun updated(ctx: Context, pkg: String, name: String, version: String) {
        post(ctx, pkg.hashCode() + 1, NotificationCompat.Builder(ctx, CH_INSTALLED)
            .setSmallIcon(R.drawable.ic_stat_shelf)
            .setContentTitle("$name updated")
            .setContentText(if (version.isNotBlank()) "Now on $version" else "Update installed")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true))
    }
}
