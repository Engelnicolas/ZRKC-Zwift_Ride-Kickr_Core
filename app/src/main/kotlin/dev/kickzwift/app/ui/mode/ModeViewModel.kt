package dev.kickzwift.app.ui.mode

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.route.RouteData
import dev.kickzwift.protocol.session.RideMode
import dev.kickzwift.protocol.settings.RecentRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class ModeChoice { FREE, ROUTE, ERG }

data class ModeUi(
    val choice: ModeChoice = ModeChoice.FREE,
    val startGradePct: Double = 0.0,
    val ergWatts: Int = DEFAULT_ERG_W,
    val recent: List<RecentRoute> = emptyList(),
    val route: RouteData? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val imperial: Boolean = false,
) {
    val canStart: Boolean get() = choice != ModeChoice.ROUTE || route != null

    companion object {
        const val DEFAULT_ERG_W = 150
    }
}

/** Écran Choix du mode (lot 8) ; parcours GPX (lot 11). */
class ModeViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val local = MutableStateFlow(ModeUi())

    val state: StateFlow<ModeUi> =
        combine(local, c.routes.recent, c.settings) { ui, recent, s -> ui.copy(recent = recent, imperial = s.imperial) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModeUi())

    fun select(choice: ModeChoice) = local.update { it.copy(choice = choice) }

    fun stepGrade(up: Boolean) =
        local.update {
            val limits = c.settings.value.gradeLimits
            val next = it.startGradePct + if (up) GRADE_STEP else -GRADE_STEP
            it.copy(startGradePct = ((next * HUNDRED).roundToInt() / HUNDRED).coerceIn(limits.minPct, limits.maxPct))
        }

    fun stepErg(up: Boolean) =
        local.update { it.copy(ergWatts = (it.ergWatts + if (up) ERG_STEP else -ERG_STEP).coerceIn(0, c.settings.value.ergMaxW)) }

    fun import(uri: Uri) = load { c.routes.import(uri) }

    fun pick(route: RecentRoute) = load { c.routes.load(route) }

    private fun load(block: suspend () -> Result<RouteData>) {
        local.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = block()
            local.update {
                it.copy(loading = false, route = result.getOrNull() ?: it.route, error = result.exceptionOrNull()?.message)
            }
        }
    }

    /** Lance la sortie ; rend faux si le mode n'est pas prêt (parcours manquant). */
    fun start(): Boolean {
        val ui = state.value
        val mode =
            when (ui.choice) {
                ModeChoice.FREE -> RideMode.FreeGrade(ui.startGradePct)
                ModeChoice.ERG -> RideMode.Erg(ui.ergWatts)
                ModeChoice.ROUTE -> ui.route?.let { RideMode.Route(it.route.name, it.profile) }
            }
        if (mode != null) c.session.start(mode, ui.route.takeIf { ui.choice == ModeChoice.ROUTE })
        return mode != null
    }

    /** Retour sans démarrer : la préparation est abandonnée. */
    fun cancel() = c.session.close()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val GRADE_STEP = 0.5
        private const val ERG_STEP = 10
        private const val HUNDRED = 100.0

        fun factory(c: AppContainer): ViewModelProvider.Factory = viewModelFactory { initializer { ModeViewModel(c) } }
    }
}
