package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VodUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val catalog: VodCatalog? = null,
    val categoryId: String? = null,
    val query: String = ""
)

/** El catalogo de peliculas (o de series) de un panel Xtream: se baja una vez y se queda en memoria. */
class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(VodUiState())
    val state: StateFlow<VodUiState> = _state.asStateFlow()
    private var loadedFor: String? = null

    fun load(source: IptvSource, kind: VodKind) {
        val key = "${source.id}|${kind.name}"
        if (loadedFor == key && _state.value.catalog != null) return
        loadedFor = key
        _state.value = VodUiState()
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = XtreamVod.cached(kind, source.xtreamServer, source.xtreamUser)
                ?: XtreamVod.fetch(sharedHttpClient, kind, source.xtreamServer, source.xtreamUser, source.xtreamPass)
            _state.update { it.copy(loading = false, error = catalog == null, catalog = catalog) }
        }
    }

    fun setCategory(id: String?) = _state.update { it.copy(categoryId = id) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }
}

/** Peliculas o series de una lista Xtream: rejilla de carteles con categorias y busqueda; al pulsar una se abre su ficha. */
@Composable
fun VodTab(source: IptvSource, kind: VodKind, viewModel: VodViewModel = viewModel(key = kind.name)) {
    val state by viewModel.state.collectAsState()
    var opened by remember { mutableStateOf<VodMovie?>(null) }
    LaunchedEffect(source.id, kind) { viewModel.load(source, kind) }

    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        state.error || state.catalog == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(if (kind == VodKind.SERIES) R.string.vod_series_error else R.string.vod_movies_error), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> {
            val catalog = state.catalog!!
            val shown = remember(catalog, state.categoryId, state.query) {
                val q = VodMovie.normalize(state.query)
                catalog.movies.filter { (state.categoryId == null || it.categoryId == state.categoryId) && (q.isEmpty() || it.searchKey.contains(q)) }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
                        placeholder = { Text(stringResource(R.string.vod_search_hint)) }, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    CategoryMenu(catalog, state.categoryId, viewModel::setCategory)
                }
                Text(
                    stringResource(R.string.vod_count, shown.size), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.vod_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp), contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(shown, key = { it.id }) { movie ->
                            Column(Modifier.clickable { opened = movie }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2030))) {
                                    if (movie.posterUrl != null) {
                                        AsyncImage(model = movie.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    }
                                }
                                Text(movie.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    opened?.let { VodDetailDialog(source, kind, it, onDismiss = { opened = null }) }
}

@Composable
private fun CategoryMenu(catalog: VodCatalog, selected: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        WbPillButton(onClick = { open = true }) {
            Text(
                catalog.categories[selected] ?: stringResource(R.string.vod_all_categories),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.vod_all_categories)) }, onClick = { open = false; onPick(null) })
            catalog.categories.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) })
            }
        }
    }
}

/** Ficha de una pelicula o serie: lo que cuenta el panel y el boton de reproducir (o las temporadas y episodios). */
@Composable
private fun VodDetailDialog(source: IptvSource, kind: VodKind, movie: VodMovie, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<VodInfo?>(null) }
    var series by remember { mutableStateOf<SeriesDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var season by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(movie.id) {
        withContext(Dispatchers.IO) {
            if (kind == VodKind.SERIES) {
                val detail = XtreamVod.fetchSeriesDetail(sharedHttpClient, source.xtreamServer, source.xtreamUser, source.xtreamPass, movie.id)
                series = detail
                info = detail?.info
                season = detail?.seasons?.firstOrNull()
            } else {
                info = XtreamVod.fetchMovieInfo(sharedHttpClient, source.xtreamServer, source.xtreamUser, source.xtreamPass, movie.id)
            }
        }
        loading = false
    }

    // El servidor que sirve los streams puede no ser el de la API (lo dice server_info); se pide al reproducir.
    suspend fun streamBase(): String = withContext(Dispatchers.IO) {
        val base = "${source.xtreamServer}/player_api.php?username=${XtreamApiPlaylist.enc(source.xtreamUser)}&password=${XtreamApiPlaylist.enc(source.xtreamPass)}"
        XtreamApiPlaylist.streamBase(sharedHttpClient, base, source.xtreamServer)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun play(series: Boolean, id: Int, extension: String, title: String) {
        scope.launch {
            val url = XtreamVod.playUrl(streamBase(), source.xtreamUser, source.xtreamPass, series, id, extension)
            VodPlayerActivity.open(context, title, url)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = onDismiss) { Text("←  " + stringResource(R.string.iptv_player_back)) }
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    item {
                        Row {
                            val poster = info?.posterUrl ?: movie.posterUrl
                            Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2030))) {
                                if (poster != null) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(movie.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                val meta = listOfNotNull(
                                    info?.year, info?.duration, info?.rating?.let { "★ " + "%.1f".format(it) }, info?.genre
                                ).joinToString("  ·  ")
                                if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                if (kind == VodKind.MOVIES) {
                                    Button(onClick = { play(false, movie.id, movie.extension, movie.name) }, modifier = Modifier.padding(top = 12.dp)) {
                                        Text("▶  " + stringResource(R.string.vod_play))
                                    }
                                }
                            }
                        }
                        info?.plot?.let { Text(it, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp)) }
                        info?.cast?.let { Text(stringResource(R.string.vod_cast, it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
                        info?.director?.let { Text(stringResource(R.string.vod_director, it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    val detail = series
                    if (kind == VodKind.SERIES) {
                        if (detail == null || detail.episodes.isEmpty()) {
                            item { Text(stringResource(R.string.vod_series_no_episodes), modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        } else {
                            item {
                                LazyRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(detail.seasons) { s ->
                                        FilterChip(selected = season == s, onClick = { season = s }, label = { Text(stringResource(R.string.vod_season, s)) })
                                    }
                                }
                            }
                            items(detail.episodesOf(season ?: detail.seasons.first()), key = { it.id }) { ep ->
                                Column(Modifier.fillMaxWidth().clickable { play(true, ep.id, ep.extension, "${movie.name} · ${ep.title.ifEmpty { "S${ep.season}E${ep.number}" }}") }.padding(vertical = 10.dp)) {
                                    Text("${ep.number}. ${ep.title}".trim(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    ep.duration?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
