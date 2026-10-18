package dev.kickzwift.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.ReadyGreen
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.Button
import dev.kickzwift.protocol.Gesture
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.device.DeviceKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * État d'un appareil par la forme autant que par la couleur (spécification, « Interface ») :
 * pastille pleine si prêt, cerclée pendant une connexion, barrée si perdu ou absent.
 */
@Composable
fun StatusDot(
    state: LinkState?,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(linkLabel(state))
    Canvas(modifier.size(size).semantics { contentDescription = label }) {
        val r = this.size.minDimension / 2
        val stroke = r / 3
        when (state) {
            LinkState.READY -> drawCircle(ReadyGreen, r)
            LinkState.CONNECTING, LinkState.HANDSHAKE, LinkState.RECONNECTING, LinkState.SCANNING ->
                drawCircle(colors.tertiary, r - stroke / 2, style = Stroke(stroke))
            else -> {
                drawCircle(colors.error, r - stroke / 2, style = Stroke(stroke))
                drawLine(colors.error, Offset(0f, this.size.height), Offset(this.size.width, 0f), stroke)
            }
        }
    }
}

@StringRes
fun linkLabel(state: LinkState?): Int =
    when (state) {
        null -> R.string.link_service_off
        LinkState.NOT_FOUND -> R.string.link_not_found
        LinkState.SCANNING -> R.string.link_scanning
        LinkState.CONNECTING -> R.string.link_connecting
        LinkState.HANDSHAKE -> R.string.link_handshake
        LinkState.READY -> R.string.link_ready
        LinkState.RECONNECTING -> R.string.link_reconnecting
        LinkState.LOST -> R.string.link_lost
    }

@StringRes
fun kindLabel(kind: DeviceKind): Int =
    when (kind) {
        DeviceKind.RIDE -> R.string.kind_ride
        DeviceKind.KICKR -> R.string.kind_kickr
        DeviceKind.HEART_RATE -> R.string.kind_heart_rate
        DeviceKind.OTHER -> R.string.kind_other
    }

@StringRes
fun buttonLabel(button: Button): Int = BUTTON_LABELS.getValue(button)

private val BUTTON_LABELS: Map<Button, Int> =
    mapOf(
        Button.UP to R.string.button_up,
        Button.DOWN to R.string.button_down,
        Button.LEFT to R.string.button_left,
        Button.RIGHT to R.string.button_right,
        Button.SHIFT_UP_LEFT to R.string.button_shift_up_left,
        Button.SHIFT_DOWN_LEFT to R.string.button_shift_down_left,
        Button.POWER_UP_LEFT to R.string.button_power_up_left,
        Button.PADDLE_LEFT to R.string.button_paddle_left,
        Button.A to R.string.button_a,
        Button.B to R.string.button_b,
        Button.Y to R.string.button_y,
        Button.Z to R.string.button_z,
        Button.SHIFT_UP_RIGHT to R.string.button_shift_up_right,
        Button.SHIFT_DOWN_RIGHT to R.string.button_shift_down_right,
        Button.POWER_UP_RIGHT to R.string.button_power_up_right,
        Button.PADDLE_RIGHT to R.string.button_paddle_right,
    )

@StringRes
fun gestureLabel(gesture: Gesture): Int =
    when (gesture) {
        Gesture.PRESS -> R.string.gesture_press
        Gesture.LONG_PRESS -> R.string.gesture_long
        Gesture.DOUBLE_PRESS -> R.string.gesture_double
    }

@Composable
fun actionText(action: Action): String =
    when (action) {
        is Action.Shift -> stringResource(R.string.action_shift, action.delta)
        is Action.Grade -> stringResource(R.string.action_grade, Format.signedPct(action.deltaPct))
        Action.PlayPause -> stringResource(R.string.action_play_pause)
        Action.NextTrack -> stringResource(R.string.action_next)
        Action.PreviousTrack -> stringResource(R.string.action_previous)
        Action.VolumeUp -> stringResource(R.string.action_volume_up)
        Action.VolumeDown -> stringResource(R.string.action_volume_down)
        Action.PauseResume -> stringResource(R.string.action_pause_resume)
        Action.OpenSpotify -> stringResource(R.string.action_open_spotify)
        Action.None -> stringResource(R.string.action_none)
    }

/** Texte d'aide, petit et centré sur la largeur de l'écran. */
@Composable
fun HelpText(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}

/** Valeur réglable par − et +, cibles de 48 dp. */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val minus = stringResource(R.string.ride_minus)
    val plus = stringResource(R.string.ride_plus)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onMinus, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text("−", modifier = Modifier.clearAndSetSemantics { contentDescription = minus })
        }
        Text(value, style = MaterialTheme.typography.titleLarge)
        FilledTonalButton(onClick = onPlus, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text("+", modifier = Modifier.clearAndSetSemantics { contentDescription = plus })
        }
    }
}

/** Ligne de bilan : libellé à gauche, valeur en grand à droite. */
@Composable
fun StatLine(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** Profil d'altitude, position marquée à [position] (0 à 1) si connue. */
@Composable
fun ProfileChart(
    elevations: List<Double>,
    modifier: Modifier = Modifier,
    position: Double? = null,
    line: Color = MaterialTheme.colorScheme.primary,
    marker: Color = MaterialTheme.colorScheme.tertiary,
) {
    Canvas(modifier) {
        if (elevations.size < 2) return@Canvas
        val min = elevations.min()
        val span = (elevations.max() - min).coerceAtLeast(1.0)
        val step = size.width / (elevations.size - 1)

        fun y(e: Double) = (size.height * (1 - (e - min) / span)).toFloat()
        val path = Path()
        elevations.forEachIndexed { i, e -> if (i == 0) path.moveTo(0f, y(e)) else path.lineTo(i * step, y(e)) }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        position?.let { p ->
            val x = (p.coerceIn(0.0, 1.0) * size.width).toFloat()
            drawLine(marker, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
        }
    }
}

/** Formats d'affichage, métriques ou impériaux, à l'anglaise comme toute l'interface. */
object Format {
    private val LOCALE = Locale.ENGLISH
    private const val KM_PER_MILE = 1.609344
    private const val FEET_PER_M = 3.28084
    private const val MS_PER_S = 1_000
    private const val S_PER_MIN = 60
    private const val S_PER_H = 3_600
    private const val M_PER_KM = 1_000.0

    /** Heures, minutes et secondes, toujours : 0:01:19. */
    fun duration(ms: Long): String {
        val s = ms / MS_PER_S
        return "%d:%02d:%02d".format(Locale.ROOT, s / S_PER_H, s % S_PER_H / S_PER_MIN, s % S_PER_MIN)
    }

    fun distance(
        m: Double,
        imperial: Boolean,
    ): String =
        if (imperial) {
            "%.2f mi".format(LOCALE, m / M_PER_KM / KM_PER_MILE)
        } else {
            "%.2f km".format(LOCALE, m / M_PER_KM)
        }

    fun speed(
        kmh: Double,
        imperial: Boolean,
    ): String = if (imperial) "%.1f mph".format(LOCALE, kmh / KM_PER_MILE) else "%.1f km/h".format(LOCALE, kmh)

    fun elevation(
        m: Double,
        imperial: Boolean,
    ): String = if (imperial) "%.0f ft".format(LOCALE, m * FEET_PER_M) else "%.0f m".format(LOCALE, m)

    fun pct(value: Double): String = "%.1f %%".format(LOCALE, value)

    fun signedPct(value: Double): String = "%+.1f %%".format(LOCALE, value)

    fun kg(value: Double): String = "%.1f kg".format(LOCALE, value)

    /** Date et heure locales d'une heure Unix en millisecondes. */
    fun dateTime(epochMs: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(LOCALE)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
}
