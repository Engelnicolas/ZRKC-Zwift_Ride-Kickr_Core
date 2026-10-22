package dev.kickzwift.protocol.gear

import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.trainer.TrainerCodec
import kotlin.math.abs
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
 *   [TAU_S] environ ;
 * - pédales qui tournent, plus lentes, hors descente (après avoir rétrogradé, ou un creux de la
 *   cadence estimée) : la chaîne reste tenue, la vitesse redescend vers celle des pédales en
 *   [TAU_S] environ ; pas de pédalage dans le vide (sortie du 18 octobre : 0 W une dizaine de
 *   secondes après 14 → 6) ;
 * - pédales arrêtées, ou plus lentes en descente : roue libre, consigne nulle ; la vitesse
 *   décroît selon la résistance (air, roulement, pente), ou croît en descente jusqu'à sa vitesse
 *   limite ; dès que les pédales la rattrapent, la chaîne réengage ;
 * - pédales arrêtées hors descente, avec le volant du KICKR : la vitesse ralentit aussi vite que
 *   le volant, proportionnellement à sa vitesse au moment où les pédales se sont arrêtées. Le
 *   volant s'arrête en 25 s environ, un vrai vélo sur le plat en plus d'une minute : l'écran suit
 *   ce que la personne voit et sent sur le trainer (sortie du 18 octobre).
 *
 * Consigne, chaîne engagée : la résistance de la route à la vitesse du vélo, multipliée par la
 * vitesse des pédales, comme sur un vrai vélo où l'effort à la roue dépend de l'allure. Un
 * changement de vitesse change aussitôt la consigne dans le rapport des braquets (+8 % de 13 à
 * 14), puis la vitesse du vélo la mène en quelques secondes à la puissance du plat du nouveau
 * rapport. La consigne calculée sur la vitesse des pédales, avec un terme d'accélération,
 * doublait d'un coup après chaque montée de vitesse (93 → 225 W) et rendait la consigne quatre
 * fois plus sensible au bruit de la cadence : la boucle consigne ↔ volant oscillait (sortie du
 * 22 octobre, 93, 225, 152, 98, 203, 117 W après 13 → 14). Sans ce terme, l'accélération ne se
 * paie qu'à travers la résistance, qui croît avec la vitesse du vélo : un départ arrêté est plus
 * léger que sur la route.
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
     * Consigne ERG à cet instant, bornée à [0, [maxW]] ; nulle en roue libre. Sinon la résistance
     * de la route à la vitesse du vélo, multipliée par la vitesse des pédales.
     */
    fun targetWatts(
        cadenceRpm: Double,
        ratio: Double,
        gradePct: Double,
        maxW: Int,
    ): Int {
        val pedals = pedalMps(cadenceRpm, ratio)
        if (pedals + FREEWHEEL_MARGIN_MPS < speedMps && !chainHeld(cadenceRpm, gradePct)) return 0
        val watts = PowerGears.resistanceN(speedMps, gradePct, massKg) * pedals
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
 *
 * Filtre : médiane des [FILTER_SIZE] dernières estimations. En ERG, la consigne tirée de la
 * cadence freine le volant, qui fait baisser la cadence estimée, donc la consigne : la boucle
 * oscillait (sortie du 18 octobre, sauts de consigne de plus de 40 W). La médiane écarte la
 * mesure isolée qui lance l'oscillation, pour une seconde de retard environ ; elle repart de
 * zéro à l'arrêt des pédales.
 *
 * Calage protégé : la cadence publiée double parfois, volant inchangé (61 → 117 rpm pendant
 * 11 s, 18 octobre ; la consigne avait suivi, de 8 à 17 W). Une mesure dont le rapport
 * s'écarte de plus de [OUTLIER_RATIO] du rapport calé est ignorée pour le calage. Après
 * [OUTLIER_LIMIT] rejets de suite, c'est le rapport calé qui est faux (autre plateau) : le
 * calage reprend.
 */
class FlywheelCadence(
    initialPerRpm: Double = DEFAULT_PER_RPM,
) {
    var perRpm: Double = initialPerRpm
        private set

    private val recent = ArrayDeque<Double>()
    private var rejected = 0

    fun update(data: RidingData): Double {
        val flywheel = data.flywheelRaw
        val raw =
            when {
                data.cadenceRpm <= 0 -> 0.0
                flywheel == null || flywheel <= 0 -> data.cadenceRpm.toDouble()
                else -> {
                    if (data.cadenceRpm >= CALIBRATE_MIN_RPM) calibrate(flywheel.toDouble() / data.cadenceRpm)
                    flywheel / perRpm
                }
            }
        if (raw <= 0) {
            recent.clear()
            return 0.0
        }
        recent.addLast(raw)
        if (recent.size > FILTER_SIZE) recent.removeFirst()
        val sorted = recent.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun calibrate(measured: Double) {
        if (abs(measured - perRpm) > perRpm * OUTLIER_RATIO && rejected < OUTLIER_LIMIT) {
            rejected++
            return
        }
        rejected = 0
        perRpm += (measured - perRpm) * GAIN
    }

    companion object {
        const val DEFAULT_PER_RPM = 305.0

        /** Sous ce seuil, la cadence publiée est trop imprécise pour caler le rapport. */
        const val CALIBRATE_MIN_RPM = 40

        /** Calage lent (une vingtaine de mesures) : le retard de la cadence publiée s'y moyenne. */
        const val GAIN = 0.05

        /** Mesures dans la médiane : environ 3 s au rythme des trames du KICKR. */
        const val FILTER_SIZE = 3

        /** Écart relatif au rapport calé au-delà duquel une mesure est ignorée pour le calage. */
        const val OUTLIER_RATIO = 0.25

        /** Rejets de suite avant de reprendre le calage : 20 mesures, bien plus que les 6 du 18 octobre. */
        const val OUTLIER_LIMIT = 20
    }
}
