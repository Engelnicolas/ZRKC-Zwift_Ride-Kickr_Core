package dev.kickzwift.app.ui.devices

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.kickzwift.app.R

// Préalables d'une liaison : permissions d'exécution et exemption de l'optimisation de batterie.

/** Permissions demandées à l'exécution (spécification, « Sécurité »). */
internal val RUNTIME_PERMISSIONS =
    buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

internal fun Context.granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Permissions manquantes et exemption de batterie, relues au retour à l'écran. */
internal data class Setup(
    val missing: List<String>,
    val batteryExempt: Boolean,
    val request: (Array<String>) -> Unit,
)

@Composable
internal fun rememberSetup(): Setup {
    val context = LocalContext.current
    // L'utilisateur a pu changer une permission dans les réglages entre-temps.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missing = remember(refresh) { RUNTIME_PERMISSIONS.filterNot { context.granted(it) } }
    val batteryExempt =
        remember(refresh) {
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
    return Setup(missing, batteryExempt) { launcher.launch(it) }
}

@Composable
internal fun SetupCard(
    permissionsMissing: Boolean,
    batteryExempt: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBattery: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (permissionsMissing) {
                Text(stringResource(R.string.links_permissions_needed))
                Button(onClick = onRequestPermissions) { Text(stringResource(R.string.links_permissions_grant)) }
            }
            if (!batteryExempt) {
                Text(stringResource(R.string.links_battery_needed))
                OutlinedButton(onClick = onRequestBattery) { Text(stringResource(R.string.links_battery_grant)) }
            }
        }
    }
}

// Exigée par la spécification : sans elle, certains constructeurs coupent l'app écran éteint.
@SuppressLint("BatteryLife")
internal fun Context.requestBatteryExemption() {
    startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:$packageName".toUri()),
    )
}
