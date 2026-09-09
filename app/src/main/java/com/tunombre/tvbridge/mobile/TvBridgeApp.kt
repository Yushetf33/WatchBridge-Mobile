package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val VERIFY = "verify"
    const val HOME = "home"
    const val REFERRAL = "referral"
    const val DEVICES = "devices"
}

/**
 * Punto de entrada de toda la UI — decide la pantalla inicial igual que
 * MainActivity.onCreate en la TV: si ya hay una verificación en caché
 * dentro del periodo de gracia (ver LicenseManager.isLikelyValid), entra
 * directo a Home; si no, pide verificar/pagar primero.
 */
@Composable
fun TvBridgeApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val startDestination = remember { if (LicenseManager.isLikelyValid(context)) Routes.HOME else Routes.VERIFY }

    TvBridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(navController = navController, startDestination = startDestination) {
                composable(Routes.VERIFY) {
                    VerifyScreen(onVerified = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.VERIFY) { inclusive = true }
                        }
                    })
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        onOpenReferral = { navController.navigate(Routes.REFERRAL) },
                        onOpenDevices = { navController.navigate(Routes.DEVICES) }
                    )
                }
                composable(Routes.REFERRAL) {
                    ReferralScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.DEVICES) {
                    DevicesScreen(
                        onBack = { navController.popBackStack() },
                        onRemovedSelf = {
                            navController.navigate(Routes.VERIFY) {
                                popUpTo(0)
                            }
                        }
                    )
                }
            }
        }
    }
}
