package dev.kickzwift.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class ActivityTotalsTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun at(text: String) =
        LocalDateTime
            .parse(text)
            .atZone(paris)
            .toInstant()
            .toEpochMilli()

    private fun ride(
        start: String,
        km: Double,
        minutes: Long,
    ) = Activity(
        startedAtMs = at(start),
        mode = ActivityMode.FREE,
        durationMs = minutes * 60_000,
        distanceM = km * 1_000,
        avgPowerW = 0,
        maxPowerW = 0,
        avgCadenceRpm = 0,
        elevationGainM = 0.0,
        shifts = 0,
    )

    @Test
    fun `mois et annee en cours, d'apres l'heure locale du depart`() {
        val rides =
            listOf(
                ride("2026-10-18T15:49:00", 10.0, 30),
                ride("2026-10-01T00:30:00", 5.0, 15), // 30 septembre 22 h 30 en UTC : octobre à Paris
                ride("2026-09-30T23:30:00", 7.0, 20),
                ride("2026-01-02T10:00:00", 3.0, 10),
                ride("2025-12-31T18:00:00", 100.0, 300),
            )
        val totals = ActivityTotals.of(rides, at("2026-10-18T16:00:00"), paris)
        assertEquals(YearMonth.of(2026, 10), totals.month)
        assertEquals(Total(15_000.0, 45 * 60_000), totals.monthTotal)
        assertEquals(Total(25_000.0, 75 * 60_000), totals.yearTotal)
    }

    @Test
    fun `aucune sortie - zero`() {
        val totals = ActivityTotals.of(emptyList(), at("2026-10-18T16:00:00"), paris)
        assertEquals(Total(), totals.monthTotal)
        assertEquals(Total(), totals.yearTotal)
    }
}
