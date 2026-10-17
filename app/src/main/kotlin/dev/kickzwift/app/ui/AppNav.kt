package dev.kickzwift.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.devices.DevicesScreen
import dev.kickzwift.app.ui.devices.DevicesViewModel
import dev.kickzwift.app.ui.mode.ModeScreen
import dev.kickzwift.app.ui.mode.ModeViewModel
import dev.kickzwift.app.ui.ride.RideScreen
import dev.kickzwift.app.ui.ride.RideViewModel
import dev.kickzwift.app.ui.settings.SettingsScreen
import dev.kickzwift.app.ui.settings.SettingsViewModel
import dev.kickzwift.app.ui.summary.SummaryScreen
import dev.kickzwift.app.ui.summary.SummaryViewModel
import dev.kickzwift.protocol.session.RidePhase

/**
 * Une seule activité, Navigation Compose. L'app s'ouvre sur Connexions ; la phase de la sortie
 * impose l'écran : une sortie en cours rouvre l'écran Sortie, une sortie finie le Bilan. La
 * barre de navigation n'existe que sur Connexions et Réglages.
 */
@Composable
fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
    val session by c.session.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(session.snapshot.phase, route) { follow(nav, session.snapshot.phase, route) }

    Scaffold(
        bottomBar = {
            if (route == Routes.DEVICES || route == Routes.SETTINGS) {
                NavigationBar {
                    Tab(nav, route, Routes.DEVICES, R.string.tab_devices, ImageVector.vectorResource(R.drawable.ic_tab_devices))
                    Tab(nav, route, Routes.SETTINGS, R.string.tab_settings, ImageVector.vectorResource(R.drawable.ic_tab_settings))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Routes.DEVICES, modifier = Modifier.safeDrawingPadding()) {
                composable(Routes.DEVICES) {
                    DevicesScreen(viewModel(factory = DevicesViewModel.factory(c)), onPrepare = { nav.navigate(Routes.MODE) })
                }
                composable(Routes.MODE) {
                    ModeScreen(
                        viewModel(factory = ModeViewModel.factory(c)),
                        onStarted = { nav.navigate(Routes.RIDE) { popUpTo(Routes.DEVICES) } },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.RIDE) { RideScreen(viewModel(factory = RideViewModel.factory(c))) }
                composable(Routes.SUMMARY) {
                    SummaryScreen(viewModel(factory = SummaryViewModel.factory(c)), onClosed = { home(nav) })
                }
                composable(Routes.SETTINGS) { SettingsScreen(viewModel(factory = SettingsViewModel.factory(c))) }
            }
        }
    }
}

private fun follow(
    nav: NavHostController,
    phase: RidePhase,
    route: String?,
) {
    val wanted =
        when (phase) {
            RidePhase.RIDING, RidePhase.PAUSED -> Routes.RIDE
            RidePhase.FINISHED -> Routes.SUMMARY
            RidePhase.IDLE -> if (route == Routes.RIDE || route == Routes.SUMMARY || route == Routes.MODE) Routes.DEVICES else null
            RidePhase.PREPARING -> null
        }
    if (wanted != null && wanted != route) {
        if (wanted == Routes.DEVICES) home(nav) else nav.navigate(wanted) { popUpTo(Routes.DEVICES) }
    }
}

private fun home(nav: NavHostController) {
    nav.navigate(Routes.DEVICES) { popUpTo(Routes.DEVICES) { inclusive = true } }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(
    nav: NavHostController,
    current: String?,
    target: String,
    label: Int,
    icon: ImageVector,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { if (current != target) nav.navigate(target) { popUpTo(Routes.DEVICES) } },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
    )
}
