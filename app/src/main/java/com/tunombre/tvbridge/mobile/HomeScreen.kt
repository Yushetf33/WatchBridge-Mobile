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
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/** Pestañas de la barra de abajo — Historial es una más al mismo nivel que
 * Películas/Series/Mi lista (no algo escondido en "Más"), y "Más" queda
 * solo para lo que de verdad es ajustes (app de destino, Referidos,
 * Dispositivos, cerrar sesión). */
enum class BottomTab { MOVIES, SERIES, WATCHLIST, HISTORY, CALENDAR, MORE }

private fun BottomTab.toCategory(): Category? = when (this) {
    BottomTab.MOVIES -> Category.MOVIES
    BottomTab.SERIES -> Category.SERIES
    BottomTab.WATCHLIST -> Category.WATCHLIST
    else -> null
}

@Composable
private fun labelFor(tab: BottomTab) = when (tab) {
    BottomTab.MOVIES -> stringResource(R.string.tab_movies)
    BottomTab.SERIES -> stringResource(R.string.tab_series)
    BottomTab.WATCHLIST -> stringResource(R.string.tab_watchlist)
    BottomTab.HISTORY -> stringResource(R.string.history_title)
    BottomTab.CALENDAR -> stringResource(R.string.tab_calendar)
    BottomTab.MORE -> stringResource(R.string.tab_more)
}

private fun iconFor(tab: BottomTab) = when (tab) {
    BottomTab.MOVIES -> R.drawable.ic_nav_movies
    BottomTab.SERIES -> R.drawable.ic_nav_series
    BottomTab.WATCHLIST -> R.drawable.ic_nav_watchlist
    BottomTab.HISTORY -> R.drawable.ic_nav_history
    BottomTab.CALENDAR -> R.drawable.ic_nav_calendar
    BottomTab.MORE -> R.drawable.ic_nav_settings
}

data class RowUi(
    val id: String,
    val title: String,
    val items: List<TmdbClient.TmdbRecommendation>,
    val isContinueWatching: Boolean = false,
    // Título "semilla" limpio (sin el "Porque viste " delante) para las
    // filas de recomendaciones por título visto — DetailScreen lo usa para
    // la línea "Recomendada porque viste: X" cuando se llega desde una de
    // estas filas. Null en el resto (Continuar viendo, Top picks, etc.),
    // donde no hay un único título que explique la recomendación.
    val seedTitle: String? = null
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val category: Category = Category.MOVIES,
    val rows: List<RowUi> = emptyList(),
    val selectedApp: PlayerApp = PlayerApp.NUVIO,
    val offline: Boolean = false,
    // Genero elegido para la pestana activa (Peliculas o Series), null = sin filtro.
    val genre: TmdbClient.Genre? = null,
    // Fase 2 de refresh() en curso: las filas de recomendaciones todavía
    // están llegando (no pintar "nada que recomendar" antes de tiempo).
    val loadingMore: Boolean = false
)

sealed class HomeEvent {
    object OpenFailed : HomeEvent()
    object SurpriseMeNotEnoughHistory : HomeEvent()
    data class UpdateCheckResult(val outcome: UpdateChecker.CheckOutcome) : HomeEvent()
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

    // Lo mas popular / mejor valorado de cada genero elegido (discover de TMDb), guardado para no repetir la peticion.
    private val discoverCache = HashMap<String, Pair<List<TmdbClient.TmdbRecommendation>, List<TmdbClient.TmdbRecommendation>>>()

    private val _uiState = MutableStateFlow(HomeUiState(selectedApp = Preferences.getSelectedApp(context)))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    /** Recalcula todo desde cero — llamado al entrar a la pantalla y en
     * cada ON_RESUME (ver HomeScreen), igual que
     * RecommendationsRowsFragment.onResume. */
    // Cada refresh() lleva su número: si mientras carga se lanza otro (p.ej.
    // dos ON_RESUME seguidos), el viejo deja de escribir estado. Sin esto,
    // el "recommendations = null" del refresh nuevo podía borrar lo que el
    // viejo acababa de calcular y perder filas (como "Recomendado para ti").
    private var refreshGeneration = 0

    fun refresh() {
        val generation = ++refreshGeneration
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val online = isOnline()

            // Fase 1: historial y Mi lista EN PARALELO (antes uno detrás de
            // otro, y el historial se pedía dos veces) y se pintan ya
            // "Continuar viendo" y Mi lista, sin esperar a TMDb.
            val historyDeferred = async { RecommendationHistory.fetchMerged(context) }
            val watchlistDeferred = async { Watchlist.fetchMerged(context) }
            val historyItems = historyDeferred.await()
            val watchlistItems = watchlistDeferred.await()
            watchlist = watchlistItems.map { it.toTmdbRecommendation() }
            continueWatching = historyItems
                .sortedByDescending { it.watchedAt }
                .take(CONTINUE_WATCHING_MAX)
                .map { it.toTmdbRecommendation() }
            if (generation != refreshGeneration) return@launch
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(loadingMore = true) }
                rebuildRows(_uiState.value.category)
                SplashGate.ready = true
            }

            // Fase 2: películas, series y próximos episodios a la vez, y
            // cada bloque se pinta en cuanto llega (antes la pantalla
            // esperaba a tenerlo todo). Las semillas de cada bloque, además,
            // se piden en paralelo (ver RecommendationEngine).
            coroutineScope {
                launch {
                    val (picks, rows) = RecommendationEngine.computeForType(historyItems, "movie")
                    if (generation != refreshGeneration) return@launch
                    updateRecommendations { it.copy(movieTopPicks = picks, movieRows = rows) }
                    withContext(Dispatchers.Main) { rebuildRows(_uiState.value.category) }
                }
                launch {
                    val (picks, rows) = RecommendationEngine.computeForType(historyItems, "tv")
                    if (generation != refreshGeneration) return@launch
                    updateRecommendations { it.copy(seriesTopPicks = picks, seriesRows = rows) }
                    withContext(Dispatchers.Main) { rebuildRows(_uiState.value.category) }
                }
                launch {
                    val upcoming = UpcomingEpisodesEngine.compute(watchlistItems, historyItems)
                    if (generation != refreshGeneration) return@launch
                    upcomingEpisodes = upcoming
                    withContext(Dispatchers.Main) { rebuildRows(_uiState.value.category) }
                }
            }
            if (generation != refreshGeneration) return@launch
            withContext(Dispatchers.Main) {
                // Sin red y sin nada que enseñar: avisarlo en vez de decir
                // "todavía no hay nada que recomendar", que engaña.
                _uiState.update { it.copy(loadingMore = false, offline = !online && it.rows.isEmpty()) }
            }
        }
    }

    // Películas y series terminan en hilos distintos: sin esto, dos
    // lecturas-y-escrituras a la vez podían pisarse y perder un bloque.
    @Synchronized
    private fun updateRecommendations(
        change: (RecommendationEngine.Recommendations) -> RecommendationEngine.Recommendations
    ) {
        val current = recommendations
            ?: RecommendationEngine.Recommendations(emptyList(), emptyList(), emptyList(), emptyList())
        recommendations = change(current)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun setCategory(category: Category) {
        // Al entrar en Mi lista, releer la caché LOCAL (SharedPreferences,
        // sin red) antes de reconstruir filas — barato, y evita que un
        // toggle hecho fuera de Home (p.ej. desde DetailScreen) tarde hasta
        // el siguiente refresh() de red en reflejarse aquí.
        if (category == Category.WATCHLIST) {
            watchlist = Watchlist.getLocal(context).map { it.toTmdbRecommendation() }
        }
        rebuildRows(category)
    }

    private fun mediaPathOf(category: Category) = if (category == Category.MOVIES) "movie" else "tv"

    /** Los generos para elegir en la pestana activa (bloqueante la primera vez: llamar desde un hilo de fondo). */
    suspend fun genresForCurrentTab(): List<TmdbClient.Genre> = withContext(Dispatchers.IO) {
        TmdbClient.fetchGenres(context, mediaPathOf(_uiState.value.category))
    }

    /** Elegir (o quitar, con null) el genero de la pestana activa: filtra las filas y trae lo mas popular y mejor
     * valorado de ese genero de TMDb. */
    fun setGenre(genre: TmdbClient.Genre?) {
        val category = _uiState.value.category
        if (category == Category.WATCHLIST) return
        val mediaPath = mediaPathOf(category)
        GenreFilter.set(context, mediaPath, genre)
        rebuildRows(category)
        if (genre == null) return
        val key = "$mediaPath:${genre.id}"
        if (discoverCache.containsKey(key)) return
        viewModelScope.launch(Dispatchers.IO) {
            val popular = TmdbClient.discover(mediaPath, genre.id, topRated = false)
            val topRated = TmdbClient.discover(mediaPath, genre.id, topRated = true)
            if (popular.isEmpty() && topRated.isEmpty()) return@launch
            discoverCache[key] = popular to topRated
            withContext(Dispatchers.Main) {
                // Solo si el usuario sigue en la misma pestana y con el mismo genero.
                if (_uiState.value.category == category && GenreFilter.get(context, mediaPath)?.id == genre.id) rebuildRows(category)
            }
        }
    }

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
            if (movies.isNotEmpty()) rows += RowUi("wl_movies", context.getString(R.string.tab_movies), movies)
            if (series.isNotEmpty()) rows += RowUi("wl_series", context.getString(R.string.tab_series), series)
        } else {
            val wantedMediaPath = if (category == Category.MOVIES) "movie" else "tv"
            val genre = GenreFilter.get(context, wantedMediaPath)
            val cwForTab = continueWatching.filter { it.mediaPath == wantedMediaPath }
            if (cwForTab.isNotEmpty()) {
                rows += RowUi("continue_watching", context.getString(R.string.row_continue_watching), cwForTab, isContinueWatching = true)
            }
            val upcomingForTab = if (category == Category.SERIES) upcomingEpisodes else emptyList()
            if (upcomingForTab.isNotEmpty()) {
                rows += RowUi("upcoming_episodes", context.getString(R.string.row_upcoming_episodes), upcomingForTab)
            }
            // Con un genero elegido todo lo que se recomienda se limita a ese genero, y se le suman las filas de "lo mas
            // popular / mejor valorado" de TMDb de ese genero: asi hay contenido aunque el historial tenga poco.
            val topPicks = GenreFilter.filter(
                recommendations?.let { if (category == Category.MOVIES) it.movieTopPicks else it.seriesTopPicks } ?: emptyList(),
                genre
            )
            if (topPicks.isNotEmpty()) {
                val title = if (genre == null) context.getString(R.string.row_top_picks)
                else context.getString(R.string.row_top_picks_genre, genre.name)
                rows += RowUi("top_picks", title, topPicks)
            }
            val genreRows = if (genre == null) null else discoverCache["$wantedMediaPath:${genre.id}"]
            if (genre != null && genreRows != null) {
                val seen = (continueWatching.map { it.tmdbId to it.mediaPath } + topPicks.map { it.tmdbId to it.mediaPath }).toSet()
                val popular = genreRows.first.filter { (it.tmdbId to it.mediaPath) !in seen }
                val topRated = genreRows.second.filter { (it.tmdbId to it.mediaPath) !in seen }
                if (popular.isNotEmpty()) rows += RowUi("genre_popular", context.getString(R.string.genre_row_popular, genre.name), popular)
                if (topRated.isNotEmpty()) rows += RowUi("genre_top_rated", context.getString(R.string.genre_row_top_rated, genre.name), topRated)
            }
            val seedRows = GenreFilter.filterRows(
                recommendations?.let { if (category == Category.MOVIES) it.movieRows else it.seriesRows } ?: emptyList(),
                genre
            )
            seedRows.forEachIndexed { index, seedRow ->
                rows += RowUi(
                    "seed_$index",
                    context.getString(R.string.row_because_you_watched, seedRow.seedTitle),
                    seedRow.recommendations,
                    seedTitle = seedRow.seedTitle
                )
            }
        }
        val tabGenre = if (category == Category.WATCHLIST) null else GenreFilter.get(context, mediaPathOf(category))
        _uiState.update { it.copy(isLoading = false, category = category, rows = rows, genre = tabGenre) }
    }

    /** Mantener pulsado sobre una tarjeta normal: alterna Mi lista — mismo
     * gesto que el long-press de Leanback en RecommendationsRowsFragment. */
    fun toggleWatchlist(rec: TmdbClient.TmdbRecommendation) {
        val inList = Watchlist.isInList(context, rec.tmdbId, rec.mediaPath)
        if (inList) Watchlist.remove(context, rec.tmdbId, rec.mediaPath)
        else Watchlist.add(context, rec.tmdbId, rec.mediaPath, rec.title, rec.posterPath, rec.overview)
        android.widget.Toast.makeText(
            context,
            if (inList) R.string.watchlist_removed else R.string.watchlist_added,
            android.widget.Toast.LENGTH_SHORT
        ).show()
        watchlist = Watchlist.getLocal(context).map { it.toTmdbRecommendation() }
        rebuildRows(_uiState.value.category)
    }

    /** Vuelta a Inicio desde otra pantalla (p.ej. la ficha, donde se pudo
     * anadir algo a Mi lista): relee de local, sin red, para que Inicio y Mi
     * lista reflejen ya el cambio sin esperar a otra carga. */
    fun refreshLocal() {
        watchlist = Watchlist.getLocal(context).map { it.toTmdbRecommendation() }
        if (!_uiState.value.isLoading) rebuildRows(_uiState.value.category)
    }

    /** Mantener pulsado sobre una tarjeta de "Continuar viendo": la quita
     * de ahí en vez de tocar Mi lista. OJO: RecommendationHistory.remove()
     * devuelve el historial SOLO LOCAL de este dispositivo (no el fusionado
     * con el backend que se usa para pintar la fila) — en el móvil, donde
     * casi todo "Continuar viendo" viene de la TV y no del propio
     * dispositivo, usar ese valor de vuelta vaciaba la fila entera al
     * quitar cualquier título. En vez de eso, filtramos la lista fusionada
     * que ya tenemos en memoria. */
    fun removeFromContinueWatching(rec: TmdbClient.TmdbRecommendation) {
        RecommendationHistory.remove(context, rec.tmdbId, rec.mediaPath)
        continueWatching = continueWatching.filterNot { it.tmdbId == rec.tmdbId && it.mediaPath == rec.mediaPath }
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

    /** Botón manual "Buscar actualizaciones" en la pestaña Más — a
     * diferencia del chequeo silencioso al abrir la app (ver TvBridgeApp),
     * este ignora el límite de frecuencia y espera a instalar directamente
     * si encuentra una versión nueva, ya que el usuario lo ha pedido en
     * primer plano (mismo patrón que MainActivity.performCheckForUpdate en
     * la TV). */
    fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = UpdateChecker.checkNow(context)
            withContext(Dispatchers.Main) { _events.emit(HomeEvent.UpdateCheckResult(outcome)) }
            if (outcome is UpdateChecker.CheckOutcome.DownloadStarted) {
                UpdateChecker.awaitDownloadAndInstall(context)
            }
        }
    }
}

/**
 * Sin barra superior con el nombre de la app (desperdiciaba una fila
 * entera solo para eso) — las pestañas viven abajo, al estilo
 * Instagram/TikTok: Películas/Series/Mi lista/Historial como contenido
 * principal, y "Más" para lo que de verdad es ajustes (app de destino,
 * Referidos, Dispositivos, cerrar sesión).
 */
@Composable
fun HomeScreen(
    onOpenReferral: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTraktImport: () -> Unit,
    onOpenDetail: (mediaPath: String, tmdbId: Int, seedTitle: String?) -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
    historyViewModel: HistoryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val historyState by historyViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(BottomTab.MOVIES) }

    val openFailedMessage = stringResource(R.string.home_open_failed)
    val notEnoughHistoryMessage = stringResource(R.string.home_not_enough_history)
    val updateUpToDateMessage = stringResource(R.string.update_check_up_to_date, BuildConfig.VERSION_NAME)
    val updateNetworkErrorMessage = stringResource(R.string.update_check_network_error)
    LaunchedEffect(Unit) { viewModel.refresh(); historyViewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                HomeEvent.OpenFailed -> openFailedMessage
                HomeEvent.SurpriseMeNotEnoughHistory -> notEnoughHistoryMessage
                is HomeEvent.UpdateCheckResult -> when (val outcome = event.outcome) {
                    is UpdateChecker.CheckOutcome.UpToDate -> updateUpToDateMessage
                    is UpdateChecker.CheckOutcome.DownloadStarted ->
                        context.getString(R.string.update_check_downloading, outcome.version)
                    is UpdateChecker.CheckOutcome.NetworkError -> updateNetworkErrorMessage
                }
            }
            snackbarHostState.showSnackbar(message)
        }
    }
    // OJO: LocalLifecycleOwner.current AQUÍ es el del NavBackStackEntry de
    // esta pantalla (lo inyecta Navigation-Compose), no el de la Activity
    // — pasa por ON_PAUSE/ON_RESUME cada vez que se navega a otra pantalla
    // por encima (p.ej. DetailScreen) y se vuelve atrás, aunque no se haya
    // salido de la app. Enganchado ahí, cada "volver" desde una ficha
    // disparaba una recarga completa (red: TMDb + backend), tan lenta como
    // abrir la app entera — justo lo que se quiere evitar. Se usa el
    // lifecycle de la Activity en su lugar, que solo pasa por ON_RESUME de
    // verdad al reabrir la app (arranque en frío, o volver de Nuvio/Stremio
    // tras ver algo — que sí interesa refrescar).
    val activity = context as? androidx.activity.ComponentActivity
    DisposableEffect(activity) {
        if (activity == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
                historyViewModel.refresh()
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // Al volver a esta pantalla desde otra (p.ej. la ficha) se relee lo local.
    // Aqui SI interesa el lifecycle del NavBackStackEntry (ver el OJO de
    // arriba: es justo lo que se quiere, y no hace red).
    val entryLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(entryLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshLocal()
        }
        entryLifecycle.addObserver(observer)
        onDispose { entryLifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                BottomTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            tab.toCategory()?.let { viewModel.setCategory(it) }
                        },
                        icon = { Icon(painterResource(iconFor(tab)), contentDescription = null, modifier = Modifier.size(24.dp)) },
                        label = {
                            // Una linea, sin partir palabras: el tamano baja solo si la
                            // etiqueta no cabe en el ancho de cada pestana (movil estrecho).
                            androidx.compose.foundation.text.BasicText(
                                labelFor(tab),
                                style = androidx.compose.ui.text.TextStyle(color = androidx.compose.material3.LocalContentColor.current),
                                maxLines = 1,
                                softWrap = false,
                                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp)
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color(0x1FFFFFFF),
                            unselectedIconColor = Color(0xFFA9B0BD),
                            unselectedTextColor = Color(0xFFA9B0BD)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                BottomTab.MORE -> MorePanel(
                    selectedApp = uiState.selectedApp,
                    onSelectApp = viewModel::setPlayerApp,
                    onOpenReferral = onOpenReferral,
                    onOpenDevices = onOpenDevices,
                    onOpenTraktImport = onOpenTraktImport,
                    onCheckForUpdate = viewModel::checkForUpdate,
                    onLogout = {
                        viewModel.logout()
                        onLoggedOut()
                    }
                )
                BottomTab.CALENDAR -> CalendarContent(onOpenDetail = { mediaPath, tmdbId -> onOpenDetail(mediaPath, tmdbId, null) })
                BottomTab.HISTORY -> HistoryContent(
                    uiState = historyState,
                    onClick = { rec -> onOpenDetail(rec.mediaPath, rec.tmdbId, null) },
                    onLongClick = historyViewModel::toggleWatchlist
                )
                else -> Column(Modifier.fillMaxSize()) {
                    when {
                        uiState.isLoading || (uiState.rows.isEmpty() && uiState.loadingMore) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        uiState.rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    stringResource(if (uiState.offline) R.string.home_offline_state else R.string.home_empty_state),
                                    modifier = Modifier.padding(32.dp),
                                    textAlign = TextAlign.Center
                                )
                                if (uiState.offline) {
                                    Button(onClick = { viewModel.refresh() }) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                        else -> LazyColumn {
                            if (uiState.category != Category.WATCHLIST) {
                                item(key = "genre_button") {
                                    GenreButton(genre = uiState.genre, loadGenres = { viewModel.genresForCurrentTab() }, onPick = viewModel::setGenre)
                                }
                            }
                            // Cabecera destacada (no en Mi lista): los primeros titulos de
                            // la primera fila de recomendaciones (o, si no, de la primera).
                            if (uiState.category != Category.WATCHLIST) {
                                val heroRow = uiState.rows.firstOrNull { !it.isContinueWatching } ?: uiState.rows.first()
                                item(key = "hero") {
                                    HeroCarousel(
                                        items = heroRow.items.take(5),
                                        onOpen = { rec -> onOpenDetail(rec.mediaPath, rec.tmdbId, heroRow.seedTitle) },
                                        onToggleWatchlist = viewModel::toggleWatchlist,
                                        onSurprise = { viewModel.surpriseMe() }
                                    )
                                }
                            }
                            items(uiState.rows, key = { it.id }) { row ->
                                RecommendationRow(
                                    title = row.title,
                                    items = row.items,
                                    showTypeBadge = uiState.category == Category.WATCHLIST,
                                    onClick = { rec -> onOpenDetail(rec.mediaPath, rec.tmdbId, row.seedTitle) },
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

/** Boton "Genero: X" (arriba a la derecha de Peliculas y Series): abre la lista de generos de TMDb con "Ninguno" primero. */
@Composable
private fun GenreButton(
    genre: TmdbClient.Genre?,
    loadGenres: suspend () -> List<TmdbClient.Genre>,
    onPick: (TmdbClient.Genre?) -> Unit
) {
    var genres by remember { mutableStateOf<List<TmdbClient.Genre>?>(null) }
    var open by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        OutlinedButton(onClick = {
            scope.launch {
                val loaded = loadGenres()
                if (loaded.isEmpty()) failed = true else { genres = loaded; open = true }
            }
        }) {
            Text(stringResource(R.string.genre_button, genre?.name ?: stringResource(R.string.genre_none)))
        }
    }
    if (open) {
        val list = genres.orEmpty()
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.genre_picker_title)) },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        TextButton(onClick = { open = false; onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.genre_none), fontWeight = if (genre == null) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    items(list) { g ->
                        TextButton(onClick = { open = false; onPick(g) }, modifier = Modifier.fillMaxWidth()) {
                            Text(g.name, fontWeight = if (genre?.id == g.id) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            text = { Text(stringResource(R.string.genre_load_failed)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(android.R.string.ok)) } }
        )
    }
}

/** Pestaña "Más": elegir la app de destino, acceso a Referidos/Dispositivos,
 * y cerrar sesión. */
@Composable
private fun MorePanel(
    selectedApp: PlayerApp,
    onSelectApp: (PlayerApp) -> Unit,
    onOpenReferral: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenTraktImport: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onLogout: () -> Unit
) {
    var confirmingLogout by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.more_open_in_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.more_open_in_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )
        PlayerAppSelector(selected = selectedApp, onSelect = onSelectApp)

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        OutlinedButton(onClick = onOpenReferral, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.more_referral_button))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.more_devices_button))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenTraktImport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.more_trakt_import_button))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCheckForUpdate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.more_check_update_button))
        }
        Spacer(Modifier.height(10.dp))
        val moreContext = androidx.compose.ui.platform.LocalContext.current
        OutlinedButton(
            onClick = {
                androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                    .launchUrl(moreContext, android.net.Uri.parse(PRIVACY_POLICY_URL))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.privacy_policy_link))
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

        OutlinedButton(
            onClick = { confirmingLogout = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text(stringResource(R.string.more_logout_button))
        }
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text(stringResource(R.string.logout_confirm_title)) },
            text = { Text(stringResource(R.string.logout_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.logout_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) { Text(stringResource(R.string.action_cancel)) }
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
