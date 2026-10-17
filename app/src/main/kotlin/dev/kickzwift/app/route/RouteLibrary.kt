package dev.kickzwift.app.route

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import dev.kickzwift.app.data.MemoryStore
import dev.kickzwift.protocol.grade.GradeProfile
import dev.kickzwift.protocol.settings.RecentRoute
import dev.kickzwift.protocol.settings.RouteFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Parcours prêt à rouler et de quoi en dessiner l'aperçu. */
data class RouteData(
    val route: RecentRoute,
    val profile: GradeProfile,
    /** Altitudes échantillonnées le long du parcours, pour le profil à l'écran. */
    val elevations: List<Double>,
) {
    val distanceM: Double get() = profile.totalDistanceM
    val elevationGainM: Double get() = profile.elevationGainM
    val maxGradePct: Double = profile.maxGradePct()
}

/**
 * Parcours GPX (lot 11) : import par le sélecteur de fichiers Android, copie dans le stockage
 * interne de l'app, réduction en profil. Aucune permission de stockage : le sélecteur donne
 * un accès ponctuel au seul fichier choisi.
 */
class RouteLibrary(
    context: Context,
    private val memory: MemoryStore,
) {
    private val context = context.applicationContext
    private val dir = File(this.context.filesDir, "routes")
    private val parser = GpxParser({ Xml.newPullParser() })

    val recent: Flow<List<RecentRoute>> = memory.memory.map { it.recentRoutes }

    /** Copie, vérifie et mémorise le fichier choisi ; il passe en tête des parcours récents. */
    suspend fun import(uri: Uri): Result<RouteData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readLimited(uri)
                val track = bytes.inputStream().use { parser.parse(it) }
                val name = track.name ?: displayName(uri) ?: "Parcours"
                val file = "${slug(name)}-${digest(bytes)}.gpx"
                check(RouteFiles.isSafeName(file)) { "nom de fichier refusé" }
                dir.mkdirs()
                File(dir, file).writeBytes(bytes)
                val route = RecentRoute(file, name)
                memory.update { it.withRoute(route) }
                dataOf(route, GradeProfile.fromTrack(track.points))
            }
        }

    /** Recharge un parcours récent ; le remonte en tête de liste. */
    suspend fun load(route: RecentRoute): Result<RouteData> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(RouteFiles.isSafeName(route.file)) { "nom de fichier refusé" }
                val track = File(dir, route.file).inputStream().use { parser.parse(it) }
                memory.update { it.withRoute(route) }
                dataOf(route, GradeProfile.fromTrack(track.points))
            }
        }

    private fun dataOf(
        route: RecentRoute,
        profile: GradeProfile,
    ): RouteData {
        val step = profile.totalDistanceM / PREVIEW_SAMPLES
        return RouteData(route, profile, (0..PREVIEW_SAMPLES).map { profile.elevationAt(it * step) })
    }

    private fun readLimited(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("fichier introuvable")
        val out = ByteArrayOutputStream()
        input.use { s ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = s.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BYTES) throw GpxException("fichier de plus de ${MAX_BYTES / MEGA} Mo")
            }
        }
        return out.toByteArray()
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }

    private fun slug(name: String) =
        name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(SLUG_MAX)
            .ifEmpty { "parcours" }

    private fun digest(bytes: ByteArray) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .take(DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MEGA = 1_000_000
        const val MAX_BYTES = 20 * MEGA
        const val PREVIEW_SAMPLES = 200
        const val SLUG_MAX = 40
        const val DIGEST_BYTES = 4
        const val BUFFER = 64 * 1024
    }
}
