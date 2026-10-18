package dev.kickzwift.app.ui.activities

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.data.Activity
import dev.kickzwift.app.ui.devices.readyToPrepare
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ActivitiesUi(
    /** null tant que la base n'a pas répondu. */
    val activities: List<Activity>? = null,
    val canStart: Boolean = false,
    val imperial: Boolean = false,
)

/** Écran Activités : démarrer une activité, revoir les sorties enregistrées. */
class ActivitiesViewModel(
    private val c: AppContainer,
) : ViewModel() {
    val state: StateFlow<ActivitiesUi> =
        combine(c.activities.activities, c.trusted, c.links.states, c.links.running, c.settings) { list, trusted, states, running, s ->
            ActivitiesUi(
                activities = list,
                canStart = running && readyToPrepare(trusted.map { it.kind to states[it.id] }),
                imperial = s.imperial,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActivitiesUi())

    /** Même entrée que « Préparer la sortie » : la suite passe par le choix du mode. */
    fun prepare() = c.session.prepare()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { ActivitiesViewModel(c) } }
    }
}

/** Activité lue dans la base ; [activity] reste null si elle n'existe pas. */
data class ActivityDetail(
    val loaded: Boolean = false,
    val activity: Activity? = null,
)

/** Écran d'une activité : bilan enregistré, commentaire modifiable, suppression. */
class ActivityViewModel(
    private val c: AppContainer,
    private val id: Long,
) : ViewModel() {
    val detail: StateFlow<ActivityDetail> =
        c.activities
            .activity(id)
            .map { ActivityDetail(loaded = true, activity = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ActivityDetail())

    val settings = c.settings

    fun saveComment(text: String) = c.saveComment(id, text)

    fun delete() = c.deleteActivity(id)

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(
            c: AppContainer,
            id: Long,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { ActivityViewModel(c, id) } }
    }
}
