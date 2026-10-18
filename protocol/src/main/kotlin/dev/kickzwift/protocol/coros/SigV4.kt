package dev.kickzwift.protocol.coros

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Identifiants S3 temporaires, tels que les rend le STS de COROS. */
data class S3Credentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String?,
    val region: String,
    val bucket: String,
)

/**
 * Signature AWS Signature Version 4 d'une requête S3, sans paramètres de requête. Les en-têtes
 * signés sont ceux de [headers] plus `host` ; `content-length` ne l'est jamais (le client HTTP
 * le récrit).
 */
object SigV4 {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val DAY_LENGTH = 8

    fun amzDate(at: Instant): String = STAMP.format(at)

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).hex()

    /** En-tête `Authorization` ; [headers] doit déjà contenir `x-amz-date` et `x-amz-content-sha256`. */
    fun authorization(
        method: String,
        host: String,
        path: String,
        headers: Map<String, String>,
        payloadSha256: String,
        credentials: S3Credentials,
        service: String = "s3",
    ): String {
        val all = (headers.mapKeys { it.key.lowercase() } + ("host" to host)).toSortedMap()
        val signed = all.keys.joinToString(";")
        val canonical =
            listOf(
                method,
                path.split("/").joinToString("/") { encode(it) },
                "",
                all.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" },
                signed,
                payloadSha256,
            ).joinToString("\n")
        val date = all.getValue("x-amz-date")
        val scope = "${date.take(DAY_LENGTH)}/${credentials.region}/$service/aws4_request"
        val toSign = listOf(ALGORITHM, date, scope, sha256Hex(canonical.encodeToByteArray())).joinToString("\n")
        var key = hmac("AWS4${credentials.secretAccessKey}".encodeToByteArray(), date.take(DAY_LENGTH))
        for (part in listOf(credentials.region, service, "aws4_request")) key = hmac(key, part)
        val signature = hmac(key, toSign).hex()
        return "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, SignedHeaders=$signed, Signature=$signature"
    }

    /** Encodage d'un segment de chemin à la façon d'AWS (RFC 3986, caractères non réservés intacts). */
    private fun encode(segment: String): String =
        buildString {
            for (b in segment.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < ASCII_LIMIT || c in "-_.~") append(c) else append("%%%02X".format(b.toInt() and BYTE))
            }
        }

    private fun hmac(
        key: ByteArray,
        data: String,
    ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.encodeToByteArray())

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private const val ASCII_LIMIT = 0x80
    private const val BYTE = 0xFF
}
