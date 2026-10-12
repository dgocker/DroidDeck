package com.droiddeck.launcher.diagnostics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.GameDiagnosticsScreen

/** Game diagnostics, opened from Setup's Diagnostics tab: [GameDiagnosticsScreen]. */
class DiagnosticsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = com.droiddeck.launcher.session.SessionPrefs.orientation(this)
        DiagnosticsManager.init(this)
        setContent {
            DroidDeckTheme(appScale = com.droiddeck.launcher.core.AppUiPrefs.scale(this)) {
                Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    GameDiagnosticsScreen(onClose = { finish() })
                }
            }
        }
    }
}
