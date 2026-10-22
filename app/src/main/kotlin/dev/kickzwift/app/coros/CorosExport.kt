package dev.kickzwift.app.coros

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** État d'un envoi à COROS, ou de la vérification du compte ([file] nul). */
sealed interface CorosStatus {
    val file: String?

    data object Idle : CorosStatus {
        override val file: String? = null
    }

    data class Sending(
        override val file: String?,
    ) : CorosStatus

    /** [imported] : COROS a confirmé l'import, l'activité est dans son historique. */
    data class Done(
        override val file: String?,
        val imported: Boolean = false,
    ) : CorosStatus

    data class Failed(
        override val file: String?,
        val failure: CorosFailure?,
    ) : CorosStatus
}

/**
 * Envois à COROS, dans la portée de l'app : ils aboutissent même si l'écran se ferme. Sans
 * compte enregistré, [CorosStatus.Failed] avec un motif nul. Une activité n'est envoyée qu'une
 * fois : COROS en ferait un doublon.
 */
class CorosExport(
    private val accounts: CorosAccountStore,
    private val client: CorosClient,
    private val scope: CoroutineScope,
) {
    private val mutableStatus = MutableStateFlow<CorosStatus>(CorosStatus.Idle)
    val status: StateFlow<CorosStatus> = mutableStatus.asStateFlow()

    private val mutableSent = MutableStateFlow<Set<String>>(emptySet())

    /** Fichiers FIT déjà reçus par COROS depuis le lancement de l'app. */
    val sent: StateFlow<Set<String>> = mutableSent.asStateFlow()

    fun send(fit: File) {
        if (fit.name in mutableSent.value) return
        run(fit.name) { a ->
            val imported = client.upload(a, fit.readBytes(), fit.name)
            mutableSent.update { it + fit.name }
            CorosStatus.Done(fit.name, imported)
        }
    }

    /**
     * Publie [text] en commentaire de l'activité COROS partie à [startEpochMs], une fois fini
     * l'envoi en cours : le bilan peut se fermer avant la fin de l'import. Sans compte, ou texte
     * vide, rien ; un échec ne va qu'au journal, le commentaire reste dans l'historique de l'app.
     */
    fun comment(
        startEpochMs: Long,
        text: String,
    ) {
        val account = accounts.account.value ?: return
        val content = text.trim().takeIf { it.isNotEmpty() } ?: return
        scope.launch {
            mutableStatus.first { it !is CorosStatus.Sending }
            try {
                client.comment(account, startEpochMs, content)
            } catch (_: CorosException) {
                // Déjà au journal ; rien à montrer.
            }
        }
    }

    /** Connexion seule, depuis les réglages. */
    fun check() =
        run(null) { a ->
            client.check(a)
            CorosStatus.Done(null)
        }

    private fun run(
        file: String?,
        block: suspend (CorosAccount) -> CorosStatus.Done,
    ) {
        if (mutableStatus.value is CorosStatus.Sending) return
        val account = accounts.account.value
        if (account == null) {
            mutableStatus.value = CorosStatus.Failed(file, null)
            return
        }
        mutableStatus.value = CorosStatus.Sending(file)
        scope.launch {
            mutableStatus.value =
                try {
                    block(account)
                } catch (e: CorosException) {
                    CorosStatus.Failed(file, e.failure)
                }
        }
    }
}
