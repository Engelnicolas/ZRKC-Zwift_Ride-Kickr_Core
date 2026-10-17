package dev.kickzwift.app.ui.mode

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.route.GpxException
import dev.kickzwift.app.route.RouteData
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.ProfileChart
import dev.kickzwift.app.ui.common.Stepper

/** Écran Choix du mode : pente libre, parcours GPX ou ERG, puis « Démarrer ». */
@Composable
fun ModeScreen(
    viewModel: ModeViewModel,
    onStarted: () -> Unit,
    onBack: () -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.import(it) }
        }
    val back = {
        viewModel.cancel()
        onBack()
    }
    BackHandler(onBack = back)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back) { Text(stringResource(R.string.back)) }
                Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item { ModeChoices(ui.choice, viewModel::select) }
        when (ui.choice) {
            ModeChoice.FREE ->
                item {
                    Text(stringResource(R.string.mode_start_grade))
                    Stepper(Format.pct(ui.startGradePct), onMinus = { viewModel.stepGrade(false) }, onPlus = { viewModel.stepGrade(true) })
                }
            ModeChoice.ERG ->
                item {
                    Text(stringResource(R.string.mode_erg_target))
                    Stepper(
                        stringResource(R.string.unit_w, ui.ergWatts),
                        onMinus = { viewModel.stepErg(false) },
                        onPlus = { viewModel.stepErg(true) },
                    )
                    Text(stringResource(R.string.mode_erg_note), style = MaterialTheme.typography.bodySmall)
                }
            ModeChoice.ROUTE -> routeItems(ui, viewModel) { pick.launch(GPX_TYPES) }
        }
        item {
            Button(
                onClick = { if (viewModel.start()) onStarted() },
                enabled = ui.canStart && !ui.loading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.mode_start)) }
        }
    }
}

@Composable
private fun ModeChoices(
    selected: ModeChoice,
    onSelect: (ModeChoice) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        ModeChoice.entries.forEach { choice ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected == choice, role = Role.RadioButton, onClick = { onSelect(choice) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == choice, onClick = null)
                Text(stringResource(label(choice)), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private fun LazyListScope.routeItems(
    ui: ModeUi,
    viewModel: ModeViewModel,
    onImport: () -> Unit,
) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onImport) { Text(stringResource(R.string.mode_import)) }
            if (ui.loading) CircularProgressIndicator()
        }
        ui.error?.let { Text(stringResource(R.string.mode_route_error, routeError(it)), color = MaterialTheme.colorScheme.error) }
    }
    item { RoutePreview(ui.route, ui.imperial) }
    if (ui.recent.isNotEmpty()) item { Text(stringResource(R.string.mode_recent), style = MaterialTheme.typography.titleMedium) }
    items(ui.recent, key = { it.file }) { r ->
        val selected = ui.route?.route?.file == r.file
        TextButton(onClick = { viewModel.pick(r) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected) "● ${r.name}" else r.name, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Les GPX arrivent avec des types MIME variés selon l'app qui les fournit.
@Composable
private fun routeError(e: Throwable): String =
    if (e is GpxException) {
        when (e.reason) {
            GpxException.Reason.INVALID -> stringResource(R.string.route_error_invalid)
            GpxException.Reason.READ -> stringResource(R.string.route_error_read)
            GpxException.Reason.NOT_FOUND -> stringResource(R.string.route_error_not_found)
            GpxException.Reason.TOO_LARGE -> stringResource(R.string.route_error_too_large, e.limit)
            GpxException.Reason.TOO_MANY_POINTS -> stringResource(R.string.route_error_too_many_points, e.limit)
            GpxException.Reason.TOO_FEW_POINTS -> stringResource(R.string.route_error_too_few_points)
        }
    } else {
        stringResource(R.string.route_error_other)
    }

private val GPX_TYPES = arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")

@Composable
private fun RoutePreview(
    route: RouteData?,
    imperial: Boolean,
) {
    if (route == null) {
        Text(stringResource(R.string.mode_no_route))
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(route.route.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(
                    R.string.mode_route_stats,
                    Format.distance(route.distanceM, imperial),
                    Format.elevation(route.elevationGainM, imperial),
                    Format.pct(route.maxGradePct),
                ),
            )
            ProfileChart(route.elevations, Modifier.fillMaxWidth().height(96.dp))
        }
    }
}

private fun label(choice: ModeChoice) =
    when (choice) {
        ModeChoice.FREE -> R.string.mode_free
        ModeChoice.ROUTE -> R.string.mode_route
        ModeChoice.ERG -> R.string.mode_erg
    }
