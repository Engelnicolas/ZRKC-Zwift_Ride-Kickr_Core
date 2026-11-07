package dev.kickzwift.protocol.fit

import kotlin.math.roundToInt

/** Une tranche de [RideCurve] ; [heartRate] null sans capteur cardiaque. */
data class CurvePoint(
    val powerW: Int,
    val heartRate: Int?,
)

/**
 * Courbe d'une sortie : puissance et fréquence cardiaque moyennes par tranche de [BUCKET_S] s de
 * pédalage, assez légère pour rester avec l'activité (3 octets par point). Une pause ne laisse pas
 * de trou : aucune mesure n'y est enregistrée, sa tranche est simplement absente.
 */
data class RideCurve(
    val points: List<CurvePoint>,
) {
    fun toBytes(): ByteArray {
        val bytes = ByteArray(points.size * POINT_BYTES)
        points.forEachIndexed { i, p ->
            val at = i * POINT_BYTES
            val power = p.powerW.coerceIn(0, MAX_U16)
            bytes[at] = power.toByte()
            bytes[at + 1] = (power shr BYTE_BITS).toByte()
            bytes[at + 2] = (p.heartRate ?: NO_HEART_RATE).coerceIn(0, MAX_U8).toByte()
        }
        return bytes
    }

    companion object {
        const val BUCKET_S = 30
        val EMPTY = RideCurve(emptyList())

        private const val POINT_BYTES = 3
        private const val BYTE_BITS = 8
        private const val MAX_U8 = 0xFF
        private const val MAX_U16 = 0xFFFF
        private const val NO_HEART_RATE = 0
        private const val MS_PER_S = 1_000L

        fun of(samples: List<FitSample>): RideCurve {
            val start = samples.firstOrNull()?.epochMs ?: return EMPTY
            return RideCurve(
                samples.groupBy { (it.epochMs - start) / (BUCKET_S * MS_PER_S) }.values.map { bucket ->
                    val heartRates = bucket.mapNotNull { s -> s.heartRate?.takeIf { it > NO_HEART_RATE } }
                    CurvePoint(
                        powerW = bucket.map { it.powerW }.average().roundToInt(),
                        heartRate = heartRates.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                    )
                },
            )
        }

        /** Vide si la longueur ne correspond pas à des points entiers. */
        fun fromBytes(bytes: ByteArray): RideCurve {
            if (bytes.size % POINT_BYTES != 0) return EMPTY
            return RideCurve(
                (bytes.indices step POINT_BYTES).map { at ->
                    val low = bytes[at].toInt() and MAX_U8
                    val high = bytes[at + 1].toInt() and MAX_U8
                    val heartRate = bytes[at + 2].toInt() and MAX_U8
                    CurvePoint(low or (high shl BYTE_BITS), heartRate.takeIf { it != NO_HEART_RATE })
                },
            )
        }
    }
}
