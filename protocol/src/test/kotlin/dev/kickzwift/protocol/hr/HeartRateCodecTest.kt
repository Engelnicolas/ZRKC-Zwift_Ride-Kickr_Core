package dev.kickzwift.protocol.hr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HeartRateCodecTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `frequence sur 8 bits`() {
        assertEquals(72, HeartRateCodec.decode(bytes(0x00, 72)))
        // Contact détecté et intervalles RR à la suite : ignorés.
        assertEquals(181, HeartRateCodec.decode(bytes(0x16, 181, 0x10, 0x03)))
    }

    @Test
    fun `frequence sur 16 bits petit-boutiste`() {
        assertEquals(260, HeartRateCodec.decode(bytes(0x01, 0x04, 0x01)))
    }

    @Test
    fun `trame tronquee ou nulle - rien`() {
        assertNull(HeartRateCodec.decode(bytes()))
        assertNull(HeartRateCodec.decode(bytes(0x00)))
        assertNull(HeartRateCodec.decode(bytes(0x01, 0x04)))
        assertNull(HeartRateCodec.decode(bytes(0x00, 0)))
    }
}
