package dev.kickzwift.app.session

import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.data.ActivityMode
import dev.kickzwift.app.phone.PhoneActions
import dev.kickzwift.app.route.RouteData
import dev.kickzwift.ble.Controller
import dev.kickzwift.ble.Trainer
import dev.kickzwift.ble.trainer.TrainerLimits
import dev.kickzwift.ble.trainer.TrainerWriter
import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.ButtonEvent
import dev.kickzwift.protocol.LinkState
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.fit.FitEncoder
import dev.kickzwift.protocol.fit.FitSample
import dev.kickzwift.protocol.fit.FitSummary
import dev.kickzwift.protocol.session.RideMode
import dev.kickzwift.protocol.session.RidePhase
import dev.kickzwift.protocol.session.RideSession
import dev.kickzwift.protocol.session.RideStats
import dev.kickzwift.protocol.session.SessionSnapshot
import dev.kickzwift.protocol.settings.AppSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/** Les appareils d'une sortie : les manettes de confiance, le trainer et le capteur cardiaque, s'il y en a. */
data class SessionDevices(
    val controllers: List<Controller>,
    val trainer: Trainer?,
    /** Fréquence du capteur cardiaque de confiance, en battements par minute. */
    val heartRate: StateFlow<Int?>? = null,
)

/** Action déclenchée depuis une manette, pour le bandeau de l'écran Sortie. */
data class ActionNotice(
    val action: Action,
    val atMs: Long,
)

/** Ce que voit l'interface. */
data class SessionUi(
    val snapshot: SessionSnapshot,
    val route: RouteData? = null,
    /** Fichier FIT de la dernière sortie, si l'option est active. */
    val fitFile: File? = null,
    /** Activité enregistrée à la fin de la sortie, pour y joindre le commentaire du bilan. */
    val activityId: Long? = null,
    /** Fréquence cardiaque du moment : le capteur d'abord, sinon celle que relaie le trainer. */
    val heartRate: Int? = null,
)

/** Ce dont le contrôleur a besoin hors de la sortie elle-même ; remplacé par des faux en test. */
interface SessionEnvironment {
    val settings: StateFlow<AppSettings>
    val phone: PhoneActions

    /** Horloge monotone, en millisecondes. */
    fun now(): Long

    /** Heure Unix, en millisecondes. */
    fun wallClock(): Long

    suspend fun lastGear(): Int?

    suspend fun saveGear(gear: Int)

    /** Enregistre le FIT de la sortie ; rend le fichier à partager. */
    suspend fun saveFit(bytes: ByteArray): File?

    /** Ajoute la sortie terminée à l'historique ; rend son identifiant, ou null en cas d'échec. */
    suspend fun saveActivity(activity: Activity): Long?

    fun log(message: String)
}

/**
 * Pilote la sortie (lot 7) : applique la correspondance boutons → actions, envoie la consigne
 * au trainer par [TrainerWriter], déclenche les actions téléphone, publie l'état.
 *
 * Toute la logique tourne sur [dispatcher], limité à un fil : commandes de l'interface,
 * événements des manettes et mesures du trainer ne se croisent jamais. Les manettes et le
 * trainer ne sont suivis que pendant [run], que fait tourner le service.
 *
 * - Les événements ne viennent que d'une manette : la première prête dans l'ordre des
 *   appareils de confiance. Le pod gauche du Ride agrège les 16 commandes ; le droit n'est
 *   qu'une redondance.
 * - En mode « Tester » des réglages, les événements sont publiés sans être appliqués.
 * - À la reconnexion du trainer, la consigne courante est réécrite (vitesse conservée).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionController(
    private val env: SessionEnvironment,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private var session = RideSession(env.settings.value.sessionConfig())
    private var route: RouteData? = null
    private var fitFile: File? = null
    private var activityId: Long? = null
    private var writer: TrainerWriter? = null
    private var trainerReady = false
    private val samples = mutableListOf<FitSample>()
    private var startedWallMs = 0L
    private var gearSave: Job? = null
    private var lastRidingLogMs: Long? = null
    private var sensorHeartRate: Int? = null

    private val mutableState = MutableStateFlow(SessionUi(session.snapshot(env.now())))
    val state: StateFlow<SessionUi> = mutableState.asStateFlow()

    private val mutableNotices = MutableSharedFlow<ActionNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notices: SharedFlow<ActionNotice> = mutableNotices.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<ButtonEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Tous les gestes reçus de la manette active, appliqués ou non ; pour le mode « Tester ». */
    val events: SharedFlow<ButtonEvent> = mutableEvents.asSharedFlow()

    /** Mode « Tester » des réglages : les gestes sont montrés, pas appliqués. */
    val testing = MutableStateFlow(false)

    fun prepare() =
        command {
            if (session.phase == RidePhase.IDLE) {
                val s = env.settings.value
                val start = env.lastGear()?.takeIf { it in 1..s.gears.size } ?: s.startGear
                session = RideSession(s.sessionConfig().copy(startGear = start))
                session.prepare()
                fitFile = null
                activityId = null
            }
        }

    fun start(
        mode: RideMode,
        routeData: RouteData? = null,
    ) = command {
        if (session.phase == RidePhase.PREPARING) {
            route = routeData
            samples.clear()
            lastRidingLogMs = null
            startedWallMs = env.wallClock()
            session.start(mode, env.now())
            env.log("sortie démarrée : ${mode::class.simpleName}")
        }
    }

    fun pause() = command { session.pause(env.now()) }

    fun resume() = command { session.resume(env.now()) }

    fun finish() =
        command {
            if (session.phase == RidePhase.RIDING || session.phase == RidePhase.PAUSED) {
                session.finish(env.now())
                val stats = session.stats(env.now())
                env.log("sortie terminée : $stats")
                fitFile = exportFit()
                activityId = env.saveActivity(activityOf(stats))
            }
        }

    fun close() =
        command {
            session.close()
            if (session.phase == RidePhase.IDLE) route = null
        }

    /** Action depuis l'écran (boutons − et + de la pente, de la cible ERG ou de la vitesse). */
    fun act(action: Action) = command { apply(action) }

    /** Remet le trainer à plat sans attendre : arrêt du service ou plantage. */
    suspend fun emergencyNeutral(): Boolean = writer?.neutral() ?: false

    /** Suit les appareils jusqu'à annulation ; une nouvelle liste remplace la précédente. */
    suspend fun run(devices: Flow<SessionDevices>) {
        withContext(dispatcher) {
            devices.collectLatest { d -> follow(d) }
        }
    }

    private suspend fun follow(d: SessionDevices) {
        coroutineScope {
            val trainer = d.trainer
            val w =
                trainer?.let {
                    TrainerWriter(
                        trainer = it,
                        limits = { env.settings.value.let { s -> TrainerLimits(s.gradeLimits, s.ergMaxW) } },
                        interval = { env.settings.value.writeIntervalMs.milliseconds },
                        now = env::now,
                        log = env::log,
                    )
                }
            writer = w
            trainerReady = false
            try {
                if (trainer != null && w != null) {
                    launch { w.run() }
                    launch { trainer.data.filterNotNull().collect { onData(it) } }
                    launch { trainer.link.collect { onTrainerLink(it) } }
                }
                d.heartRate?.let { hr ->
                    launch {
                        hr.collect {
                            sensorHeartRate = it
                            publish()
                        }
                    }
                }
                launch { activeEvents(d.controllers).collect { onEvent(it) } }
                launch { ticks() }
                awaitCancellation()
            } finally {
                writer = null
                sensorHeartRate = null
            }
        }
    }

    private fun activeEvents(controllers: List<Controller>): Flow<ButtonEvent> =
        controllers.map { c -> c.events.filter { isPrimary(controllers, c) } }.merge()

    private fun isPrimary(
        all: List<Controller>,
        c: Controller,
    ): Boolean = all.firstOrNull { it.link.value == LinkState.READY }.let { it == null || it.id == c.id }

    private suspend fun ticks() {
        var n = 0
        while (true) {
            delay(TICK_MS)
            val before = session.phase
            session.tick(env.now())
            if (session.phase != before || n % TICKS_PER_SECOND == 0) submit()
            publish()
            n++
        }
    }

    private fun onData(data: RidingData) {
        session.onRidingData(data)
        if (session.phase == RidePhase.RIDING) logRiding(data)
        if (session.phase == RidePhase.RIDING && env.settings.value.fitExport) {
            samples +=
                FitSample(
                    epochMs = env.wallClock() - (env.now() - data.atMs),
                    powerW = data.powerW,
                    cadenceRpm = data.cadenceRpm,
                    speedKmh = data.speedKmh,
                    distanceM = session.stats(env.now()).distanceM,
                    heartRate = sensorHeartRate ?: data.heartRate,
                )
        }
        publish()
    }

    /**
     * Une ligne par seconde au plus pendant la sortie : mesures du trainer et consigne
     * écrite, pour voir si la puissance suit les changements de rapport.
     */
    private fun logRiding(data: RidingData) {
        val now = env.now()
        if (lastRidingLogMs?.let { now - it < RIDING_LOG_MS } == true) return
        lastRidingLogMs = now
        val speed = "%.1f".format(Locale.ROOT, data.speedKmh)
        val extras =
            listOfNotNull(
                data.flywheelRaw?.let { "volant $it" },
                (sensorHeartRate ?: data.heartRate)?.let { "FC $it" },
            ).joinToString("") { ", $it" }
        env.log(
            "pédalage : ${data.powerW} W, ${data.cadenceRpm} rpm, $speed km/h$extras, " +
                "vitesse ${session.gear}, consigne ${describe(writer?.lastSent)}",
        )
    }

    private fun describe(target: TrainerTarget?): String =
        when (target) {
            is TrainerTarget.Simulation -> "rapport ${target.gearRatio}, pente ${target.gradePct} %"
            is TrainerTarget.Erg -> "ERG ${target.watts} W"
            TrainerTarget.Neutral -> "à plat"
            null -> "aucune"
        }

    private fun onTrainerLink(state: LinkState) {
        val ready = state == LinkState.READY
        session.onTrainerLink(ready, env.now())
        if (ready && !trainerReady) session.target()?.let { writer?.refresh(it) }
        trainerReady = ready
        publish()
    }

    private fun onEvent(event: ButtonEvent) {
        mutableEvents.tryEmit(event)
        if (testing.value) return
        val action =
            env.settings.value.mapping
                .actionFor(event)
        if (action != Action.None) apply(action)
        publish()
    }

    private fun apply(action: Action) {
        val done =
            if (env.phone.perform(action)) {
                true
            } else {
                val before = session.gear
                val changed = session.onAction(action, env.now())
                if (changed) submit()
                if (session.gear != before) saveGearLater(session.gear)
                changed
            }
        if (done) mutableNotices.tryEmit(ActionNotice(action, env.now()))
    }

    private fun submit() {
        session.target()?.let { writer?.submit(it) }
    }

    private fun saveGearLater(gear: Int) {
        gearSave?.cancel()
        gearSave =
            scope.launch(dispatcher) {
                delay(GEAR_SAVE_DELAY_MS)
                env.saveGear(gear)
            }
    }

    private suspend fun exportFit(): File? {
        if (!env.settings.value.fitExport || samples.isEmpty()) return null
        val stats = session.stats(env.now())
        val bytes =
            FitEncoder.encode(
                samples.toList(),
                FitSummary(
                    startEpochMs = startedWallMs,
                    endEpochMs = env.wallClock(),
                    timerMs = stats.elapsedMs,
                    distanceM = stats.distanceM,
                    avgPowerW = stats.avgPowerW,
                    maxPowerW = stats.maxPowerW,
                    avgCadenceRpm = stats.avgCadenceRpm,
                ),
            )
        return env.saveFit(bytes)
    }

    private fun activityOf(stats: RideStats): Activity {
        val mode = session.mode
        return Activity(
            startedAtMs = startedWallMs,
            mode =
                when (mode) {
                    is RideMode.Erg -> ActivityMode.ERG
                    is RideMode.Route -> ActivityMode.ROUTE
                    else -> ActivityMode.FREE
                },
            routeName = (mode as? RideMode.Route)?.name,
            durationMs = stats.elapsedMs,
            distanceM = stats.distanceM,
            avgPowerW = stats.avgPowerW,
            maxPowerW = stats.maxPowerW,
            avgCadenceRpm = stats.avgCadenceRpm,
            elevationGainM = stats.elevationGainM,
            shifts = stats.shifts,
        )
    }

    private fun command(block: suspend () -> Unit) {
        scope.launch(dispatcher) {
            block()
            submit()
            publish()
        }
    }

    private fun publish() {
        val snapshot = session.snapshot(env.now())
        mutableState.value = SessionUi(snapshot, route, fitFile, activityId, sensorHeartRate ?: snapshot.last?.heartRate)
    }

    private companion object {
        const val TICK_MS = 250L
        const val TICKS_PER_SECOND = 4
        const val GEAR_SAVE_DELAY_MS = 2_000L
        const val RIDING_LOG_MS = 1_000L
    }
}
