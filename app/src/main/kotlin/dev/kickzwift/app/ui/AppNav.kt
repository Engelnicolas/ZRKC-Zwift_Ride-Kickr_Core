package dev.kickzwift.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.kickzwift.app.AppContainer
import dev.kickzwift.app.R
import dev.kickzwift.app.ui.activities.ActivitiesScreen
import dev.kickzwift.app.ui.activities.ActivitiesViewModel
import dev.kickzwift.app.ui.activities.ActivityScreen
import dev.kickzwift.app.ui.activities.ActivityViewModel
import dev.kickzwift.app.ui.devices.DevicesScreen
import dev.kickzwift.app.ui.devices.DevicesViewModel
import dev.kickzwift.app.ui.mode.ModeScreen
import dev.kickzwift.app.ui.mode.ModeViewModel
import dev.kickzwift.app.ui.ride.RideScreen
import dev.kickzwift.app.ui.ride.RideViewModel
import dev.kickzwift.app.ui.settings.SettingsPage
import dev.kickzwift.app.ui.settings.SettingsPageScreen
import dev.kickzwift.app.ui.settings.SettingsScreen
import dev.kickzwift.app.ui.settings.SettingsViewModel
import dev.kickzwift.app.ui.summary.SummaryScreen
import dev.kickzwift.app.ui.summary.SummaryViewModel
import dev.kickzwift.protocol.session.RidePhase

/**
 * Une seule activité, Navigation Compose. L'app s'ouvre sur Connexions ; la phase de la sortie
 * impose l'écran : une sortie en cours rouvre l'écran Sortie, une sortie finie le Bilan, dont la
 * fermeture mène à l'historique des activités. La barre de navigation et le logo, qui mène aux
 * réglages, n'existent que sur Connexions, Activités et le menu des Réglages : l'écran Sortie
 * garde toute sa hauteur ; une page des réglages, comme une activité, a son bouton Retour.
 */
@Composable
fun AppNav(c: AppContainer) {
    val nav = rememberNavController()
    val session by c.session.state.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    LaunchedEffect(session.snapshot.phase, route) { follow(nav, session.snapshot.phase, route) }

    Scaffold(
        topBar = {
            if (route in TAB_ROUTES) {
                LogoBar(onClick = { if (route != Routes.SETTINGS) nav.navigate(Routes.SETTINGS) { popUpTo(Routes.DEVICES) } })
            }
        },
        bottomBar = {
            if (route in TAB_ROUTES) {
                NavigationBar {
                    Tab(nav, route, Routes.DEVICES, R.string.tab_devices, ImageVector.vectorResource(R.drawable.ic_tab_devices))
                    Tab(nav, route, Routes.ACTIVITIES, R.string.tab_activities, ImageVector.vectorResource(R.drawable.ic_tab_activities))
                    Tab(nav, route, Routes.SETTINGS, R.string.tab_settings, ImageVector.vectorResource(R.drawable.ic_tab_settings))
                }
            }
        },
    ) { padding ->
        // Marges du Scaffold consommées : safeDrawingPadding ne rajoute pas la barre d'état.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                    SummaryScreen(
                        viewModel(factory = SummaryViewModel.factory(c)),
                        onClosed = { nav.navigate(Routes.ACTIVITIES) { popUpTo(Routes.DEVICES) } },
                    )
                }
                composable(Routes.ACTIVITIES) {
                    ActivitiesScreen(
                        viewModel(factory = ActivitiesViewModel.factory(c)),
                        onPrepare = { nav.navigate(Routes.MODE) },
                        onOpen = { nav.navigate(Routes.activity(it)) },
                    )
                }
                composable(Routes.ACTIVITY, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    ActivityScreen(viewModel(factory = ActivityViewModel.factory(c, id)), onBack = { nav.popBackStack() })
                }
                settings(nav, c)
            }
        }
    }
}

/** Menu des réglages et ses pages. */
private fun NavGraphBuilder.settings(
    nav: NavHostController,
    c: AppContainer,
) {
    composable(Routes.SETTINGS) { SettingsScreen(onOpen = { nav.navigate(Routes.settingsPage(it.route)) }) }
    composable(Routes.SETTINGS_PAGE) { entry ->
        val page = SettingsPage.of(entry.arguments?.getString("page")) ?: SettingsPage.BUTTONS
        SettingsPageScreen(viewModel(factory = SettingsViewModel.factory(c)), page, onBack = { nav.popBackStack() })
    }
}

/** Logo en haut à gauche, à peine plus clair que le fond ; le toucher ouvre les réglages. */
@Composable
private fun LogoBar(onClick: () -> Unit) {
    val label = stringResource(R.string.tab_settings)
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, top = 8.dp),
    ) {
        Icon(
            ImageVector.vectorResource(R.drawable.ic_logo),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = LOGO_ALPHA),
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
                    .padding(4.dp)
                    .size(LOGO_SIZE),
        )
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

private val TAB_ROUTES = setOf(Routes.DEVICES, Routes.ACTIVITIES, Routes.SETTINGS)

private const val LOGO_ALPHA = 0.22f

// Trois fois le pignon de la version précédente (22 dp visibles dans une icône de 40 dp).
private val LOGO_SIZE = 66.dp

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
