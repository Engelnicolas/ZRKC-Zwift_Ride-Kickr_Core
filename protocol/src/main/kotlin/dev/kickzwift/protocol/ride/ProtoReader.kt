package dev.kickzwift.protocol.ride

/**
 * Lecteur protobuf minimal : varints et champs délimités, rien d'autre.
 *
 * Le message boutons du Ride n'a que deux champs ; ce lecteur évite d'embarquer
 * le runtime protobuf pour si peu. Toute entrée invalide lève [MalformedFrameException].
 */
internal class ProtoReader(
    private val buf: ByteArray,
    private var pos: Int = 0,
    private val end: Int = buf.size,
) {
    val hasMore: Boolean get() = pos < end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < MAX_VARINT_BITS) {
            if (pos >= end) throw MalformedFrameException("varint tronqué")
            val b = buf[pos++].toInt()
            result = result or ((b and VARINT_PAYLOAD).toLong() shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_GROUP_BITS
        }
        throw MalformedFrameException("varint trop long")
    }

    /** Lit une clé de champ : (numéro, type de fil). */
    fun readTag(): Pair<Int, Int> {
        val key = readVarint()
        return (key ushr TAG_TYPE_BITS).toInt() to (key and TAG_TYPE_MASK).toInt()
    }

    /** Sous-lecteur sur un champ délimité (type de fil 2). */
    fun readDelimited(): ProtoReader {
        val len = readVarint()
        if (len < 0 || len > end - pos) throw MalformedFrameException("longueur hors trame")
        val sub = ProtoReader(buf, pos, pos + len.toInt())
        pos += len.toInt()
        return sub
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_LEN -> readDelimited()
            WIRE_FIXED64 -> advance(FIXED64_BYTES)
            WIRE_FIXED32 -> advance(FIXED32_BYTES)
            else -> throw MalformedFrameException("type de fil $wireType non géré")
        }
    }

    private fun advance(n: Int) {
        if (n > end - pos) throw MalformedFrameException("champ fixe tronqué")
        pos += n
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
        private const val MAX_VARINT_BITS = 64
        private const val VARINT_PAYLOAD = 0x7F
        private const val VARINT_CONTINUE = 0x80
        private const val VARINT_GROUP_BITS = 7
        private const val TAG_TYPE_BITS = 3
        private const val TAG_TYPE_MASK = 0x07L
        private const val FIXED64_BYTES = 8
        private const val FIXED32_BYTES = 4

        /** sint32 protobuf : 199 -> -100, 200 -> +100. */
        fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()
    }
}

class MalformedFrameException(
    message: String,
) : RuntimeException(message)
