package com.tunombre.tvbridge.mobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        SplashGate.ready = false
        splash.setKeepOnScreenCondition { !SplashGate.ready }
        Handler(Looper.getMainLooper()).postDelayed({ SplashGate.ready = true }, SPLASH_MAX_MS)
        setContent { TvBridgeApp() }
    }

    private companion object {
        const val SPLASH_MAX_MS = 2000L
    }
}
