package com.apricotiris.IrisShot.result

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.LocalizedComponentActivity
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme

class ResultListActivity : LocalizedComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = AppPreferences(this).loadSettings()

        setContent {
            GameLongShotTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                ResultListRoute(
                    isActive = { true },
                    refreshKey = Unit,
                    onBack = ::finish,
                )
            }
        }
    }
}
