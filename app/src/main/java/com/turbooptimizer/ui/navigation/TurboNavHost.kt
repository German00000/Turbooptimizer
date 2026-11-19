package com.turbooptimizer.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.turbooptimizer.AppContainer
import com.turbooptimizer.TurboApp
import com.turbooptimizer.ui.screens.AppsScreen
import com.turbooptimizer.ui.screens.CleanerScreen
import com.turbooptimizer.ui.screens.DeviceInfoScreen
import com.turbooptimizer.ui.screens.DiagnosticsScreen
import com.turbooptimizer.ui.screens.GameBoosterScreen
import com.turbooptimizer.ui.screens.HomeScreen
import com.turbooptimizer.ui.screens.OptimizeScreen
import com.turbooptimizer.ui.screens.RamBoostScreen
import com.turbooptimizer.ui.screens.RecorderScreen
import com.turbooptimizer.ui.screens.SecurityScreen

object Routes {
    const val HOME = "home"
    const val OPTIMIZE = "optimize"
    const val CLEANER = "cleaner"
    const val APPS = "apps"
    const val GAME = "game"
    const val RAM_BOOST = "ram_boost"
    const val RECORDER = "recorder"
    const val DEVICE = "device"
    const val SECURITY = "security"
    const val DIAGNOSTICS = "diagnostics"
}

/** Crea un ViewModel con acceso al [AppContainer] (inyección manual). */
@Composable
inline fun <reified VM : ViewModel> turboViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as TurboApp).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

@Composable
fun TurboNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val go: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable(Routes.HOME) { HomeScreen(go) }
        composable(Routes.OPTIMIZE) { OptimizeScreen(back) }
        composable(Routes.CLEANER) { CleanerScreen(back) }
        composable(Routes.APPS) { AppsScreen(back) }
        composable(Routes.GAME) { GameBoosterScreen(back, go) }
        composable(Routes.RAM_BOOST) { RamBoostScreen(back) }
        composable(Routes.RECORDER) { RecorderScreen(back) }
        composable(Routes.DEVICE) { DeviceInfoScreen(back) }
        composable(Routes.SECURITY) { SecurityScreen(back) }
        composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(back) }
    }
}
