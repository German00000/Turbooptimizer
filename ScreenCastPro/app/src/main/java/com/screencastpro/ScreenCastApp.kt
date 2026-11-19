package com.screencastpro

import android.app.Application
import android.os.Build

class ScreenCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        DiagLog.i("App", "inicio: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }
}
