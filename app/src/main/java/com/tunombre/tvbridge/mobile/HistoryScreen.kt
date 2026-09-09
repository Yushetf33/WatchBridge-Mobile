package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private const val DAY_MS = 24 * 60 * 60 * 1000L

data class HistoryUiState(
    val isLoading: Boolean = true,
    val buckets: List<RowUi> = emptyList()
)

/**
 * Historial completo — todo lo detectado como visto de verdad (abierto
 * desde esta app o desde cualquier otro dispositivo vinculado al mismo
 * email), agrupado por cercanía en el tiempo. Puerto directo de
 * HistoryRowsFragment en la app de TV: mismo agrupado por buckets, mismo
 * gesto (tocar abre, mantener pulsado alterna Mi lista) — a diferencia de
 * "Continuar viendo" en Home (que solo muestra los últimos N), esta
 * pantalla es el historial completo para consultarlo.
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val items = RecommendationHistory.fetchMerged(context)
            val buckets = LinkedHashMap<String, MutableList<TmdbClient.TmdbRecommendation>>()
            for (item in items) {
                buckets.getOrPut(bucketLabel(item.watchedAt)) { mutableListOf() }.add(item.toTmdbRecommendation())
            }
            val rows = buckets.entries.mapIndexed { index, (label, list) -> RowUi("bucket_$index", label, list) }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false, buckets = rows) }
            }
        }
    }

    fun openRecommendation(rec: TmdbClient.TmdbRecommendation, onFailed: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val candidate = TmdbCandidate(rec.tmdbId, rec.mediaPath, rec.title, year = null, posterPath = rec.posterPath, overview = rec.overview)
            val match = TmdbClient.resolveCandidate(context, candidate)
            withContext(Dispatchers.Main) {
                if (match != null) StremioLauncher.open(context, match) else onFailed()
            }
        }
    }

    fun toggleWatchlist(rec: TmdbClient.TmdbRecommendation) {
        val inList = Watchlist.isInList(context, rec.tmdbId, rec.mediaPath)
        if (inList) Watchlist.remove(context, rec.tmdbId, rec.mediaPath)
        else Watchlist.add(context, rec.tmdbId, rec.mediaPath, rec.title, rec.posterPath, rec.overview)
    }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Entradas sin fecha (watchedAt=0, de antes de que esto existiera) van
     * directas a "Antes" en vez de colarse en "Hoy" por culpa de un 0. */
    private fun bucketLabel(watchedAt: Long): String {
        if (watchedAt <= 0L) return context.getString(R.string.history_bucket_earlier)
        val diffDays = (startOfDay(System.currentTimeMillis()) - startOfDay(watchedAt)) / DAY_MS
        return when {
            diffDays <= 0L -> context.getString(R.string.history_bucket_today)
            diffDays == 1L -> context.getString(R.string.history_bucket_yesterday)
            diffDays in 2..6 -> context.getString(R.string.history_bucket_this_week)
            else -> context.getString(R.string.history_bucket_earlier)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.buckets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.history_empty),
                        modifier = Modifier.padding(32.dp),
                        textAlign = TextAlign.Center
                    )
                }
                else -> LazyColumn {
                    items(uiState.buckets, key = { it.id }) { bucket ->
                        RecommendationRow(
                            title = bucket.title,
                            items = bucket.items,
                            onClick = { rec -> viewModel.openRecommendation(rec) {} },
                            onLongClick = { rec -> viewModel.toggleWatchlist(rec) }
                        )
                    }
                }
            }
        }
    }
}
