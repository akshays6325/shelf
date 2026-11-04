package com.personal.shelf

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ShelfApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        UpdateWorker.schedule(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { inForeground = true }
            override fun onStop(owner: LifecycleOwner) { inForeground = false }
        })
    }

    companion object {
        @Volatile var inForeground = false
    }
}
