package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.TrainerTarget
import dev.kickzwift.protocol.grade.GradeLimits

/**
 * Vitesses virtuelles en repli FTMS : le trainer ne connaît pas de rapport, on les simule par
 * un décalage de pente, comme dadepro/zwift-virtual-shifting.
 *
 * Décalage = (rapport / [referenceRatio] − 1) × [pctPerRelativeRatio] : nul au rapport de
 * référence (vitesse 12 de la table Zwift, 2,40), négatif en dessous, positif au-dessus. Avec
 * la table Zwift : −3,4 % en vitesse 1, +6,4 % en vitesse 24. Valeurs de départ, à ajuster
 * sur le vélo ; la pente finale reste dans [limits].
 */
class FtmsGears(
    private val referenceRatio: Double = REFERENCE_RATIO,
    private val pctPerRelativeRatio: Double = PCT_PER_RELATIVE_RATIO,
    private val limits: GradeLimits = GradeLimits(),
) {
    init {
        require(referenceRatio > 0) { "rapport de référence nul" }
    }

    fun offsetPct(ratio: Double): Double = (ratio / referenceRatio - 1) * pctPerRelativeRatio

    /** Trame du point de contrôle FTMS pour une consigne. */
    fun encode(target: TrainerTarget): ByteArray =
        when (target) {
            is TrainerTarget.Simulation ->
                FtmsCodec.simulation((target.gradePct + offsetPct(target.gearRatio)).coerceIn(limits.minPct, limits.maxPct))
            is TrainerTarget.Erg -> FtmsCodec.targetPower(target.watts)
            TrainerTarget.Neutral -> FtmsCodec.simulation(0.0)
        }

    companion object {
        const val REFERENCE_RATIO = 2.40
        const val PCT_PER_RELATIVE_RATIO = 5.0
    }
}
