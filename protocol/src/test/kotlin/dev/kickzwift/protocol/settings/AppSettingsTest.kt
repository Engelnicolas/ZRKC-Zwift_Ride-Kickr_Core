package dev.kickzwift.protocol.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppSettingsTest {
    @Test
    fun `plafond de puissance - 600 W par defaut, par pas de 50 W`() {
        assertEquals(600, AppSettings().ergMaxW)
        assertEquals(650, AppSettings().steppedErgMax(up = true))
        assertEquals(550, AppSettings().steppedErgMax(up = false))
    }

    @Test
    fun `plafond de puissance - borne a 100 et 1 800 W`() {
        assertEquals(1_800, AppSettings(ergMaxW = 1_800).steppedErgMax(up = true))
        assertEquals(1_800, AppSettings(ergMaxW = 1_780).steppedErgMax(up = true))
        assertEquals(100, AppSettings(ergMaxW = 100).steppedErgMax(up = false))
        assertThrows<IllegalArgumentException> { AppSettings(ergMaxW = 1_801) }
    }

    @Test
    fun `plafond de puissance - relecture, une valeur au-dela de 1 800 W repart du defaut`() {
        val stored = AppSettings(ergMaxW = 1_800)
        assertEquals(1_800, SettingsCodec.decode(SettingsCodec.encode(stored)).ergMaxW)
        val edited = SettingsCodec.encode(stored).also { replaceCeiling(it) }
        assertEquals(600, SettingsCodec.decode(edited).ergMaxW)
    }

    /** Varint de 1 800 (88 0E) réécrit en 2 000 (D0 0F), de même longueur. */
    private fun replaceCeiling(bytes: ByteArray) {
        val at =
            (0 until bytes.size - 2).first {
                bytes[it].toInt() == 15 shl 3 && bytes[it + 1] == 0x88.toByte() && bytes[it + 2] == 0x0E.toByte()
            }
        bytes[at + 1] = 0xD0.toByte()
        bytes[at + 2] = 0x0F.toByte()
    }
}
