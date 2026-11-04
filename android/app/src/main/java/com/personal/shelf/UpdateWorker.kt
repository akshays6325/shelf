package com.personal.shelf

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Checks the catalog every 15 minutes, notifies about new versions and installs them silently where Android allows it. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val apps = runCatching { Catalog.fetch(ctx) }.getOrElse { return Result.retry() }
        Prefs.setLastChecked(ctx, System.currentTimeMillis())
        runUpdates(ctx, apps, background = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "shelf-updates"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Updates that Android lets Shelf install without a prompt (Android 12+, apps Shelf installed). */
        fun silentUpdates(ctx: Context, apps: List<AppEntry>): List<AppEntry> =
            if (Prefs.autoUpdate(ctx) && Build.VERSION.SDK_INT >= 31)
                apps.filter { Packages.hasUpdate(ctx, it) && Packages.installedByUs(ctx, it.packageName) }
            else emptyList()

        /** Announces each new version of an installed app once, then installs what can be installed silently. */
        suspend fun runUpdates(ctx: Context, apps: List<AppEntry>, background: Boolean) {
            val silent = silentUpdates(ctx, apps)

            // With notifications off nothing is marked as announced, so turning them on later still tells you.
            if (background && Notifier.enabled(ctx)) {
                val updates = apps.filter { Packages.hasUpdate(ctx, it) }
                fun key(a: AppEntry) = "${a.packageName}:${a.versionCode}"
                val announced = Prefs.notified(ctx)
                updates.filter { key(it) !in announced }.forEach { Notifier.available(ctx, it, it in silent) }
                Prefs.setNotified(ctx, updates.map(::key).toSet())
            }

            // Shelf updating itself restarts it, so do that last.
            silent.sortedBy { it.packageName == ctx.packageName }.forEach { Installer.install(ctx, it, background) }
        }
    }
}
