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

private fun Activity.startMonth(zone: ZoneId) = YearMonth.from(Instant.ofEpochMilli(startedAtMs).atZone(zone))

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
                val started = a.startMonth(zone)
                if (started.year == month.year) {
                    yearTotal += a
                    if (started == month) monthTotal += a
                }
            }
            return ActivityTotals(month, monthTotal, yearTotal)
        }
    }
}

/** Sorties d'un même mois et leur total. */
data class MonthActivities(
    val month: YearMonth,
    val total: Total,
    val activities: List<Activity>,
) {
    companion object {
        /** Par mois de départ, le plus récent en tête ; l'ordre des sorties est conservé. */
        fun of(
            activities: List<Activity>,
            zone: ZoneId,
        ): List<MonthActivities> =
            activities
                .groupBy { it.startMonth(zone) }
                .toSortedMap(reverseOrder())
                .map { (month, rides) -> MonthActivities(month, rides.fold(Total(), Total::plus), rides) }
    }
}
