package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

data class ReferralUiState(
    val isLoading: Boolean = true,
    val code: String? = null,
    val paidCount: Int = 0,
    val required: Int = 10,
    val lifetimeGranted: Boolean = false,
    val inputCode: String = "",
    val isApplying: Boolean = false,
    val statusMessage: String? = null
)

/** Puerto directo de la parte de referidos de SubscriptionActivity — mismo
 * backend (/api/referral), sin cambios: es puramente por email, no le
 * importa si el dispositivo es una TV o un móvil (ver plan, sección 3). */
class ReferralViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(ReferralUiState())
    val uiState: StateFlow<ReferralUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val stats = ReferralManager.fetchOwnCode(context)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        code = stats?.code,
                        paidCount = stats?.paidCount ?: 0,
                        required = stats?.required ?: 10,
                        lifetimeGranted = stats?.lifetimeGranted ?: false
                    )
                }
            }
        }
    }

    fun onInputChange(value: String) = _uiState.update { it.copy(inputCode = value) }

    fun applyCode() {
        val code = _uiState.value.inputCode.trim()
        if (code.isBlank()) return
        _uiState.update { it.copy(isApplying = true, statusMessage = "Comprobando…") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = ReferralManager.applyCode(context, code)
            withContext(Dispatchers.Main) {
                val success = result is ReferralManager.ApplyResult.Success
                _uiState.update {
                    it.copy(
                        isApplying = false,
                        statusMessage = messageFor(result),
                        inputCode = if (success) "" else it.inputCode
                    )
                }
                if (success) load()
            }
        }
    }

    private fun messageFor(result: ReferralManager.ApplyResult): String = when (result) {
        ReferralManager.ApplyResult.Success -> "¡Código aplicado!"
        is ReferralManager.ApplyResult.Failed -> when (result.reason) {
            "invalid_code" -> "Código no válido"
            "own_code" -> "No puedes usar tu propio código"
            "already_referred" -> "Ya usaste un código antes"
            "limit_reached" -> "Este código ya alcanzó su límite"
            "not_verified" -> "Verifica tu suscripción primero"
            else -> "Error, inténtalo de nuevo"
        }
        ReferralManager.ApplyResult.NetworkError -> "Error de red"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralScreen(onBack: () -> Unit, viewModel: ReferralViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Referidos") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.code != null -> {
                    Text("Tu código", style = MaterialTheme.typography.titleSmall)
                    Text(state.code!!, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (state.lifetimeGranted) "¡Lifetime desbloqueado por referidos!"
                        else "${state.paidCount}/${state.required} amigos que han comprado",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Comparte tu código. Cuando tu amigo abra una recomendación de verdad, ganas +4 días de prueba; si compra Lifetime, +15 días más.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> Text("Verifica tu suscripción primero para ver tu código de referido")
            }

            HorizontalDivider()

            Text("¿Te ha invitado un amigo?", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = state.inputCode,
                onValueChange = viewModel::onInputChange,
                label = { Text("Su código") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { viewModel.applyCode() },
                enabled = !state.isApplying,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Aplicar código")
            }
            state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
