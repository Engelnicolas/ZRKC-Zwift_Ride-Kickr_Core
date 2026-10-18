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
 * - pédales qui tournent, plus lentes, hors descente (après avoir rétrogradé, ou un creux de la
 *   cadence estimée) : la chaîne reste tenue, la vitesse redescend vers celle des pédales en
 *   [TAU_S] environ et la consigne est aussitôt la puissance du nouveau rapport ; pas de
 *   pédalage dans le vide (sortie du 18 octobre : 0 W une dizaine de secondes après 14 → 6) ;
 * - pédales arrêtées, ou plus lentes en descente : roue libre, consigne nulle ; la vitesse
 *   décroît selon la résistance (air, roulement, pente), ou croît en descente jusqu'à sa vitesse
 *   limite ; dès que les pédales la rattrapent, la chaîne réengage ;
 * - pédales arrêtées hors descente, avec le volant du KICKR : la vitesse ralentit aussi vite que
 *   le volant, proportionnellement à sa vitesse au moment où les pédales se sont arrêtées. Le
 *   volant s'arrête en 25 s environ, un vrai vélo sur le plat en plus d'une minute : l'écran suit
 *   ce que la personne voit et sent sur le trainer (sortie du 18 octobre).
 */
class VirtualBike(
    private val massKg: Double,
) {
    var speedMps: Double = 0.0
        private set
    private var lastMs: Long? = null

    /** Vitesse et volant à l'arrêt des pédales ; nul tant qu'elles tournent. */
    private var coastFrom: Pair<Double, Int>? = null

    val speedKmh: Double get() = speedMps * KMH_PER_MPS

    /** Vélo arrêté : départ et reprise de la sortie. */
    fun reset() {
        speedMps = 0.0
        lastMs = null
        coastFrom = null
    }

    /**
     * Avance le temps jusqu'à [nowMs] ; un trou de plus de [MAX_GAP_MS] n'est pas intégré.
     * [flywheel] : vitesse brute du volant du KICKR, nulle sans volant (FTMS).
     */
    fun advance(
        nowMs: Long,
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
        flywheel: Int? = null,
    ) {
        val dt = lastMs?.let { (nowMs - it) / MS_PER_S }
        lastMs = nowMs
        if (dt == null || dt <= 0 || dt * MS_PER_S > MAX_GAP_MS) return
        val pedals = pedalMps(cadenceRpm, ratio)
        // Vitesse en roue libre au bout de dt ; la chaîne n'entraîne que si les pédales la dépassent
        // (une descente peut emmener le vélo plus vite que des pédales lentes, ou immobiles).
        val free = minOf(roadFree(dt, gradePct), flywheelFree(cadenceRpm, gradePct, flywheel))
        val towardPedals = speedMps + (pedals - speedMps) * minOf(1.0, dt / TAU_S)
        speedMps =
            when {
                pedals > free -> maxOf(free, towardPedals)
                chainHeld(cadenceRpm, gradePct) -> towardPedals
                else -> free
            }
    }

    // Pédales qui tournent hors descente : la chaîne ne lâche pas, même plus lentes que la roue.
    private fun chainHeld(
        cadenceRpm: Double,
        gradePct: Double,
    ) = cadenceRpm > 0 && gradePct >= 0

    private fun roadFree(
        dt: Double,
        gradePct: Double,
    ) = (speedMps - PowerGears.resistanceN(speedMps, gradePct, massKg) / massKg * dt).coerceAtLeast(0.0)

    // Pédales arrêtées hors descente : la vitesse suit le volant depuis l'arrêt des pédales.
    // Sinon, aucune limite : la route seule décide.
    private fun flywheelFree(
        cadenceRpm: Double,
        gradePct: Double,
        flywheel: Int?,
    ): Double {
        if (cadenceRpm > 0 || gradePct < 0 || flywheel == null) {
            coastFrom = null
            return Double.MAX_VALUE
        }
        val (speed, from) = coastFrom ?: (speedMps to flywheel).also { coastFrom = it }
        return if (from > 0) speed * flywheel.coerceIn(0, from) / from else Double.MAX_VALUE
    }

    /**
     * Consigne ERG à cet instant, bornée à [0, [maxW]] ; nulle en roue libre. Chaîne tenue avec
     * des pédales plus lentes : la puissance qui tient leur vitesse, sans terme de freinage.
     */
    fun targetWatts(
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
        maxW: Int,
    ): Int {
        val pedals = pedalMps(cadenceRpm, ratio)
        if (pedals + FREEWHEEL_MARGIN_MPS < speedMps && !chainHeld(cadenceRpm, gradePct)) return 0
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
