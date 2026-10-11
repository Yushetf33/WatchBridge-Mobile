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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class IptvTab { CHANNELS, SPORTS, MOVIES, SERIES }

data class IptvUiState(
    val sources: List<IptvSource> = emptyList(),
    val active: IptvSource? = null,
    val channels: List<IptvChannel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val favorites: Set<String> = emptySet(),
    val group: String? = null,
    val favoritesOnly: Boolean = false,
    val query: String = "",
    /** Sube cada vez que llega la guia, para repintar el "ahora / despues" de cada canal. */
    val epgVersion: Int = 0
)

class IptvViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val _state = MutableStateFlow(IptvUiState())
    val state: StateFlow<IptvUiState> = _state.asStateFlow()

    init {
        reloadSources()
    }

    private fun reloadSources() {
        val sources = IptvStore.all(context)
        val active = IptvStore.active(context)
        _state.update {
            it.copy(
                sources = sources, active = active,
                favorites = active?.let { a -> IptvStore.favorites(context, a.id) } ?: emptySet()
            )
        }
        if (active != null && _state.value.channels.isEmpty()) load(force = false)
    }

    fun load(force: Boolean) {
        val source = _state.value.active ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val channels = IptvRepository.channels(context, source, force)
                _state.update { it.copy(channels = channels, loading = false, group = null, favorites = IptvStore.favorites(context, source.id)) }
                // La guia llega despues: los canales se ven ya.
                val epg = IptvRepository.loadEpg(context, force)
                if (epg != null) _state.update { it.copy(epgVersion = it.epgVersion + 1) }
            } catch (e: IptvRepository.LoadException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Error") }
            }
        }
    }

    fun addSource(source: IptvSource) {
        IptvStore.add(context, source)
        _state.update { it.copy(channels = emptyList()) }
        reloadSources()
        load(force = true)
    }

    fun selectSource(id: String) {
        IptvStore.setActive(context, id)
        _state.update { it.copy(channels = emptyList(), group = null, query = "", favoritesOnly = false) }
        reloadSources()
        load(force = false)
    }

    fun removeSource(id: String) {
        IptvStore.remove(context, id)
        _state.update { it.copy(channels = emptyList(), group = null, error = null) }
        reloadSources()
    }

    fun setGroup(group: String?) = _state.update { it.copy(group = group, favoritesOnly = false) }
    fun setFavoritesOnly(on: Boolean) = _state.update { it.copy(favoritesOnly = on, group = null) }
    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleFavorite(channel: IptvChannel) {
        val source = _state.value.active ?: return
        IptvStore.toggleFavorite(context, source.id, channel.key)
        _state.update { it.copy(favorites = IptvStore.favorites(context, source.id)) }
    }
}

/** Pestaña IPTV: tus propias listas (a mano), canales con guia, favoritos y busqueda, y los eventos deportivos de la guia. */
@Composable
fun IptvScreen(
    onOpenSettings: () -> Unit,
    onPlay: (IptvChannel, List<IptvChannel>?) -> Unit,
    viewModel: IptvViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(IptvTab.CHANNELS) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        SettingsGearRow(onOpenSettings) {
            if (state.active != null) {
                ListMenu(state, viewModel, onAdd = { adding = true })
            }
        }
        if (state.active == null) {
            IptvSetup(onAdd = { adding = true })
        } else {
            when {
                state.loading && state.channels.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.channels.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                ) {
                    Text(state.error ?: "", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.load(force = true) }) { Text(stringResource(R.string.retry)) }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == IptvTab.CHANNELS, onClick = { tab = IptvTab.CHANNELS }, label = { Text(stringResource(R.string.iptv_tab_channels)) })
                        FilterChip(selected = tab == IptvTab.SPORTS, onClick = { tab = IptvTab.SPORTS }, label = { Text(stringResource(R.string.iptv_tab_sports)) })
                        // Peliculas y Series: solo con una lista Xtream (los paneles las sirven por su API).
                        if (state.active?.isXtream == true) {
                            FilterChip(selected = tab == IptvTab.MOVIES, onClick = { tab = IptvTab.MOVIES }, label = { Text(stringResource(R.string.tab_movies)) })
                            FilterChip(selected = tab == IptvTab.SERIES, onClick = { tab = IptvTab.SERIES }, label = { Text(stringResource(R.string.tab_series)) })
                        }
                    }
                    when (tab) {
                        IptvTab.CHANNELS -> ChannelsList(state, viewModel) { onPlay(it, null) }
                        IptvTab.SPORTS -> SportsTab(state, onPlay)
                        IptvTab.MOVIES -> state.active?.let { VodTab(it, VodKind.MOVIES) }
                        IptvTab.SERIES -> state.active?.let { VodTab(it, VodKind.SERIES) }
                    }
                }
            }
        }
    }
    if (adding) AddListDialog(onDismiss = { adding = false }, onAdd = { adding = false; viewModel.addSource(it) })
}

@Composable
private fun IptvSetup(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.iptv_setup_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.iptv_setup_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.iptv_add_list)) }
    }
}

/** El menu de listas: cambiar de lista, añadir otra, actualizar y borrar la activa. */
@Composable
private fun ListMenu(state: IptvUiState, vm: IptvViewModel, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.active?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
            Text(" ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text((if (source.id == state.active?.id) "● " else "○ ") + source.name) },
                    onClick = { open = false; vm.selectSource(source.id) }
                )
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.iptv_add_list)) }, onClick = { open = false; onAdd() })
            DropdownMenuItem(text = { Text(stringResource(R.string.iptv_refresh)) }, onClick = { open = false; vm.load(force = true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.iptv_delete_list)) }, onClick = { open = false; confirmDelete = true })
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.iptv_delete_title, state.active?.name.orEmpty())) },
            text = { Text(stringResource(R.string.iptv_delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; state.active?.let { vm.removeSource(it.id) } }) { Text(stringResource(R.string.iptv_delete_list)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun ChannelsList(state: IptvUiState, vm: IptvViewModel, onPlay: (IptvChannel) -> Unit) {
    val groups = remember(state.channels) { state.channels.mapNotNull { it.groupTitle }.distinct().sorted() }
    val shown = remember(state.channels, state.group, state.favoritesOnly, state.query, state.favorites) {
        val q = state.query.trim().lowercase()
        state.channels.filter { c ->
            (state.group == null || c.groupTitle == state.group) &&
                (!state.favoritesOnly || c.key in state.favorites) &&
                (q.isEmpty() || c.name.lowercase().contains(q))
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.iptv_search_hint)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(selected = state.group == null && !state.favoritesOnly, onClick = { vm.setGroup(null) }, label = { Text(stringResource(R.string.iptv_group_all)) })
            }
            item {
                FilterChip(selected = state.favoritesOnly, onClick = { vm.setFavoritesOnly(!state.favoritesOnly) }, label = { Text("★ " + stringResource(R.string.iptv_group_favorites)) })
            }
            items(groups) { g ->
                FilterChip(selected = state.group == g, onClick = { vm.setGroup(g) }, label = { Text(g, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(6.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.iptv_no_channels), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
                items(shown, key = { it.streamUrl }) { channel ->
                    ChannelRow(channel, channel.key in state.favorites, state.epgVersion, onClick = { onPlay(channel) }, onFavorite = { vm.toggleFavorite(channel) })
                }
            }
        }
    }
}

@Composable
internal fun ChannelRow(channel: IptvChannel, favorite: Boolean, epgVersion: Int, onClick: () -> Unit, onFavorite: () -> Unit) {
    val now = System.currentTimeMillis()
    val (current, next) = remember(channel, epgVersion, now / 60_000) { IptvRepository.nowNext(channel, now) }
    val format = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B2030)), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            } else {
                Text(channel.name.take(2).uppercase(), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current != null) {
                Text(
                    "${format.format(Date(current.startMs))} · ${current.title}",
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val total = (current.endMs - current.startMs).coerceAtLeast(1)
                LinearProgressIndicator(
                    progress = { ((now - current.startMs).toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).height(3.dp)
                )
                if (next != null) {
                    Text(
                        "${format.format(Date(next.startMs))} · ${next.title}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Text(stringResource(R.string.iptv_no_epg), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆", fontSize = 22.sp) }
    }
}

/** Añadir una lista a mano: un enlace M3U (con guia opcional) o un acceso Xtream (servidor, usuario y contraseña). */
@Composable
private fun AddListDialog(onDismiss: () -> Unit, onAdd: (IptvSource) -> Unit) {
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val valid = if (xtream) server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() else url.trim().startsWith("http", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iptv_add_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !xtream, onClick = { xtream = false })
                    Text(stringResource(R.string.iptv_mode_m3u), Modifier.clickable { xtream = false })
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = xtream, onClick = { xtream = true })
                    Text(stringResource(R.string.iptv_mode_xtream), Modifier.clickable { xtream = true })
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_name)) }, modifier = Modifier.fillMaxWidth())
                if (!xtream) {
                    OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_url)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(epg, { epg = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_epg)) }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(server, { server = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_server)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_user)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text(stringResource(R.string.iptv_field_pass)) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                // Un enlace get.php / player_api.php pegado en el modo M3U es en realidad un acceso Xtream.
                val detected = if (!xtream) IptvRepository.detectXtream(url.trim()) else null
                val source = if (xtream || detected != null) {
                    val (s, u, p) = detected ?: Triple(IptvRepository.normalizeXtreamServer(server) ?: server.trim(), user.trim(), pass.trim())
                    IptvSource("", name.trim().ifEmpty { s.substringAfter("://") }, "XTREAM", "", epg.trim().ifEmpty { null }, s, u, p)
                } else {
                    IptvSource("", name.trim().ifEmpty { url.trim().substringAfter("://").substringBefore('/') }, "URL", url.trim(), epg.trim().ifEmpty { null }, "", "", "")
                }
                onAdd(source)
            }) { Text(stringResource(R.string.iptv_add_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
