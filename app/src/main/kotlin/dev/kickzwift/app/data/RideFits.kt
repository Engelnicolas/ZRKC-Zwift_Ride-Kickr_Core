package dev.kickzwift.app.data

import dev.kickzwift.protocol.fit.FitDecoder
import dev.kickzwift.protocol.fit.FitSample
import dev.kickzwift.protocol.fit.RideCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Écart maximal entre le départ d'une activité et la première mesure de son FIT. */
private const val MATCH_MS = 60_000L

private fun sameRide(
    firstSampleMs: Long,
    startedAtMs: Long,
) = abs(firstSampleMs - startedAtMs) <= MATCH_MS

private fun File.fits() = listFiles { f -> f.extension == "fit" }.orEmpty().asList()

/** Courbe de l'activité d'après le FIT parti en même temps qu'elle ; vide s'il n'y en a pas. */
internal fun curveFor(
    startedAtMs: Long,
    rides: List<List<FitSample>>,
): RideCurve =
    rides
        .firstOrNull { sameRide(it.first().epochMs, startedAtMs) }
        ?.let(RideCurve::of)
        ?: RideCurve.EMPTY

/**
 * Reprend la courbe des sorties enregistrées sans elle, depuis les FIT encore dans [fitDir]. Une
 * activité sans FIT reçoit une courbe vide : elle n'est plus cherchée aux lancements suivants.
 */
suspend fun ActivityStore.backfillCurves(
    fitDir: File,
    log: (String) -> Unit,
) {
    val missing = withoutCurve()
    if (missing.isEmpty()) return
    val rides =
        withContext(Dispatchers.IO) {
            fitDir
                .fits()
                .map { FitDecoder.samples(it.readBytes()) }
                .filter { it.isNotEmpty() }
        }
    var found = 0
    for (a in missing) {
        val curve = curveFor(a.startedAtMs, rides)
        if (curve.points.isNotEmpty()) found++
        setCurve(a.id, curve)
    }
    log("courbes reprises des FIT : $found sur ${missing.size}")
}

/** Supprime le FIT parti en même temps que l'activité [startedAtMs]. Sans activité, il n'a plus d'usage. */
suspend fun deleteFit(
    fitDir: File,
    startedAtMs: Long,
    log: (String) -> Unit,
) {
    withContext(Dispatchers.IO) {
        fitDir
            .fits()
            .filter { f -> FitDecoder.samples(f.readBytes()).firstOrNull()?.let { sameRide(it.epochMs, startedAtMs) } == true }
            .forEach { if (!it.delete()) log("FIT non supprimé : ${it.name}") }
    }
}
