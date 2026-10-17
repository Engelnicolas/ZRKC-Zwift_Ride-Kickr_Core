package dev.kickzwift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.kickzwift.app.ui.KickZwiftTheme
import dev.kickzwift.app.ui.links.LinksScreen
import dev.kickzwift.app.ui.links.LinksViewModel

/** Seule activité. Lot 4 : écran provisoire des liaisons, en attendant les écrans du lot 8. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KickZwiftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LinksScreen(viewModel(factory = LinksViewModel.factory(container)))
                }
            }
        }
    }
}
