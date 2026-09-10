package com.tunombre.tvbridge.mobile

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object Routes {
    const val VERIFY = "verify"
    const val HOME = "home"
    const val REFERRAL = "referral"
    const val DEVICES = "devices"
    const val TRAKT_IMPORT = "trakt_import"
    private const val DETAIL_BASE = "detail/{mediaPath}/{tmdbId}"
    const val DETAIL = "$DETAIL_BASE?seedTitle={seedTitle}"
    fun detail(mediaPath: String, tmdbId: Int, seedTitle: String? = null): String {
        val base = "detail/$mediaPath/$tmdbId"
        return if (seedTitle != null) "$base?seedTitle=${java.net.URLEncoder.encode(seedTitle, "UTF-8")}" else base
    }
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
    // Si ShareTargetActivity nos ha lanzado con una ficha pendiente (ver
    // ShareTargetActivity.EXTRA_MEDIA_PATH/EXTRA_TMDB_ID) y ya hay una
    // verificación válida, se entra directo a esa ficha en vez de a Home —
    // así "Compartir → TvRecommendationBridge" aterriza donde de verdad
    // importa. Si no hay verificación todavía, se ignora (caso raro: pasa
    // en una instalación recién hecha, sin verificar) y se sigue el flujo
    // normal de Verify.
    val activity = context as? ComponentActivity
    val startDestination = remember {
        val pendingMediaPath = activity?.intent?.getStringExtra(ShareTargetActivity.EXTRA_MEDIA_PATH)
        val pendingTmdbId = activity?.intent?.getIntExtra(ShareTargetActivity.EXTRA_TMDB_ID, -1) ?: -1
        when {
            !LicenseManager.isLikelyValid(context) -> Routes.VERIFY
            pendingMediaPath != null && pendingTmdbId != -1 -> Routes.detail(pendingMediaPath, pendingTmdbId)
            else -> Routes.HOME
        }
    }

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
                        onOpenTraktImport = { navController.navigate(Routes.TRAKT_IMPORT) },
                        onOpenDetail = { mediaPath, tmdbId, seedTitle ->
                            navController.navigate(Routes.detail(mediaPath, tmdbId, seedTitle))
                        },
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
                composable(Routes.TRAKT_IMPORT) {
                    TraktImportScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    Routes.DETAIL,
                    arguments = listOf(
                        navArgument("mediaPath") { type = NavType.StringType },
                        navArgument("tmdbId") { type = NavType.IntType },
                        navArgument("seedTitle") { type = NavType.StringType; nullable = true; defaultValue = null }
                    )
                ) { backStackEntry ->
                    val mediaPath = backStackEntry.arguments?.getString("mediaPath") ?: "movie"
                    val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
                    val seedTitle = backStackEntry.arguments?.getString("seedTitle")
                    DetailScreen(
                        tmdbId = tmdbId,
                        mediaPath = mediaPath,
                        seedTitle = seedTitle,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { newMediaPath, newTmdbId ->
                            navController.navigate(Routes.detail(newMediaPath, newTmdbId))
                        }
                    )
                }
            }
        }
    }
}
