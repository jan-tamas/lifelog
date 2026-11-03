package net.tamasoft.lifelog

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LifelogService.channel(this)
    }
}
