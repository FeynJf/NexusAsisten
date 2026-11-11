package com.nexus.asisten

import android.app.Application
import com.nexus.asisten.data.Prefs

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
