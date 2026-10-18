package dev.kickzwift.protocol.fit

import java.io.ByteArrayOutputStream
import kotlin.math.roundToLong

/** Une mesure de la sortie, horodatée en millisecondes Unix. */
data class FitSample(
    val epochMs: Long,
    val powerW: Int,
    val cadenceRpm: Int,
    val speedKmh: Double,
    val distanceM: Double,
    val heartRate: Int?,
)

/** Ce qu'il faut pour clore l'activité. */
data class FitSummary(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Temps de pédalage, pauses exclues. */
    val timerMs: Long,
    val distanceM: Double,
    val avgPowerW: Int,
    val maxPowerW: Int,
    val avgCadenceRpm: Int,
)

/**
 * Fichier FIT d'activité minimal, écrit à la main plutôt qu'avec le SDK de Garmin : en-tête,
 * file_id, une mesure (record) par échantillon, un tour (lap), la séance (session, vélo en
 * intérieur) et l'activité, puis le CRC. Échelles du profil FIT : vitesse en mm/s, distance
 * en cm, durées en ms, horodatage en secondes depuis le 31 décembre 1989.
 *
 * COROS Training Hub refuserait comme vides les fichiers de moins de 20 Ko (relevé tiers, non
 * confirmé par COROS) : un fichier plus petit que [minBytes] est complété par des messages de
 * remplissage d'un numéro réservé aux fabricants (0xFF00), que les lecteurs ignorent.
 */
object FitEncoder {
    /** 20 Ko et une marge. */
    const val MIN_IMPORT_BYTES = 21_000

    fun encode(
        samples: List<FitSample>,
        summary: FitSummary,
        minBytes: Int = MIN_IMPORT_BYTES,
    ): ByteArray {
        val w = FitWriter()
        w.define(LOCAL_FILE_ID, Mesg.FILE_ID, Field.FILE_TYPE, Field.MANUFACTURER, Field.PRODUCT, Field.TIME_CREATED)
        w.data(LOCAL_FILE_ID, Value.ACTIVITY_FILE, Value.DEVELOPMENT, 0L, fitTime(summary.startEpochMs))

        w.define(LOCAL_RECORD, Mesg.RECORD, Field.TIMESTAMP, Field.DISTANCE, Field.SPEED, Field.POWER, Field.CADENCE, Field.HEART_RATE)
        samples.forEach { s ->
            w.data(
                LOCAL_RECORD,
                fitTime(s.epochMs),
                (s.distanceM * CM_PER_M).roundToLong(),
                uint16((s.speedKmh * MM_PER_S_PER_KMH).roundToLong()),
                uint16(s.powerW.toLong()),
                uint8(s.cadenceRpm.toLong()),
                s.heartRate?.let { uint8(it.toLong()) } ?: Value.INVALID_UINT8,
            )
        }

        val totals = totalsOf(summary)
        w.define(LOCAL_LAP, Mesg.LAP, *(TOTAL_FIELDS + arrayOf(Field.EVENT, Field.EVENT_TYPE)))
        w.data(LOCAL_LAP, *(totals + longArrayOf(Value.EVENT_LAP, Value.STOP)))

        w.define(LOCAL_SESSION, Mesg.SESSION, *(TOTAL_FIELDS + SESSION_FIELDS))
        w.data(
            LOCAL_SESSION,
            *(
                totals +
                    longArrayOf(
                        Value.EVENT_SESSION,
                        Value.STOP,
                        Value.CYCLING,
                        Value.INDOOR_CYCLING,
                        uint16(summary.avgPowerW.toLong()),
                        uint16(summary.maxPowerW.toLong()),
                        uint8(summary.avgCadenceRpm.toLong()),
                        0L,
                        1L,
                    )
            ),
        )

        w.define(
            LOCAL_ACTIVITY,
            Mesg.ACTIVITY,
            Field.TIMESTAMP,
            Field.TOTAL_TIMER,
            Field.NUM_SESSIONS,
            Field.ACTIVITY_TYPE,
            Field.EVENT,
            Field.EVENT_TYPE,
        )
        w.data(LOCAL_ACTIVITY, fitTime(summary.endEpochMs), summary.timerMs.coerceAtLeast(0), 1L, 0L, Value.EVENT_ACTIVITY, Value.STOP)
        w.padTo(minBytes)
        return w.file()
    }

    /** CRC-16 du protocole FIT (CRC-16/ARC : polynôme 0xA001, valeur initiale 0). */
    fun crc(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and BYTE
            crc = nibble(crc, b and NIBBLE)
            crc = nibble(crc, b shr NIBBLE_BITS)
        }
        return crc
    }

    private fun nibble(
        crc: Int,
        n: Int,
    ): Int = ((crc shr NIBBLE_BITS) and CRC_LOW_12) xor CRC_TABLE[crc and NIBBLE] xor CRC_TABLE[n]

    private fun totalsOf(s: FitSummary) =
        longArrayOf(
            fitTime(s.endEpochMs),
            fitTime(s.startEpochMs),
            (s.endEpochMs - s.startEpochMs).coerceAtLeast(0),
            s.timerMs.coerceAtLeast(0),
            (s.distanceM * CM_PER_M).roundToLong(),
        )

    private fun fitTime(epochMs: Long): Long = (epochMs / MS_PER_S - FIT_EPOCH_S).coerceAtLeast(0)

    private fun uint8(v: Long) = v.coerceIn(0, Value.MAX_UINT8)

    private fun uint16(v: Long) = v.coerceIn(0, Value.MAX_UINT16)

    /** Types de base FIT utilisés ici. */
    internal enum class Base(
        val code: Int,
        val size: Int,
    ) {
        ENUM(0x00, 1),
        UINT8(0x02, 1),
        UINT16(0x84, 2),
        UINT32(0x86, 4),
    }

    /** Champs du profil FIT : numéro et type. */
    internal enum class Field(
        val num: Int,
        val base: Base,
    ) {
        FILE_TYPE(0, Base.ENUM),
        MANUFACTURER(1, Base.UINT16),
        PRODUCT(2, Base.UINT16),
        TIME_CREATED(4, Base.UINT32),
        TIMESTAMP(253, Base.UINT32),
        DISTANCE(5, Base.UINT32),
        SPEED(6, Base.UINT16),
        POWER(7, Base.UINT16),
        CADENCE(4, Base.UINT8),
        HEART_RATE(3, Base.UINT8),
        START_TIME(2, Base.UINT32),
        TOTAL_ELAPSED(7, Base.UINT32),
        TOTAL_TIMER_LAP(8, Base.UINT32),
        TOTAL_DISTANCE(9, Base.UINT32),
        EVENT(0, Base.ENUM),
        EVENT_TYPE(1, Base.ENUM),
        SPORT(5, Base.ENUM),
        SUB_SPORT(6, Base.ENUM),
        AVG_POWER(20, Base.UINT16),
        MAX_POWER(21, Base.UINT16),
        AVG_CADENCE(18, Base.UINT8),
        FIRST_LAP_INDEX(25, Base.UINT16),
        NUM_LAPS(26, Base.UINT16),
        TOTAL_TIMER(0, Base.UINT32),
        NUM_SESSIONS(1, Base.UINT16),
        ACTIVITY_TYPE(2, Base.ENUM),
    }

    /** Numéros globaux des messages. */
    private object Mesg {
        const val FILE_ID = 0
        const val SESSION = 18
        const val LAP = 19
        const val RECORD = 20
        const val ACTIVITY = 34
    }

    /** Valeurs d'énumérations du profil FIT. */
    private object Value {
        const val ACTIVITY_FILE = 4L
        const val DEVELOPMENT = 255L
        const val EVENT_SESSION = 8L
        const val EVENT_LAP = 9L
        const val EVENT_ACTIVITY = 26L
        const val STOP = 1L
        const val CYCLING = 2L
        const val INDOOR_CYCLING = 6L
        const val INVALID_UINT8 = 0xFFL
        const val MAX_UINT8 = 0xFEL
        const val MAX_UINT16 = 0xFFFEL
    }

    private val TOTAL_FIELDS = arrayOf(Field.TIMESTAMP, Field.START_TIME, Field.TOTAL_ELAPSED, Field.TOTAL_TIMER_LAP, Field.TOTAL_DISTANCE)
    private val SESSION_FIELDS =
        arrayOf(
            Field.EVENT,
            Field.EVENT_TYPE,
            Field.SPORT,
            Field.SUB_SPORT,
            Field.AVG_POWER,
            Field.MAX_POWER,
            Field.AVG_CADENCE,
            Field.FIRST_LAP_INDEX,
            Field.NUM_LAPS,
        )

    private const val LOCAL_FILE_ID = 0
    private const val LOCAL_RECORD = 1
    private const val LOCAL_LAP = 2
    private const val LOCAL_SESSION = 3
    private const val LOCAL_ACTIVITY = 4

    private const val FIT_EPOCH_S = 631_065_600L
    private const val MS_PER_S = 1_000
    private const val CM_PER_M = 100.0
    private const val MM_PER_S_PER_KMH = 1_000.0 / 3.6
    internal const val BYTE = 0xFF
    private const val NIBBLE = 0x0F
    private const val NIBBLE_BITS = 4
    private const val CRC_LOW_12 = 0x0FFF
    private val CRC_TABLE =
        intArrayOf(
            0x0000,
            0xCC01,
            0xD801,
            0x1400,
            0xF001,
            0x3C00,
            0x2800,
            0xE401,
            0xA001,
            0x6C00,
            0x7800,
            0xB401,
            0x5000,
            0x9C01,
            0x8801,
            0x4400,
        )
}

/** Messages de définition et de données, en-tête et CRC du fichier. */
private class FitWriter {
    private val out = ByteArrayOutputStream()
    private val layouts = mutableMapOf<Int, Array<out FitEncoder.Field>>()

    fun define(
        local: Int,
        global: Int,
        vararg fields: FitEncoder.Field,
    ) {
        layouts[local] = fields
        u8(DEFINITION or local)
        u8(0) // réservé
        u8(0) // petit-boutiste
        u16(global)
        u8(fields.size)
        fields.forEach {
            u8(it.num)
            u8(it.base.size)
            u8(it.base.code)
        }
    }

    fun data(
        local: Int,
        vararg values: Long,
    ) {
        val fields = layouts.getValue(local)
        require(values.size == fields.size) { "valeurs et champs en nombre différent" }
        u8(local)
        fields.forEachIndexed { i, f ->
            when (f.base) {
                FitEncoder.Base.UINT16 -> u16(values[i].toInt())
                FitEncoder.Base.UINT32 -> u32(values[i])
                FitEncoder.Base.ENUM, FitEncoder.Base.UINT8 -> u8(values[i].toInt())
            }
        }
    }

    /**
     * Complète les données pour que le fichier atteigne [fileBytes] : messages de 250 octets
     * (valeur invalide 0xFF) d'un message propre au fabricant, sans signification.
     */
    fun padTo(fileBytes: Int) {
        val dataBytes = fileBytes - HEADER_SIZE - CRC_SIZE
        if (out.size() >= dataBytes) return
        u8(DEFINITION or LOCAL_PAD)
        u8(0) // réservé
        u8(0) // petit-boutiste
        u16(PAD_MESG)
        u8(1)
        u8(0) // champ 0
        u8(PAD_CHUNK)
        u8(BASE_BYTE)
        while (out.size() < dataBytes) {
            u8(LOCAL_PAD)
            repeat(PAD_CHUNK) { u8(FitEncoder.BYTE) }
        }
    }

    fun file(): ByteArray {
        val data = out.toByteArray()
        out.reset()
        u8(HEADER_SIZE)
        u8(PROTOCOL_VERSION)
        u16(PROFILE_VERSION)
        u32(data.size.toLong())
        ".FIT".forEach { u8(it.code) }
        u16(FitEncoder.crc(out.toByteArray()))
        out.write(data)
        val crc = FitEncoder.crc(out.toByteArray())
        u16(crc)
        return out.toByteArray()
    }

    private fun u8(v: Int) = out.write(v and FitEncoder.BYTE)

    private fun u16(v: Int) {
        u8(v)
        u8(v shr BITS_8)
    }

    private fun u32(v: Long) {
        u16((v and LOW_16).toInt())
        u16(((v shr BITS_16) and LOW_16).toInt())
    }

    private companion object {
        const val DEFINITION = 0x40
        const val HEADER_SIZE = 14
        const val CRC_SIZE = 2
        const val LOCAL_PAD = 15
        const val PAD_MESG = 0xFF00
        const val PAD_CHUNK = 250
        const val BASE_BYTE = 0x0D
        const val PROTOCOL_VERSION = 0x20
        const val PROFILE_VERSION = 2140
        const val BITS_8 = 8
        const val BITS_16 = 16
        const val LOW_16 = 0xFFFFL
    }
}
