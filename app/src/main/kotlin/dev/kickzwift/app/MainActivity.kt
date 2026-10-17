package dev.kickzwift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kickzwift.app.ui.KickZwiftTheme
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.gear.GearTable

/** Écran vide du socle (lot 2) : il prouve que les trois modules sont reliés. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KickZwiftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SocleScreen()
                }
            }
        }
    }
}

@Composable
private fun SocleScreen() {
    Column(
        modifier = Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.socle_status), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.socle_protocol, Button.entries.size, GearTable.ZWIFT.size),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview
@Composable
private fun SocleScreenPreview() {
    KickZwiftTheme { Surface { SocleScreen() } }
}
