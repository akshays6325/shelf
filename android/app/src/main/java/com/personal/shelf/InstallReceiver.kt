package com.personal.shelf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val name = intent.getStringExtra(EXTRA_NAME) ?: pkg
        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                if (ShelfApp.inForeground) {
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // Android blocks opening screens from the background, so ask through a notification.
                    Notifier.confirmInstall(ctx, pkg, name, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Installer.set(pkg, InstallState.Idle)
                Installer.changed.tryEmit(pkg)
                Notifier.clearConfirm(ctx, pkg)
                if (!ShelfApp.inForeground) Notifier.updated(ctx, pkg, name, version)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Installer.set(pkg, InstallState.Idle)
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed ($status)"
                val friendly = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || msg.contains("signature", true))
                    "The installed copy is signed with a different key. Uninstall it, then install from Shelf."
                else msg
                Installer.set(pkg, InstallState.Failed(friendly))
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "shelf.pkg"
        const val EXTRA_NAME = "shelf.name"
        const val EXTRA_VERSION = "shelf.version"
        const val EXTRA_BACKGROUND = "shelf.background"
    }
}
