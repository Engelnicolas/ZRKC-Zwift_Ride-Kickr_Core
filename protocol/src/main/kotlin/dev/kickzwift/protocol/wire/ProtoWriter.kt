package dev.kickzwift.protocol.wire

import java.io.ByteArrayOutputStream

/** Écrivain protobuf minimal, pendant de [ProtoReader] : varints et sous-messages. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(
        field: Int,
        value: Long,
    ): ProtoWriter {
        require(value >= 0) { "varint négatif : encoder en zigzag avec sint()" }
        tag(field, ProtoReader.WIRE_VARINT)
        raw(value)
        return this
    }

    /** sint32 protobuf (zigzag) : -100 -> 199, +100 -> 200. */
    fun sint(
        field: Int,
        value: Int,
    ): ProtoWriter = varint(field, ((value shl 1) xor (value shr SIGN_SHIFT)).toLong() and UINT32_MASK)

    fun message(
        field: Int,
        payload: ByteArray,
    ): ProtoWriter {
        tag(field, ProtoReader.WIRE_LEN)
        raw(payload.size.toLong())
        out.write(payload)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(
        field: Int,
        wire: Int,
    ) = raw((field.toLong() shl TAG_TYPE_BITS) or wire.toLong())

    private fun raw(value: Long) {
        var v = value
        while (v >= VARINT_CONTINUE) {
            out.write(((v and VARINT_PAYLOAD) or VARINT_CONTINUE).toInt())
            v = v ushr VARINT_GROUP_BITS
        }
        out.write(v.toInt())
    }

    private companion object {
        const val VARINT_PAYLOAD = 0x7FL
        const val VARINT_CONTINUE = 0x80L
        const val VARINT_GROUP_BITS = 7
        const val TAG_TYPE_BITS = 3
        const val SIGN_SHIFT = 31
        const val UINT32_MASK = 0xFFFF_FFFFL
    }
}
