package com.personal.shelf

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SelfUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifier.updated(ctx, ctx.packageName, "Shelf", BuildConfig.VERSION_NAME)
    }
}
