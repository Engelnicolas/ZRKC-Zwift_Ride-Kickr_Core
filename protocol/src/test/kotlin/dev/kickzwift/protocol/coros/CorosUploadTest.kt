package dev.kickzwift.protocol.coros

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class CorosUploadTest {
    @Test
    fun `MD5 - valeurs de reference`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", CorosUpload.md5Hex(ByteArray(0)))
        assertEquals("5f4dcc3b5aa765d61d8327deb882cf99", CorosUpload.md5Hex("password".encodeToByteArray()))
    }

    @Test
    fun `connexion - e-mail et MD5 du mot de passe, JSON echappe`() {
        assertEquals(
            """{"account":"a\"b@c.fr","accountType":2,"pwd":"5f4dcc3b5aa765d61d8327deb882cf99"}""",
            CorosUpload.loginJson("a\"b@c.fr", "5f4dcc3b5aa765d61d8327deb882cf99"),
        )
        assertEquals("https://teameuapi.coros.com/account/login", CorosUpload.loginUrl(CorosUpload.Region.EUROPE))
        assertEquals("https://teamapi.coros.com/activity/fit/import", CorosUpload.importUrl(CorosUpload.Region.AMERICAS))
        assertEquals("https://teameuapi.coros.com/account/query", CorosUpload.accountUrl(CorosUpload.Region.EUROPE))
    }

    @Test
    fun `STS - URL et cookies de la region, sel retire avant le base64`() {
        assertEquals(
            "https://t.coros.com/api/proxy/oss/sts?bucket=eu-coros&service=aws&v=2",
            CorosUpload.stsUrl(CorosUpload.Region.EUROPE),
        )
        assertEquals("CPL-coros-token=abc; CPL-coros-region=3", CorosUpload.stsCookie(CorosUpload.Region.EUROPE, "abc"))
        assertEquals("CPL-coros-token=abc; CPL-coros-region=1", CorosUpload.stsCookie(CorosUpload.Region.AMERICAS, "abc"))
        val json = """{"AccessKeyId":"ASIAEXAMPLE","Region":"eu-central-1"}"""
        val b64 = Base64.getEncoder().encodeToString(json.encodeToByteArray())
        val salted = b64.substring(0, 10) + "9y78gpoERW4lBNYL" + b64.substring(10)
        assertEquals(json, CorosUpload.decodeStsCredentials(salted))
    }

    @Test
    fun `import - cle, fuseau en quarts d'heure, JSON du formulaire`() {
        assertEquals("fit_zip/42/abc.zip", CorosUpload.objectKey("42", "abc"))
        assertEquals(8, CorosUpload.timezoneQuarterHours(ZoneOffset.ofHours(2)))
        assertEquals(-20, CorosUpload.timezoneQuarterHours(ZoneOffset.ofHours(-5)))
        assertEquals(
            """{"source":1,"timezone":8,"bucket":"eu-coros","md5":"abc","size":321,"object":"fit_zip/42/abc.zip",""" +
                """"serviceName":"aws","oriFileName":"zrkc-20261018-1549.fit"}""",
            CorosUpload.importJson("eu-coros", "abc", 321, "fit_zip/42/abc.zip", "zrkc-20261018-1549.fit", 8),
        )
    }

    @Test
    fun `archive - dossier md5 puis le fichier, sans compression, lisible par java util zip`() {
        val fit = ByteArray(3_000) { (it % 251).toByte() }
        val md5 = CorosUpload.md5Hex(fit)
        val zip = CorosUpload.zip(fit, md5, "sortie.fit", Instant.parse("2026-10-18T15:49:02Z"))
        val entries = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                entries += e to z.readBytes()
            }
        }
        assertEquals(listOf("$md5/", "$md5/sortie.fit"), entries.map { it.first.name })
        assertTrue(entries[0].first.isDirectory)
        assertEquals(ZipEntry.STORED, entries[1].first.method)
        assertArrayEquals(fit, entries[1].second)
        // Fin du fichier : enregistrement de fin de répertoire central, deux entrées, sans commentaire.
        assertEquals(0x06054b50, le32(zip, zip.size - 22))
        assertEquals(2, zip[zip.size - 22 + 10].toInt())
    }

    private fun le32(
        b: ByteArray,
        at: Int,
    ) = (0 until 4).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }

    @Test
    fun `SigV4 - exemple GET Object de la documentation AWS`() {
        val creds = S3Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null, "us-east-1", "examplebucket")
        val empty = SigV4.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", empty)
        val auth =
            SigV4.authorization(
                method = "GET",
                host = "examplebucket.s3.amazonaws.com",
                path = "/test.txt",
                headers = mapOf("Range" to "bytes=0-9", "x-amz-content-sha256" to empty, "x-amz-date" to "20130524T000000Z"),
                payloadSha256 = empty,
                credentials = creds,
            )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            auth,
        )
        assertEquals("20130524T000000Z", SigV4.amzDate(Instant.parse("2013-05-24T00:00:00Z")))
        assertNull(creds.sessionToken)
    }
}
