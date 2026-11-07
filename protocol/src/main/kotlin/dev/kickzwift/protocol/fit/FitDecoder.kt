package dev.kickzwift.protocol.fit

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Relit les mesures d'un fichier écrit par [FitEncoder] : en-têtes normaux, petit-boutiste, sans
 * champs de développeur. Tout le reste, et un CRC faux, rend une liste vide.
 */
object FitDecoder {
    private const val MIN_FILE_BYTES = 14
    private const val SIGNATURE_AT = 8
    private const val SIGNATURE = ".FIT"
    private const val DATA_SIZE_AT = 4
    private const val COMPRESSED = 0x80
    private const val DEFINITION = 0x40
    private const val DEVELOPER = 0x20
    private const val LOCAL_MASK = 0x0F
    private const val FIELD_BYTES = 3
    private const val INVALID_U8 = 0xFFL
    private const val INVALID_U16 = 0xFFFFL
    private const val BYTE_BITS = 8

    private class Field(
        val num: Int,
        val size: Int,
    )

    private class Layout(
        val global: Int,
        val fields: List<Field>,
    )

    fun samples(file: ByteArray): List<FitSample> {
        if (!isIntact(file)) return emptyList()
        val buffer = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = headerSize(file)
        val end = headerSize + buffer.getInt(DATA_SIZE_AT)
        buffer.position(headerSize)
        return try {
            read(buffer, end)
        } catch (_: BufferUnderflowException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun headerSize(file: ByteArray) = file[0].toInt() and FitEncoder.BYTE

    private fun isIntact(file: ByteArray) =
        file.size >= MIN_FILE_BYTES &&
            FitEncoder.crc(file) == 0 &&
            headerSize(file) <= file.size &&
            String(file, SIGNATURE_AT, SIGNATURE.length, Charsets.US_ASCII) == SIGNATURE

    private fun read(
        b: ByteBuffer,
        end: Int,
    ): List<FitSample> {
        val layouts = HashMap<Int, Layout>()
        val samples = mutableListOf<FitSample>()
        while (b.position() < end) {
            val header = b.u8()
            require(header and (COMPRESSED or DEVELOPER) == 0) { "en-tête non pris en charge" }
            val local = header and LOCAL_MASK
            if (header and DEFINITION != 0) {
                layouts[local] = definition(b)
            } else {
                val layout = requireNotNull(layouts[local]) { "message sans définition" }
                val values = layout.fields.associate { it.num to b.unsigned(it.size) }
                if (layout.global == FitEncoder.Mesg.RECORD) samples += sample(values)
            }
        }
        return samples
    }

    private fun definition(b: ByteBuffer): Layout {
        b.u8() // réservé
        require(b.u8() == 0) { "gros-boutiste non pris en charge" }
        val global = b.short.toInt() and FitEncoder.U16
        val count = b.u8()
        val fields =
            List(count) {
                val raw = ByteArray(FIELD_BYTES).also { b.get(it) }
                Field(raw[0].toInt() and FitEncoder.BYTE, raw[1].toInt() and FitEncoder.BYTE)
            }
        return Layout(global, fields)
    }

    private fun sample(values: Map<Int, Long>) =
        FitSample(
            epochMs = (values.value(FitEncoder.Field.TIMESTAMP) + FitEncoder.FIT_EPOCH_S) * FitEncoder.MS_PER_S,
            powerW = values.value(FitEncoder.Field.POWER).takeIf { it != INVALID_U16 }?.toInt() ?: 0,
            cadenceRpm = values.value(FitEncoder.Field.CADENCE).takeIf { it != INVALID_U8 }?.toInt() ?: 0,
            speedKmh = (values.value(FitEncoder.Field.SPEED).takeIf { it != INVALID_U16 } ?: 0L) / FitEncoder.MM_PER_S_PER_KMH,
            distanceM = values.value(FitEncoder.Field.DISTANCE) / FitEncoder.CM_PER_M,
            heartRate = values.value(FitEncoder.Field.HEART_RATE).takeIf { it != INVALID_U8 }?.toInt(),
        )

    private fun Map<Int, Long>.value(field: FitEncoder.Field) = this[field.num] ?: 0L

    private fun ByteBuffer.u8() = get().toInt() and FitEncoder.BYTE

    private fun ByteBuffer.unsigned(size: Int): Long {
        val raw = ByteArray(size).also { get(it) }
        return raw.reversed().fold(0L) { acc, byte -> (acc shl BYTE_BITS) or (byte.toLong() and FitEncoder.BYTE.toLong()) }
    }
}
