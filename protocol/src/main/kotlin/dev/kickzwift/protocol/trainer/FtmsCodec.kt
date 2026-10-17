package dev.kickzwift.protocol.trainer

import dev.kickzwift.protocol.RidingData
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Trame « Indoor Bike Data » (0x2AD2) ; un champ absent du drapeau vaut null. */
data class IndoorBikeData(
    val speedKmh: Double? = null,
    val averageSpeedKmh: Double? = null,
    val cadenceRpm: Double? = null,
    val averageCadenceRpm: Double? = null,
    val totalDistanceM: Int? = null,
    val resistanceLevel: Int? = null,
    val powerW: Int? = null,
    val averagePowerW: Int? = null,
    val totalEnergyKcal: Int? = null,
    val energyPerHourKcal: Int? = null,
    val energyPerMinuteKcal: Int? = null,
    val heartRate: Int? = null,
    val metabolicEquivalent: Double? = null,
    val elapsedTimeS: Int? = null,
    val remainingTimeS: Int? = null,
) {
    /** Vitesse en 0,01 km/h par définition de la norme : pas d'hypothèse d'unité ici. */
    fun toRidingData(atMs: Long): RidingData =
        RidingData(
            powerW = powerW ?: 0,
            cadenceRpm = cadenceRpm?.roundToInt() ?: 0,
            speedKmh = speedKmh ?: 0.0,
            heartRate = heartRate?.takeIf { it > 0 },
            atMs = atMs,
        )
}

/** Trame reçue d'un trainer en FTMS. */
sealed interface FtmsFrame {
    data class IndoorBike(
        val data: IndoorBikeData,
    ) : FtmsFrame

    /** Réponse du point de contrôle (0x80) : opcode de la requête et code de résultat. */
    data class ControlResponse(
        val requestOpcode: Int,
        val result: Int,
    ) : FtmsFrame {
        val succeeded: Boolean get() = result == FtmsCodec.RESULT_SUCCESS
    }

    data class Malformed(
        val reason: String,
    ) : FtmsFrame
}

/**
 * Codec FTMS (Fitness Machine Service 0x1826), protocole de repli de la spécification :
 * point de contrôle 0x2AD9 en écriture, « Indoor Bike Data » 0x2AD2 en notification.
 * Tous les entiers sont en little-endian.
 *
 * En repli, la vitesse virtuelle se simule par un décalage de pente : ce calcul revient
 * à TrainerWriter (lot 6), ce codec ne fait qu'encoder et décoder.
 */
object FtmsCodec {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_OR_RESUME = 0x07
    const val OP_STOP_OR_PAUSE = 0x08
    const val OP_SET_SIMULATION = 0x11
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    /** Valeurs de Zwift : Crr 0,004 ; coefficient de traînée 0,51. */
    const val DEFAULT_CRR = 0.004
    const val DEFAULT_CW = 0.51

    private const val STOP = 0x01
    private const val PAUSE = 0x02
    private const val WIND_SCALE = 1_000.0
    private const val GRADE_SCALE = 100.0
    private const val CRR_SCALE = 10_000.0
    private const val CW_SCALE = 100.0

    fun requestControl(): ByteArray = byteArrayOf(OP_REQUEST_CONTROL.toByte())

    fun reset(): ByteArray = byteArrayOf(OP_RESET.toByte())

    fun startOrResume(): ByteArray = byteArrayOf(OP_START_OR_RESUME.toByte())

    fun stop(): ByteArray = byteArrayOf(OP_STOP_OR_PAUSE.toByte(), STOP.toByte())

    fun pause(): ByteArray = byteArrayOf(OP_STOP_OR_PAUSE.toByte(), PAUSE.toByte())

    /** Mode ERG : puissance cible en watts (sint16). */
    fun targetPower(watts: Int): ByteArray {
        require(watts in 0..Short.MAX_VALUE) { "puissance cible hors bornes : $watts" }
        return le(1 + Short.SIZE_BYTES) { put(OP_SET_TARGET_POWER.toByte()).putShort(watts.toShort()) }
    }

    /**
     * Paramètres de simulation : vent (0,001 m/s, sint16), pente (0,01 %, sint16),
     * Crr (0,0001, uint8), coefficient de traînée (0,01 kg/m, uint8).
     */
    fun simulation(
        gradePct: Double,
        windMps: Double = 0.0,
        crr: Double = DEFAULT_CRR,
        cw: Double = DEFAULT_CW,
    ): ByteArray {
        val wind = scaled(windMps, WIND_SCALE, Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt(), "vent")
        val grade = scaled(gradePct, GRADE_SCALE, Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt(), "pente")
        val crrRaw = scaled(crr, CRR_SCALE, 0..UBYTE_MAX, "Crr")
        val cwRaw = scaled(cw, CW_SCALE, 0..UBYTE_MAX, "cw")
        return le(1 + 2 * Short.SIZE_BYTES + 2) {
            put(OP_SET_SIMULATION.toByte()).putShort(wind.toShort()).putShort(grade.toShort())
            put(crrRaw.toByte()).put(cwRaw.toByte())
        }
    }

    /** Ne lève jamais : une trame tronquée donne [FtmsFrame.Malformed]. */
    fun decodeIndoorBikeData(frame: ByteArray): FtmsFrame = FtmsDecoder.indoorBikeData(frame)

    /** Ne lève jamais ; une trame qui n'est pas une réponse 0x80 donne [FtmsFrame.Malformed]. */
    fun decodeControlResponse(frame: ByteArray): FtmsFrame =
        if (frame.size >= RESPONSE_SIZE && (frame[0].toInt() and BYTE_MASK) == OP_RESPONSE) {
            FtmsFrame.ControlResponse(frame[1].toInt() and BYTE_MASK, frame[2].toInt() and BYTE_MASK)
        } else {
            FtmsFrame.Malformed("pas une réponse du point de contrôle")
        }

    private fun scaled(
        value: Double,
        scale: Double,
        range: IntRange,
        name: String,
    ): Int {
        val raw = (value * scale).roundToInt()
        require(raw in range) { "$name hors bornes : $value" }
        return raw
    }

    private inline fun le(
        size: Int,
        fill: ByteBuffer.() -> Unit,
    ): ByteArray =
        ByteBuffer
            .allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply(fill)
            .array()

    internal const val BYTE_MASK = 0xFF
    private const val UBYTE_MAX = 0xFF
    private const val RESPONSE_SIZE = 3
}

/** Lecture de « Indoor Bike Data » : drapeaux sur 16 bits, puis les champs présents dans l'ordre. */
internal object FtmsDecoder {
    private const val MORE_DATA = 0
    private const val AVERAGE_SPEED = 1
    private const val CADENCE = 2
    private const val AVERAGE_CADENCE = 3
    private const val TOTAL_DISTANCE = 4
    private const val RESISTANCE = 5
    private const val POWER = 6
    private const val AVERAGE_POWER = 7
    private const val ENERGY = 8
    private const val HEART_RATE = 9
    private const val METABOLIC = 10
    private const val ELAPSED = 11
    private const val REMAINING = 12
    private const val SPEED_SCALE = 100.0
    private const val CADENCE_SCALE = 2.0
    private const val MET_SCALE = 10.0
    private const val USHORT_MASK = 0xFFFF
    private const val WORD_BITS = 16

    fun indoorBikeData(frame: ByteArray): FtmsFrame =
        try {
            FtmsFrame.IndoorBike(read(ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)))
        } catch (e: BufferUnderflowException) {
            FtmsFrame.Malformed("trame Indoor Bike Data tronquée (${frame.size} octets) : $e")
        }

    private fun read(b: ByteBuffer): IndoorBikeData {
        val flags = b.uShort()

        fun has(bit: Int) = flags and (1 shl bit) != 0

        // Bit 0 « More Data » : la vitesse instantanée est présente quand il vaut 0.
        val speed = b.opt(!has(MORE_DATA)) { uShort() / SPEED_SCALE }
        val avgSpeed = b.opt(has(AVERAGE_SPEED)) { uShort() / SPEED_SCALE }
        val cadence = b.opt(has(CADENCE)) { uShort() / CADENCE_SCALE }
        val avgCadence = b.opt(has(AVERAGE_CADENCE)) { uShort() / CADENCE_SCALE }
        val distance = b.opt(has(TOTAL_DISTANCE)) { uShort() or (uByte() shl WORD_BITS) }
        val resistance = b.opt(has(RESISTANCE)) { short.toInt() }
        val power = b.opt(has(POWER)) { short.toInt() }
        val avgPower = b.opt(has(AVERAGE_POWER)) { short.toInt() }
        val energy = b.opt(has(ENERGY)) { Triple(uShort(), uShort(), uByte()) }
        val hr = b.opt(has(HEART_RATE)) { uByte() }
        val met = b.opt(has(METABOLIC)) { uByte() / MET_SCALE }
        val elapsed = b.opt(has(ELAPSED)) { uShort() }
        val remaining = b.opt(has(REMAINING)) { uShort() }
        return IndoorBikeData(
            speed,
            avgSpeed,
            cadence,
            avgCadence,
            distance,
            resistance,
            power,
            avgPower,
            energy?.first,
            energy?.second,
            energy?.third,
            hr,
            met,
            elapsed,
            remaining,
        )
    }

    /** Lit le champ seulement si son drapeau est levé ; sinon null, sans avancer. */
    private inline fun <T> ByteBuffer.opt(
        present: Boolean,
        read: ByteBuffer.() -> T,
    ): T? = if (present) read() else null

    private fun ByteBuffer.uShort(): Int = short.toInt() and USHORT_MASK

    private fun ByteBuffer.uByte(): Int = get().toInt() and FtmsCodec.BYTE_MASK
}
