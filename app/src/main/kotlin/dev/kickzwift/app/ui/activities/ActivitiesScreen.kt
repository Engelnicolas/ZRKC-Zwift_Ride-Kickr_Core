package dev.kickzwift.app.ui.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityMode
import dev.kickzwift.app.data.ActivityTotals
import dev.kickzwift.app.data.Total
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.HelpText

/**
 * Écran Activités : « Démarrer une activité », totaux du mois et de l'année, puis l'historique,
 * la plus récente en tête.
 */
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
        ui.totals?.let { t -> item { TotalsCard(t, ui.imperial) } }
        val list = ui.activities
        if (list != null && list.isEmpty()) item { Text(stringResource(R.string.activities_empty)) }
        items(list.orEmpty(), key = { it.id }) { a -> ActivityRow(a, ui.imperial, onClick = { onOpen(a.id) }) }
    }
}

/** Distance et temps du mois et de l'année en cours, en rouge sur l'encart. */
@Composable
private fun TotalsCard(
    totals: ActivityTotals,
    imperial: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TotalColumn(
                stringResource(R.string.activities_month, Format.month(totals.month)),
                totals.monthTotal,
                imperial,
                Modifier.weight(1f),
            )
            TotalColumn(stringResource(R.string.activities_year, totals.month.year), totals.yearTotal, imperial, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TotalColumn(
    title: String,
    total: Total,
    imperial: Boolean,
    modifier: Modifier,
) {
    val on = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = on, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            Format.distance(total.distanceM, imperial),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = on,
        )
        Text(Format.hours(total.durationMs), style = MaterialTheme.typography.titleMedium, color = on)
    }
}

/** Une sortie : liseré rouge, date et mode, trois mesures, commentaire. */
@Composable
private fun ActivityRow(
    a: Activity,
    imperial: Boolean,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(ACCENT).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Format.dateTime(a.startedAtMs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        modeLabel(a),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier
                                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth()) {
                    RowStat(stringResource(R.string.activities_time), Format.duration(a.durationMs), Modifier.weight(1f))
                    RowStat(stringResource(R.string.activities_distance), Format.distance(a.distanceM, imperial), Modifier.weight(1f))
                    RowStat(
                        stringResource(R.string.activities_avg_power),
                        stringResource(R.string.unit_w, a.avgPowerW),
                        Modifier.weight(1f),
                    )
                }
                if (a.comment.isNotEmpty()) {
                    Text(
                        a.comment,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Mesure d'une carte : petit libellé, valeur en gras. */
@Composable
private fun RowStat(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
private val ACCENT = 6.dp
