package com.warpx.vpn

import android.app.Application
import com.warpx.vpn.data.Store

class App : Application() {
    companion object {
        lateinit var ctx: App
    }

    override fun onCreate() {
        super.onCreate()
        ctx = this
        seedBundledProfiles()
    }

    private fun seedBundledProfiles() {
        val prefs = getSharedPreferences("warpx", MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false)) return
        try {
            val names = assets.list("profiles") ?: emptyArray()
            var n = 0
            for (name in names) {
                val text = assets.open("profiles/$name").bufferedReader().readText()
                if (text.contains("[Interface]")) {
                    Store.addProfile(this, name, text)
                    n++
                }
            }
            if (n > 0) prefs.edit().putBoolean("seeded", true).apply()
        } catch (e: Exception) {
            // профилей в assets нет — не страшно
        }
    }
}
