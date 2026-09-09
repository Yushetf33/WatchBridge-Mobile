package com.tunombre.tvbridge.mobile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Misma paleta que la web de marketing (ver TvReccomendationBridge/docs/index.html)
 * — fondo oscuro, acento azul. Un color de marca reconocible entre la web,
 * la app de TV y esta ayuda a que se sientan la misma cosa, no tres
 * proyectos sueltos. */
private val BrandBackground = Color(0xFF0B0D12)
private val BrandSurface = Color(0xFF12151C)
private val BrandSurfaceVariant = Color(0xFF171B24)
private val BrandOutline = Color(0xFF262B36)
private val BrandAccent = Color(0xFF5B8DEF)
private val BrandAccentContainer = Color(0xFF1B2A4A)
private val BrandSuccess = Color(0xFF4CD964)
private val BrandError = Color(0xFFE5484D)
private val BrandTextPrimary = Color(0xFFE8E9EB)
private val BrandTextMuted = Color(0xFF9AA1AC)

private val TvBridgeColorScheme = darkColorScheme(
    primary = BrandAccent,
    onPrimary = BrandBackground,
    primaryContainer = BrandAccentContainer,
    onPrimaryContainer = BrandAccent,
    secondary = BrandSuccess,
    onSecondary = BrandBackground,
    background = BrandBackground,
    onBackground = BrandTextPrimary,
    surface = BrandSurface,
    onSurface = BrandTextPrimary,
    surfaceVariant = BrandSurfaceVariant,
    onSurfaceVariant = BrandTextMuted,
    outline = BrandOutline,
    error = BrandError,
    onError = Color.White
)

@Composable
fun TvBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvBridgeColorScheme,
        content = content
    )
}
