package dev.kickzwift.app.ui.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityMode
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.StatLine

/** Une activité de l'historique : son bilan, son commentaire modifiable ; suppression confirmée. */
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    onBack: () -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(stringResource(R.string.activity_title), style = MaterialTheme.typography.headlineSmall)
        }
        val a = detail.activity
        when {
            a != null -> Details(a, settings.imperial, viewModel::saveComment, onDelete = { deleting = true })
            detail.loaded -> Text(stringResource(R.string.activity_not_found))
        }
    }

    if (deleting) {
        DeleteDialog(
            onConfirm = {
                deleting = false
                viewModel.delete()
                onBack()
            },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun DeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.activity_delete_title)) },
        text = { Text(stringResource(R.string.activity_delete_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.activity_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } },
    )
}

@Composable
private fun Details(
    a: Activity,
    imperial: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var comment by rememberSaveable(a.id) { mutableStateOf(a.comment) }
    Text(Format.dateTime(a.startedAtMs), style = MaterialTheme.typography.titleLarge)
    Text(modeLabel(a), style = MaterialTheme.typography.bodyMedium)
    StatLine(stringResource(R.string.summary_duration), Format.duration(a.durationMs))
    StatLine(stringResource(R.string.summary_distance), Format.distance(a.distanceM, imperial))
    StatLine(stringResource(R.string.summary_avg_power), stringResource(R.string.unit_w, a.avgPowerW))
    StatLine(stringResource(R.string.summary_max_power), stringResource(R.string.unit_w, a.maxPowerW))
    StatLine(stringResource(R.string.summary_cadence), stringResource(R.string.unit_rpm, a.avgCadenceRpm))
    if (a.mode != ActivityMode.ERG) StatLine(stringResource(R.string.summary_elevation), Format.elevation(a.elevationGainM, imperial))
    StatLine(stringResource(R.string.summary_shifts), a.shifts.toString())
    HorizontalDivider()
    CommentField(comment, onChange = { comment = it })
    Button(onClick = { onSave(comment) }, enabled = comment.trim() != a.comment) {
        Text(stringResource(R.string.activity_comment_save))
    }
    HorizontalDivider()
    OutlinedButton(onClick = onDelete) {
        Text(stringResource(R.string.activity_delete), color = MaterialTheme.colorScheme.error)
    }
}
