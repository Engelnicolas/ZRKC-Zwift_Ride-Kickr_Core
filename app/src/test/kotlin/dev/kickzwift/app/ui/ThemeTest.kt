package dev.kickzwift.app.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.YearMonth

class ThemeTest {
    @Test
    fun `une couleur differente pour chacun des douze mois`() {
        assertEquals(12, (1..12).map { monthAccent(YearMonth.of(2026, it)) }.toSet().size)
    }

    @Test
    fun `un mois garde sa couleur d'une annee sur l'autre`() {
        assertEquals(monthAccent(YearMonth.of(2026, 11)), monthAccent(YearMonth.of(2027, 11)))
    }
}
