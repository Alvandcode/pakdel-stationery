package com.pakdel.manager.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warehouse
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pakdel.core.designsystem.component.LoadingBox
import com.pakdel.core.data.session.Session
import com.pakdel.manager.ui.auth.LoginScreen
import com.pakdel.manager.ui.dashboard.DashboardScreen
import com.pakdel.manager.ui.inventory.InventoryScreen
import com.pakdel.manager.ui.invoices.InvoicesScreen
import com.pakdel.manager.ui.pos.PosScreen
import com.pakdel.manager.ui.products.ProductsScreen
import com.pakdel.manager.ui.settings.SettingsScreen

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Dest("dashboard", "داشبورد", Icons.Outlined.Dashboard),
    Dest("products", "محصولات", Icons.Outlined.Inventory2),
    Dest("inventory", "انبار", Icons.Outlined.Warehouse),
    Dest("pos", "صندوق فروش", Icons.Outlined.PointOfSale),
    Dest("settings", "تنظیمات", Icons.Outlined.Settings),
)

/** صندوق فروش فقط برای نقش‌هایی که مجوز صدور فاکتور دارند نمایش داده می‌شود. */
private fun destinationsFor(session: Session): List<Dest> =
    destinations.filter { it.route != "pos" || session.canCreateSale }

sealed interface RootState {
    data object Loading : RootState
    data object LoggedOut : RootState
    data class LoggedIn(val session: Session) : RootState
}

@Composable
fun ManagerRoot(
    viewModel: RootViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        RootState.Loading -> LoadingBox()
        RootState.LoggedOut -> LoginScreen()
        is RootState.LoggedIn -> ManagerScaffold(current.session)
    }
}

@Composable
private fun ManagerScaffold(session: Session) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val destinations = destinationsFor(session)

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding),
        ) {
            composable("dashboard") {
                DashboardScreen(onOpenInvoices = { navController.navigate("invoices") })
            }
            composable("invoices") {
                InvoicesScreen(onBack = { navController.popBackStack() })
            }
            composable("products") { ProductsScreen() }
            composable("inventory") { InventoryScreen() }
            composable("pos") { PosScreen(session) }
            composable("settings") { SettingsScreen() }
        }
    }
}
