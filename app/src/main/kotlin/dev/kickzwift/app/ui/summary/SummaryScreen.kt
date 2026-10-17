package dev.kickzwift.app.ui.summary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.shareFile
import dev.kickzwift.protocol.session.RideMode

@Composable
fun SummaryScreen(
    viewModel: SummaryViewModel,
    onClosed: () -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.session.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats = ui.snapshot.stats
    val imperial = settings.imperial
    val close = {
        viewModel.close()
        onClosed()
    }
    BackHandler(onBack = close)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.headlineSmall)
        Line(stringResource(R.string.summary_duration), Format.duration(stats.elapsedMs))
        Line(stringResource(R.string.summary_distance), Format.distance(stats.distanceM, imperial))
        Line(stringResource(R.string.summary_avg_power), stringResource(R.string.unit_w, stats.avgPowerW))
        Line(stringResource(R.string.summary_max_power), stringResource(R.string.unit_w, stats.maxPowerW))
        Line(stringResource(R.string.summary_cadence), stringResource(R.string.unit_rpm, stats.avgCadenceRpm))
        if (ui.snapshot.mode !is RideMode.Erg) {
            Line(
                stringResource(R.string.summary_elevation),
                Format.elevation(stats.elevationGainM, imperial),
            )
        }
        Line(stringResource(R.string.summary_shifts), stats.shifts.toString())
        HorizontalDivider()
        Text(stringResource(R.string.summary_coros))
        ui.fitFile?.let { file ->
            OutlinedButton(
                onClick = { context.shareFile(file, "application/vnd.ant.fit") },
            ) { Text(stringResource(R.string.summary_share_fit)) }
        }
        Button(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.summary_close)) }
    }
}

@Composable
private fun Line(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}
