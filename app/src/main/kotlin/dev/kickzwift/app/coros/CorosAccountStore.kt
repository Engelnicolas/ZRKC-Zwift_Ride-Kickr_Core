package dev.kickzwift.app.coros

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.kickzwift.protocol.coros.CorosUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Compte COROS : seul le MD5 du mot de passe est gardé, c'est ce que l'API de connexion reçoit. */
data class CorosAccount(
    val email: String,
    val passwordMd5: String,
    val region: CorosUpload.Region,
)

/**
 * Compte COROS chiffré sur le téléphone (AES-GCM, clé du Keystore Android, jamais exportable),
 * dans le stockage interne de l'app, exclu des sauvegardes comme le reste. Jamais journalisé.
 */
class CorosAccountStore(
    context: Context,
) {
    private val file = File(context.applicationContext.filesDir, "coros_account.bin")
    private val mutableAccount = MutableStateFlow(load())
    val account: StateFlow<CorosAccount?> = mutableAccount.asStateFlow()

    /** Le mot de passe n'est pas gardé tel quel : il est remplacé aussitôt par son MD5. */
    suspend fun save(
        email: String,
        password: String,
        region: CorosUpload.Region,
    ) = withContext(Dispatchers.IO) {
        val account = CorosAccount(email.trim(), CorosUpload.md5Hex(password.encodeToByteArray()), region)
        val plain = listOf(account.region.name, account.email, account.passwordMd5).joinToString("\n").encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        file.writeBytes(cipher.iv + cipher.doFinal(plain))
        mutableAccount.value = account
    }

    suspend fun forget() =
        withContext(Dispatchers.IO) {
            file.delete()
            mutableAccount.value = null
        }

    // Fichier illisible (clé effacée avec les données de l'app, fichier abîmé) : pas de compte.
    private fun load(): CorosAccount? =
        try {
            if (!file.exists()) {
                null
            } else {
                val bytes = file.readBytes()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
                val (region, email, md5) = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString().split("\n")
                CorosAccount(email, md5, CorosUpload.Region.valueOf(region))
            }
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "coros_account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}
