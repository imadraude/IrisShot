package com.apricotiris.IrisShot

import android.app.Application
import com.apricotiris.IrisShot.ui.AppLocaleManager

class GameLongShotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.synchronizeFrameworkLocale(this)
    }
}
