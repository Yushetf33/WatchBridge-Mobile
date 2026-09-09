package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Misma idea que RecommendationsRowsFragment.Category en la app de TV. */
enum class Category { MOVIES, SERIES, WATCHLIST }

private fun labelFor(category: Category) = when (category) {
    Category.MOVIES -> "Películas"
    Category.SERIES -> "Series"
    Category.WATCHLIST -> "Mi lista"
}

private fun emojiFor(category: Category) = when (category) {
    Category.MOVIES -> "🎬"
    Category.SERIES -> "📺"
    Category.WATCHLIST -> "🔖"
}

data class RowUi(
    val id: String,
    val title: String,
    val items: List<TmdbClient.TmdbRecommendation>,
    val isContinueWatching: Boolean = false
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val category: Category = Category.MOVIES,
    val rows: List<RowUi> = emptyList(),
    val selectedApp: PlayerApp = PlayerApp.NUVIO
)

sealed class HomeEvent {
    object OpenFailed : HomeEvent()
    object SurpriseMeNotEnoughHistory : HomeEvent()
}

private const val CONTINUE_WATCHING_MAX = 15
private const val SURPRISE_ME_POOL_SIZE = 20

/**
 * Pantalla de "Recomendado para ti" — mismo dato y misma lógica de
 * orquestación que RecommendationsRowsFragment/RecommendationsHomeFragment
 * en la app de TV (ver plan, sección 4), reconstruida con Compose en vez
 * de Leanback (no tiene sentido en una pantalla táctil).
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    private var recommendations: RecommendationEngine.Recommendations? = null
    private var watchlist: List<TmdbClient.TmdbRecommendation> = emptyList()
    private var continueWatching: List<TmdbClient.TmdbRecommendation> = emptyList()
    private var upcomingEpisodes: List<TmdbClient.TmdbRecommendation> = emptyList()

    private val _uiState = MutableStateFlow(HomeUiState(selectedApp = Preferences.getSelectedApp(context)))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    /** Recalcula todo desde cero — llamado al entrar a la pantalla y en
     * cada ON_RESUME (ver HomeScreen), igual que
     * RecommendationsRowsFragment.onResume. */
    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = RecommendationEngine.compute(context)
            val watchlistItems = Watchlist.fetchMerged(context)
            val historyItems = RecommendationHistory.fetchMerged(context)
            recommendations = result
            watchlist = watchlistItems.map { it.toTmdbRecommendation() }
            continueWatching = historyItems
                .sortedByDescending { it.watchedAt }
                .take(CONTINUE_WATCHING_MAX)
                .map { it.toTmdbRecommendation() }
            upcomingEpisodes = UpcomingEpisodesEngine.compute(watchlistItems, historyItems)
            withContext(Dispatchers.Main) {
                rebuildRows(_uiState.value.category)
            }
        }
    }

    fun setCategory(category: Category) = rebuildRows(category)

    fun setPlayerApp(app: PlayerApp) {
        Preferences.setSelectedApp(context, app)
        _uiState.update { it.copy(selectedApp = app) }
    }

    /** Solo borra estado LOCAL (email verificado + cachés de historial/Mi
     * lista) — a diferencia de "eliminar este dispositivo" en
     * DevicesScreen, NO desvincula el dispositivo del backend, así que no
     * gasta ni libera ningún hueco de la cuota de dispositivos. Pensado
     * para poder probar con otra cuenta sin tener que desvincular nada. */
    fun logout() {
        LicenseManager.clear(context)
        Watchlist.clearLocal(context)
        RecommendationHistory.clearLocal(context)
    }

    private fun rebuildRows(category: Category) {
        val rows = mutableListOf<RowUi>()
        if (category == Category.WATCHLIST) {
            val movies = watchlist.filter { it.type == MediaType.MOVIE }
            val series = watchlist.filter { it.type == MediaType.SERIES }
            if (movies.isNotEmpty()) rows += RowUi("wl_movies", "Películas", movies)
            if (series.isNotEmpty()) rows += RowUi("wl_series", "Series", series)
        } else {
            val wantedMediaPath = if (category == Category.MOVIES) "movie" else "tv"
            val cwForTab = continueWatching.filter { it.mediaPath == wantedMediaPath }
            if (cwForTab.isNotEmpty()) {
                rows += RowUi("continue_watching", "Continuar viendo", cwForTab, isContinueWatching = true)
            }
            val upcomingForTab = if (category == Category.SERIES) upcomingEpisodes else emptyList()
            if (upcomingForTab.isNotEmpty()) {
                rows += RowUi("upcoming_episodes", "Próximos episodios", upcomingForTab)
            }
            val topPicks = recommendations?.let { if (category == Category.MOVIES) it.movieTopPicks else it.seriesTopPicks } ?: emptyList()
            if (topPicks.isNotEmpty()) {
                rows += RowUi("top_picks", "Recomendado para ti", topPicks)
            }
            val seedRows = recommendations?.let { if (category == Category.MOVIES) it.movieRows else it.seriesRows } ?: emptyList()
            seedRows.forEachIndexed { index, seedRow ->
                rows += RowUi("seed_$index", "Porque viste ${seedRow.seedTitle}", seedRow.recommendations)
            }
        }
        _uiState.update { it.copy(isLoading = false, category = category, rows = rows) }
    }

    /** Toca una tarjeta: resuelve el imdbId contra el backend propio (gate
     * de suscripción, ver TmdbClient.resolveCandidate) y abre en la app
     * elegida — mismo camino que RecommendationOpenActivity en la TV, sin
     * la indirección de la URI interna: aquí todo pasa en el mismo
     * proceso. */
    fun openRecommendation(rec: TmdbClient.TmdbRecommendation) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = TmdbCandidate(rec.tmdbId, rec.mediaPath, rec.title, year = null, posterPath = rec.posterPath, overview = rec.overview)
            val match = TmdbClient.resolveCandidate(context, candidate)
            withContext(Dispatchers.Main) {
                if (match != null) StremioLauncher.open(context, match) else _events.emit(HomeEvent.OpenFailed)
            }
        }
    }

    /** Mantener pulsado sobre una tarjeta normal: alterna Mi lista — mismo
     * gesto que el long-press de Leanback en RecommendationsRowsFragment. */
    fun toggleWatchlist(rec: TmdbClient.TmdbRecommendation) {
        val inList = Watchlist.isInList(context, rec.tmdbId, rec.mediaPath)
        if (inList) Watchlist.remove(context, rec.tmdbId, rec.mediaPath)
        else Watchlist.add(context, rec.tmdbId, rec.mediaPath, rec.title, rec.posterPath, rec.overview)
        watchlist = Watchlist.getLocal(context).map { it.toTmdbRecommendation() }
        rebuildRows(_uiState.value.category)
    }

    /** Mantener pulsado sobre una tarjeta de "Continuar viendo": la quita
     * de ahí en vez de tocar Mi lista — mismo criterio que
     * RecommendationsRowsFragment.onRemoveFromContinueWatching. */
    fun removeFromContinueWatching(rec: TmdbClient.TmdbRecommendation) {
        val updated = RecommendationHistory.remove(context, rec.tmdbId, rec.mediaPath)
        continueWatching = updated.sortedByDescending { it.watchedAt }.take(CONTINUE_WATCHING_MAX).map { it.toTmdbRecommendation() }
        rebuildRows(_uiState.value.category)
    }

    /** Puerto de RecommendationsHomeFragment.onSurpriseMeClicked: elige al
     * azar (ponderado por popularidad) un título del fondo de la pestaña
     * activa y lo abre directamente. */
    fun surpriseMe() {
        viewModelScope.launch(Dispatchers.IO) {
            val category = _uiState.value.category
            val recs = recommendations ?: RecommendationEngine.compute(context).also { recommendations = it }
            val pool = (if (category == Category.SERIES) recs.seriesRows else recs.movieRows)
                .flatMap { it.recommendations }
                .distinctBy { it.mediaPath to it.tmdbId }
            if (pool.isEmpty()) {
                _events.emit(HomeEvent.SurpriseMeNotEnoughHistory)
                return@launch
            }
            val topPool = pool.sortedByDescending { it.popularity }.take(SURPRISE_ME_POOL_SIZE)
            val pick = topPool[Random.nextInt(topPool.size)]
            val match = TmdbClient.resolveCandidate(
                context,
                TmdbCandidate(pick.tmdbId, pick.mediaPath, pick.title, year = null, posterPath = pick.posterPath, overview = pick.overview)
            )
            withContext(Dispatchers.Main) {
                if (match != null) StremioLauncher.open(context, match) else _events.emit(HomeEvent.OpenFailed)
            }
        }
    }
}

/**
 * Sin barra superior con el nombre de la app (desperdiciaba una fila
 * entera solo para eso) — las pestañas viven abajo, al estilo
 * Instagram/TikTok, con Películas/Series/Mi lista como contenido
 * principal y una cuarta pestaña "Más" para lo que antes vivía en la
 * barra superior (selector de app de destino, Referidos, Dispositivos).
 */
@Composable
fun HomeScreen(
    onOpenReferral: () -> Unit,
    onOpenDevices: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                HomeEvent.OpenFailed -> "No se pudo abrir ese título — comprueba tu suscripción o vuelve a intentarlo"
                HomeEvent.SurpriseMeNotEnoughHistory -> "Todavía no hay suficiente historial para esto"
            }
            snackbarHostState.showSnackbar(message)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Category.entries.forEach { category ->
                    NavigationBarItem(
                        selected = !showMore && uiState.category == category,
                        onClick = {
                            showMore = false
                            viewModel.setCategory(category)
                        },
                        icon = { Text(emojiFor(category)) },
                        label = { Text(labelFor(category)) }
                    )
                }
                NavigationBarItem(
                    selected = showMore,
                    onClick = { showMore = true },
                    icon = { Text("⚙️") },
                    label = { Text("Más") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (showMore) {
                MorePanel(
                    selectedApp = uiState.selectedApp,
                    onSelectApp = viewModel::setPlayerApp,
                    onOpenReferral = onOpenReferral,
                    onOpenDevices = onOpenDevices,
                    onLogout = {
                        viewModel.logout()
                        onLoggedOut()
                    }
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (uiState.category != Category.WATCHLIST) {
                        Button(
                            onClick = { viewModel.surpriseMe() },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("🎲 Sorpréndeme")
                        }
                    }
                    when {
                        uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        uiState.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Todavía no hay nada que recomendar — abre algún título para empezar",
                                modifier = Modifier.padding(32.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> LazyColumn {
                            items(uiState.rows, key = { it.id }) { row ->
                                RecommendationRow(
                                    title = row.title,
                                    items = row.items,
                                    onClick = viewModel::openRecommendation,
                                    onLongClick = { rec ->
                                        if (row.isContinueWatching) viewModel.removeFromContinueWatching(rec)
                                        else viewModel.toggleWatchlist(rec)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pestaña "Más": lo que antes vivía en la barra superior — elegir la app
 * de destino, acceso a Referidos/Dispositivos, y cerrar sesión. */
@Composable
private fun MorePanel(
    selectedApp: PlayerApp,
    onSelectApp: (PlayerApp) -> Unit,
    onOpenReferral: () -> Unit,
    onOpenDevices: () -> Unit,
    onLogout: () -> Unit
) {
    var confirmingLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Abrir recomendaciones en", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "La app donde se abre cada título que toques",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        PlayerAppSelector(selected = selectedApp, onSelect = onSelectApp)

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        OutlinedButton(onClick = onOpenReferral, modifier = Modifier.fillMaxWidth()) {
            Text("🎁 Referidos")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) {
            Text("📱 Dispositivos")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        OutlinedButton(
            onClick = { confirmingLogout = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("🚪 Cerrar sesión")
        }
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Solo se borra este dispositivo (el email guardado aquí, y el historial/Mi lista en caché) — no se desvincula de tu cuenta ni gastas ningún hueco de dispositivo. Puedes volver a verificar con el mismo email u otro distinto.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    onLogout()
                }) { Text("Cerrar sesión") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun PlayerAppSelector(selected: PlayerApp, onSelect: (PlayerApp) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(PlayerApp.entries) { app ->
            FilterChip(
                selected = app == selected,
                onClick = { onSelect(app) },
                label = { Text(app.label) }
            )
        }
    }
}
