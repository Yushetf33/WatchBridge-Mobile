package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Enviar a la TV" para un canal de IPTV: lista las TVs vinculadas a la cuenta y, al elegir una, deja pendiente en el
 * servidor la apertura de ese canal (el mismo mecanismo que el boton "Ver" de los avisos y que el envio de peliculas:
 * la TV lo recoge por push o sondeo). La TV lo busca en su propia lista por la direccion del canal y, si no la
 * encuentra, por el nombre.
 */
@Composable
fun SendChannelToTvDialog(channel: IptvChannel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<BoundDevice>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val email = LicenseManager.getSavedEmail(context)
        val loaded = if (email == null) null else withContext(Dispatchers.IO) { DeviceApi.listDevices(context, email) as? DevicesResult.Loaded }
        if (loaded == null) loadFailed = true else devices = loaded.devices.filter { it.deviceType == "tv" }
    }

    val sentText = stringResource(R.string.detail_sent_to_tv, "%s")
    val failedText = stringResource(R.string.detail_send_to_tv_failed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.iptv_send_to_tv_title, channel.name), maxLines = 2) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val list = devices
                when {
                    result != null -> Text(result!!)
                    loadFailed -> Text(stringResource(R.string.iptv_send_to_tv_load_failed))
                    list == null || sending -> CircularProgressIndicator()
                    list.isEmpty() -> Text(stringResource(R.string.iptv_send_to_tv_none), textAlign = TextAlign.Start)
                    else -> list.forEach { tv ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                sending = true
                                scope.launch {
                                    val email = LicenseManager.getSavedEmail(context)
                                    val ok = email != null && withContext(Dispatchers.IO) {
                                        DeviceApi.sendRemoteOpenIptv(context, email, tv.deviceId, channel.streamUrl, channel.name, null)
                                    }
                                    sending = false
                                    result = if (ok) sentText.replace("%s", tv.deviceName ?: tv.deviceId) else failedText
                                }
                            }
                        ) { Text("📺  " + (tv.deviceName ?: tv.deviceId)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(if (result != null) android.R.string.ok else R.string.action_cancel)) } }
    )
}
