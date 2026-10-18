package dev.kickzwift.app.coros

import dev.kickzwift.protocol.coros.CorosUpload
import dev.kickzwift.protocol.coros.S3Credentials
import dev.kickzwift.protocol.coros.SigV4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Pourquoi l'envoi à COROS a échoué, pour l'écran. */
enum class CorosFailure {
    /** Identifiants refusés, ou compte d'une autre région. */
    LOGIN,

    /** Pas de réseau, délai dépassé. */
    NETWORK,

    /** Réponse inattendue de COROS : l'API privée a peut-être changé. */
    SERVICE,
}

class CorosException(
    val failure: CorosFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Envoi d'un FIT à COROS Training Hub ([CorosUpload] pour le protocole). Une connexion neuve à
 * chaque envoi : le jeton de COROS est invalidé dès que la personne se connecte ailleurs. Seuls
 * hôtes joints : l'API de la région, faq.coros.com et le seau S3 que désigne COROS.
 */
class CorosClient(
    private val log: (String) -> Unit,
) {
    /** Rend l'identifiant de l'import ; lève [CorosException]. */
    suspend fun upload(
        account: CorosAccount,
        fit: ByteArray,
        fileName: String,
    ): String =
        withContext(Dispatchers.IO) {
            try {
                val (token, userId) = login(account)
                val s3 = sts(account.region)
                val md5 = CorosUpload.md5Hex(fit)
                val zip = CorosUpload.zip(fit, md5, fileName, Instant.now())
                val key = CorosUpload.objectKey(userId, md5)
                put(s3, key, zip)
                val timezone = CorosUpload.timezoneQuarterHours(ZoneId.systemDefault().rules.getOffset(Instant.now()))
                val import = CorosUpload.importJson(s3.bucket, md5, zip.size, key, fileName, timezone)
                val id = register(account.region, token, import)
                log("COROS : activité envoyée, import $id")
                id
            } catch (e: IOException) {
                log("COROS : envoi impossible, ${e.javaClass.simpleName}")
                throw CorosException(CorosFailure.NETWORK, e.message ?: "réseau", e)
            } catch (e: JSONException) {
                log("COROS : réponse illisible")
                throw CorosException(CorosFailure.SERVICE, e.message ?: "JSON", e)
            } catch (e: CorosException) {
                log("COROS : envoi refusé (${e.failure}), ${e.message}")
                throw e
            }
        }

    /** Connexion seule, pour vérifier le compte ; lève [CorosException]. */
    suspend fun check(account: CorosAccount) {
        withContext(Dispatchers.IO) {
            try {
                login(account)
                log("COROS : compte vérifié")
            } catch (e: IOException) {
                throw CorosException(CorosFailure.NETWORK, e.message ?: "réseau", e)
            } catch (e: JSONException) {
                throw CorosException(CorosFailure.SERVICE, e.message ?: "JSON", e)
            }
        }
    }

    private fun login(account: CorosAccount): Pair<String, String> {
        val body = CorosUpload.loginJson(account.email, account.passwordMd5).encodeToByteArray()
        val token =
            envelope(
                post(CorosUpload.loginUrl(account.region), body, "application/json", emptyMap()),
                CorosFailure.LOGIN,
            ).getString("accessToken")
        val headers = mapOf("accessToken" to token, "Content-Type" to "application/json")
        val user = envelope(request("GET", CorosUpload.accountUrl(account.region), null, headers), CorosFailure.SERVICE)
        return token to user.getString("userId")
    }

    private fun sts(region: CorosUpload.Region): S3Credentials {
        val reply = JSONObject(request("GET", CorosUpload.stsUrl(region), null, emptyMap()).second)
        if (reply.optInt("code") != STS_OK) throw CorosException(CorosFailure.SERVICE, "STS : ${reply.optString("msg")}")
        val c = JSONObject(CorosUpload.decodeStsCredentials(reply.getJSONObject("data").getString("credentials")))
        return S3Credentials(
            accessKeyId = c.getString("AccessKeyId"),
            secretAccessKey = c.getString("SecretAccessKey"),
            sessionToken = c.optString("SessionToken").takeIf { it.isNotEmpty() },
            region = c.getString("Region"),
            bucket = c.getString("Bucket"),
        )
    }

    private fun put(
        s3: S3Credentials,
        key: String,
        zip: ByteArray,
    ) {
        val host = "${s3.bucket}.s3.${s3.region}.amazonaws.com"
        val sha = SigV4.sha256Hex(zip)
        val headers =
            buildMap {
                put("Content-Type", ZIP)
                put("x-amz-content-sha256", sha)
                put("x-amz-date", SigV4.amzDate(Instant.now()))
                s3.sessionToken?.let { put("x-amz-security-token", it) }
            }
        val auth = SigV4.authorization("PUT", host, "/$key", headers, sha, s3)
        val (status, text) = request("PUT", "https://$host/$key", zip, headers + ("Authorization" to auth))
        if (status !in HTTP_OK) throw CorosException(CorosFailure.SERVICE, "S3 $status ${text.take(ERROR_EXCERPT)}")
    }

    private fun register(
        region: CorosUpload.Region,
        token: String,
        json: String,
    ): String {
        val boundary = "zrkc-${UUID.randomUUID()}"
        val body =
            (
                "--$boundary\r\nContent-Disposition: form-data; name=\"jsonParameter\"\r\n\r\n" +
                    "$json\r\n--$boundary--\r\n"
            ).encodeToByteArray()
        val reply = post(CorosUpload.importUrl(region), body, "multipart/form-data; boundary=$boundary", mapOf("AccessToken" to token))
        return envelope(reply, CorosFailure.SERVICE).optString("idString", "?")
    }

    /** Enveloppe de Training Hub : `result` « 0000 » si réussi, sinon `message`. */
    private fun envelope(
        reply: Pair<Int, String>,
        onRefusal: CorosFailure,
    ): JSONObject {
        val json = JSONObject(reply.second)
        if (json.optString("result") !=
            CorosUpload.SUCCESS
        ) {
            throw CorosException(onRefusal, json.optString("message", "HTTP ${reply.first}"))
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    private fun post(
        url: String,
        body: ByteArray,
        type: String,
        headers: Map<String, String>,
    ) = request("POST", url, body, headers + ("Content-Type" to type))

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            val stream = if (status >= HTTP_ERROR) c.errorStream else c.inputStream
            return status to (stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val ZIP = "application/zip"
        const val STS_OK = 200
        val HTTP_OK = 200..299
        const val HTTP_ERROR = 400
        const val TIMEOUT_MS = 30_000
        const val ERROR_EXCERPT = 200
    }
}
