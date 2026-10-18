package dev.kickzwift.protocol.gear

import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.trainer.TrainerCodec
import kotlin.math.roundToInt

/**
 * Vitesses calculées par l'app (réglage « Gears by power ») : chaque seconde, la vitesse d'un
 * vrai vélo d'après la cadence et le rapport, puis la puissance qu'il faut pour la tenir sur la
 * pente du moment, envoyée au trainer en consigne ERG. Le KICKR applique fidèlement l'ERG et la
 * pente, pas le rapport du protocole Zwift (essais du 18 octobre).
 *
 * Résistance de Zwift, comme celle que reçoit le KICKR : CWa 0,51, Crr 0,004 ; roue de 700c.
 * La roue libre et l'inertie sont dans [VirtualBike].
 */
object PowerGears {
    const val WHEEL_M = 2.105

    private const val CWA = TrainerCodec.DEFAULT_CWA / 10_000.0
    private const val CRR = TrainerCodec.DEFAULT_CRR / 100_000.0
    private const val G = 9.81
    private const val S_PER_MIN = 60.0
    private const val KMH_PER_MPS = 3.6
    private const val HUNDRED = 100.0

    fun speedKmh(
        cadenceRpm: Double,
        ratio: Double,
    ): Double = cadenceRpm.coerceAtLeast(0.0) / S_PER_MIN * ratio * WHEEL_M * KMH_PER_MPS

    /** Force qui freine le vélo à [speedMps] : air, roulement et pente (négative en descente). */
    fun resistanceN(
        speedMps: Double,
        gradePct: Double,
        massKg: Double,
    ): Double = CWA / 2 * speedMps * speedMps + CRR * massKg * G + massKg * G * gradePct / HUNDRED

    /** Puissance pour tenir [speedKmh] : air, roulement et pente (négative en descente). */
    fun roadWatts(
        speedKmh: Double,
        gradePct: Double,
        massKg: Double,
    ): Double {
        val v = speedKmh / KMH_PER_MPS
        return resistanceN(v, gradePct, massKg) * v
    }

    /** Consigne ERG, bornée à [0, [maxW]] : une descente ne pousse pas les pédales. */
    fun targetWatts(
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
        massKg: Double,
        maxW: Int,
    ): Int = roadWatts(speedKmh(cadenceRpm, ratio), gradePct, massKg).roundToInt().coerceIn(0, maxW)
}

/**
 * Vélo virtuel des vitesses par la puissance, avec roue libre et inertie. Sa vitesse [speedMps]
 * est comparée chaque seconde à celle des pédales (cadence × rapport × roue) :
 *
 * - pédales au moins aussi rapides : la chaîne entraîne, la vitesse rejoint celle des pédales en
 *   [TAU_S] environ, et la consigne est la puissance pour tenir la vitesse des pédales, plus celle
 *   qui accélère la masse ;
 * - pédales plus lentes ou arrêtées : roue libre, consigne nulle ; la vitesse décroît selon la
 *   résistance (air, roulement, pente), ou croît en descente jusqu'à sa vitesse limite ; dès que
 *   les pédales la rattrapent, la chaîne réengage.
 */
class VirtualBike(
    private val massKg: Double,
) {
    var speedMps: Double = 0.0
        private set
    private var lastMs: Long? = null

    val speedKmh: Double get() = speedMps * KMH_PER_MPS

    /** Vélo arrêté : départ et reprise de la sortie. */
    fun reset() {
        speedMps = 0.0
        lastMs = null
    }

    /** Avance le temps jusqu'à [nowMs] ; un trou de plus de [MAX_GAP_MS] n'est pas intégré. */
    fun advance(
        nowMs: Long,
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
    ) {
        val dt = lastMs?.let { (nowMs - it) / MS_PER_S }
        lastMs = nowMs
        if (dt == null || dt <= 0 || dt * MS_PER_S > MAX_GAP_MS) return
        val pedals = pedalMps(cadenceRpm, ratio)
        // Vitesse en roue libre au bout de dt ; la chaîne n'entraîne que si les pédales la dépassent
        // (une descente peut emmener le vélo plus vite que des pédales lentes, ou immobiles).
        val free = (speedMps - PowerGears.resistanceN(speedMps, gradePct, massKg) / massKg * dt).coerceAtLeast(0.0)
        speedMps =
            if (pedals > free) {
                maxOf(free, speedMps + (pedals - speedMps) * minOf(1.0, dt / TAU_S))
            } else {
                free
            }
    }

    /** Consigne ERG à cet instant, bornée à [0, [maxW]] ; nulle en roue libre. */
    fun targetWatts(
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
        maxW: Int,
    ): Int {
        val pedals = pedalMps(cadenceRpm, ratio)
        if (pedals + FREEWHEEL_MARGIN_MPS < speedMps) return 0
        val accelerating = massKg * (pedals - speedMps).coerceAtLeast(0.0) / TAU_S
        val watts = (PowerGears.resistanceN(pedals, gradePct, massKg) + accelerating) * pedals
        return watts.roundToInt().coerceIn(0, maxW)
    }

    private fun pedalMps(
        cadenceRpm: Double,
        ratio: Double,
    ) = PowerGears.speedKmh(cadenceRpm, ratio) / KMH_PER_MPS

    companion object {
        /** Temps de rattrapage de la vitesse des pédales : environ celui d'une relance réelle. */
        const val TAU_S = 4.0
        const val MAX_GAP_MS = 3_000L

        /** Écart sous lequel pédales et roue tournent ensemble (bruit de la cadence). */
        const val FREEWHEEL_MARGIN_MPS = 0.1
        private const val MS_PER_S = 1_000.0
        private const val KMH_PER_MPS = 3.6
    }
}

/**
 * Cadence d'après la vitesse du volant, qui suit le pédalage sans délai, alors que la cadence
 * publiée par le KICKR en est déduite avec quelques secondes de retard. Le rapport volant / rpm
 * (environ 305 sur le Ride et le Zwift Cog relevés) se cale en continu sur la cadence publiée.
 * Sans volant (FTMS), la cadence publiée telle quelle ; pédales arrêtées, zéro.
 */
class FlywheelCadence(
    initialPerRpm: Double = DEFAULT_PER_RPM,
) {
    var perRpm: Double = initialPerRpm
        private set

    fun update(data: RidingData): Double {
        val flywheel = data.flywheelRaw
        return when {
            data.cadenceRpm <= 0 -> 0.0
            flywheel == null || flywheel <= 0 -> data.cadenceRpm.toDouble()
            else -> {
                if (data.cadenceRpm >= CALIBRATE_MIN_RPM) perRpm += (flywheel.toDouble() / data.cadenceRpm - perRpm) * GAIN
                flywheel / perRpm
            }
        }
    }

    companion object {
        const val DEFAULT_PER_RPM = 305.0

        /** Sous ce seuil, la cadence publiée est trop imprécise pour caler le rapport. */
        const val CALIBRATE_MIN_RPM = 40

        /** Calage lent (une vingtaine de mesures) : le retard de la cadence publiée s'y moyenne. */
        const val GAIN = 0.05
    }
}
