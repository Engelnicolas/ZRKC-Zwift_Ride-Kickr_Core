package dev.kickzwift.app.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import java.io.File

/** Écran Bilan (lot 9) : l'état vient tout droit de la session terminée. */
class SummaryViewModel(
    private val c: AppContainer,
) : ViewModel() {
    val session = c.session.state
    val settings = c.settings
    val coros = c.coros.status

    fun sendToCoros(fit: File) = c.coros.send(fit)

    /** Joint le commentaire à l'activité enregistrée, puis referme la sortie. */
    fun close(comment: String) {
        val id = session.value.activityId
        if (id != null && comment.isNotBlank()) c.saveComment(id, comment)
        c.session.close()
    }

    companion object {
        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { SummaryViewModel(c) } }
    }
}
