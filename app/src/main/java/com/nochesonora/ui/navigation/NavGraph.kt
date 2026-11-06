package com.nochesonora.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nochesonora.R
import com.nochesonora.ui.theme.Background
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SurfaceHigh
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nochesonora.ui.detail.NightDetailScreen
import com.nochesonora.ui.diagnostics.DiagnosticsScreen
import com.nochesonora.ui.history.HistoryScreen
import com.nochesonora.ui.home.HomeScreen
import com.nochesonora.ui.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val DETAIL = "night/{id}"
    fun detail(id: Long) = "night/$id"
}

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.AutoMirrored.Filled.List),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

/** Actions that arrive from outside the app (notification, widget, Quick Settings tile). */
sealed interface PendingAction {
    data object StartNight : PendingAction
    data class OpenSession(val sessionId: Long) : PendingAction
}

@Composable
fun NavGraph(
    pendingAction: PendingAction? = null,
    onActionHandled: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                Column {
                    HorizontalDivider(color = Border)
                NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                indicatorColor = SurfaceHigh,
                                unselectedIconColor = OnSurfaceMuted,
                                unselectedTextColor = OnSurfaceMuted,
                            ),
                        )
                    }
                }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSession = { navController.navigate(Routes.detail(it)) },
                    startRequested = pendingAction == PendingAction.StartNight,
                    onStartHandled = onActionHandled,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenSession = { navController.navigate(Routes.detail(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                NightDetailScreen(sessionId = id, onBack = { navController.popBackStack() })
            }
        }
        // After the NavHost (the graph exists by now). Open a night: go to its detail. Start: go to Home, which handles it.
        LaunchedEffect(pendingAction) {
            when (pendingAction) {
                is PendingAction.OpenSession -> {
                    if (pendingAction.sessionId > 0) navController.navigate(Routes.detail(pendingAction.sessionId)) { launchSingleTop = true }
                    onActionHandled()
                }
                PendingAction.StartNight -> navController.navigate(Routes.HOME) {
                    popUpTo(navController.graph.findStartDestination().id)
                    launchSingleTop = true
                }
                null -> Unit
            }
        }
    }
}
