package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.gear.PowerGears

/**
 * Essai du renvoi (Réglages › Resend test) : le rapport tient-il si la consigne est renvoyée
 * régulièrement, comme Zwift (pente environ une fois par seconde) et open-cog (toutes les 2 s) ?
 * L'app, elle, ne réécrit jamais une consigne identique. Trois façons, chacune en vitesse 12
 * ([LOW]) puis 17 ([HIGH]), pendant qu'on pédale à cadence constante ; même séquence que
 * `prototype/kickr_sequence.py renvoi`. Les étapes en [HIGH] durent [HOLD_MS] pour voir si la
 * puissance retombe après le pic du changement : la puissance du début d'étape ([EARLY_MS]) est
 * comparée à celle de la fin, et à celle d'un vrai vélo au même rapport et à la même cadence.
 */
object ResendTestPlan {
    const val LOW = 2.40
    const val HIGH = 3.49
    const val WARMUP_MS = 15_000L
    const val STEP_MS = 25_000L
    const val HOLD_MS = 40_000L
    const val KEEPALIVE_MS = 1_000L

    /** Début d'étape ignoré, le temps que la résistance se stabilise. */
    const val SETTLE_MS = 10_000L

    /** Début d'étape, où tombe le pic d'un changement de rapport. */
    val EARLY_MS = 2_000L..8_000L

    enum class Way {
        /** Rapport et masses une fois, comme l'app aujourd'hui. */
        NO_RESEND,

        /** Pente renvoyée chaque seconde, comme Zwift. */
        GRADE,

        /** Pente, rapport et masses renvoyés chaque seconde. */
        GRADE_AND_GEAR,
    }

    /**
     * Une étape : [frames] envoyées à son début, [keepalive] toutes les [KEEPALIVE_MS] jusqu'à
     * sa fin ; [way] et [ratio] nuls pour l'échauffement.
     */
    data class Step(
        val way: Way?,
        val ratio: Double?,
        val frames: List<ByteArray>,
        val keepalive: List<ByteArray>,
        val durationMs: Long,
    ) {
        /** E, F ou G, comme dans prototype/kickr_sequence.py. */
        val code: String get() = way?.let { ('E' + it.ordinal).toString() } ?: "-"
    }

    data class Sample(
        /** Depuis le début de l'étape. */
        val atMs: Long,
        val powerW: Int,
        val cadenceRpm: Int,
    )

    data class Result(
        val step: Step,
        /** Moyenne sur [EARLY_MS] ; null sans pédalage mesuré. */
        val earlyW: Int?,
        val powerW: Int,
        val cadenceRpm: Int,
        val realBikeW: Int,
    )

    /** Échauffement (à plat), puis les six étapes ; les masses sont celles des réglages. */
    fun steps(
        bikeKg: Double,
        riderKg: Double,
    ): List<Step> =
        listOf(Step(null, null, listOf(TrainerCodec.simulation(0.0)), emptyList(), WARMUP_MS)) +
            Way.entries.flatMap { way ->
                listOf(LOW, HIGH).map { ratio ->
                    val physical = TrainerCodec.physical(ratio, bikeKg, riderKg)
                    val keepalive =
                        when (way) {
                            Way.NO_RESEND -> emptyList()
                            Way.GRADE -> listOf(TrainerCodec.simulation(0.0))
                            Way.GRADE_AND_GEAR -> listOf(TrainerCodec.simulation(0.0), physical)
                        }
                    Step(way, ratio, listOf(physical), keepalive, if (ratio == HIGH) HOLD_MS else STEP_MS)
                }
            }

    /** Remise en état, à la fin comme à l'arrêt : à plat, en vitesse 12. */
    val RESTORE: List<ByteArray> get() = listOf(TrainerCodec.simulation(0.0), TrainerCodec.gearRatio(LOW))

    /** Puissance d'un vrai vélo à plat, avec la résistance que reçoit le KICKR (CWa et Crr de Zwift). */
    fun realBikeW(
        cadenceRpm: Double,
        ratio: Double,
        massKg: Double,
    ): Double = PowerGears.roadWatts(PowerGears.speedKmh(cadenceRpm, ratio), 0.0, massKg)

    /** Moyennes de début et de fin d'étape ; null pour l'échauffement ou sans pédalage mesuré. */
    fun result(
        step: Step,
        samples: List<Sample>,
        massKg: Double,
    ): Result? {
        val pedaling = samples.filter { it.cadenceRpm > 0 }
        val tail = pedaling.filter { it.atMs >= SETTLE_MS }
        val early = pedaling.filter { it.atMs in EARLY_MS }
        val ratio = step.ratio
        return if (ratio == null || tail.isEmpty()) {
            null
        } else {
            val cadence = tail.map { it.cadenceRpm }.average()
            Result(
                step = step,
                earlyW =
                    early
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.powerW }
                        ?.average()
                        ?.toInt(),
                powerW = tail.map { it.powerW }.average().toInt(),
                cadenceRpm = cadence.toInt(),
                realBikeW = realBikeW(cadence, ratio, massKg).toInt(),
            )
        }
    }
}
