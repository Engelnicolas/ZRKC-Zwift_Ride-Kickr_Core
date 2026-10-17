package dev.kickzwift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.service.RideService
import dev.kickzwift.app.ui.AppNav
import dev.kickzwift.app.ui.KickZwiftTheme
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.settings.ThemeChoice

/** Seule activité de l'app ; les écrans sont des destinations Navigation Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        setContent {
            val settings by c.settings.collectAsStateWithLifecycle()
            val dark =
                when (settings.theme) {
                    ThemeChoice.DARK -> true
                    ThemeChoice.LIGHT -> false
                    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                }
            KickZwiftTheme(dark = dark) {
                Surface(modifier = Modifier.fillMaxSize()) { AppNav(c) }
            }
        }
    }

    override fun onDestroy() {
        // L'app quittée hors sortie : inutile de garder les liaisons ouvertes.
        if (isFinishing && container.session.state.value.snapshot.phase == RidePhase.IDLE) RideService.stop(this)
        super.onDestroy()
    }
}
