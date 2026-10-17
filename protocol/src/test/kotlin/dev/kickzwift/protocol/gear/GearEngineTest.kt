package dev.kickzwift.protocol.gear

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GearEngineTest {
    @Test
    fun `table Zwift - 24 rapports de 0,75 a 5,49`() {
        assertEquals(24, GearTable.ZWIFT.size)
        assertEquals(0.75, GearTable.ZWIFT.first())
        assertEquals(5.49, GearTable.ZWIFT.last())
    }

    @Test
    fun `vitesse 12 - rapport 2,40 soit GearRatioX10000 24000`() {
        val engine = GearEngine(GearTable.ZWIFT, start = 12)
        assertEquals(2.40, engine.ratio)
        assertEquals(24_000, GearTable.toGearRatioX10000(engine.ratio))
    }

    @Test
    fun `changements bornes aux butees`() {
        val engine = GearEngine(GearTable.ZWIFT, start = 2)
        assertEquals(1, engine.shift(-3))
        assertEquals(1, engine.shift(-1))
        assertEquals(4, engine.shift(+3))
        assertEquals(24, engine.shift(+100))
        assertEquals(24, engine.gear.value)
    }

    @Test
    fun `rafale de 10 changements`() {
        val engine = GearEngine(GearTable.ZWIFT, start = 12)
        repeat(10) { engine.shift(+1) }
        assertEquals(22, engine.gear.value)
    }

    @Test
    fun `configuration invalide refusee`() {
        assertThrows<IllegalArgumentException> { GearEngine(GearTable.ZWIFT, start = 0) }
        assertThrows<IllegalArgumentException> { GearEngine(listOf(1.0, 0.9), start = 1) }
        assertThrows<IllegalArgumentException> { GearEngine(emptyList(), start = 1) }
    }
}
