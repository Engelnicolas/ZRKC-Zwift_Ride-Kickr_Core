package dev.kickzwift.protocol.session

import dev.kickzwift.protocol.Action
import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.gear.FlywheelCadence
import dev.kickzwift.protocol.gear.GearEngine
import dev.kickzwift.protocol.gear.VirtualBike
import dev.kickzwift.protocol.grade.GradeLimits
import dev.kickzwift.protocol.grade.GradeProfile
import kotlin.math.max
import kotlin.math.roundToInt

/** Machine à états de la sortie (spécification, « Machines à états »). */
enum class RidePhase { IDLE, PREPARING, RIDING, PAUSED, FINISHED }

/** Pourquoi la sortie est en pause. */
enum class PauseReason {
    USER,

    /** KICKR perdu depuis plus de 30 s. */
    TRAINER_LOST,

    /** Fin du parcours GPX : l'écran propose Terminer. */
    ROUTE_END,
}

sealed interface RideMode {
    data class FreeGrade(
        val startGradePct: Double = 0.0,
    ) : RideMode

    data class Route(
        val name: String,
        val profile: GradeProfile,
    ) : RideMode

    /** Puissance cible ; les vitesses sont désactivées. */
    data class Erg(
        val watts: Int,
    ) : RideMode
}

/** Réglages de la sortie, valeurs de départ de la spécification (« Comportements temps réel »). */
data class SessionConfig(
    val gears: List<Double>,
    val startGear: Int,
    val riderKg: Double = 75.0,
    val bikeKg: Double = 9.5,
    val gradeLimits: GradeLimits = GradeLimits(),
    val gradeStepPct: Double = 0.5,
    val ergStepW: Int = 10,
    val ergMaxW: Int = 600,
    val maxShiftsPerSecond: Int = 10,
    val trainerLostPauseMs: Long = 30_000,
    val powerWindowMs: Long = 3_000,
    /** Vitesses calculées par l'app ([dev.kickzwift.protocol.gear.PowerGears]) plutôt qu'appliquées par le trainer. */
    val powerGears: Boolean = false,
) {
    init {
        require(riderKg > 0 && bikeKg > 0) { "masses nulles" }
        require(ergMaxW > 0 && ergStepW > 0 && gradeStepPct > 0) { "pas ou plafond nul" }
        require(maxShiftsPerSecond > 0) { "aucun changement de vitesse permis" }
    }
}

/** Bilan de la sortie. */
data class RideStats(
    val elapsedMs: Long = 0,
    val distanceM: Double = 0.0,
    val avgPowerW: Int = 0,
    val maxPowerW: Int = 0,
    val avgCadenceRpm: Int = 0,
    val elevationGainM: Double = 0.0,
    val shifts: Int = 0,
)

/** Ce que l'interface affiche ; recalculé à chaque appel de [RideSession.snapshot]. */
data class SessionSnapshot(
    val phase: RidePhase,
    val mode: RideMode?,
    val gear: Int,
    val gearCount: Int,
    val ratio: Double,
    /** Pente en vigueur : réglée en pente libre, calculée sur le parcours ; 0 en ERG. */
    val gradePct: Double,
    val ergWatts: Int?,
    val pauseReason: PauseReason?,
    val trainerLost: Boolean,
    /** Puissance moyenne sur 3 s, ou null sans donnée récente. */
    val power3sW: Int?,
    val last: RidingData?,
    val stats: RideStats,
)

/**
 * Logique de la sortie, sans coroutine ni Android : le temps est fourni par l'appelant.
 *
 * - Les changements de vitesse modifient la vitesse engagée dans toutes les phases, sauf en
 *   ERG où ils sont ignorés ; au plus [SessionConfig.maxShiftsPerSecond] par seconde.
 * - « Pente ± » règle la pente en pente libre, la cible par pas de 10 W en ERG, rien sur un parcours.
 * - La consigne ([target]) n'existe qu'en RIDING ; en pause et à la fin, c'est Neutral.
 *   Une pause et un changement de vitesse reçus dans la même fenêtre d'écriture donnent donc
 *   Neutral : la pause l'emporte.
 * - Distance = intégrale de la vitesse reçue, en RIDING seulement ; un trou de plus de 3 s
 *   entre deux mesures n'est pas intégré.
 * - Vitesses par la puissance ([SessionConfig.powerGears], hors ERG) : un [VirtualBike] suit la
 *   cadence (estimée par le volant), le rapport et la pente, avec roue libre et inertie (pédales
 *   arrêtées hors descente, la vitesse ralentit comme le volant du KICKR) ; sa
 *   puissance est la consigne, sa vitesse celle qui s'affiche et s'intègre. Il repart arrêté au
 *   départ et à chaque reprise ; en pause et à la fin, la consigne est une puissance nulle.
 */
class RideSession(
    private val config: SessionConfig,
) {
    private val engine = GearEngine(config.gears, config.startGear)

    var phase: RidePhase = RidePhase.IDLE
        private set
    var mode: RideMode? = null
        private set
    private var pauseReason: PauseReason? = null
    private var gradePct = 0.0
    private var ergWatts = 0

    private var ridingSince: Long? = null
    private var elapsedBefore = 0L
    private var distanceM = 0.0
    private var climbM = 0.0
    private var lastSample: RidingData? = null
    private val window = ArrayDeque<RidingData>()
    private var powerSum = 0L
    private var powerCount = 0
    private var maxPower = 0
    private var cadenceSum = 0L
    private var cadenceCount = 0
    private var shifts = 0
    private val recentShifts = ArrayDeque<Long>()
    private var trainerLostSince: Long? = null
    private val flywheelCadence = FlywheelCadence()
    private var cadenceRpm = 0.0
    private val bike = VirtualBike(config.riderKg + config.bikeKg)

    val gear: Int get() = engine.gear.value

    /** IDLE → PREPARING. */
    fun prepare() {
        if (phase == RidePhase.IDLE) phase = RidePhase.PREPARING
    }

    /** PREPARING → RIDING, chrono lancé. */
    fun start(
        rideMode: RideMode,
        nowMs: Long,
    ) {
        check(phase == RidePhase.PREPARING) { "démarrage depuis $phase" }
        resetStats()
        mode = rideMode
        when (rideMode) {
            is RideMode.FreeGrade -> gradePct = clampGrade(rideMode.startGradePct)
            is RideMode.Erg -> ergWatts = rideMode.watts.coerceIn(0, config.ergMaxW)
            is RideMode.Route -> gradePct = 0.0
        }
        phase = RidePhase.RIDING
        ridingSince = nowMs
        bike.reset()
    }

    fun pause(
        nowMs: Long,
        reason: PauseReason = PauseReason.USER,
    ) {
        if (phase != RidePhase.RIDING) return
        stopClock(nowMs)
        phase = RidePhase.PAUSED
        pauseReason = reason
    }

    fun resume(nowMs: Long) {
        if (phase != RidePhase.PAUSED) return
        phase = RidePhase.RIDING
        pauseReason = null
        ridingSince = nowMs
        lastSample = null
        bike.reset()
    }

    /** RIDING ou PAUSED → FINISHED : bilan figé. */
    fun finish(nowMs: Long) {
        if (phase != RidePhase.RIDING && phase != RidePhase.PAUSED) return
        stopClock(nowMs)
        phase = RidePhase.FINISHED
        pauseReason = null
    }

    /** Fermeture du bilan, ou abandon de la préparation : retour à IDLE. */
    fun close() {
        if (phase == RidePhase.FINISHED || phase == RidePhase.PREPARING) {
            phase = RidePhase.IDLE
            mode = null
        }
    }

    /** Applique une action de la correspondance ; renvoie vrai si elle a changé la sortie. */
    fun onAction(
        action: Action,
        nowMs: Long,
    ): Boolean =
        when (action) {
            is Action.Shift -> shift(action.delta, nowMs)
            is Action.Grade -> adjust(action.deltaPct)
            Action.PauseResume ->
                when (phase) {
                    RidePhase.RIDING -> true.also { pause(nowMs) }
                    RidePhase.PAUSED -> true.also { resume(nowMs) }
                    else -> false
                }
            else -> false
        }

    fun onRidingData(received: RidingData) {
        val data =
            if (usesPowerGears()) {
                cadenceRpm = flywheelCadence.update(received)
                if (phase == RidePhase.RIDING) bike.advance(received.atMs, cadenceRpm, engine.ratio, currentGrade(), received.flywheelRaw)
                received.copy(speedKmh = bike.speedKmh)
            } else {
                received
            }
        window.addLast(data)
        while (window.isNotEmpty() && data.atMs - window.first().atMs > config.powerWindowMs) window.removeFirst()
        if (phase != RidePhase.RIDING) {
            lastSample = data
            return
        }
        lastSample?.let { previous -> integrate(previous, data) }
        lastSample = data
        powerSum += data.powerW
        powerCount++
        maxPower = max(maxPower, data.powerW)
        if (data.cadenceRpm > 0) {
            cadenceSum += data.cadenceRpm
            cadenceCount++
        }
    }

    /** État de la liaison au trainer ; sa perte prolongée met la sortie en pause dans [tick]. */
    fun onTrainerLink(
        ready: Boolean,
        nowMs: Long,
    ) {
        trainerLostSince = if (ready) null else trainerLostSince ?: nowMs
    }

    /** À appeler chaque seconde : pause sur perte du KICKR et en fin de parcours. */
    fun tick(nowMs: Long) {
        if (phase != RidePhase.RIDING) return
        val lostSince = trainerLostSince
        val route = mode as? RideMode.Route
        when {
            lostSince != null && nowMs - lostSince >= config.trainerLostPauseMs -> pause(nowMs, PauseReason.TRAINER_LOST)
            route != null && route.profile.isFinished(distanceM) -> pause(nowMs, PauseReason.ROUTE_END)
        }
    }

    /** Consigne à envoyer au trainer, ou null tant qu'aucune sortie n'a commencé. */
    fun target(): TrainerTarget? =
        when (phase) {
            RidePhase.IDLE, RidePhase.PREPARING -> null
            RidePhase.PAUSED, RidePhase.FINISHED -> if (config.powerGears) TrainerTarget.Erg(0) else TrainerTarget.Neutral
            RidePhase.RIDING ->
                when {
                    mode is RideMode.Erg -> TrainerTarget.Erg(ergWatts)
                    config.powerGears -> TrainerTarget.Erg(bike.targetWatts(cadenceRpm, engine.ratio, currentGrade(), config.ergMaxW))
                    else -> TrainerTarget.Simulation(currentGrade(), engine.ratio, config.riderKg, config.bikeKg)
                }
        }

    fun snapshot(nowMs: Long): SessionSnapshot =
        SessionSnapshot(
            phase = phase,
            mode = mode,
            gear = engine.gear.value,
            gearCount = config.gears.size,
            ratio = engine.ratio,
            gradePct = if (mode is RideMode.Erg) 0.0 else currentGrade(),
            ergWatts = if (mode is RideMode.Erg) ergWatts else null,
            pauseReason = pauseReason,
            trainerLost = trainerLostSince != null,
            power3sW =
                window.takeIf { it.isNotEmpty() && nowMs - it.last().atMs <= config.powerWindowMs }?.let { w ->
                    w.sumOf { it.powerW } /
                        w.size
                },
            last = window.lastOrNull(),
            stats = stats(nowMs),
        )

    fun stats(nowMs: Long): RideStats =
        RideStats(
            elapsedMs = elapsedBefore + (ridingSince?.let { nowMs - it } ?: 0),
            distanceM = distanceM,
            avgPowerW = if (powerCount == 0) 0 else (powerSum / powerCount).toInt(),
            maxPowerW = maxPower,
            avgCadenceRpm = if (cadenceCount == 0) 0 else (cadenceSum / cadenceCount).toInt(),
            elevationGainM = climbM,
            shifts = shifts,
        )

    private fun shift(
        delta: Int,
        nowMs: Long,
    ): Boolean {
        while (recentShifts.isNotEmpty() && nowMs - recentShifts.first() >= SECOND_MS) recentShifts.removeFirst()
        val allowed = mode !is RideMode.Erg && recentShifts.size < config.maxShiftsPerSecond
        val before = engine.gear.value
        val changed = allowed && engine.shift(delta) != before
        if (changed) {
            recentShifts.addLast(nowMs)
            if (phase == RidePhase.RIDING || phase == RidePhase.PAUSED) shifts++
        }
        return changed
    }

    private fun adjust(deltaPct: Double): Boolean {
        if (phase != RidePhase.RIDING && phase != RidePhase.PAUSED) return false
        return when (mode) {
            is RideMode.FreeGrade -> {
                val step = if (deltaPct > 0) config.gradeStepPct else -config.gradeStepPct
                val next = clampGrade(((gradePct + step) * HUNDRED).roundToInt() / HUNDRED)
                (next != gradePct).also { gradePct = next }
            }
            is RideMode.Erg -> {
                val next = (ergWatts + if (deltaPct > 0) config.ergStepW else -config.ergStepW).coerceIn(0, config.ergMaxW)
                (next != ergWatts).also { ergWatts = next }
            }
            else -> false
        }
    }

    private fun currentGrade(): Double =
        when (val m = mode) {
            is RideMode.Route -> m.profile.targetGrade(distanceM, config.gradeLimits)
            else -> gradePct
        }

    private fun integrate(
        previous: RidingData,
        data: RidingData,
    ) {
        val dt = data.atMs - previous.atMs
        if (dt <= 0 || dt > MAX_GAP_MS) return
        val step = data.speedKmh / KMH_PER_MPS * dt / SECOND_MS
        if (step <= 0) return
        val route = mode as? RideMode.Route
        climbM +=
            if (route != null) {
                max(0.0, route.profile.elevationAt(distanceM + step) - route.profile.elevationAt(distanceM))
            } else if (mode is RideMode.FreeGrade) {
                max(0.0, gradePct / HUNDRED * step)
            } else {
                0.0
            }
        distanceM += step
    }

    private fun stopClock(nowMs: Long) {
        ridingSince?.let { elapsedBefore += nowMs - it }
        ridingSince = null
        lastSample = null
    }

    private fun resetStats() {
        elapsedBefore = 0
        ridingSince = null
        distanceM = 0.0
        climbM = 0.0
        lastSample = null
        powerSum = 0
        powerCount = 0
        maxPower = 0
        cadenceSum = 0
        cadenceCount = 0
        shifts = 0
        pauseReason = null
    }

    private fun usesPowerGears() = config.powerGears && mode !is RideMode.Erg

    private fun clampGrade(pct: Double) = pct.coerceIn(config.gradeLimits.minPct, config.gradeLimits.maxPct)

    private companion object {
        const val SECOND_MS = 1_000L
        const val MAX_GAP_MS = 3_000L
        const val KMH_PER_MPS = 3.6
        const val HUNDRED = 100.0
    }
}
