package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Un dispositivo vinculado a la suscripción, tal y como lo devuelve /api/devices. */
data class BoundDevice(
    val deviceId: String,
    val deviceName: String?,
    val deviceType: String,
    val boundAt: Long,
    val isThisDevice: Boolean
)

sealed class DevicesResult {
    data class Loaded(val devices: List<BoundDevice>) : DevicesResult()
    data class Error(val reason: String) : DevicesResult()
}

/**
 * Llama a /api/devices (listar/eliminar dispositivos vinculados a un
 * email). El backend exige que ESTE dispositivo (por su deviceId) ya esté
 * vinculado a ese email como prueba de propiedad — ver
 * TvRecommendationBridge-backend/api/devices.js.
 */
object DeviceApi {

    private const val TAG = "DeviceApi"
    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun listDevices(context: Context, email: String): DevicesResult {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "list")
        }
        return runCatching { call(body) }.fold(
            onSuccess = { json ->
                if (json.optBoolean("ok", false)) {
                    DevicesResult.Loaded(parseDevices(json))
                } else {
                    DevicesResult.Error(json.optString("reason", "unknown"))
                }
            },
            onFailure = {
                Log.e(TAG, "Error listando dispositivos", it)
                DevicesResult.Error("network_error")
            }
        )
    }

    /** "Enviar a la TV" (ver DetailScreen) — deja pendiente para
     * [targetDeviceId] (debe ser otro dispositivo de esta misma cuenta) la
     * apertura de este título; la propia TV la recoge sondeando por su
     * cuenta (RemoteOpenClient.poll en la app de TV), no hay push. true si
     * el backend confirmó que quedó guardada, no si la TV ya la abrió. */
    fun sendRemoteOpen(
        context: Context,
        email: String,
        targetDeviceId: String,
        tmdbId: Int,
        mediaPath: String,
        title: String,
        posterPath: String?,
        overview: String?
    ): Boolean {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "remote_open_send")
            put("targetDeviceId", targetDeviceId)
            put("tmdbId", tmdbId)
            put("mediaPath", mediaPath)
            put("title", title)
            if (posterPath != null) put("posterPath", posterPath)
            if (overview != null) put("overview", overview)
        }
        return runCatching { call(body) }
            .onFailure { Log.e(TAG, "Error enviando a la TV", it) }
            .getOrNull()
            ?.optBoolean("ok", false) ?: false
    }

    /** "Ver ahora" en el aviso de "el programa está a punto de empezar" (ver
     * MobileFcmService/IptvAlertVerReceiver) — mismo mecanismo que
     * [sendRemoteOpen], pero para un canal de IPTV en vez de un título de
     * TMDb (ver RemoteOpenClient.PendingRemoteOpen.IptvChannel en la app de
     * TV, que sabe reconocer type="iptv"). */
    fun sendRemoteOpenIptv(
        context: Context,
        email: String,
        targetDeviceId: String,
        streamUrl: String,
        channelName: String,
        programTitle: String?
    ): Boolean {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "remote_open_send")
            put("targetDeviceId", targetDeviceId)
            put("type", "iptv")
            put("streamUrl", streamUrl)
            put("channelName", channelName)
            if (programTitle != null) put("programTitle", programTitle)
        }
        return runCatching { call(body) }
            .onFailure { Log.e(TAG, "Error enviando canal de IPTV a la TV", it) }
            .getOrNull()
            ?.optBoolean("ok", false) ?: false
    }

    /** Envía (o actualiza) el token de FCM de este dispositivo, para recibir
     * el aviso de "el programa está a punto de empezar" que manda la TV (ver
     * MobileFcmService, IptvAlertVerReceiver) — best effort, un fallo aquí
     * no rompe nada más de la app, solo ese aviso en concreto no llegaría. */
    fun registerFcmToken(context: Context, email: String, fcmToken: String) {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "register_fcm_token")
            put("fcmToken", fcmToken)
        }
        runCatching { call(body) }.onFailure { Log.e(TAG, "Error registrando token FCM", it) }
    }

    /** true si se pudo eliminar. */
    fun removeDevice(context: Context, email: String, targetDeviceId: String): Boolean {
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("action", "remove")
            put("targetDeviceId", targetDeviceId)
        }
        return runCatching { call(body) }
            .onFailure { Log.e(TAG, "Error eliminando dispositivo", it) }
            .getOrNull()
            ?.optBoolean("ok", false) ?: false
    }

    private fun parseDevices(json: JSONObject): List<BoundDevice> {
        val devices = mutableListOf<BoundDevice>()
        val arr = json.optJSONArray("devices") ?: return devices
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            devices.add(
                BoundDevice(
                    deviceId = d.getString("deviceId"),
                    deviceName = if (d.has("deviceName") && !d.isNull("deviceName")) d.getString("deviceName") else null,
                    deviceType = if (d.has("deviceType") && !d.isNull("deviceType")) d.getString("deviceType") else "tv",
                    boundAt = d.optLong("boundAt", 0L),
                    isThisDevice = d.optBoolean("isThisDevice", false)
                )
            )
        }
        return devices
    }

    private fun call(body: JSONObject): JSONObject {
        val apiUrl = BuildConfig.LICENSE_API_URL
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/api/devices")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IllegalStateException("empty response body")
            return JSONObject(responseBody)
        }
    }
}
