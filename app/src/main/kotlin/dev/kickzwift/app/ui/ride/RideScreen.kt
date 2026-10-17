package dev.kickzwift.app.ui.ride

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.common.Format
import dev.kickzwift.app.ui.common.ProfileChart
import dev.kickzwift.app.ui.common.StatusDot
import dev.kickzwift.app.ui.common.Stepper
import dev.kickzwift.app.ui.common.actionText
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.session.PauseReason
import dev.kickzwift.protocol.session.RideMode
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.session.SessionSnapshot
import dev.kickzwift.protocol.settings.OrientationChoice
import kotlinx.coroutines.withTimeoutOrNull

/** Écran Sortie : lisible à 1,5 m, pilotable aux manettes ; l'écran ne sert qu'à Pause et Terminer. */
@Composable
fun RideScreen(viewModel: RideViewModel) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val s = ui.snapshot ?: return
    KeepScreenOn(ui.orientation)
    GearFeedback(s.gear, ui.haptics)

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding().padding(12.dp)) {
            val landscape = maxWidth > maxHeight
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusStrip(ui)
                Banners(ui, s)
                if (landscape) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GearBlock(s, Modifier.weight(1f))
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Metrics(s, ui.imperial)
                            ModeZone(s, ui, viewModel::act)
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GearBlock(s, Modifier.fillMaxWidth())
                        Metrics(s, ui.imperial)
                        ModeZone(s, ui, viewModel::act)
                    }
                }
                Controls(s.phase, onPause = viewModel::togglePause, onFinish = viewModel::finish)
            }
        }
    }
}

/** Écran allumé tant que la sortie est affichée ; orientation des réglages. */
@Composable
private fun KeepScreenOn(orientation: OrientationChoice) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(orientation) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.requestedOrientation =
            when (orientation) {
                OrientationChoice.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                OrientationChoice.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationChoice.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

@Composable
private fun GearFeedback(
    gear: Int,
    haptics: Boolean,
) {
    val feedback = LocalHapticFeedback.current
    val first = remember { intArrayOf(gear) }
    LaunchedEffect(gear) {
        if (gear != first[0] && haptics) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        first[0] = gear
    }
}

@Composable
private fun StatusStrip(ui: RideUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ui.devices.forEach { d ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(d.state)
                Text(d.device.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Banners(
    ui: RideUi,
    s: SessionSnapshot,
) {
    ui.lost.firstOrNull()?.let { lost ->
        Banner(stringResource(R.string.ride_lost, lost.device.name), MaterialTheme.colorScheme.errorContainer)
    }
    if (s.phase == RidePhase.PAUSED) {
        val text =
            when (s.pauseReason) {
                PauseReason.TRAINER_LOST -> stringResource(R.string.ride_paused_trainer)
                PauseReason.ROUTE_END -> stringResource(R.string.ride_paused_route)
                else -> stringResource(R.string.ride_paused)
            }
        Banner(text, MaterialTheme.colorScheme.tertiaryContainer)
    }
    ui.notice?.let { Banner(actionText(it.action), MaterialTheme.colorScheme.secondaryContainer) }
}

@Composable
private fun Banner(
    text: String,
    color: Color,
) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(color, MaterialTheme.shapes.small).padding(12.dp),
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun GearBlock(
    s: SessionSnapshot,
    modifier: Modifier,
) {
    // Le chiffre grossit brièvement à chaque changement.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(s.gear) {
        pulse.snapTo(PULSE_SCALE)
        pulse.animateTo(1f)
    }
    val description = stringResource(R.string.ride_gear_description, s.gear, s.gearCount)
    Column(modifier.semantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (s.mode is RideMode.Erg) "—" else s.gear.toString(),
            fontSize = 128.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.scale(pulse.value),
        )
        Text(stringResource(R.string.ride_gear_of, s.gearCount), style = MaterialTheme.typography.titleLarge)
        GearBar(s.gear, s.gearCount, Modifier.fillMaxWidth().height(28.dp).padding(top = 8.dp))
    }
}

@Composable
private fun GearBar(
    gear: Int,
    count: Int,
    modifier: Modifier,
) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant
    val atStop = gear == 1 || gear == count
    val blink = remember { Animatable(1f) }
    LaunchedEffect(gear) {
        if (atStop) {
            blink.snapTo(BLINK_ALPHA)
            blink.animateTo(1f)
        }
    }
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            drawRoundRect(
                color = if (i < gear) on.copy(alpha = blink.value) else off,
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun Metrics(
    s: SessionSnapshot,
    imperial: Boolean,
) {
    val last = s.last
    val erg = s.ergWatts
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(R.string.ride_power), s.power3sW?.let { stringResource(R.string.unit_w, it) } ?: "—", Modifier.weight(1f))
            Metric(
                stringResource(R.string.ride_cadence),
                last?.let { stringResource(R.string.unit_rpm, it.cadenceRpm) } ?: "—",
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(R.string.ride_speed), last?.let { Format.speed(it.speedKmh, imperial) } ?: "—", Modifier.weight(1f))
            if (erg != null) {
                Metric(stringResource(R.string.ride_target), stringResource(R.string.unit_w, erg), Modifier.weight(1f))
            } else {
                Metric(stringResource(R.string.ride_grade), Format.pct(s.gradePct), Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(stringResource(R.string.ride_time), Format.duration(s.stats.elapsedMs), Modifier.weight(1f))
            Metric(stringResource(R.string.ride_distance), Format.distance(s.stats.distanceM, imperial), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ModeZone(
    s: SessionSnapshot,
    ui: RideUi,
    act: (Action) -> Unit,
) {
    when (val mode = s.mode) {
        is RideMode.Route -> {
            val route = ui.route
            val profileDescription = stringResource(R.string.ride_profile_description)
            val total = mode.profile.totalDistanceM
            Column {
                if (route != null) {
                    ProfileChart(
                        route.elevations,
                        Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = profileDescription },
                        position = s.stats.distanceM / total,
                    )
                }
                Text(stringResource(R.string.ride_remaining, Format.distance((total - s.stats.distanceM).coerceAtLeast(0.0), ui.imperial)))
            }
        }
        is RideMode.Erg ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Stepper(
                    stringResource(R.string.unit_w, s.ergWatts ?: 0),
                    onMinus = { act(Action.Grade(-GRADE_STEP)) },
                    onPlus = { act(Action.Grade(GRADE_STEP)) },
                )
            }
        else ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Stepper(Format.pct(s.gradePct), onMinus = { act(Action.Grade(-GRADE_STEP)) }, onPlus = { act(Action.Grade(GRADE_STEP)) })
            }
    }
}

@Composable
private fun Controls(
    phase: RidePhase,
    onPause: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPause, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
            Text(
                stringResource(
                    if (phase ==
                        RidePhase.PAUSED
                    ) {
                        R.string.ride_resume
                    } else {
                        R.string.ride_pause
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        }
        // Terminer demande un appui long de 2 s : pas de fin de sortie par mégarde.
        val label = stringResource(R.string.ride_finish)
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.extraLarge)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        if (withTimeoutOrNull(FINISH_HOLD_MS) { tryAwaitRelease() } == null) onFinish()
                    })
                }.semantics {
                    role = Role.Button
                    onLongClick(label) {
                        onFinish()
                        true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.ride_finish),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private const val PULSE_SCALE = 1.25f
private const val BLINK_ALPHA = 0.2f
private const val FINISH_HOLD_MS = 2_000L

// Pas de « Pente ± » ; en ERG, la même action règle la cible de 10 W.
private const val GRADE_STEP = 0.5
