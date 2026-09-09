package com.tunombre.tvbridge.mobile

import android.app.Application
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Verificación de email/pago — pantalla de arranque si todavía no hay una
 * sesión válida en caché (ver LicenseManager.isLikelyValid, consultado
 * desde TvBridgeApp). Reutiliza exactamente el mismo backend y el mismo
 * mecanismo de auto-vinculación por webhook de Stripe que
 * SubscriptionActivity en la app de TV — ver LicenseManager.pollForClaim.
 *
 * A diferencia de la TV (que muestra un QR para pagar desde el móvil),
 * aquí el usuario YA está en el móvil: los botones de pago abren
 * directamente una Custom Tab con el mismo Payment Link.
 */
private const val CLAIM_POLL_INTERVAL_MS = 4000L

class VerifyViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var email by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var isChecking by mutableStateOf(false)
        private set
    var isPolling by mutableStateOf(false)
        private set

    private var pollingJob: Job? = null

    fun onEmailChange(value: String) {
        email = value
    }

    /** Botón "Verificar" — email tecleado a mano (ya sea porque el usuario
     * ya pagó en la TV con este mismo email, o porque prefiere no pasar
     * por el navegador). */
    fun verify(onSuccess: () -> Unit) {
        val trimmed = email.trim()
        if (trimmed.isBlank() || !trimmed.contains("@")) {
            statusMessage = "Introduce un email válido"
            return
        }
        isChecking = true
        statusMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = LicenseManager.verifyNow(context, trimmed)
            withContext(Dispatchers.Main) {
                isChecking = false
                when (result) {
                    is VerifyResult.Valid -> onSuccess()
                    is VerifyResult.Invalid -> statusMessage = messageFor(result)
                    VerifyResult.NetworkError -> statusMessage = "Error de red — inténtalo de nuevo"
                }
            }
        }
    }

    private fun messageFor(result: VerifyResult.Invalid): String = when (result.reason) {
        "device_mismatch" -> "Ya tienes el máximo de dispositivos móviles vinculados a este email" +
            (result.retryInDays?.let { " — vuelve a intentarlo en $it días, o quita uno desde \"Dispositivos\"" } ?: "")
        "trial_already_used" -> "Este dispositivo ya usó su prueba gratis antes"
        "not_subscribed" -> "No hay ninguna suscripción activa con ese email"
        else -> "No se pudo verificar — inténtalo de nuevo"
    }

    /** Añade el deviceId como client_reference_id del Payment Link, con el
     * prefijo "mobile:" para que el backend sepa que es un cupo de
     * dispositivo distinto al de las TVs — ver
     * TvRecommendationBridge-backend/api/_shared.js (parseClientReferenceId). */
    fun paymentUri(paymentUrl: String): Uri {
        val deviceId = LicenseManager.getDeviceId(context)
        return Uri.parse(paymentUrl).buildUpon()
            .appendQueryParameter("client_reference_id", "mobile:$deviceId")
            .build()
    }

    /** Sondea en segundo plano hasta que un webhook de Stripe reclame este
     * dispositivo — mismo bucle que SubscriptionActivity.startClaimPolling,
     * reiniciado en cada ON_RESUME (ver VerifyScreen) para que volver del
     * navegador tras pagar dispare un sondeo fresco. */
    fun startPolling(onSuccess: () -> Unit) {
        if (pollingJob?.isActive == true) return
        isPolling = true
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val result = LicenseManager.pollForClaim(context)
                val claimedEmail = LicenseManager.getSavedEmail(context)
                if (result != null && claimedEmail != null) {
                    withContext(Dispatchers.Main) {
                        isPolling = false
                        onSuccess()
                    }
                    return@launch
                }
                delay(CLAIM_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        isPolling = false
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}

@Composable
fun VerifyScreen(onVerified: () -> Unit, viewModel: VerifyViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Sondea mientras la pantalla está visible (igual que SubscriptionActivity
    // en la TV) — así, si el usuario paga en la Custom Tab y vuelve a la app,
    // ON_RESUME relanza el sondeo y detecta el pago sin que haga falta tocar
    // nada más.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startPolling(onVerified)
                Lifecycle.Event.ON_PAUSE -> viewModel.stopPolling()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "TvRecommendationBridge",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Mismo email que usaste (o vas a usar) en la TV — el historial y las recomendaciones se comparten entre todos tus dispositivos.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ya tengo cuenta", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = viewModel.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.verify(onVerified) },
                    enabled = !viewModel.isChecking,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (viewModel.isChecking) "Comprobando…" else "Verificar")
                }
                viewModel.statusMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("¿Todavía no tienes cuenta?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Button(
                    onClick = { openCustomTab(context, viewModel.paymentUri(BuildConfig.MONTHLY_PAYMENT_URL)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Empezar prueba gratis de 2 días")
                }
                OutlinedButton(
                    onClick = { openCustomTab(context, viewModel.paymentUri(BuildConfig.LIFETIME_PAYMENT_URL)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Comprar de por vida — 4,99 €")
                }
            }
        }

        if (viewModel.isPolling) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Esperando confirmación del pago…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun openCustomTab(context: android.content.Context, uri: Uri) {
    CustomTabsIntent.Builder().build().launchUrl(context, uri)
}
