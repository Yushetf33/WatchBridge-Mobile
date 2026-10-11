package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SportsUiState(
    val loading: Boolean = true,
    val events: List<SportEvent> = emptyList(),
    /** Escudos por evento (clave de [SportEvent.key]): local y visitante. */
    val badges: Map<String, Pair<String?, String?>> = emptyMap(),
    /** Eventos que ESPN da por terminados aunque la guia los siga poniendo. */
    val finished: Set<String> = emptySet()
)

/** Los eventos deportivos de la guia de la lista activa (ver [SportsEvents]) con sus escudos y los marcados como terminados. */
class SportsViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val _state = MutableStateFlow(SportsUiState())
    val state: StateFlow<SportsUiState> = _state.asStateFlow()
    private var generation = 0

    fun load(channels: List<IptvChannel>, epgReady: Boolean) {
        val gen = ++generation
        if (!epgReady) {
            _state.update { it.copy(loading = false, events = emptyList()) }
            return
        }
        SportsDbClient.attach(context)
        WikipediaBadges.attach(context)
        viewModelScope.launch(Dispatchers.IO) {
            val found = SportsEvents.build(channels, IptvRepository.cachedEpg, System.currentTimeMillis())
            if (gen != generation) return@launch
            _state.update { it.copy(loading = false, events = found) }
            launch { lookUpBadges(found, gen) }
            checkFinished(gen)
        }
    }

    private fun lookUpBadges(list: List<SportEvent>, gen: Int) {
        val candidates = list.filter { it.isVersus && it.sport.sportsDb != null }
        if (candidates.isEmpty()) return
        fun known(event: SportEvent): Boolean {
            val sport = event.sport.sportsDb!!
            return SportsDbClient.isResolved(event.home!!, event.away!!, sport) ||
                (SportsDbClient.isTeamKnown(event.home, sport) && SportsDbClient.isTeamKnown(event.away!!, sport))
        }
        var pending = candidates.filter { known(it) } + candidates.filterNot { known(it) }
        for (pass in 0 until 3) {
            if (pass > 0) {
                if (pending.isEmpty()) return
                Thread.sleep(45_000)
            }
            val incomplete = ArrayList<SportEvent>()
            for ((index, event) in pending.withIndex()) {
                if (gen != generation) return
                val sport = event.sport.sportsDb!!
                val flags = CountryFlags.pair(event.home!!, event.away!!)
                if (flags != null) {
                    _state.update { it.copy(badges = it.badges + (event.key to flags)) }
                    continue
                }
                val resolve = {
                    val home = SportsDbClient.searchTeam(event.home, sport)
                    val away = SportsDbClient.searchTeam(event.away!!, sport)
                    if (home != null && away != null) home to away
                    else (SportsDbClient.resolveMatch(event.home, event.away, sport)?.let { it.first to it.second } ?: (home to away))
                }
                val teams = try {
                    if (index < 16 && pass == 0) resolve() else SportsDbClient.runLowPriority(resolve)
                } catch (e: Exception) {
                    null
                }
                val homeUrl = teams?.first?.badgeUrl ?: runCatching { WikipediaBadges.find(event.home, sport) }.getOrNull()
                val awayUrl = teams?.second?.badgeUrl ?: runCatching { WikipediaBadges.find(event.away!!, sport) }.getOrNull()
                if (homeUrl == null || awayUrl == null) incomplete.add(event)
                if (homeUrl == null && awayUrl == null) continue
                _state.update { it.copy(badges = it.badges + (event.key to (homeUrl to awayUrl))) }
            }
            pending = incomplete
        }
    }

    /** Cada minuto: los eventos que ESPN da por terminados dejan de salir. */
    private suspend fun checkFinished(gen: Int) {
        while (gen == generation) {
            delay(60_000)
            val now = System.currentTimeMillis()
            val done = HashSet<String>()
            for (event in _state.value.events) {
                if (!event.isVersus || event.startMs > now || event.key in _state.value.finished) continue
                val sport = event.sport.sportsDb ?: continue
                val found = runCatching { EspnScores.find(event.home!!, event.away!!, sport, event.startMs, event.competition.orEmpty()) }.getOrNull() ?: continue
                if (runCatching { EspnScores.snapshot(found.ref) }.getOrNull()?.phase == SportsDbClient.MatchPhase.FINISHED) done.add(event.key)
            }
            if (done.isNotEmpty()) _state.update { it.copy(finished = it.finished + done) }
            _state.update { it.copy(events = it.events.filter { e -> e.endMs > System.currentTimeMillis() }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SportsTab(state: IptvUiState, onPlay: (IptvChannel, List<IptvChannel>?) -> Unit, viewModel: SportsViewModel = viewModel()) {
    val context = LocalContext.current
    val sports by viewModel.state.collectAsState()
    var sportFilter by remember { mutableStateOf<Sport?>(null) }
    var liveOnly by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<SportEvent?>(null) }
    var alertFor by remember { mutableStateOf<SportEvent?>(null) }
    var alertTick by remember { mutableIntStateOf(0) }
    val epgReady = state.epgVersion > 0 || IptvRepository.cachedEpg != null

    LaunchedEffect(state.channels, state.epgVersion) { viewModel.load(state.channels, epgReady) }

    val now = System.currentTimeMillis()
    val shown = remember(sports, sportFilter, liveOnly, now / 30_000) {
        sports.events.filter {
            (sportFilter == null || it.sport == sportFilter) && (!liveOnly || it.startMs <= now) && it.endMs > now && it.key !in sports.finished
        }
    }
    val presentSports = remember(sports.events) { Sport.values().filter { s -> sports.events.any { it.sport == s } } }

    Column(Modifier.fillMaxSize()) {
        when {
            !epgReady -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.sports_loading), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sports.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = liveOnly, onClick = { liveOnly = !liveOnly }, label = { Text(stringResource(R.string.sports_live_only)) }) }
                    item { FilterChip(selected = sportFilter == null, onClick = { sportFilter = null }, label = { Text(stringResource(R.string.sports_all)) }) }
                    items(presentSports) { s ->
                        FilterChip(selected = sportFilter == s, onClick = { sportFilter = if (sportFilter == s) null else s }, label = { Text(stringResource(s.labelRes)) })
                    }
                }
                Text(
                    pluralStringResource(R.plurals.sports_count, shown.size, shown.size),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (liveOnly) R.string.sports_empty_live else R.string.sports_empty),
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        var lastDay = -1L
                        shown.forEach { event ->
                            val day = dayNumber(maxOf(event.startMs, now))
                            if (day != lastDay) {
                                lastDay = day
                                item(key = "day_$day") {
                                    Text(dayLabel(context, maxOf(event.startMs, now), now), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                                }
                            }
                            item(key = event.key) {
                                EventCard(
                                    event = event,
                                    badges = sports.badges[event.key],
                                    hasAlert = alertTick >= 0 && event.channels.any { SportsAlerts.isScheduled(context, it.key, event.startMs) },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { if (event.channels.size == 1) onPlay(event.channels[0], event.channels) else choose = event },
                                        onLongClick = { if (event.startMs > System.currentTimeMillis()) alertFor = event }
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    choose?.let { event ->
        AlertDialog(
            onDismissRequest = { choose = null },
            title = { Text(stringResource(R.string.sports_choose_channel)) },
            text = {
                Column {
                    event.channels.forEach { c ->
                        TextButton(onClick = { choose = null; onPlay(c, event.channels) }, modifier = Modifier.fillMaxWidth()) { Text(c.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
    alertFor?.let { event ->
        AlertDialog(
            onDismissRequest = { alertFor = null },
            title = { Text(event.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    event.channels.forEach { c ->
                        val scheduled = SportsAlerts.isScheduled(context, c.key, event.startMs)
                        val action = stringResource(if (scheduled) R.string.iptv_alert_cancel else R.string.iptv_alert_schedule)
                        TextButton(onClick = {
                            SportsAlerts.toggle(context, c.key, c.name, event.title, event.startMs)
                            alertTick++
                            alertFor = null
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (event.channels.size > 1) "$action · ${c.name}" else action) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { alertFor = null }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun EventCard(event: SportEvent, badges: Pair<String?, String?>?, hasAlert: Boolean, modifier: Modifier) {
    val live = event.startMs <= System.currentTimeMillis()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMs))
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF171B24)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(event.sport.labelRes).uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(event.competition.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (hasAlert) Text("🔔", fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            if (live) {
                Text(
                    stringResource(R.string.sports_live_badge), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFFF4D4F)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (event.isVersus) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(event.home.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Badge(badges?.first, event.home.orEmpty())
                }
                Text(time, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 12.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Badge(badges?.second, event.away.orEmpty())
                    Spacer(Modifier.width(8.dp))
                    Text(event.away.orEmpty(), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Text(event.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        val first = event.channels.first()
        Text(
            if (event.channels.size > 1) stringResource(R.string.sports_channels_more, first.name, event.channels.size - 1) else first.name,
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Escudo o bandera del equipo; si no hay (o mientras llega), un circulo con sus iniciales. */
@Composable
private fun Badge(url: String?, name: String) {
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        val initials = remember(name) {
            val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(2)
                else -> "${words[0].first()}${words[1].first()}"
            }.uppercase()
        }
        val hue = remember(name) { (name.lowercase().hashCode().mod(360)).toFloat() }
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.hsv(hue, 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

private fun dayNumber(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

private fun dayLabel(context: android.content.Context, ms: Long, now: Long): String {
    val date = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ms)).replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return when (dayNumber(ms) - dayNumber(now)) {
        0L -> context.getString(R.string.sports_today) + " · " + date
        1L -> context.getString(R.string.sports_tomorrow) + " · " + date
        else -> date
    }
}
