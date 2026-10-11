package com.tunombre.tvbridge.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
import androidx.media3.ui.AspectRatioFrameLayout
import com.tunombre.tvbridge.Mpeg2Native
import com.tunombre.tvbridge.WatchBridgeRenderersFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import java.text.DateFormat
import java.util.Date
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reproductor de un canal IPTV a pantalla completa (horizontal): toca la pantalla para ver el nombre, lo que se emite
 * ahora y despues, cambiar de canal, el audio y volver. Los canales a los que se puede saltar son los de la lista que
 * se esta viendo ([IptvRepository.currentChannels]); desde los eventos deportivos se pasa la lista con solo esos canales.
 */
class IptvPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var channels: List<IptvChannel> = emptyList()
    private var index = 0
    private var altIndex = 0

    // Marcador, goles y tarjetas del partido que emite el canal (si lo es); se actualiza solo mientras se ve.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var matchJob: Job? = null
    private val matchPanel = mutableStateOf<MatchPanel?>(null)

    private class MatchPanel(
        val home: SportsDbClient.Team,
        val away: SportsDbClient.Team,
        val score: String,
        val status: String,
        val live: Boolean,
        val events: List<String>
    )

    private val channelState = mutableIntStateOf(0)
    private val errorState = mutableStateOf<String?>(null)
    private val audioTracks = mutableIntStateOf(0)

    // Video MPEG-2 (la TDT: La 1, La 2...) decodificado por software y dibujado en esta vista GL; solo visible con ese video.
    private var glFrame: AspectRatioFrameLayout? = null
    private var glView: VideoDecoderGLSurfaceView? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
        channels = pending ?: IptvRepository.currentChannels
        index = channels.indexOfFirst { it.key == key }
        if (index < 0) {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val exo = buildPlayer()
        player = exo
        play(index)
        setContent {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
                PlayerContent(exo)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        val renderers = WatchBridgeRenderersFactory(this, null, softwareMpeg2Video = true, softwareMp2First = true)
        val exo = ExoPlayer.Builder(this, renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 400, 2_500).setPrioritizeTimeOverSizeThresholds(true).build())
            .build()
        val gl = VideoDecoderGLSurfaceView(this)
        glView = gl
        renderers.softwareRenderer?.let { exo.createMessage(it).setType(androidx.media3.exoplayer.Renderer.MSG_SET_VIDEO_OUTPUT).setPayload(gl).send() }
        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                audioTracks.intValue = tracks.groups.count { g -> g.type == C.TRACK_TYPE_AUDIO && g.isSupported }
                val videoMime = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.getTrackFormat(0)?.sampleMimeType
                val software = videoMime == androidx.media3.common.MimeTypes.VIDEO_MPEG2 && Mpeg2Native.available
                if (software) {
                    // La forma de la imagen sale del flujo (4:3 o 16:9); si no la declara, la TDT es 16:9 (720x576 "anamorfico").
                    val format = tracks.groups.first { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }.getTrackFormat(0)
                    val declared = if (format.width > 0 && format.height > 0) format.width * format.pixelWidthHeightRatio / format.height else 0f
                    glFrame?.setAspectRatio(if (format.pixelWidthHeightRatio != 1f && declared > 0f) declared else 16f / 9f)
                }
                glFrame?.visibility = if (software) View.VISIBLE else View.INVISIBLE
                // El PlayerView tapa el video con un fondo negro hasta que ve el primer fotograma; con el de software no hay que taparlo.
                playerView?.setShutterBackgroundColor(if (software) android.graphics.Color.TRANSPARENT else android.graphics.Color.BLACK)
            }


            override fun onPlayerError(error: PlaybackException) {
                if (tryNextUrl(error)) return
                errorState.value = getString(R.string.iptv_player_error)
            }
        })
        return exo
    }

    // Un canal puede tener varias formas de pedirse (ver IptvChannel.altStreamUrls): si la principal da 403/404 se prueba la siguiente.
    private fun tryNextUrl(error: PlaybackException): Boolean {
        val channel = channels[index]
        val code = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != 403 && code != 404) return false
        val candidates = listOf(channel.streamUrl) + channel.altStreamUrls
        if (altIndex + 1 >= candidates.size) return false
        altIndex++
        load(channel, candidates[altIndex])
        return true
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun load(channel: IptvChannel, url: String) {
        val exo = player ?: return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(channel.userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .apply { channel.referrer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) } }
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors)
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).build()).build()
        exo.setMediaSource(factory.createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun play(newIndex: Int) {
        index = newIndex.mod(channels.size)
        altIndex = 0
        errorState.value = null
        audioTracks.intValue = 0
        channelState.intValue = index
        load(channels[index], channels[index].streamUrl)
        trackMatch(channels[index])
    }

    /** Si ahora mismo el canal da un partido (por la guia), saca sus equipos y va pidiendo marcador, goles y tarjetas. */
    private fun trackMatch(channel: IptvChannel) {
        matchJob?.cancel()
        matchPanel.value = null
        val context = applicationContext
        matchJob = scope.launch {
            val program = IptvRepository.nowNext(channel).first ?: return@launch
            if (!EpgManager.isSportsProgram(program)) return@launch
            val teams = EpgManager.parseMatchTeams(program.title) ?: return@launch
            SportsDbClient.attach(context)
            WikipediaBadges.attach(context)
            val sport = EpgManager.sportOf(program)
            val (team1, team2) = SportsDbClient.resolveForDisplay(teams.first, teams.second, sport)
            val ref = SportsDbClient.findEvent(team1, team2, program.startMs, sport, program.title) ?: return@launch
            while (isActive) {
                val state = SportsDbClient.fetchState(ref, team1.id, sport, fresh = true)
                val timeline = if (sport == SportsDbClient.SOCCER) SportsDbClient.fetchTimeline(ref, team1.id, team2.id) else emptyList()
                val headline = MatchUi.headline(context, state, program.startMs)
                val lines = timeline.takeLast(7).reversed().map { MatchUi.eventLine(context, it, team1, team2) }
                matchPanel.value = MatchPanel(team1, team2, headline.score, headline.status, headline.live, lines)
                if (state?.phase == SportsDbClient.MatchPhase.FINISHED) break
                delay(SportsDbClient.pollIntervalMs(ref, 30_000L))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        player?.release()
        player = null
        if (pending === channels) pending = null
        super.onDestroy()
    }

    @Composable
    private fun PlayerContent(exo: ExoPlayer) {
        var overlay by remember { mutableStateOf(true) }
        var audioDialog by remember { mutableStateOf(false) }
        val current = channelState.intValue
        // El panel se apaga solo a los pocos segundos.
        LaunchedEffect(overlay, current, audioDialog) {
            if (overlay && !audioDialog) {
                delay(if (matchPanel.value != null) 9000 else 5000)
                overlay = false
            }
        }
        val channel = channels[current.coerceIn(0, channels.size - 1)]
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { overlay = !overlay }) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = exo
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                    playerView = view
                    val frame = AspectRatioFrameLayout(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        visibility = View.INVISIBLE
                        glView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
                    }
                    glFrame = frame
                    FrameLayout(ctx).apply {
                        addView(view)
                        addView(frame, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
                    }
                }
            )
            errorState.value?.let { message ->
                Text(message, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            }
            if (overlay) matchPanel.value?.let { panel -> MatchOverlay(panel, Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)) }
            if (overlay) {
                val (now, next) = IptvRepository.nowNext(channel)
                val format = DateFormat.getTimeInstance(DateFormat.SHORT)
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 16.dp)
                ) {
                    Text(channel.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (now != null) {
                        Text("${format.format(Date(now.startMs))} – ${format.format(Date(now.endMs))} · ${now.title}", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (next != null) {
                        Text(getString(R.string.iptv_next_prefix, "${format.format(Date(next.startMs))} · ${next.title}"), color = Color(0xFFB9BCC4), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { finish() }) { Text("←  " + stringResource(R.string.iptv_player_back), color = Color.White) }
                        Row {
                            TextButton(onClick = { play(index - 1); overlay = true }) { Text("◀  " + stringResource(R.string.iptv_player_prev), color = Color.White) }
                            if (audioTracks.intValue > 1) {
                                TextButton(onClick = { audioDialog = true }) { Text("🔊  " + stringResource(R.string.iptv_player_audio), color = Color.White) }
                            }
                            TextButton(onClick = { play(index + 1); overlay = true }) { Text(stringResource(R.string.iptv_player_next) + "  ▶", color = Color.White) }
                        }
                    }
                }
            }
            if (audioDialog) AudioDialog(exo) { audioDialog = false }
        }
    }

    /** Marcador, minuto y los goles y tarjetas: a la derecha, sobre el video. */
    @Composable
    private fun MatchOverlay(panel: MatchPanel, modifier: Modifier) {
        Column(
            modifier.width(300.dp).background(Color(0xCC0B0D12), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TeamBadge(panel.home)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(panel.score, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(panel.status, color = if (panel.live) Color(0xFFFF4D4F) else Color(0xFFB9BCC4), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                TeamBadge(panel.away)
            }
            Spacer(Modifier.padding(top = 8.dp))
            if (panel.events.isEmpty()) {
                Text(stringResource(R.string.iptv_match_no_events), color = Color(0xFFB9BCC4), fontSize = 13.sp)
            } else {
                panel.events.forEach { line ->
                    Text(line, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
            }
        }
    }

    @Composable
    private fun TeamBadge(team: SportsDbClient.Team) {
        Box(Modifier.width(56.dp).padding(2.dp), contentAlignment = Alignment.Center) {
            val url = team.badgeUrl
            if (url != null) AsyncImage(model = url, contentDescription = team.name, modifier = Modifier.width(48.dp).padding(2.dp))
            else Text(team.name.take(3).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }

    @Composable
    private fun AudioDialog(exo: ExoPlayer, onDismiss: () -> Unit) {
        val entries = remember {
            val list = ArrayList<Triple<Tracks.Group, Int, String>>()
            exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
                for (i in 0 until group.length) if (group.isTrackSupported(i)) list.add(Triple(group, i, audioLabel(group.getTrackFormat(i), list.size)))
            }
            list
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.iptv_player_audio)) },
            text = {
                Column {
                    entries.forEach { (group, index, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(index))).build()
                            onDismiss()
                        }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = group.isTrackSelected(index), onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
        )
    }

    private fun audioLabel(format: androidx.media3.common.Format, index: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { c -> c.titlecase() } }
        val name = format.label?.takeIf { it.isNotBlank() } ?: language ?: getString(R.string.iptv_audio_track_n, index + 1)
        val codec = when (format.sampleMimeType) {
            "audio/ac3" -> "AC-3"
            "audio/eac3" -> "E-AC-3"
            "audio/mp4a-latm" -> "AAC"
            "audio/mpeg", "audio/mpeg-L2" -> "MPEG"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
            else -> null
        }
        val channelsText = when (format.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        return listOfNotNull(name, codec, channelsText).joinToString(" · ")
    }

    companion object {
        private const val EXTRA_CHANNEL_KEY = "channel_key"

        // Lista a la que se puede saltar con anterior/siguiente cuando no es la de la lista completa (p.ej. los canales de un evento).
        @Volatile
        private var pending: List<IptvChannel>? = null

        /** Abre [channel]; [among] = los canales entre los que cambiar (por defecto, todos los de la lista activa). */
        fun open(context: Context, channel: IptvChannel, among: List<IptvChannel>? = null) {
            pending = among
            context.startActivity(Intent(context, IptvPlayerActivity::class.java).putExtra(EXTRA_CHANNEL_KEY, channel.key))
        }
    }
}
