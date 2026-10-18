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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kickzwift.app.R
import dev.kickzwift.app.phone.MediaState
import dev.kickzwift.app.phone.PhoneNotice
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Écran Sortie : lisible à 1,5 m, pilotable aux manettes ; l'écran ne sert qu'à Pause et Terminer. */
@Composable
fun RideScreen(viewModel: RideViewModel) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val s = ui.snapshot ?: return
    val context = LocalContext.current
    val grantMedia = { context.startActivity(viewModel.mediaAccess()) }
    KeepScreenOn(ui.orientation)
    GearFeedback(s.gear, ui.haptics)

    Surface(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.safeDrawingPadding().padding(12.dp)) {
            val landscape = maxWidth > maxHeight
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneNotices(ui.phoneNotices)
                SensorsCard(ui)
                Banners(ui, s)
                if (landscape) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        GearBlock(s, Modifier.weight(1f))
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Metrics(s, ui)
                            Player(ui.media, grantMedia, viewModel::act)
                            ModeZone(s, ui, viewModel::act)
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GearBlock(s, Modifier.fillMaxWidth())
                        Metrics(s, ui)
                        Player(ui.media, grantMedia, viewModel::act)
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

/**
 * Notifications des autres apps, en haut de l'écran : elles poussent l'encart des capteurs et le
 * reste vers le bas au lieu de le recouvrir comme un bandeau Android.
 */
@Composable
private fun PhoneNotices(notices: List<PhoneNotice>) {
    notices.forEach { n ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            n.app?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
            n.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            n.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Les appareils de confiance, regroupés dans un encart, deux par ligne. */
@Composable
private fun SensorsCard(ui: RideUi) {
    if (ui.devices.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.ride_sensors),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.devices.chunked(SENSORS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { d ->
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(d.state)
                        Text(d.device.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(SENSORS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
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
    ui: RideUi,
) {
    val imperial = ui.imperial
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(
                stringResource(R.string.ride_heart_rate),
                ui.heartRate?.let { stringResource(R.string.unit_bpm, it) } ?: "—",
                Modifier.weight(1f),
            )
            // Unité inconnue (prototype/zwift.proto) : la valeur brute du trainer, telle quelle.
            Metric(stringResource(R.string.ride_flywheel), last?.flywheelRaw?.toString() ?: "—", Modifier.weight(1f))
        }
    }
}

/**
 * Lecteur, Spotify d'abord : titre, lecture ou pause, volume, et les commandes précédent,
 * lecture/pause, suivant, volume − et +, qui passent par les mêmes actions que les manettes.
 * Relu deux fois par seconde : ce que font les manettes s'y voit aussitôt. Sans l'accès aux
 * notifications, ni titre ni état précis.
 */
@Composable
private fun Player(
    media: MediaState,
    onGrant: () -> Unit,
    act: (Action) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title =
            when {
                media.title != null -> media.title
                media.access -> stringResource(R.string.player_unknown_title)
                else -> stringResource(R.string.player_music)
            }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val state = stringResource(if (media.playing) R.string.player_playing else R.string.player_paused)
        Text(
            listOfNotNull(media.artist, state).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            MediaButton(stringResource(R.string.action_previous), { act(Action.PreviousTrack) }) { TrackIcon(next = false) }
            // L'icône montre ce que fait l'appui : pause pendant la lecture, lecture sinon.
            MediaButton(stringResource(R.string.action_play_pause), { act(Action.PlayPause) }) { PlayIcon(!media.playing) }
            MediaButton(stringResource(R.string.action_next), { act(Action.NextTrack) }) { TrackIcon(next = true) }
            MediaButton(stringResource(R.string.action_volume_down), { act(Action.VolumeDown) }) { Text("−") }
            MediaButton(stringResource(R.string.action_volume_up), { act(Action.VolumeUp) }) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_volume, media.volume, media.maxVolume), style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { if (media.maxVolume > 0) media.volume.toFloat() / media.maxVolume else 0f },
                modifier = Modifier.weight(1f),
                drawStopIndicator = {},
            )
        }
        if (!media.access) {
            TextButton(onClick = onGrant) { Text(stringResource(R.string.player_grant)) }
            Text(stringResource(R.string.player_grant_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Bouton du lecteur, cible de 56 dp ; [label] est lu par les lecteurs d'écran à la place de l'icône. */
@Composable
private fun MediaButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = 56.dp, minHeight = 56.dp).clearAndSetSemantics { contentDescription = label },
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(Modifier.size(MEDIA_ICON), contentAlignment = Alignment.Center) { icon() }
    }
}

/** Triangle de lecture, ou deux barres de pause. */
@Composable
private fun PlayIcon(play: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(MEDIA_ICON)) {
        if (play) {
            val path =
                Path().apply {
                    moveTo(size.width * ICON_INSET, 0f)
                    lineTo(size.width, size.height / 2)
                    lineTo(size.width * ICON_INSET, size.height)
                    close()
                }
            drawPath(path, color)
        } else {
            val bar = size.width * PAUSE_BAR
            drawRect(color, Offset(size.width * PAUSE_LEFT, 0f), Size(bar, size.height))
            drawRect(color, Offset(size.width * PAUSE_RIGHT, 0f), Size(bar, size.height))
        }
    }
}

/** Piste suivante (triangle puis barre) ou précédente (barre puis triangle). */
@Composable
private fun TrackIcon(next: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(MEDIA_ICON)) {
        val w = size.width
        val bar = w * TRACK_BAR
        val tip = w - bar
        val path =
            Path().apply {
                if (next) {
                    moveTo(0f, 0f)
                    lineTo(tip, size.height / 2)
                    lineTo(0f, size.height)
                } else {
                    moveTo(w, 0f)
                    lineTo(bar, size.height / 2)
                    lineTo(w, size.height)
                }
                close()
            }
        drawPath(path, color)
        drawRect(color, Offset(if (next) tip else 0f, 0f), Size(bar, size.height))
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
        // Pente libre : pas de boutons à l'écran, la pente se règle aux manettes et s'affiche dans les mesures.
        else -> Unit
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
        // Terminer demande d'appuyer 3 s, décompte 3, 2, 1 sur le bouton : pas de fin de sortie
        // par mégarde. Relâcher avant la fin annule.
        val label = stringResource(R.string.ride_finish_description)
        var countdown by remember { mutableStateOf<Int?>(null) }
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 64.dp)
                .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.extraLarge)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val held =
                            coroutineScope {
                                val ticks =
                                    launch {
                                        for (n in FINISH_COUNTDOWN downTo 1) {
                                            countdown = n
                                            delay(FINISH_STEP_MS)
                                        }
                                    }
                                val released = withTimeoutOrNull(FINISH_COUNTDOWN * FINISH_STEP_MS) { tryAwaitRelease() }
                                ticks.cancel()
                                countdown = null
                                released == null
                            }
                        if (held) onFinish()
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
                countdown?.toString() ?: stringResource(R.string.ride_finish),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = if (countdown != null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = if (countdown != null) FontWeight.Bold else null,
            )
        }
    }
}

private const val PULSE_SCALE = 1.25f
private const val BLINK_ALPHA = 0.2f
private const val FINISH_COUNTDOWN = 3
private const val FINISH_STEP_MS = 1_000L
private const val SENSORS_PER_ROW = 2

// Icônes du lecteur, en fractions de leur largeur.
private val MEDIA_ICON = 22.dp
private const val TRACK_BAR = 0.2f
private const val ICON_INSET = 0.2f
private const val PAUSE_BAR = 0.3f
private const val PAUSE_LEFT = 0.1f
private const val PAUSE_RIGHT = 0.6f

// Pas de « Pente ± » ; en ERG, la même action règle la cible de 10 W.
private const val GRADE_STEP = 0.5
