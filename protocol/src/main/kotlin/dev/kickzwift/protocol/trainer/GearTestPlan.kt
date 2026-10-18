package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.gear.PowerGears

/**
 * Essai des vitesses (Réglages › Gear test) : quatre façons d'envoyer un rapport au KICKR,
 * chacune en vitesse 12 ([LOW]) puis 17 ([HIGH]), pendant qu'on pédale à cadence constante.
 * Sur la fin de chaque étape, la puissance mesurée est comparée à celle d'un vrai vélo au même
 * rapport et à la même cadence : la bonne façon est celle qui s'en approche.
 */
object GearTestPlan {
    const val LOW = 2.40
    const val HIGH = 3.49
    const val WARMUP_MS = 15_000L
    const val STEP_MS = 25_000L

    /** Début d'étape ignoré, le temps que la résistance se stabilise. */
    const val SETTLE_MS = 10_000L

    enum class Way {
        /** Le rapport seul, comme le script du 17 octobre. */
        GEAR_ONLY,

        /** Le rapport seul, après une trame de pente. */
        AFTER_GRADE,

        /** Le rapport et les masses, la seconde trame de l'app. */
        WITH_MASSES,

        /** Pente, rapport et masses dans une seule trame. */
        COMBINED,
    }

    /** Une étape : trames envoyées à son début, puis attente ; [way] et [ratio] nuls pour l'échauffement. */
    data class Step(
        val way: Way?,
        val ratio: Double?,
        val frames: List<ByteArray>,
        val durationMs: Long,
    ) {
        /** A, B, C ou D, comme dans prototype/kickr_sequence.py. */
        val code: String get() = way?.let { ('A' + it.ordinal).toString() } ?: "-"
    }

    data class Sample(
        /** Depuis le début de l'étape. */
        val atMs: Long,
        val powerW: Int,
        val cadenceRpm: Int,
        val speedKmh: Double,
    )

    data class Result(
        val step: Step,
        val powerW: Int,
        val cadenceRpm: Int,
        val speedKmh: Double,
        val realBikeW: Int,
    )

    /** Échauffement, puis les huit étapes ; les masses sont celles des réglages. */
    fun steps(
        bikeKg: Double,
        riderKg: Double,
    ): List<Step> =
        listOf(Step(null, null, emptyList(), WARMUP_MS)) +
            Way.entries.flatMap { way ->
                listOf(LOW, HIGH).map { ratio ->
                    val frames =
                        when (way) {
                            Way.GEAR_ONLY -> listOf(TrainerCodec.gearRatio(ratio))
                            Way.AFTER_GRADE ->
                                if (ratio == LOW) {
                                    listOf(TrainerCodec.simulation(0.0), TrainerCodec.gearRatio(ratio))
                                } else {
                                    listOf(TrainerCodec.gearRatio(ratio))
                                }
                            Way.WITH_MASSES -> listOf(TrainerCodec.physical(ratio, bikeKg, riderKg))
                            Way.COMBINED -> listOf(TrainerCodec.combined(0.0, ratio, bikeKg, riderKg))
                        }
                    Step(way, ratio, frames, STEP_MS)
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

    /** Moyennes de fin d'étape ; null pour l'échauffement ou sans pédalage mesuré. */
    fun result(
        step: Step,
        samples: List<Sample>,
        massKg: Double,
    ): Result? {
        val tail = samples.filter { it.atMs >= SETTLE_MS && it.cadenceRpm > 0 }
        val ratio = step.ratio
        return if (ratio == null || tail.isEmpty()) {
            null
        } else {
            val cadence = tail.map { it.cadenceRpm }.average()
            Result(
                step = step,
                powerW = tail.map { it.powerW }.average().toInt(),
                cadenceRpm = cadence.toInt(),
                speedKmh = tail.map { it.speedKmh }.average(),
                realBikeW = realBikeW(cadence, ratio, massKg).toInt(),
            )
        }
    }

    /** Vitesse d'un vrai vélo en km/h d'après la cadence, pour comparer à la vitesse simulée. */
    fun realBikeKmh(
        cadenceRpm: Double,
        ratio: Double,
    ): Double = PowerGears.speedKmh(cadenceRpm, ratio)
}
