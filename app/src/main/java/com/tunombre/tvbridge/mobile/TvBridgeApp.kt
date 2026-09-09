package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // Chequeo silencioso en cada apertura de la app (limitado a una vez cada
    // pocas horas, ver UpdateChecker) — si hay una versión más nueva
    // publicada en el repo público, se descarga sola en segundo plano y
    // avisa con una notificación para instalarla cuando termine. No hay
    // forma de saltarse la confirmación manual de instalación de Android,
    // así que esto deja ese último toque como único paso del usuario.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { UpdateChecker.checkAndDownloadIfNewer(context) }
    }

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
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onLoggedOut = {
                            navController.navigate(Routes.VERIFY) {
                                popUpTo(0)
                            }
                        }
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
