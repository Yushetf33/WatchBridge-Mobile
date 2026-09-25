package com.tunombre.tvbridge.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        SplashGate.ready = false
        val openedAt = SystemClock.uptimeMillis()
        // El logo se queda hasta que Inicio tenga algo que pintar, pero
        // nunca menos de SPLASH_MIN_MS (para que no sea un parpadeo si
        // carga al instante) ni más de SPLASH_MAX_MS (para que un fallo de
        // red no lo deje eterno).
        splash.setKeepOnScreenCondition {
            val elapsed = SystemClock.uptimeMillis() - openedAt
            !(SplashGate.ready && elapsed >= SPLASH_MIN_MS)
        }
        Handler(Looper.getMainLooper()).postDelayed({ SplashGate.ready = true }, SPLASH_MAX_MS)
        setContent { TvBridgeApp() }
    }

    private companion object {
        const val SPLASH_MIN_MS = 600L
        const val SPLASH_MAX_MS = 2000L
    }
}
