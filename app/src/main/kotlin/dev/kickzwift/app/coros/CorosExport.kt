package dev.kickzwift.app.coros

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    data class Done(
        override val file: String?,
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
            client.upload(a, fit.readBytes(), fit.name)
            mutableSent.update { it + fit.name }
        }
    }

    /** Connexion seule, depuis les réglages. */
    fun check() = run(null) { a -> client.check(a) }

    private fun run(
        file: String?,
        block: suspend (CorosAccount) -> Unit,
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
                    CorosStatus.Done(file)
                } catch (e: CorosException) {
                    CorosStatus.Failed(file, e.failure)
                }
        }
    }
}
