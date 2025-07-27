package com.billscanner.app.navigation

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.billscanner.app.ui.screen.BillResultsScreen
import com.billscanner.app.ui.screen.CameraCaptureScreen
import com.billscanner.app.ui.screen.HomeScreen
import com.billscanner.app.ui.viewmodel.BillScannerViewModel

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Camera : Screen("camera")
    object BillResults : Screen("bill_results")
}

@Composable
fun BillScannerNavigation(
    navController: NavHostController = rememberNavController()
) {
    val viewModel: BillScannerViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToCamera = {
                    navController.navigate(Screen.Camera.route)
                },
                onNavigateToBillResults = {
                    navController.navigate(Screen.BillResults.route)
                },
                viewModel = viewModel
            )
        }

        composable(Screen.Camera.route) {
            CameraCaptureScreen(
                onImageCaptured = { imageFile ->
                    viewModel.scanBill(imageFile)
                    navController.popBackStack()
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.BillResults.route) {
            uiState.billResponse?.let { billResponse ->
                BillResultsScreen(
                    billResponse = billResponse,
                    onNavigateBack = {
                        viewModel.clearBillResponse()
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    },
                    onScanAnother = {
                        viewModel.clearBillResponse()
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                        navController.navigate(Screen.Camera.route)
                    }
                )
            }
        }
    }
}
