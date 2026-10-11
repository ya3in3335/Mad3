package com.samtex.eidreader.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.samtex.eidreader.ui.diagnostics.DiagnosticsScreen
import com.samtex.eidreader.ui.home.HomeScreen
import com.samtex.eidreader.ui.identity.AccessScreen
import com.samtex.eidreader.ui.identity.IdentityScreen
import com.samtex.eidreader.ui.results.ResultsScreen
import com.samtex.eidreader.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val DETAILS = "details"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"
    const val ACCESS = "access"
    const val IDENTITY = "identity"
}

@Composable
fun SamtexNavHost(onOpenNfcSettings: () -> Unit) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = hiltViewModel(),
                onOpenDetails = { nav.navigate(Routes.DETAILS) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenNfcSettings = onOpenNfcSettings,
                onReadIdentity = { nav.navigate(Routes.ACCESS) },
            )
        }
        composable(Routes.ACCESS) {
            AccessScreen(
                viewModel = hiltViewModel(),
                onBack = { nav.popBackStack() },
                onStarted = {
                    nav.navigate(Routes.IDENTITY) { popUpTo(Routes.ACCESS) { inclusive = true } }
                },
            )
        }
        composable(Routes.IDENTITY) {
            IdentityScreen(
                viewModel = hiltViewModel(),
                onBack = { nav.popBackStack(Routes.HOME, inclusive = false) },
                onRetry = {
                    nav.navigate(Routes.ACCESS) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(Routes.DETAILS) {
            ResultsScreen(
                viewModel = hiltViewModel(),
                onBack = { nav.popBackStack() },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
        }
    }
}
