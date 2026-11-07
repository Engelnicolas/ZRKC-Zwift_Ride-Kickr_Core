package dev.kickzwift.app.ui.activities

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.protocol.fit.CurvePoint
import dev.kickzwift.protocol.fit.RideCurve

/** Échelle serrée sur les deux courbes, bornes arrondies au multiple de [SCALE_STEP] qui les encadre. */
internal fun chartScale(points: List<CurvePoint>): IntRange {
    val values = points.flatMap { listOfNotNull(it.powerW, it.heartRate) }
    val bottom = (values.minOrNull() ?: 0) / SCALE_STEP * SCALE_STEP
    val top = ((values.maxOrNull() ?: 0) + SCALE_STEP - 1) / SCALE_STEP * SCALE_STEP
    return bottom..maxOf(top, bottom + SCALE_STEP)
}

/**
 * Puissance en ligne à la couleur du mois et fréquence cardiaque en ligne neutre, sur une seule
 * échelle : watts et bpm tiennent dans les mêmes valeurs, sans second axe. L'échelle ne part pas
 * de zéro, d'où des lignes sans aplat : une surface ferait lire une hauteur qui n'existe pas.
 */
@Composable
fun RideCurveChart(
    curve: RideCurve,
    durationMs: Long,
    accent: Color,
) {
    val points = curve.points
    val hasHeartRate = points.any { it.heartRate != null }
    val scale = chartScale(points)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val maxPower = points.maxOf { it.powerW }
    val description =
        if (hasHeartRate) {
            stringResource(R.string.activity_chart_description, maxPower, points.maxOf { it.heartRate ?: 0 })
        } else {
            stringResource(R.string.activity_chart_description_power, maxPower)
        }
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem(accent, stringResource(R.string.activity_chart_power))
            if (hasHeartRate) LegendItem(MaterialTheme.colorScheme.onSurface, stringResource(R.string.activity_chart_heart_rate))
        }
        Row(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
            Box(Modifier.width(SCALE_WIDTH).fillMaxHeight()) {
                ScaleLabel(scale.last, Alignment.TopStart)
                ScaleLabel((scale.first + scale.last) / 2, Alignment.CenterStart)
                ScaleLabel(scale.first, Alignment.BottomStart)
            }
            Plot(points, scale, accent, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().padding(start = SCALE_WIDTH), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.trackTime(0), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(Format.trackTime(durationMs), style = MaterialTheme.typography.labelSmall, color = muted)
        }
    }
}

@Composable
private fun BoxScope.ScaleLabel(
    value: Int,
    alignment: Alignment,
) {
    Text(
        value.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(alignment),
    )
}

@Composable
private fun LegendItem(
    color: Color,
    label: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 3.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Plot(
    points: List<CurvePoint>,
    scale: IntRange,
    accent: Color,
    modifier: Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val heartRate = MaterialTheme.colorScheme.onSurface
    val ring = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        fun x(i: Int) = size.width * i / (points.size - 1)

        fun y(value: Int) = size.height * (1 - (value - scale.first).toFloat() / (scale.last - scale.first))

        val hairline = 1.dp.toPx()
        listOf(0f, size.height / 2, size.height).forEach { y ->
            drawLine(grid, Offset(0f, y), Offset(size.width, y), hairline)
        }
        val power = Path()
        points.forEachIndexed { i, p -> if (i == 0) power.moveTo(x(i), y(p.powerW)) else power.lineTo(x(i), y(p.powerW)) }
        drawCurve(power, accent, LINE)
        val beats = Path()
        points.forEachIndexed { i, p ->
            val value = p.heartRate
            when {
                value == null -> Unit
                i == 0 || points[i - 1].heartRate == null -> beats.moveTo(x(i), y(value))
                else -> beats.lineTo(x(i), y(value))
            }
        }
        drawCurve(beats, ring, LINE + RING * 2)
        drawCurve(beats, heartRate, LINE)
    }
}

private fun DrawScope.drawCurve(
    path: Path,
    color: Color,
    width: Dp,
) {
    drawPath(path, color, style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private const val SCALE_STEP = 20
private val CHART_HEIGHT = 88.dp
private val SCALE_WIDTH = 32.dp
private val LINE = 2.dp
private val RING = 2.dp
