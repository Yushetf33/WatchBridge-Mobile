package com.tunombre.tvbridge.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMDB_URL_REGEX = Regex("imdb\\.com/title/(tt\\d+)")
private val TMDB_URL_REGEX = Regex("themoviedb\\.org/(movie|tv)/(\\d+)")

private sealed class ShareState {
    object Loading : ShareState()
    object NotFound : ShareState()
    object NotVerified : ShareState()
    data class Ambiguous(val query: String, val candidates: List<TmdbCandidate>) : ShareState()
}

/**
 * Puerto de entrada de "Compartir → TvRecommendationBridge" desde cualquier
 * app (navegador, Reddit, Telegram...) y del esquema propio
 * `tvbridge://movie/{imdbId}`. Actividad propia y aislada (mismo patrón que
 * MatchPickerActivity/ConfirmOpenActivity en la app de TV: taskAffinity
 * propia, fuera de recientes) en vez de meter esto en MainActivity — solo
 * hace la resolución (título/URL → tmdbId) y, en cuanto sabe cuál es el
 * título exacto, se lo pasa a MainActivity para que aterrice en
 * DetailScreen. No duplica esa pantalla aquí.
 */
class ShareTargetActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MEDIA_PATH = "pendingDetailMediaPath"
        const val EXTRA_TMDB_ID = "pendingDetailTmdbId"
    }

    private val viewModel: ShareTargetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.attach(this)
        if (!LicenseManager.isLikelyValid(this)) {
            viewModel.setNotVerified()
        } else {
            val sharedText = when (intent?.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.data?.let { uri ->
                    // tvbridge://movie/{imdbId} — el host es "movie", el
                    // primer segmento del path es el imdbId.
                    uri.lastPathSegment?.let { "imdb.com/title/$it" }
                }
                else -> null
            }
            viewModel.resolve(sharedText)
        }

        setContent {
            TvBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShareTargetContent(
                        viewModel = viewModel,
                        onResolved = { mediaPath, tmdbId ->
                            startActivity(
                                Intent(this, MainActivity::class.java).apply {
                                    putExtra(EXTRA_MEDIA_PATH, mediaPath)
                                    putExtra(EXTRA_TMDB_ID, tmdbId)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                            )
                            finish()
                        },
                        onGiveUp = { message ->
                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private class ShareTargetViewModel : ViewModel() {
    private val _state = MutableStateFlow<ShareState>(ShareState.Loading)
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private lateinit var appContext: android.content.Context

    fun setNotVerified() {
        _state.update { ShareState.NotVerified }
    }

    fun resolve(sharedText: String?) {
        if (sharedText.isNullOrBlank()) {
            _state.update { ShareState.NotFound }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val context = appContext
            val imdbMatch = IMDB_URL_REGEX.find(sharedText)
            val tmdbMatch = TMDB_URL_REGEX.find(sharedText)
            val resolved: ResolvedShare? = when {
                imdbMatch != null -> TmdbClient.findByImdbId(imdbMatch.groupValues[1])?.let {
                    ResolvedShare(it.mediaPath, it.tmdbId)
                }
                tmdbMatch != null -> {
                    val mediaPath = tmdbMatch.groupValues[1]
                    val tmdbId = tmdbMatch.groupValues[2].toIntOrNull()
                    tmdbId?.let { ResolvedShare(mediaPath, it) }
                }
                else -> when (val resolution = TmdbClient.resolve(context, sharedText.trim())) {
                    is TmdbResolution.Resolved -> ResolvedShare(
                        if (resolution.match.type == MediaType.SERIES) "tv" else "movie",
                        resolution.match.tmdbId
                    )
                    is TmdbResolution.Ambiguous -> {
                        withContext(Dispatchers.Main) {
                            _state.update { ShareState.Ambiguous(resolution.query, resolution.candidates) }
                        }
                        null
                    }
                    null -> null
                }
            }
            if (resolved != null) {
                withContext(Dispatchers.Main) { _pendingResolved.update { resolved } }
            } else if (_state.value == ShareState.Loading) {
                withContext(Dispatchers.Main) { _state.update { ShareState.NotFound } }
            }
        }
    }

    fun pickCandidate(context: android.content.Context, candidate: TmdbCandidate) {
        _state.update { ShareState.Loading }
        viewModelScope.launch(Dispatchers.IO) {
            val match = TmdbClient.resolveCandidate(context, candidate)
            withContext(Dispatchers.Main) {
                if (match != null) _pendingResolved.update { ResolvedShare(candidate.mediaPath, candidate.tmdbId) }
                else _state.update { ShareState.NotFound }
            }
        }
    }

    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
    }

    private val _pendingResolved = MutableStateFlow<ResolvedShare?>(null)
    val pendingResolved: StateFlow<ResolvedShare?> = _pendingResolved.asStateFlow()
}

private data class ResolvedShare(val mediaPath: String, val tmdbId: Int)

@Composable
private fun ShareTargetContent(
    viewModel: ShareTargetViewModel,
    onResolved: (mediaPath: String, tmdbId: Int) -> Unit,
    onGiveUp: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.state.collectAsState()
    val pendingResolved by viewModel.pendingResolved.collectAsState()
    val notFoundMessage = stringResource(R.string.share_not_found)
    val notVerifiedMessage = stringResource(R.string.share_verify_first)

    LaunchedEffect(pendingResolved) {
        pendingResolved?.let { onResolved(it.mediaPath, it.tmdbId) }
    }
    LaunchedEffect(state) {
        when (val current = state) {
            is ShareState.NotFound -> onGiveUp(notFoundMessage)
            is ShareState.NotVerified -> onGiveUp(notVerifiedMessage)
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val current = state) {
            is ShareState.Ambiguous -> Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(R.string.share_match_picker_title, current.query),
                    style = MaterialTheme.typography.titleMedium
                )
                current.candidates.forEach { candidate ->
                    Button(
                        onClick = { viewModel.pickCandidate(context, candidate) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (candidate.year != null) "${candidate.title} (${candidate.year})" else candidate.title)
                    }
                }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text(stringResource(R.string.share_resolving), modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}
