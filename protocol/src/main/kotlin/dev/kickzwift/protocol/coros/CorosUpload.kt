package dev.kickzwift.protocol.coros

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.CRC32

/**
 * Envoi d'une activité à COROS Training Hub par son API privée, non documentée, telle que la
 * décrit Pinta365/coros (MIT, vérifiée de bout en bout sur la région Europe), STS mis à jour
 * d'après le site de Training Hub en octobre 2026 :
 *
 * 1. `account/login` : e-mail et MD5 du mot de passe → jeton ; `account/query` → identifiant ;
 * 2. `/api/proxy/oss/sts` du site de Training Hub de la région ([Region.webBase]), avec le jeton
 *    en cookie : identifiants S3 temporaires du seau de la région. L'ancien
 *    `faq.coros.com/openapi/oss/sts` répond 404 depuis octobre 2026 ; le relais de `t.coros.com`
 *    refuse un jeton européen (401) : le site renvoie chaque compte vers le domaine de sa région ;
 * 3. PUT S3 (SigV4) du FIT emballé dans `{md5}.zip`, entrée `{md5}/{fichier}`, sans compression ;
 * 4. `activity/fit/import` : déclaration du fichier déposé.
 *
 * Ici, seules les parties sans réseau ; les appels sont dans l'app. Le sel du STS est une valeur
 * propre à COROS, relevée dans son site : si COROS le change, l'envoi cesse.
 */
object CorosUpload {
    /**
     * [webBase] : site de Training Hub de la région, où le site renvoie le compte après la
     * connexion ; [id] : son numéro de région (cookie `CPL-coros-region`).
     */
    enum class Region(
        val apiBase: String,
        val webBase: String,
        val bucket: String,
        val id: Int,
    ) {
        EUROPE("https://teameuapi.coros.com", "https://trainingeu.coros.com", "eu-coros", 3),
        AMERICAS("https://teamapi.coros.com", "https://training.coros.com", "coros-s3", 1),
    }

    private const val STS_PATH = "/api/proxy/oss/sts"
    private const val STS_SALT = "9y78gpoERW4lBNYL"

    /** Réponse réussie de l'API de Training Hub (champ `result`). */
    const val SUCCESS = "0000"

    private const val ACCOUNT_TYPE_EMAIL = 2
    private const val SOURCE_UPLOAD = 1
    private const val SECONDS_PER_QUARTER_HOUR = 900

    fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    fun loginUrl(region: Region) = "${region.apiBase}/account/login"

    fun accountUrl(region: Region) = "${region.apiBase}/account/query"

    fun importUrl(region: Region) = "${region.apiBase}/activity/fit/import"

    fun stsUrl(region: Region) = "${region.webBase}$STS_PATH?bucket=${region.bucket}&service=aws&v=2"

    /** Cookies du STS, comme les pose le site après la connexion : le jeton et la région. */
    fun stsCookie(
        region: Region,
        token: String,
    ) = "CPL-coros-token=$token; CPL-coros-region=${region.id}"

    /** Corps de `account/login` ; le mot de passe n'y figure que par son MD5. */
    fun loginJson(
        email: String,
        passwordMd5: String,
    ): String = """{"account":${Json.string(email)},"accountType":$ACCOUNT_TYPE_EMAIL,"pwd":${Json.string(passwordMd5)}}"""

    /** Champ `data.credentials` du STS : JSON en base64, où COROS a glissé un sel à retirer. */
    fun decodeStsCredentials(field: String): String = String(Base64.getDecoder().decode(field.replaceFirst(STS_SALT, "")), Charsets.UTF_8)

    fun objectKey(
        userId: String,
        md5: String,
    ) = "fit_zip/$userId/$md5.zip"

    /** Décalage horaire en quarts d'heure, comme l'attend l'import : 8 pour UTC+2. */
    fun timezoneQuarterHours(offset: ZoneOffset): Int = offset.totalSeconds / SECONDS_PER_QUARTER_HOUR

    /** Champ `jsonParameter` du formulaire d'import. */
    fun importJson(
        bucket: String,
        md5: String,
        zipSize: Int,
        objectKey: String,
        fileName: String,
        timezone: Int,
    ): String =
        """{"source":$SOURCE_UPLOAD,"timezone":$timezone,"bucket":${Json.string(bucket)},"md5":${Json.string(md5)},""" +
            """"size":$zipSize,"object":${Json.string(objectKey)},"serviceName":"aws","oriFileName":${Json.string(fileName)}}"""

    /** Archive attendue par COROS : un dossier `{md5}/` et le fichier dedans, sans compression. */
    fun zip(
        content: ByteArray,
        md5: String,
        fileName: String,
        at: Instant,
    ): ByteArray = StoredZip.build(listOf("$md5/" to null, "$md5/$fileName" to content), at)
}

/** ZIP minimal sans compression, en-têtes comme ceux du site de COROS (heure DOS en UTC). */
internal object StoredZip {
    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val END = 0x06054b50
    private const val VERSION_NEEDED = 10
    private const val VERSION_MADE_BY = 20
    private const val DOS_DIRECTORY = 0x10
    private const val DOS_EPOCH_YEAR = 1980
    private const val FLAG_UTF8 = 0x800
    private const val ASCII_MAX = 0x7F
    private const val BYTE = 0xFF
    private const val HOUR_SHIFT = 11
    private const val MINUTE_SHIFT = 5
    private const val YEAR_SHIFT = 9
    private const val MONTH_SHIFT = 5

    /** [entries] : nom et contenu, null pour un dossier (nom terminé par une barre). */
    fun build(
        entries: List<Pair<String, ByteArray?>>,
        at: Instant,
    ): ByteArray {
        val utc = at.atOffset(ZoneOffset.UTC)
        val time = (utc.hour shl HOUR_SHIFT) or (utc.minute shl MINUTE_SHIFT) or (utc.second shr 1)
        val date = ((utc.year - DOS_EPOCH_YEAR) shl YEAR_SHIFT) or (utc.monthValue shl MONTH_SHIFT) or utc.dayOfMonth
        val prepared = entries.map { (name, body) -> Entry.of(name, body) }
        val out = LittleEndian()
        val offsets = prepared.map { e -> out.size.also { local(out, e, time, date) } }
        val centralStart = out.size
        prepared.forEachIndexed { i, e -> central(out, e, time, date, offsets[i]) }
        val centralSize = out.size - centralStart
        out
            .u32(END.toLong())
            .u16(0)
            .u16(0)
            .u16(prepared.size)
            .u16(prepared.size)
        out.u32(centralSize.toLong()).u32(centralStart.toLong()).u16(0)
        return out.toByteArray()
    }

    private fun local(
        out: LittleEndian,
        e: Entry,
        time: Int,
        date: Int,
    ) {
        out
            .u32(LOCAL.toLong())
            .u16(VERSION_NEEDED)
            .u16(e.flags)
            .u16(0)
            .u16(time)
            .u16(date)
        out
            .u32(e.crc)
            .u32(e.data.size.toLong())
            .u32(e.data.size.toLong())
            .u16(e.name.size)
            .u16(0)
        out.bytes(e.name).bytes(e.data)
    }

    private fun central(
        out: LittleEndian,
        e: Entry,
        time: Int,
        date: Int,
        offset: Int,
    ) {
        out
            .u32(CENTRAL.toLong())
            .u16(VERSION_MADE_BY)
            .u16(VERSION_NEEDED)
            .u16(e.flags)
            .u16(0)
            .u16(time)
            .u16(date)
        out
            .u32(e.crc)
            .u32(e.data.size.toLong())
            .u32(e.data.size.toLong())
            .u16(e.name.size)
            .u16(0)
            .u16(0)
            .u16(0)
            .u16(0)
        out.u32(if (e.directory) DOS_DIRECTORY.toLong() else 0L).u32(offset.toLong())
        out.bytes(e.name)
    }

    private class Entry(
        val name: ByteArray,
        val data: ByteArray,
        val crc: Long,
        val directory: Boolean,
        val flags: Int,
    ) {
        companion object {
            fun of(
                name: String,
                body: ByteArray?,
            ): Entry {
                val bytes = name.encodeToByteArray()
                val data = body ?: ByteArray(0)
                val flags = if (bytes.any { it.toInt() and BYTE > ASCII_MAX }) FLAG_UTF8 else 0
                return Entry(bytes, data, CRC32().apply { update(data) }.value, body == null, flags)
            }
        }
    }
}

internal class LittleEndian {
    private val out = ByteArrayOutputStream()

    val size: Int get() = out.size()

    fun u16(v: Int) = apply { repeat(U16_BYTES) { out.write((v shr (it * BITS)) and BYTE) } }

    fun u32(v: Long) = apply { repeat(U32_BYTES) { out.write(((v shr (it * BITS)) and BYTE.toLong()).toInt()) } }

    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun toByteArray(): ByteArray = out.toByteArray()

    private companion object {
        const val U16_BYTES = 2
        const val U32_BYTES = 4
        const val BITS = 8
        const val BYTE = 0xFF
    }
}

/** Chaînes JSON, sans dépendance : guillemets, barres obliques inverses, caractères de contrôle. */
internal object Json {
    private const val CONTROL_MAX = 0x1F

    fun string(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c.code <= CONTROL_MAX -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
