package dev.kickzwift.app.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.service.RideService
import dev.kickzwift.app.ui.common.StatusDot
import dev.kickzwift.app.ui.common.kindLabel
import dev.kickzwift.app.ui.common.linkLabel
import dev.kickzwift.ble.scan.ScannedDevice
import dev.kickzwift.protocol.device.DeviceKind

/** Écran Connexions : cartes d'appareils, recherche, préparation de la sortie. */
@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onPrepare: () -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val (missing, batteryExempt, request) = rememberSetup()
    var forgetting by remember { mutableStateOf<DeviceCard?>(null) }

    // Les liaisons démarrent d'elles-mêmes dès que les permissions sont accordées.
    LaunchedEffect(missing.isEmpty()) { if (missing.isEmpty()) RideService.start(context) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineSmall) }
        if (missing.isNotEmpty() || !batteryExempt) {
            item {
                SetupCard(
                    permissionsMissing = missing.isNotEmpty(),
                    batteryExempt = batteryExempt,
                    onRequestPermissions = { request(missing.toTypedArray()) },
                    onRequestBattery = { context.requestBatteryExemption() },
                )
            }
        }
        if (!ui.serviceRunning && missing.isEmpty()) {
            item { OutlinedButton(onClick = { RideService.start(context) }) { Text(stringResource(R.string.devices_service_start)) } }
        }
        if (ui.cards.isEmpty()) item { Text(stringResource(R.string.devices_empty)) }
        val rides = ui.cards.filter { it.device.kind == DeviceKind.RIDE }
        items(ui.cards, key = { it.device.id.address }) { card ->
            DeviceCardView(card, rideIndex = rides.indexOf(card).takeIf { it >= 0 }?.plus(1), onLongPress = { forgetting = card })
        }
        if (ui.cards.isNotEmpty()) item { Text(stringResource(R.string.devices_forget_hint), style = MaterialTheme.typography.bodySmall) }
        item {
            SearchRow(ui.scan) {
                if (ui.scan == ScanStatus.Scanning) {
                    viewModel.stopScan()
                } else {
                    // Vérification écrite en clair : Lint ne la reconnaît pas derrière un utilitaire.
                    val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    if (scan == PackageManager.PERMISSION_GRANTED) viewModel.startScan() else request(RUNTIME_PERMISSIONS)
                }
            }
        }
        items(ui.found, key = { "found-" + it.id.address }) { FoundRow(it) { viewModel.trust(it) } }
        item {
            PrepareButton(ui.canPrepare) {
                viewModel.prepare()
                onPrepare()
            }
        }
    }

    forgetting?.let { card ->
        ForgetDialog(card, onConfirm = { viewModel.forget(card.device.id) }, onDismiss = { forgetting = null })
    }
}

@Composable
private fun ForgetDialog(
    card: DeviceCard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_forget_title, card.device.name)) },
        text = { Text(stringResource(R.string.devices_forget_text)) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.devices_forget)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } },
    )
}

@Composable
private fun PrepareButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(stringResource(R.string.devices_prepare))
    }
    if (!enabled) Text(stringResource(R.string.devices_prepare_hint), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCardView(
    card: DeviceCard,
    rideIndex: Int?,
    onLongPress: () -> Unit,
) {
    val colors =
        if (card.locked) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        } else {
            CardDefaults.cardColors()
        }
    Card(colors = colors, modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongPress)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(card.state)
                Column(Modifier.weight(1f)) {
                    val title = rideIndex?.let { stringResource(R.string.devices_ride_pod, it) } ?: card.device.name
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${stringResource(kindLabel(card.device.kind))} · ${card.device.id.address}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(linkLabel(card.state)), style = MaterialTheme.typography.labelLarge)
            }
            val details =
                listOfNotNull(
                    card.battery?.let { stringResource(R.string.devices_battery, it) },
                    card.firmware?.let { stringResource(R.string.devices_firmware, it) },
                )
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            if (card.locked) Text(stringResource(R.string.devices_locked), color = MaterialTheme.colorScheme.onErrorContainer)
            if (rideIndex == 1) Text(stringResource(R.string.devices_ride_tip), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SearchRow(
    scan: ScanStatus,
    onToggle: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.devices_found), style = MaterialTheme.typography.titleMedium)
            if (scan == ScanStatus.Scanning) {
                OutlinedButton(onClick = onToggle) { Text(stringResource(R.string.devices_search_stop)) }
            } else {
                Button(onClick = onToggle) { Text(stringResource(R.string.devices_search)) }
            }
        }
        (scan as? ScanStatus.Failed)?.let { failed ->
            Text(
                failed.code?.let { stringResource(R.string.devices_search_failed, it) } ?: stringResource(R.string.devices_search_denied),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FoundRow(
    device: ScannedDevice,
    onTrust: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: device.id.address, style = MaterialTheme.typography.titleSmall)
                Text("${stringResource(kindLabel(device.kind))} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onTrust) { Text(stringResource(R.string.devices_trust)) }
        }
    }
}
