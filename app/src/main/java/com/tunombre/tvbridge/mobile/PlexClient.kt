package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Busca si un título está en el catálogo gratuito de streaming de Plex
 * (watch.plex.tv) y devuelve la URL directa de su ficha.
 *
 * La consulta a Plex la hace el backend propio (acción plex_watch_url de
 * /api/devices) en vez de la app: así el token de Plex vive solo en el
 * servidor y no viaja dentro del APK, donde cualquiera podía extraerlo.
 * Solo funciona en un dispositivo ya verificado (el backend exige que
 * esté vinculado a un email).
 */
object PlexClient {

    private const val TAG = "PlexClient"

    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Devuelve la URL de watch.plex.tv para este título si está en el
     * catálogo gratuito, o null si no se encuentra o falla la consulta (no
     * todo lo que hay en TMDb está en Plex).
     *
     * OJO: petición de red SÍNCRONA — llamar siempre desde un hilo de
     * fondo, igual que TmdbClient.
     */
    fun findWatchUrl(context: Context, match: TmdbMatch): String? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        val email = LicenseManager.getSavedEmail(context)
        if (apiUrl.isBlank() || email == null) return null

        val body = JSONObject().apply {
            put("action", "plex_watch_url")
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("imdbId", match.imdbId)
            put("plexType", if (match.type == MediaType.SERIES) 2 else 1)
        }.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}/api/devices")
            .post(body)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Búsqueda en Plex falló: ${response.code}")
                    return null
                }
                val json = JSONObject(response.body?.string() ?: return null)
                if (json.isNull("url")) null else json.optString("url").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando en Plex", e)
            null
        }
    }
}
