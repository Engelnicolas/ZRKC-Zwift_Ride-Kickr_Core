package dev.kickzwift.app.route

import dev.kickzwift.protocol.grade.TrackPoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

/** Trace lue dans un GPX : nom et points dans l'ordre. */
data class GpxTrack(
    val name: String?,
    val points: List<TrackPoint>,
)

/** Fichier refusé ; [reason] donne le texte affiché, [limit] la borne dépassée s'il y en a une. */
class GpxException(
    val reason: Reason,
    val limit: Int = 0,
    cause: Throwable? = null,
) : Exception(if (cause == null) reason.name else "${reason.name} : ${cause.message}", cause) {
    enum class Reason { INVALID, READ, NOT_FOUND, TOO_LARGE, TOO_MANY_POINTS, TOO_FEW_POINTS }
}

/**
 * Lecteur GPX sur XmlPullParser, sans dépendance (spécification, « Architecture »).
 *
 * Lit les points de trace (`trkpt`) et, à défaut, de route (`rtept`) : latitude, longitude,
 * altitude. Un point sans altitude reprend la précédente (0 au début). Fichier hostile :
 * coordonnées hors bornes écartées, au plus [maxPoints] points, entités externes refusées
 * par le parseur.
 */
class GpxParser(
    private val newParser: () -> XmlPullParser,
    private val maxPoints: Int = MAX_POINTS,
) {
    // Sur un fichier corrompu, le parseur XML lève parfois une erreur d'exécution
    // (StringIndexOutOfBoundsException relevée par le test aléatoire) : un import ne doit
    // jamais faire tomber l'app.
    @Suppress("TooGenericExceptionCaught")
    fun parse(input: InputStream): GpxTrack =
        try {
            read(newParser().apply { setInput(input, null) })
        } catch (e: XmlPullParserException) {
            throw GpxException(GpxException.Reason.INVALID, cause = e)
        } catch (e: IOException) {
            throw GpxException(GpxException.Reason.READ, cause = e)
        } catch (e: RuntimeException) {
            throw GpxException(GpxException.Reason.INVALID, cause = e)
        }

    private class Builder {
        var name: String? = null
        val track = mutableListOf<TrackPoint>()
        val route = mutableListOf<TrackPoint>()
        var lat = Double.NaN
        var lon = Double.NaN
        var ele: Double? = null
        var lastEle = 0.0
    }

    private fun read(p: XmlPullParser): GpxTrack {
        val b = Builder()
        val path = ArrayDeque<String>()
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = p.name.substringAfter(':')
                    path.addLast(tag)
                    if (tag == "trkpt" || tag == "rtept") startPoint(p, b)
                }
                XmlPullParser.TEXT -> text(path, p.text.trim(), b)
                XmlPullParser.END_TAG -> {
                    val tag = path.removeLastOrNull()
                    if (tag == "trkpt") endPoint(b, b.track)
                    if (tag == "rtept") endPoint(b, b.route)
                }
            }
            if (b.track.size + b.route.size > maxPoints) throw GpxException(GpxException.Reason.TOO_MANY_POINTS, maxPoints)
        }
        val points = b.track.ifEmpty { b.route }
        if (points.size < 2) throw GpxException(GpxException.Reason.TOO_FEW_POINTS)
        return GpxTrack(b.name, points)
    }

    private fun startPoint(
        p: XmlPullParser,
        b: Builder,
    ) {
        b.lat = p.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
        b.lon = p.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
        b.ele = null
    }

    private fun text(
        path: ArrayDeque<String>,
        value: String,
        b: Builder,
    ) {
        if (value.isEmpty()) return
        val tag = path.lastOrNull()
        val parent = path.getOrNull(path.size - 2)
        when {
            tag == "ele" && (parent == "trkpt" || parent == "rtept") -> b.ele = value.toDoubleOrNull()?.takeIf { it.isFinite() }
            tag == "name" && b.name == null && parent in NAMED -> b.name = value.take(MAX_NAME)
        }
    }

    private fun endPoint(
        b: Builder,
        into: MutableList<TrackPoint>,
    ) {
        if (b.lat !in LAT_RANGE || b.lon !in LON_RANGE) return
        val ele = (b.ele ?: b.lastEle).coerceIn(ELE_RANGE)
        b.lastEle = ele
        into += TrackPoint(b.lat, b.lon, ele)
    }

    companion object {
        const val MAX_POINTS = 200_000
        private const val MAX_NAME = 80
        private val NAMED = setOf("trk", "rte", "metadata")
        private val LAT_RANGE = -90.0..90.0
        private val LON_RANGE = -180.0..180.0
        private val ELE_RANGE = -500.0..9_000.0
    }
}
