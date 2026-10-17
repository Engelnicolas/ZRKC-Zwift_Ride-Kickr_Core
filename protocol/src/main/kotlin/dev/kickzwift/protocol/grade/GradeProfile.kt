package dev.kickzwift.protocol.grade

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Point d'une trace GPX, tel que le lit GpxImporter. */
data class TrackPoint(
    val latDeg: Double,
    val lonDeg: Double,
    val elevationM: Double,
)

/** Bornes de pente envoyées au trainer (spécification : −10 % à +16 % par défaut). */
data class GradeLimits(
    val minPct: Double = DEFAULT_MIN_PCT,
    val maxPct: Double = DEFAULT_MAX_PCT,
) {
    init {
        require(minPct < maxPct) { "bornes de pente inversées" }
    }

    companion object {
        const val DEFAULT_MIN_PCT = -10.0
        const val DEFAULT_MAX_PCT = 16.0
    }
}

/**
 * Profil d'un parcours : altitude en fonction de la distance parcourue.
 *
 * La pente envoyée au trainer est la pente moyenne sur les [LOOK_AHEAD_M] mètres devant
 * la position (dénivelé / distance), bornée par [GradeLimits]. L'altitude entre deux
 * points est interpolée linéairement. En fin de parcours, la fenêtre est ramenée aux
 * derniers mètres disponibles.
 */
class GradeProfile private constructor(
    private val distances: DoubleArray,
    private val elevations: DoubleArray,
) {
    val totalDistanceM: Double get() = distances.last()

    /** Dénivelé positif cumulé, point à point. */
    val elevationGainM: Double =
        (1 until elevations.size).sumOf { max(0.0, elevations[it] - elevations[it - 1]) }

    fun isFinished(distanceM: Double): Boolean = distanceM >= totalDistanceM

    fun elevationAt(distanceM: Double): Double {
        val d = distanceM.coerceIn(0.0, totalDistanceM)
        val i = segmentOf(d)
        val span = distances[i + 1] - distances[i]
        return elevations[i] + (elevations[i + 1] - elevations[i]) * (d - distances[i]) / span
    }

    /** Pente moyenne en % sur [lookAheadM] mètres devant [distanceM], sans bornes. */
    fun gradeAt(
        distanceM: Double,
        lookAheadM: Double = LOOK_AHEAD_M,
    ): Double {
        require(lookAheadM > 0) { "fenêtre nulle" }
        val end = min(distanceM.coerceAtLeast(0.0) + lookAheadM, totalDistanceM)
        val start = max(0.0, min(distanceM, end - lookAheadM))
        return (elevationAt(end) - elevationAt(start)) / (end - start) * PERCENT
    }

    /** Pente à envoyer au trainer : [gradeAt], ramenée dans [limits]. */
    fun targetGrade(
        distanceM: Double,
        limits: GradeLimits = GradeLimits(),
    ): Double = gradeAt(distanceM).coerceIn(limits.minPct, limits.maxPct)

    /** Pente maximale du parcours, échantillonnée tous les [stepM] mètres (aperçu GPX). */
    fun maxGradePct(stepM: Double = SAMPLE_STEP_M): Double {
        require(stepM > 0) { "pas nul" }
        var best = Double.NEGATIVE_INFINITY
        var d = 0.0
        while (d < totalDistanceM) {
            best = max(best, gradeAt(d))
            d += stepM
        }
        return best
    }

    /** Index du segment [i, i + 1] qui contient [d] (recherche dichotomique). */
    private fun segmentOf(d: Double): Int {
        var lo = 0
        var hi = distances.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (distances[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }

    companion object {
        const val LOOK_AHEAD_M = 50.0
        private const val SAMPLE_STEP_M = 10.0
        private const val PERCENT = 100.0
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val MIN_SPACING_M = 0.01

        /** Construit le profil d'une trace ; les points confondus (moins de 1 cm) sont écartés. */
        fun fromTrack(points: List<TrackPoint>): GradeProfile {
            val dist = mutableListOf<Double>()
            val ele = mutableListOf<Double>()
            var previous: TrackPoint? = null
            for (p in points) {
                val step = previous?.let { haversineM(it, p) } ?: 0.0
                if (previous == null || step >= MIN_SPACING_M) {
                    dist += (dist.lastOrNull() ?: 0.0) + step
                    ele += p.elevationM
                    previous = p
                }
            }
            return fromProfile(dist, ele)
        }

        /** Profil déjà réduit : distances cumulées strictement croissantes depuis 0, altitudes. */
        fun fromProfile(
            distancesM: List<Double>,
            elevationsM: List<Double>,
        ): GradeProfile {
            require(distancesM.size == elevationsM.size) { "autant de distances que d'altitudes" }
            require(distancesM.size >= 2) { "il faut au moins deux points distincts" }
            require(distancesM.first() == 0.0) { "la première distance doit être 0" }
            require(distancesM.zipWithNext().all { (a, b) -> b > a }) { "distances non strictement croissantes" }
            require(elevationsM.all { it.isFinite() }) { "altitude invalide" }
            return GradeProfile(distancesM.toDoubleArray(), elevationsM.toDoubleArray())
        }

        /** Distance au sol entre deux points (formule de haversine, Terre sphérique). */
        fun haversineM(
            a: TrackPoint,
            b: TrackPoint,
        ): Double {
            val dLat = Math.toRadians(b.latDeg - a.latDeg)
            val dLon = Math.toRadians(b.lonDeg - a.lonDeg)
            val h =
                sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(a.latDeg)) * cos(Math.toRadians(b.latDeg)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, h)))
        }
    }
}
