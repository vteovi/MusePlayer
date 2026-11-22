package com.muse.player.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muse.player.ui.about.AboutScreen
import com.muse.player.ui.library.LibraryScreen
import com.muse.player.ui.library.LibraryViewModel
import com.muse.player.ui.network.NetworkViewModel
import com.muse.player.ui.settings.SettingsScreen

@Composable
fun MuseApp() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val networkViewModel: NetworkViewModel = viewModel()

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                navController = navController,
                viewModel = libraryViewModel,
                networkViewModel = networkViewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate("about") }
            )
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
