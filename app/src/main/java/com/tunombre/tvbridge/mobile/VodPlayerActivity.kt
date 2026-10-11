package com.tunombre.tvbridge.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Reproductor de una pelicula o un episodio de una lista Xtream: a pantalla completa, con la barra de avance de siempre. */
class VodPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private val error = mutableStateOf<String?>(null)
    private var triedHls = false

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build()
        player = exo
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // Muchos paneles sirven las peliculas como HLS aunque el enlace acabe en .mp4: se reintenta una vez asi.
                if (!triedHls && e.cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) {
                    triedHls = true
                    exo.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    error.value = getString(R.string.iptv_player_error)
                }
            }
        })
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build())
        exo.prepare()
        exo.playWhenReady = true
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        controllerShowTimeoutMs = 3500
                        player = exo
                    }
                })
                error.value?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
            }
        }
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, title: String, url: String) {
            context.startActivity(Intent(context, VodPlayerActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title))
        }
    }
}
