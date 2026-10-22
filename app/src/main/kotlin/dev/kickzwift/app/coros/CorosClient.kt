package dev.kickzwift.app.coros

import dev.kickzwift.protocol.coros.CorosUpload
import dev.kickzwift.protocol.coros.S3Credentials
import dev.kickzwift.protocol.coros.SigV4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    /** Trop de requêtes (HTTP 429) : COROS limite fort, réessayer plus tard. */
    BUSY,

    /** Fichier déposé, mais COROS n'a pas pu l'importer. */
    REJECTED,

    /** Réponse inattendue de COROS : l'API privée a peut-être changé. */
    SERVICE,
}

class CorosException(
    val failure: CorosFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Envoi d'un FIT à COROS Training Hub et commentaire de l'activité ([CorosUpload] pour le
 * protocole). Une connexion neuve à chaque appel : le jeton de COROS est invalidé dès que la
 * personne se connecte ailleurs. Seuls hôtes joints : l'API et le site de Training Hub de la
 * région (STS), et le seau S3 que désigne COROS.
 */
class CorosClient(
    private val log: (String) -> Unit,
) {
    /**
     * Rend vrai si COROS a confirmé l'import, faux s'il le traite encore ; lève [CorosException].
     * Un fichier déjà importé n'est pas renvoyé : COROS en ferait une deuxième activité (nouvel
     * essai après une réponse perdue, par exemple).
     */
    suspend fun upload(
        account: CorosAccount,
        fit: ByteArray,
        fileName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val (token, userId) = login(account)
                val md5 = CorosUpload.md5Hex(fit)
                if (importState(account.region, token, md5) == CorosUpload.ImportState.DONE) {
                    log("COROS : fichier déjà importé, pas de nouvel envoi")
                    return@withContext true
                }
                val s3 = sts(account.region, token)
                val zip = CorosUpload.zip(fit, md5, fileName, Instant.now())
                val key = CorosUpload.objectKey(userId, md5)
                put(s3, key, zip)
                val timezone = CorosUpload.timezoneQuarterHours(ZoneId.systemDefault().rules.getOffset(Instant.now()))
                val import = CorosUpload.importJson(s3.bucket, md5, zip.size, key, fileName, timezone)
                val job = register(account.region, token, import)
                log("COROS : activité envoyée, import ${job.optString("idString", "?")}")
                awaitImport(account.region, token, md5, CorosUpload.importState(job.optInt("status"), job.optInt("errorSize")))
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

    /**
     * Publie [text] en commentaire de l'activité COROS partie à [startEpochMs] ; rend faux si
     * COROS ne la liste pas (jamais envoyée, ou import pas encore visible après quelques essais).
     * Lève [CorosException].
     */
    suspend fun comment(
        account: CorosAccount,
        startEpochMs: Long,
        text: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val (token, _) = login(account)
                val labelId = awaitActivity(account.region, token, startEpochMs)
                if (labelId == null) {
                    log("COROS : activité absente de la liste, commentaire non publié")
                    return@withContext false
                }
                val body = CorosUpload.commentJson(labelId, text).encodeToByteArray()
                envelopeRoot(
                    post(CorosUpload.commentUrl(account.region), body, "application/json", mapOf("accessToken" to token)),
                    CorosFailure.SERVICE,
                )
                log("COROS : commentaire publié")
                true
            } catch (e: IOException) {
                log("COROS : commentaire non publié, ${e.javaClass.simpleName}")
                throw CorosException(CorosFailure.NETWORK, e.message ?: "réseau", e)
            } catch (e: JSONException) {
                log("COROS : réponse illisible")
                throw CorosException(CorosFailure.SERVICE, e.message ?: "JSON", e)
            } catch (e: CorosException) {
                log("COROS : commentaire refusé (${e.failure}), ${e.message}")
                throw e
            }
        }

    // Juste après l'import, l'activité peut tarder à paraître dans la liste : quelques essais.
    private suspend fun awaitActivity(
        region: CorosUpload.Region,
        token: String,
        startEpochMs: Long,
    ): String? {
        val day = Instant.ofEpochMilli(startEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val url = CorosUpload.activityListUrl(region, day.minusDays(1), day.plusDays(1))
        repeat(ACTIVITY_POLLS) { attempt ->
            if (attempt > 0) delay(IMPORT_POLL_MS)
            val list = envelope(request("GET", url, null, mapOf("accessToken" to token)), CorosFailure.SERVICE).optJSONArray("dataList")
            val listed =
                (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val a = list?.optJSONObject(i) ?: return@mapNotNull null
                    val id = a.optString("labelId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    CorosUpload.ListedActivity(id, a.optLong("startTime"))
                }
            CorosUpload.findActivity(listed, startEpochMs)?.let { return it }
        }
        return null
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

    // Une réponse qui n'est pas du JSON (page 404, connexion refusée) est signalée avec son
    // statut : le journal dit alors quelle étape a cassé. Jamais le corps d'une réponse réussie,
    // qui porte les identifiants S3.
    private fun sts(
        region: CorosUpload.Region,
        token: String,
    ): S3Credentials {
        val (status, text) = request("GET", CorosUpload.stsUrl(region), null, mapOf("Cookie" to CorosUpload.stsCookie(region, token)))
        val reply =
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw CorosException(CorosFailure.SERVICE, "STS : HTTP $status, réponse non JSON", e)
            }
        if (reply.optInt("code") != STS_OK) {
            throw CorosException(CorosFailure.SERVICE, "STS : HTTP $status, code ${reply.opt("code")}, ${reply.optString("msg")}")
        }
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
    ): JSONObject {
        val boundary = "zrkc-${UUID.randomUUID()}"
        val body =
            (
                "--$boundary\r\nContent-Disposition: form-data; name=\"jsonParameter\"\r\n\r\n" +
                    "$json\r\n--$boundary--\r\n"
            ).encodeToByteArray()
        val type = "multipart/form-data; boundary=$boundary"
        val reply = post(CorosUpload.importUrl(region), body, type, mapOf("AccessToken" to token), IMPORT_TIMEOUT_MS)
        return envelope(reply, CorosFailure.SERVICE)
    }

    // Déclaré, le fichier est chez COROS : une liste muette ou injoignable laisse l'envoi réussi,
    // non confirmé, plutôt que d'inviter à un nouvel essai qui ferait un doublon.
    private suspend fun awaitImport(
        region: CorosUpload.Region,
        token: String,
        md5: String,
        first: CorosUpload.ImportState?,
    ): Boolean {
        var state = first
        var polls = 0
        while (state != CorosUpload.ImportState.DONE && polls < IMPORT_POLLS) {
            if (state == CorosUpload.ImportState.FAILED) throw CorosException(CorosFailure.REJECTED, "import en échec")
            delay(IMPORT_POLL_MS)
            polls++
            state =
                try {
                    importState(region, token, md5)
                } catch (_: IOException) {
                    null
                } catch (e: CorosException) {
                    log("COROS : suivi de l'import interrompu (${e.failure})")
                    return false
                }
        }
        log(if (state == CorosUpload.ImportState.DONE) "COROS : import terminé" else "COROS : import encore en cours")
        return state == CorosUpload.ImportState.DONE
    }

    // Tâche d'import du fichier, retrouvée par son MD5 ; nulle si COROS ne la liste pas. Une liste
    // illisible ne bloque rien : ce n'est qu'une précaution.
    private fun importState(
        region: CorosUpload.Region,
        token: String,
        md5: String,
    ): CorosUpload.ImportState? {
        val body = CorosUpload.importListJson(IMPORT_LIST_SIZE).encodeToByteArray()
        return try {
            val reply = post(CorosUpload.importListUrl(region), body, "application/json", mapOf("accessToken" to token))
            val jobs = envelopeRoot(reply, CorosFailure.SERVICE).optJSONArray("data") ?: JSONArray()
            (0 until jobs.length())
                .map { jobs.getJSONObject(it) }
                .firstOrNull { it.optString("md5") == md5 }
                ?.let { CorosUpload.importState(it.optInt("status"), it.optInt("errorSize")) }
        } catch (_: JSONException) {
            log("COROS : liste des imports illisible")
            null
        } catch (e: CorosException) {
            if (e.failure != CorosFailure.SERVICE) throw e
            log("COROS : liste des imports indisponible, ${e.message}")
            null
        }
    }

    private fun envelope(
        reply: Pair<Int, String>,
        onRefusal: CorosFailure,
    ): JSONObject = envelopeRoot(reply, onRefusal).optJSONObject("data") ?: JSONObject()

    /** Enveloppe de Training Hub : `result` « 0000 » si réussi, sinon `message`. */
    private fun envelopeRoot(
        reply: Pair<Int, String>,
        onRefusal: CorosFailure,
    ): JSONObject {
        val json =
            try {
                JSONObject(reply.second)
            } catch (e: JSONException) {
                throw CorosException(CorosFailure.SERVICE, "HTTP ${reply.first}, réponse non JSON", e)
            }
        if (json.optString("result") !=
            CorosUpload.SUCCESS
        ) {
            throw CorosException(onRefusal, json.optString("message", "HTTP ${reply.first}"))
        }
        return json
    }

    private fun post(
        url: String,
        body: ByteArray,
        type: String,
        headers: Map<String, String>,
        timeoutMs: Int = TIMEOUT_MS,
    ) = request("POST", url, body, headers + ("Content-Type" to type), timeoutMs)

    // HTTP 429 : COROS limite les requêtes, quelle que soit l'étape ; la réponse n'est pas du JSON.
    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
        timeoutMs: Int = TIMEOUT_MS,
    ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val status = c.responseCode
            if (status == HTTP_TOO_MANY_REQUESTS) throw CorosException(CorosFailure.BUSY, "HTTP 429")
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
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val TIMEOUT_MS = 30_000

        // L'import répond lentement (Pinta365/coros lui laisse 60 s) ; puis 10 relevés de la liste
        // des imports, à 3 s d'intervalle.
        const val IMPORT_TIMEOUT_MS = 60_000
        const val IMPORT_POLLS = 10
        const val IMPORT_POLL_MS = 3_000L
        const val IMPORT_LIST_SIZE = 10

        // Activité cherchée dans la liste jusqu'à 5 fois, au rythme du suivi de l'import.
        const val ACTIVITY_POLLS = 5
        const val ERROR_EXCERPT = 200
    }
}
