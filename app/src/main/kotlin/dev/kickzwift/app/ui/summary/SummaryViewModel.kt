package dev.kickzwift.app.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.coros.CorosStatus
import java.io.File

/** Écran Bilan (lot 9) : l'état vient tout droit de la session terminée. */
class SummaryViewModel(
    private val c: AppContainer,
) : ViewModel() {
    val session = c.session.state
    val settings = c.settings
    val coros = c.coros.status
    val corosSent = c.coros.sent

    fun sendToCoros(fit: File) = c.coros.send(fit)

    /**
     * Joint le commentaire à l'activité enregistrée, puis referme la sortie. Activité envoyée à
     * COROS, ou en cours d'envoi : le commentaire y est publié aussi, une fois l'import fini.
     */
    fun close(comment: String) {
        val id = session.value.activityId
        val fit = session.value.fitFile?.name
        val exported = fit != null && (fit in corosSent.value || (coros.value as? CorosStatus.Sending)?.file == fit)
        if (id != null && comment.isNotBlank()) c.saveComment(id, comment, toCoros = exported)
        c.session.close()
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { SummaryViewModel(c) } }
    }
}
