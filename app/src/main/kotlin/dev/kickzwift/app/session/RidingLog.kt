package dev.kickzwift.app.session

import dev.kickzwift.protocol.RidingData
import dev.kickzwift.protocol.TrainerTarget
import java.util.Locale

/**
 * Une ligne par seconde au plus pendant la sortie : mesures du trainer et consigne écrite, pour
 * voir si la puissance suit les changements de rapport. Deux vitesses : celle que publie le
 * KICKR, et celle qui s'affiche (le vélo virtuel avec les vitesses par la puissance).
 */
class RidingLog(
    private val env: SessionEnvironment,
) {
    private var lastMs: Long? = null

    /** Au départ d'une sortie : la première mesure est journalisée aussitôt. */
    fun reset() {
        lastMs = null
    }

    fun log(
        data: RidingData,
        shownKmh: Double,
        sensorHeartRate: Int?,
        gear: Int,
        sent: TrainerTarget?,
    ) {
        val now = env.now()
        if (lastMs?.let { now - it < INTERVAL_MS } == true) return
        lastMs = now
        val speed = "%.1f".format(Locale.ROOT, data.speedKmh)
        val shown = "%.1f".format(Locale.ROOT, shownKmh)
        val extras =
            listOfNotNull(
                data.flywheelRaw?.let { "volant $it" },
                (sensorHeartRate ?: data.heartRate)?.let { "FC $it" },
            ).joinToString("") { ", $it" }
        val speeds = "$speed km/h KICKR, $shown km/h affichés"
        env.log("pédalage : ${data.powerW} W, ${data.cadenceRpm} rpm, $speeds$extras, vitesse $gear, consigne ${describe(sent)}")
    }

    private fun describe(target: TrainerTarget?): String =
        when (target) {
            is TrainerTarget.Simulation -> "rapport ${target.gearRatio}, pente ${target.gradePct} %"
            is TrainerTarget.Erg -> "ERG ${target.watts} W"
            TrainerTarget.Neutral -> "à plat"
            null -> "aucune"
        }

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}
