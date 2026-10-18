package dev.kickzwift.app.ui.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityMode
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.HelpText

/** Écran Activités : « Démarrer une activité », puis l'historique, la plus récente en tête. */
@Composable
fun ActivitiesScreen(
    viewModel: ActivitiesViewModel,
    onPrepare: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.activities_title), style = MaterialTheme.typography.headlineSmall) }
        item {
            Button(
                onClick = {
                    viewModel.prepare()
                    onPrepare()
                },
                enabled = ui.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
            ) { Text(stringResource(R.string.activities_start)) }
            if (!ui.canStart) HelpText(stringResource(R.string.devices_prepare_hint))
        }
        val list = ui.activities
        if (list != null && list.isEmpty()) item { Text(stringResource(R.string.activities_empty)) }
        items(list.orEmpty(), key = { it.id }) { a -> ActivityRow(a, ui.imperial, onClick = { onOpen(a.id) }) }
    }
}

@Composable
private fun ActivityRow(
    a: Activity,
    imperial: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.dateTime(a.startedAtMs), style = MaterialTheme.typography.titleMedium)
            Text(modeLabel(a), style = MaterialTheme.typography.bodySmall)
            Text(
                listOf(
                    Format.duration(a.durationMs),
                    Format.distance(a.distanceM, imperial),
                    stringResource(R.string.unit_w, a.avgPowerW),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (a.comment.isNotEmpty()) {
                Text(
                    a.comment,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Mode de la sortie, suivi du nom du parcours s'il y en a un. */
@Composable
fun modeLabel(a: Activity): String {
    val mode =
        stringResource(
            when (a.mode) {
                ActivityMode.FREE -> R.string.mode_free
                ActivityMode.ROUTE -> R.string.mode_route
                ActivityMode.ERG -> R.string.mode_erg
            },
        )
    return a.routeName?.let { "$mode · $it" } ?: mode
}

/** Commentaire rapide sur une sortie, borné à [Activity.MAX_COMMENT] caractères. */
@Composable
fun CommentField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(Activity.MAX_COMMENT)) },
        modifier = modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.activity_comment)) },
        placeholder = { Text(stringResource(R.string.activity_comment_hint)) },
        supportingText = { Text("${value.length}/${Activity.MAX_COMMENT}") },
        minLines = 2,
        maxLines = COMMENT_LINES,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
    )
}

private const val COMMENT_LINES = 5
