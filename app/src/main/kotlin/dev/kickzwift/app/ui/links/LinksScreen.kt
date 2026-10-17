package dev.kickzwift.app.ui.links

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.service.RideService
import dev.kickzwift.ble.scan.ScannedDevice
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.DeviceKind

@Composable
fun LinksScreen(viewModel: LinksViewModel) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val (missing, batteryExempt, request) = rememberSetup()

    LazyColumn(
        modifier = Modifier.safeDrawingPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(stringResource(R.string.links_title), style = MaterialTheme.typography.headlineSmall) }

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

        item {
            ServiceCard(
                running = ui.serviceRunning,
                canStart = Manifest.permission.BLUETOOTH_CONNECT !in missing,
                onStart = { RideService.start(context) },
                onStop = { RideService.stop(context) },
            )
        }

        item { Text(stringResource(R.string.links_trusted), style = MaterialTheme.typography.titleMedium) }
        if (ui.trusted.isEmpty()) {
            item { Text(stringResource(R.string.links_trusted_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(ui.trusted, key = { it.device.id.address }) { row ->
            TrustedDeviceRow(row, onForget = { viewModel.forget(row.device.id) })
        }

        item {
            SearchHeader(
                scanning = ui.scan == ScanStatus.Scanning,
                onStart = {
                    // Vérification écrite en clair : Lint ne la reconnaît pas derrière granted().
                    val scanGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    if (scanGranted == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startScan()
                    } else {
                        request(RUNTIME_PERMISSIONS)
                    }
                },
                onStop = viewModel::stopScan,
            )
        }
        (ui.scan as? ScanStatus.Failed)?.let { failed -> item { ScanError(failed) } }
        items(ui.found, key = { "found-" + it.id.address }) { device ->
            FoundRow(device, onTrust = { viewModel.trust(device) })
        }
    }
}

@Composable
private fun TrustedDeviceRow(
    row: TrustedRow,
    onForget: () -> Unit,
) {
    DeviceRow(
        name = row.device.name,
        detail = "${stringResource(row.device.kind.label())} · ${row.device.id.address}",
        status = stringResource(row.state.label()),
        action = stringResource(R.string.links_forget),
        onAction = onForget,
    )
}

@Composable
private fun SearchHeader(
    scanning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.links_search), style = MaterialTheme.typography.titleMedium)
        if (scanning) {
            OutlinedButton(onClick = onStop) { Text(stringResource(R.string.links_search_stop)) }
        } else {
            Button(onClick = onStart) { Text(stringResource(R.string.links_search_start)) }
        }
    }
}

@Composable
private fun ScanError(failed: ScanStatus.Failed) {
    Text(
        if (failed.code == null) {
            stringResource(R.string.links_search_denied)
        } else {
            stringResource(R.string.links_search_failed, failed.code)
        },
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun FoundRow(
    device: ScannedDevice,
    onTrust: () -> Unit,
) {
    DeviceRow(
        name = device.name ?: device.id.address,
        detail = "${stringResource(device.kind.label())} · ${device.rssi} dBm",
        status = null,
        action = stringResource(R.string.links_trust),
        onAction = onTrust,
    )
}

@Composable
private fun ServiceCard(
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(if (running) R.string.links_service_on else R.string.links_service_off))
            if (running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.links_service_stop)) }
            } else {
                Button(onClick = onStart, enabled = canStart) { Text(stringResource(R.string.links_service_start)) }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    detail: String,
    status: String?,
    action: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall)
                status?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            }
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@StringRes
private fun DeviceKind.label(): Int =
    when (this) {
        DeviceKind.RIDE -> R.string.kind_ride
        DeviceKind.KICKR -> R.string.kind_kickr
        DeviceKind.OTHER -> R.string.kind_other
    }

@StringRes
private fun LinkState?.label(): Int =
    when (this) {
        null -> R.string.link_service_off
        LinkState.NOT_FOUND -> R.string.link_not_found
        LinkState.SCANNING -> R.string.link_scanning
        LinkState.CONNECTING -> R.string.link_connecting
        LinkState.HANDSHAKE -> R.string.link_handshake
        LinkState.READY -> R.string.link_ready
        LinkState.RECONNECTING -> R.string.link_reconnecting
        LinkState.LOST -> R.string.link_lost
    }
