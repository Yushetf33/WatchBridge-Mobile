package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenReferral: () -> Unit,
    onOpenDevices: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

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
        topBar = {
            TopAppBar(
                title = { Text("TvRecommendationBridge") },
                actions = {
                    TextButton(onClick = onOpenReferral) { Text("Referidos") }
                    TextButton(onClick = onOpenDevices) { Text("Dispositivos") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = uiState.category.ordinal) {
                Category.entries.forEach { category ->
                    Tab(
                        selected = uiState.category == category,
                        onClick = { viewModel.setCategory(category) },
                        text = { Text(labelFor(category)) }
                    )
                }
            }

            PlayerAppSelector(selected = uiState.selectedApp, onSelect = viewModel::setPlayerApp)

            if (uiState.category != Category.WATCHLIST) {
                Button(
                    onClick = { viewModel.surpriseMe() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Sorpréndeme")
                }
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Todavía no hay nada que recomendar — abre algún título para empezar",
                        modifier = Modifier.padding(32.dp)
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

/** Elegir a qué app se abre cada recomendación — no existe un equivalente
 * de "Ajustes" completo todavía (ver plan), así que el selector vive aquí
 * mismo, siempre visible en vez de escondido en otra pantalla. */
@Composable
private fun PlayerAppSelector(selected: PlayerApp, onSelect: (PlayerApp) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
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
