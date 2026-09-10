package com.tunombre.tvbridge.mobile

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class TraktImportResult(val history: Int, val watchlist: Int, val skipped: Int)

sealed class TraktImportUiState {
    object Idle : TraktImportUiState()
    object Starting : TraktImportUiState()
    data class WaitingForApproval(val userCode: String, val verificationUrl: String) : TraktImportUiState()
    data class Success(val result: TraktImportResult) : TraktImportUiState()
    data class Error(val messageRes: Int) : TraktImportUiState()
}

/**
 * Importar de Trakt: mismo flujo de "device code" que trakt.tv/activate —
 * se le enseña un código corto al usuario, lo introduce en su navegador, y
 * aquí se sondea (/api/referral, acciones "trakt_start"/"trakt_poll" — ver
 * _trakt.js en el backend, no tienen endpoint propio por cupo de Vercel)
 * hasta que lo aprueba. En cuanto se aprueba, el propio backend importa el
 * historial y Mi lista de Trakt en la misma llamada.
 */
class TraktImportViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _uiState = MutableStateFlow<TraktImportUiState>(TraktImportUiState.Idle)
    val uiState: StateFlow<TraktImportUiState> = _uiState.asStateFlow()

    fun start() {
        val email = LicenseManager.getSavedEmail(context) ?: run {
            _uiState.update { TraktImportUiState.Error(R.string.trakt_import_error_generic) }
            return
        }
        _uiState.update { TraktImportUiState.Starting }
        viewModelScope.launch(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("action", "trakt_start")
                put("email", email)
                put("deviceId", LicenseManager.getDeviceId(context))
            }
            val json = postJson(body)
            if (json == null || !json.optBoolean("ok", false)) {
                withContext(Dispatchers.Main) { _uiState.update { TraktImportUiState.Error(R.string.trakt_import_error_generic) } }
                return@launch
            }
            val userCode = json.optString("userCode")
            val verificationUrl = json.optString("verificationUrl")
            val intervalSeconds = json.optInt("interval", 5)
            withContext(Dispatchers.Main) {
                _uiState.update { TraktImportUiState.WaitingForApproval(userCode, verificationUrl) }
            }
            pollLoop(email, intervalSeconds)
        }
    }

    private suspend fun pollLoop(email: String, intervalSeconds: Int) {
        while (true) {
            delay(intervalSeconds * 1000L)
            val body = JSONObject().apply {
                put("action", "trakt_poll")
                put("email", email)
                put("deviceId", LicenseManager.getDeviceId(context))
            }
            val json = postJson(body)
            if (json == null) continue // fallo de red puntual — se reintenta en el siguiente ciclo

            val ok = json.optBoolean("ok", false)
            if (ok && json.optString("status") == "pending") continue
            if (ok && json.optString("status") == "completed") {
                val imported = json.optJSONObject("imported")
                val result = TraktImportResult(
                    history = imported?.optInt("history", 0) ?: 0,
                    watchlist = imported?.optInt("watchlist", 0) ?: 0,
                    skipped = imported?.optInt("skipped", 0) ?: 0
                )
                // Refresca las cachés locales (mismas funciones que Home usa
                // al abrir/reanudar) para que lo importado aparezca en el
                // acto, sin tener que reiniciar la app.
                Watchlist.fetchMerged(context)
                RecommendationHistory.fetchMerged(context)
                withContext(Dispatchers.Main) { _uiState.update { TraktImportUiState.Success(result) } }
                return
            }
            // Terminal: no_pending_import / expired / denied / invalid / not_entitled / server_error
            val reason = json.optString("reason")
            val messageRes = when (reason) {
                "expired" -> R.string.trakt_import_error_expired
                "denied" -> R.string.trakt_import_error_denied
                else -> R.string.trakt_import_error_generic
            }
            withContext(Dispatchers.Main) { _uiState.update { TraktImportUiState.Error(messageRes) } }
            return
        }
    }

    private fun postJson(body: JSONObject): JSONObject? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) return null
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/api/referral")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                JSONObject(responseBody)
            }
        } catch (e: Exception) {
            Log.e("TraktImportViewModel", "Error llamando a /api/referral", e)
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraktImportScreen(onBack: () -> Unit, viewModel: TraktImportViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trakt_import_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val state = uiState) {
                is TraktImportUiState.Idle -> {
                    Text(stringResource(R.string.trakt_import_intro), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.trakt_import_start_button))
                    }
                }
                is TraktImportUiState.Starting -> CircularProgressIndicator()
                is TraktImportUiState.WaitingForApproval -> {
                    Text(
                        stringResource(R.string.trakt_import_open_url, state.verificationUrl),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        state.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                    CircularProgressIndicator()
                    Text(stringResource(R.string.trakt_import_waiting), modifier = Modifier.padding(top = 12.dp))
                }
                is TraktImportUiState.Success -> {
                    Text(
                        stringResource(
                            R.string.trakt_import_success,
                            state.result.history,
                            state.result.watchlist,
                            state.result.skipped
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.trakt_import_close_button))
                    }
                }
                is TraktImportUiState.Error -> {
                    Text(stringResource(state.messageRes), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.trakt_import_start_button))
                    }
                }
            }
        }
    }
}
