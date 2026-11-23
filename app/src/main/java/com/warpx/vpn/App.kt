package com.warpx.vpn

import android.app.Application

class App : Application() {
    companion object {
        lateinit var ctx: App
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
    }
}
