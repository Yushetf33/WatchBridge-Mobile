package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailUiState(
    val isLoading: Boolean = true,
    val details: TmdbDetails? = null,
    val isInWatchlist: Boolean = false,
    val moreLikeThis: List<TmdbClient.TmdbRecommendation> = emptyList(),
    val installedApps: List<PlayerApp> = emptyList(),
    val defaultApp: PlayerApp = PlayerApp.NUVIO,
    val tvDevices: List<BoundDevice> = emptyList()
)

sealed class DetailEvent {
    object OpenFailed : DetailEvent()
    data class SentToTv(val deviceName: String) : DetailEvent()
    object SendToTvFailed : DetailEvent()
}

/**
 * Ficha propia de una película/serie — a diferencia del tap directo de
 * antes (tarjeta → resuelve → abre), aquí se para primero a mostrar la
 * metadata de TMDb (póster/backdrop/nota/año/duración/sinopsis) y deja
 * elegir CÓMO abrirlo: con la app por defecto de un toque, o con
 * cualquier otra instalada solo para esta vez (ver
 * StremioLauncher.open(overrideApp=...)). No es un reproductor ni un
 * catálogo propio — solo la interfaz que decide dónde abrirlo.
 */
class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DetailEvent>()
    val events = _events.asSharedFlow()

    fun load(tmdbId: Int, mediaPath: String) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val details = TmdbClient.fetchDetails(tmdbId, mediaPath)
            val moreLikeThis = TmdbClient.fetchRecommendations(tmdbId, mediaPath).take(10)
            val isInWatchlist = Watchlist.isInList(context, tmdbId, mediaPath)
            val type = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
            val defaultApp = Preferences.getAppFor(context, type)
            val installedApps = PlayerApp.entries.filter { isAppInstalled(it) }
            val tvDevices = LicenseManager.getSavedEmail(context)?.let { email ->
                (DeviceApi.listDevices(context, email) as? DevicesResult.Loaded)?.devices?.filter { it.deviceType == "tv" }
            } ?: emptyList()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        details = details,
                        isInWatchlist = isInWatchlist,
                        moreLikeThis = moreLikeThis,
                        installedApps = installedApps,
                        defaultApp = defaultApp,
                        tvDevices = tvDevices
                    )
                }
            }
        }
    }

    /** "Enviar a la TV" — deja pendiente la apertura en [target] (ver
     * DeviceApi.sendRemoteOpen); la propia TV la recoge sondeando por su
     * cuenta en segundo plano, no hay confirmación de que ya se haya
     * abierto de verdad, solo de que el backend la guardó. */
    fun sendToTv(target: BoundDevice) {
        val details = _uiState.value.details ?: return
        val email = LicenseManager.getSavedEmail(context) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = DeviceApi.sendRemoteOpen(
                context, email, target.deviceId,
                details.tmdbId, details.mediaPath, details.title, details.posterPath, details.overview
            )
            withContext(Dispatchers.Main) {
                _events.emit(
                    if (ok) DetailEvent.SentToTv(target.deviceName ?: target.deviceId)
                    else DetailEvent.SendToTvFailed
                )
            }
        }
    }

    private fun isAppInstalled(app: PlayerApp): Boolean =
        if (app == PlayerApp.NUVIO) {
            StremioLauncher.NUVIO_PACKAGES.any { StremioLauncher.isPackageInstalled(context, it) }
        } else {
            StremioLauncher.isPackageInstalled(context, app.packageName)
        }

    /** Botón principal "Ver con {app por defecto}". */
    fun watchNow() = open(overrideApp = null)

    /** Elegido explícitamente en "Cambiar reproductor" — abre siempre en
     * esa app en concreto, sin tocar la preferencia guardada (ver
     * StremioLauncher.open(overrideApp=...)). */
    fun watchWith(app: PlayerApp) = open(overrideApp = app)

    private fun open(overrideApp: PlayerApp?) {
        val details = _uiState.value.details ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = TmdbCandidate(details.tmdbId, details.mediaPath, details.title, year = details.year, posterPath = details.posterPath, overview = details.overview)
            val match = TmdbClient.resolveCandidate(context, candidate)
            withContext(Dispatchers.Main) {
                if (match != null) StremioLauncher.open(context, match, overrideApp) else _events.emit(DetailEvent.OpenFailed)
            }
        }
    }

    fun toggleWatchlist() {
        val details = _uiState.value.details ?: return
        val inList = _uiState.value.isInWatchlist
        if (inList) Watchlist.remove(context, details.tmdbId, details.mediaPath)
        else Watchlist.add(context, details.tmdbId, details.mediaPath, details.title, details.posterPath, details.overview)
        _uiState.update { it.copy(isInWatchlist = !inList) }
    }
}

@Composable
fun DetailScreen(
    tmdbId: Int,
    mediaPath: String,
    seedTitle: String?,
    onBack: () -> Unit,
    onOpenDetail: (mediaPath: String, tmdbId: Int) -> Unit,
    viewModel: DetailViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val openFailedMessage = stringResource(R.string.home_open_failed)
    val sendToTvFailedMessage = stringResource(R.string.detail_send_to_tv_failed)
    var showPlayerPicker by remember { mutableStateOf(false) }
    var showTvPicker by remember { mutableStateOf(false) }

    LaunchedEffect(tmdbId, mediaPath) { viewModel.load(tmdbId, mediaPath) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                DetailEvent.OpenFailed -> snackbarHostState.showSnackbar(openFailedMessage)
                DetailEvent.SendToTvFailed -> snackbarHostState.showSnackbar(sendToTvFailedMessage)
                is DetailEvent.SentToTv -> snackbarHostState.showSnackbar(
                    context.getString(R.string.detail_sent_to_tv, event.deviceName)
                )
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.details == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.detail_not_found), modifier = Modifier.padding(32.dp))
                }
                else -> {
                    val details = uiState.details!!
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Box {
                            val backdropOrPoster = details.backdropPath ?: details.posterPath
                            if (backdropOrPoster != null) {
                                AsyncImage(
                                    model = "https://image.tmdb.org/t/p/w780$backdropOrPoster",
                                    contentDescription = details.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                                )
                            } else {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(16f / 10f)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                            }
                            // Velo que funde el fondo con la pantalla (el logo/titulo va encima).
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to Color(0x800B0D12),
                                            0.4f to Color.Transparent,
                                            1f to MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                            )
                            Text(
                                "\u2190",
                                color = Color.White,
                                fontSize = 20.sp,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x990B0D12))
                                    .clickable(onClick = onBack)
                                    .wrapContentSize(Alignment.Center)
                            )
                        }

                        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                            if (details.logoPath != null) {
                                AsyncImage(
                                    model = "https://image.tmdb.org/t/p/w500${details.logoPath}",
                                    contentDescription = details.title,
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.CenterStart,
                                    modifier = Modifier.height(64.dp).fillMaxWidth(0.65f)
                                )
                            } else {
                                Text(details.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                metaLine(TmdbClient.TmdbRecommendation(details.tmdbId, details.mediaPath, details.title, details.posterPath, 0.0, details.overview), details),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            details.overview?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(12.dp))
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (seedTitle != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(R.string.detail_recommended_because, seedTitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = viewModel::watchNow,
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp)
                            ) {
                                Text(stringResource(R.string.detail_watch_now, uiState.defaultApp.label))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { showPlayerPicker = !showPlayerPicker }) {
                                    Text(stringResource(R.string.detail_change_player))
                                }
                                OutlinedButton(onClick = viewModel::toggleWatchlist, shape = RoundedCornerShape(24.dp)) {
                                    Text(
                                        if (uiState.isInWatchlist) stringResource(R.string.detail_in_watchlist)
                                        else stringResource(R.string.detail_add_to_watchlist)
                                    )
                                }
                            }
                            if (showPlayerPicker) {
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(uiState.installedApps) { app ->
                                        FilterChip(
                                            selected = false,
                                            onClick = {
                                                showPlayerPicker = false
                                                viewModel.watchWith(app)
                                            },
                                            label = { Text(app.label) }
                                        )
                                    }
                                }
                            }

                            if (uiState.tvDevices.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                if (uiState.tvDevices.size == 1) {
                                    val tv = uiState.tvDevices[0]
                                    OutlinedButton(onClick = { viewModel.sendToTv(tv) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.detail_send_to_tv, tv.deviceName ?: tv.deviceId))
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { showTvPicker = !showTvPicker },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.detail_send_to_tv_picker))
                                    }
                                    if (showTvPicker) {
                                        Spacer(Modifier.height(8.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(uiState.tvDevices) { tv ->
                                                FilterChip(
                                                    selected = false,
                                                    onClick = {
                                                        showTvPicker = false
                                                        viewModel.sendToTv(tv)
                                                    },
                                                    label = { Text(tv.deviceName ?: tv.deviceId) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.moreLikeThis.isNotEmpty()) {
                            RecommendationRow(
                                title = stringResource(R.string.detail_more_like_this),
                                items = uiState.moreLikeThis,
                                onClick = { rec -> onOpenDetail(rec.mediaPath, rec.tmdbId) },
                                onLongClick = { }
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
