package com.tunombre.tvbridge.mobile

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.DateFormat
import java.util.Date

data class DevicesUiState(
    val isLoading: Boolean = true,
    val devices: List<BoundDevice> = emptyList(),
    val error: String? = null
)

/** Puerto directo de DeviceManagerActivity — mismo /api/devices, sin
 * cambios salvo mostrar el nuevo campo deviceType (ver plan, sección 3)
 * para distinguir TV de móvil/tablet en la lista. Sirve, entre otras
 * cosas, para que un "device_mismatch" al verificar no sea un callejón
 * sin salida: el usuario puede venir aquí y liberar un hueco sin esperar
 * los 30 días de REBIND_COOLDOWN_DAYS del backend. */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    fun load() {
        val email = LicenseManager.getSavedEmail(context) ?: run {
            _uiState.update { it.copy(isLoading = false, error = "not_verified") }
            return
        }
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = DeviceApi.listDevices(context, email)
            withContext(Dispatchers.Main) {
                when (result) {
                    is DevicesResult.Loaded -> _uiState.update { it.copy(isLoading = false, devices = result.devices, error = null) }
                    is DevicesResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.reason) }
                }
            }
        }
    }

    fun remove(device: BoundDevice, onRemovedSelf: () -> Unit) {
        val email = LicenseManager.getSavedEmail(context) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val ok = DeviceApi.removeDevice(context, email, device.deviceId)
            withContext(Dispatchers.Main) {
                if (!ok) return@withContext
                if (device.isThisDevice) {
                    LicenseManager.clear(context)
                    onRemovedSelf()
                } else {
                    load()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit, onRemovedSelf: () -> Unit, viewModel: DevicesViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    var pendingRemoval by remember { mutableStateOf<BoundDevice?>(null) }
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dispositivos") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Atrás") } }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.devices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.error == "not_verified") "Verifica tu suscripción primero" else "No hay dispositivos vinculados")
                }
                else -> LazyColumn {
                    items(state.devices, key = { it.deviceId }) { device ->
                        val boundDate = if (device.boundAt > 0) DateFormat.getDateInstance().format(Date(device.boundAt)) else "?"
                        val typeLabel = if (device.deviceType == "mobile") "Móvil/Tablet" else "TV"
                        ListItem(
                            headlineContent = { Text(device.deviceName ?: "Dispositivo desconocido") },
                            supportingContent = { Text("$typeLabel · vinculado el $boundDate" + if (device.isThisDevice) " · este dispositivo" else "") },
                            trailingContent = { TextButton(onClick = { pendingRemoval = device }) { Text("Quitar") } }
                        )
                    }
                }
            }
        }
    }

    pendingRemoval?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("¿Quitar este dispositivo?") },
            text = { Text(device.deviceName ?: "Dispositivo desconocido") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(device, onRemovedSelf)
                    pendingRemoval = null
                }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancelar") } }
        )
    }
}
