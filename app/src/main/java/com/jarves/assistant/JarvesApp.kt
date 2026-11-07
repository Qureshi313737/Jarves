package com.jarves.assistant

import android.app.Application

class JarvesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
