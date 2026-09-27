package com.tunombre.tvbridge.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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

        // Avisos de "hoy/mañana se estrena X" (ver PremiereNotifier) — pide
        // el permiso una vez (Android 13+) y (re)programa la comprobación
        // diaria en cada apertura; es idempotente, así que no pasa nada por
        // llamarlo siempre.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PremiereScheduler.schedule(this)

        setContent { TvBridgeApp() }
    }

    private companion object {
        const val SPLASH_MIN_MS = 600L
        const val SPLASH_MAX_MS = 2000L
    }
}
