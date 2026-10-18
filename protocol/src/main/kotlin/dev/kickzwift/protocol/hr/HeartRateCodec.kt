package dev.kickzwift.protocol.hr

/**
 * Mesure de fréquence cardiaque (caractéristique 0x2A37 du service standard 0x180D) :
 * octet de drapeaux, puis la fréquence sur 8 bits, ou sur 16 bits petit-boutiste si le bit 0
 * des drapeaux est levé. Les champs suivants (contact, énergie, intervalles RR) sont ignorés.
 */
object HeartRateCodec {
    private const val FLAG_UINT16 = 0x01
    private const val BYTE_MASK = 0xFF
    private const val BYTE_BITS = 8
    private const val UINT8_FRAME = 2
    private const val UINT16_FRAME = 3

    /** Fréquence en battements par minute, ou null si la trame est tronquée ou vaut 0. */
    fun decode(frame: ByteArray): Int? {
        val wide = frame.isNotEmpty() && frame[0].toInt() and FLAG_UINT16 != 0
        val bpm =
            when {
                wide && frame.size >= UINT16_FRAME -> byte(frame, 1) or (byte(frame, 2) shl BYTE_BITS)
                !wide && frame.size >= UINT8_FRAME -> byte(frame, 1)
                else -> null
            }
        return bpm?.takeIf { it > 0 }
    }

    private fun byte(
        frame: ByteArray,
        i: Int,
    ) = frame[i].toInt() and BYTE_MASK
}
