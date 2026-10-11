package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SeriesColor = Color(0xFF5B8DEF)
private val MovieColor = Color(0xFFF5A623)
private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.US)

data class CalendarUiState(
    val loading: Boolean = true,
    val events: List<TmdbClient.ScheduleEvent> = emptyList(),
    val error: Boolean = false
)

/** Estrenos de lo que el usuario sigue: episodios de las series de su historial
 * y de Mi lista, y las peliculas de Mi lista. Todo desde TMDb (ver
 * TmdbClient.fetchSchedule), sin pasar por el backend. */
class CalendarViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    // Titulos ocultos del calendario ("tv:1396" -> "Breaking Bad"), guardados en local.
    private val prefs get() = context.getSharedPreferences("tvbridge_prefs", android.content.Context.MODE_PRIVATE)
    private val _hidden = MutableStateFlow(readHidden())
    val hidden: StateFlow<Map<String, String>> = _hidden.asStateFlow()

    private fun readHidden(): Map<String, String> = try {
        val json = org.json.JSONObject(prefs.getString(KEY_HIDDEN, null) ?: "{}")
        json.keys().asSequence().associateWith { json.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun saveHidden(map: Map<String, String>) {
        prefs.edit().putString(KEY_HIDDEN, org.json.JSONObject(map).toString()).apply()
        _hidden.value = map
    }

    fun hide(event: TmdbClient.ScheduleEvent) = saveHidden(_hidden.value + ("${event.mediaPath}:${event.tmdbId}" to event.title))

    fun unhide(key: String) = saveHidden(_hidden.value - key)

    private companion object {
        const val KEY_HIDDEN = "calendar_hidden"
    }

    fun load() {
        _state.update { it.copy(loading = it.events.isEmpty(), error = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val tracked = LinkedHashMap<String, Triple<Int, String, Pair<String, String?>>>()
            for (w in Watchlist.getLocal(context)) tracked.putIfAbsent("${w.mediaPath}:${w.tmdbId}", Triple(w.tmdbId, w.mediaPath, w.title to w.posterPath))
            for (h in RecommendationHistory.getAll(context)) {
                if (h.mediaPath == "tv") tracked.putIfAbsent("tv:${h.tmdbId}", Triple(h.tmdbId, "tv", h.title to h.posterPath))
            }
            val pool = Executors.newFixedThreadPool(6)
            val events = try {
                tracked.values.take(60).map { (id, path, info) ->
                    pool.submit(Callable { TmdbClient.fetchSchedule(id, path, info.first, info.second) })
                }.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
            } finally {
                pool.shutdown()
            }
            withContext(Dispatchers.Main) {
                _state.update { CalendarUiState(loading = false, events = events.sortedBy { it.date }, error = tracked.isNotEmpty() && events.isEmpty()) }
            }
        }
    }
}

private fun startOfMonth(offsetMonths: Int): Calendar = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    add(Calendar.MONTH, offsetMonths)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** Calendario mensual (estilo Google Calendar): recuadros por dia con puntos
 * de color por estreno (azul series, ambar peliculas; miniaturas en tablet) y,
 * debajo, el detalle del dia seleccionado. */
@Composable
fun CalendarContent(
    onOpenDetail: (mediaPath: String, tmdbId: Int) -> Unit,
    viewModel: CalendarViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val hidden by viewModel.hidden.collectAsState()
    var showHiddenDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.load() }

    var monthOffset by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(0) } // 0 todo, 1 series, 2 peliculas
    val todayIso = remember { ISO.format(Date()) }
    var selectedIso by remember { mutableStateOf(todayIso) }
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    val month = startOfMonth(monthOffset)
    val monthTitle = SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(month.time)
        .replaceFirstChar { it.uppercase() }
    val filtered = state.events.filter { "${it.mediaPath}:${it.tmdbId}" !in hidden }.filter { filter == 0 || (filter == 1 && it.mediaPath == "tv") || (filter == 2 && it.mediaPath == "movie") }
    val byDate = filtered.groupBy { it.date }

    // Al cambiar de mes se selecciona su primer dia con estrenos (o el dia 1; en el
    // mes actual, hoy) — si no, el detalle se quedaba en un dia de otro mes.
    fun goToMonth(newOffset: Int) {
        monthOffset = newOffset
        val prefix = SimpleDateFormat("yyyy-MM", Locale.US).format(startOfMonth(newOffset).time)
        selectedIso = when {
            newOffset == 0 -> todayIso
            else -> filtered.map { it.date }.filter { it.startsWith(prefix) }.minOrNull() ?: "$prefix-01"
        }
    }

    // Cuadricula: la semana empieza en el primer dia segun la region.
    val firstDow = month.firstDayOfWeek
    val leading = (month.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = leading + daysInMonth
    val rows = (cells + 6) / 7

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { goToMonth(monthOffset - 1) }) { Text("\u2039", fontSize = 26.sp) }
                Text(monthTitle, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { goToMonth(monthOffset + 1) }) { Text("\u203A", fontSize = 26.sp) }
            }
            // Fila desplazable: en un movil estrecho no caben chips + botones y el texto
            // se partia ("Tod/ay"); asi cada uno mantiene su ancho y se desliza.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(stringResource(R.string.calendar_filter_all), stringResource(R.string.tab_series), stringResource(R.string.tab_movies)).forEachIndexed { i, label ->
                    WbTab(filter == i, label, { filter = i })
                }
                TextButton(onClick = { goToMonth(0) }) { Text(stringResource(R.string.calendar_today), maxLines = 1, softWrap = false) }
            }
            // Cabecera de dias de la semana
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                val names = SimpleDateFormat("EEEEE", Locale.getDefault())
                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, firstDow) }
                repeat(7) {
                    Text(names.format(cal.time).uppercase(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
            }
        }
        items(rows) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                repeat(7) { col ->
                    val day = row * 7 + col - leading + 1
                    if (day < 1 || day > daysInMonth) {
                        Spacer(Modifier.weight(1f).aspectRatio(if (isTablet) 0.95f else 0.85f))
                    } else {
                        val iso = String.format(Locale.US, "%04d-%02d-%02d", month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1, day)
                        DayCell(
                            day = day,
                            events = byDate[iso].orEmpty(),
                            isToday = iso == todayIso,
                            isSelected = iso == selectedIso,
                            isTablet = isTablet,
                            modifier = Modifier.weight(1f),
                            onClick = { selectedIso = iso }
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            val selected = byDate[selectedIso].orEmpty()
            val label = runCatching { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(ISO.parse(selectedIso)!!) }.getOrDefault(selectedIso)
                .replaceFirstChar { it.uppercase() }
            Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error -> Text(stringResource(R.string.calendar_loading_error), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.events.isEmpty() -> Text(stringResource(R.string.calendar_empty_hint), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                selected.isEmpty() -> Text(stringResource(R.string.calendar_empty_day), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(byDate[selectedIso].orEmpty()) { event ->
            EventRow(event, onHide = { viewModel.hide(event) }) { onOpenDetail(event.mediaPath, event.tmdbId) }
        }
        item {
            // Al final de la lista, siempre a la vista (en la fila de filtros, en un
            // movil estrecho quedaba fuera de pantalla).
            if (hidden.isNotEmpty()) {
                TextButton(onClick = { showHiddenDialog = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.calendar_hidden_button, hidden.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHiddenDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHiddenDialog = false },
            title = { Text(stringResource(R.string.calendar_hidden_title)) },
            text = {
                Column {
                    hidden.entries.forEach { (key, name) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { viewModel.unhide(key) }) { Text(stringResource(R.string.calendar_show)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHiddenDialog = false }) { Text(stringResource(R.string.calendar_close)) } }
        )
    }
    // Sin ocultos ya no hay nada que gestionar: se cierra solo.
    LaunchedEffect(hidden.isEmpty()) { if (hidden.isEmpty()) showHiddenDialog = false }
}

@Composable
private fun DayCell(
    day: Int,
    events: List<TmdbClient.ScheduleEvent>,
    isToday: Boolean,
    isSelected: Boolean,
    isTablet: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .aspectRatio(if (isTablet) 0.95f else 0.85f)
            .padding(2.dp)
            .clip(shape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .then(if (isToday) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Text(
            day.toString(),
            modifier = Modifier.align(Alignment.TopStart),
            fontSize = 12.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (events.isNotEmpty()) {
            if (isTablet) {
                // Tablet: carteles en miniatura (los mismos de las pestanas Peliculas/Series),
                // hasta dos por dia y "+N" si hay mas; una barrita del color del tipo debajo
                // de cada uno (azul serie, ambar pelicula).
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Con un solo estreno se centra con el MISMO ancho que cada uno de dos
                    // (si no, un cartel unico ocupaba toda la casilla y se salia).
                    val shown = events.take(2)
                    if (shown.size == 1) Spacer(Modifier.weight(0.5f))
                    shown.forEach { ev ->
                        Column(Modifier.weight(1f)) {
                            AsyncImage(
                                model = ev.posterPath?.let { "https://image.tmdb.org/t/p/w154$it" },
                                contentDescription = ev.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                            Box(Modifier.fillMaxWidth().height(3.dp).background(if (ev.mediaPath == "tv") SeriesColor else MovieColor))
                        }
                    }
                    if (shown.size == 1) Spacer(Modifier.weight(0.5f))
                }
                if (events.size > 2) {
                    Text(
                        "+${events.size - 2}",
                        modifier = Modifier.align(Alignment.TopEnd),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    events.take(3).forEach {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (it.mediaPath == "tv") SeriesColor else MovieColor))
                    }
                    if (events.size > 3) Text("+${events.size - 3}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: TmdbClient.ScheduleEvent, onHide: () -> Unit, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = event.posterPath?.let { "https://image.tmdb.org/t/p/w154$it" },
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (event.mediaPath == "tv") SeriesColor else MovieColor))
                Spacer(Modifier.width(6.dp))
                Text(event.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                event.subtitle ?: stringResource(R.string.calendar_release),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            event.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("\u22EE", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.calendar_hide)) },
                    onClick = { menuOpen = false; onHide() }
                )
            }
        }
    }
}
