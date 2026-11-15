package com.devcode940.ForexAnalyst

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.devcode940.ForexAnalyst.data.settings.SettingsRepository
import com.devcode940.ForexAnalyst.ui.navigation.ForexNavHost
import com.devcode940.ForexAnalyst.ui.theme.ForexAnalystTheme

/**
 * Single-activity host for the Compose navigation graph.
 *
 * WHY: The theme preference is collected here (above the NavHost) so a change
 * in Settings recomposes the whole tree once, instead of every screen having
 * to know about appearance.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsRepository = SettingsRepository.getInstance(application)
        setContent {
            val settings by settingsRepository.settings.collectAsState()
            ForexAnalystTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ForexNavHost()
                }
            }
        }
    }
}
