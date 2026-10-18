package dev.kickzwift.app.data

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Distance et temps cumulés d'un ensemble de sorties. */
data class Total(
    val distanceM: Double = 0.0,
    val durationMs: Long = 0,
) {
    operator fun plus(a: Activity) = Total(distanceM + a.distanceM, durationMs + a.durationMs)
}

/** Totaux du mois et de l'année en cours, d'après l'heure de départ de chaque sortie. */
data class ActivityTotals(
    val month: YearMonth,
    val monthTotal: Total,
    val yearTotal: Total,
) {
    companion object {
        fun of(
            activities: List<Activity>,
            nowMs: Long,
            zone: ZoneId,
        ): ActivityTotals {
            val month = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
            var monthTotal = Total()
            var yearTotal = Total()
            for (a in activities) {
                val started = YearMonth.from(Instant.ofEpochMilli(a.startedAtMs).atZone(zone))
                if (started.year == month.year) {
                    yearTotal += a
                    if (started == month) monthTotal += a
                }
            }
            return ActivityTotals(month, monthTotal, yearTotal)
        }
    }
}
