package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Mi lista" — títulos que el usuario quiere ver más adelante, no lo que ya
 * ha visto (eso es RecommendationHistory). Mismo patrón: caché local +
 * sincronización con el backend por email, compartida entre los
 * dispositivos vinculados — ver watchlist-add.js/watchlist-remove.js/
 * watchlist-list.js.
 */
data class WatchlistItem(
    val tmdbId: Int,
    val mediaPath: String,
    val title: String,
    val posterPath: String?,
    val overview: String?
)

object Watchlist {
    private const val TAG = "Watchlist"
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_WATCHLIST = "watchlist_items"

    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Añade un título al principio de la lista local (sin duplicados) y lo
     * manda al backend en segundo plano — mejor esfuerzo, igual que
     * RecommendationHistory.record. */
    fun add(context: Context, tmdbId: Int, mediaPath: String, title: String, posterPath: String?, overview: String?) {
        val current = getAllLocal(context).toMutableList()
        current.removeAll { it.tmdbId == tmdbId && it.mediaPath == mediaPath }
        current.add(0, WatchlistItem(tmdbId, mediaPath, title, posterPath, overview))
        saveLocal(context, current)

        val email = LicenseManager.getSavedEmail(context) ?: return
        val deviceId = LicenseManager.getDeviceId(context)
        Thread {
            val body = JSONObject().apply {
                put("email", email)
                put("deviceId", deviceId)
                put("tmdbId", tmdbId)
                put("mediaPath", mediaPath)
                put("title", title)
                if (posterPath != null) put("posterPath", posterPath)
                if (overview != null) put("overview", overview)
            }
            postJson("/api/watchlist-add", body)
        }.start()
    }

    /** Quita un título de la lista local y del backend — mismo criterio de
     * mejor esfuerzo que [add]. Devuelve la lista local ya actualizada para
     * que quien llame pueda refrescar la UI al momento sin tener que volver
     * a pedir la lista fusionada — [fetchMerged] justo después de esto
     * podría alcanzar al backend antes de que la petición de borrado en
     * segundo plano haya terminado, y "resucitar" el ítem recién quitado. */
    fun remove(context: Context, tmdbId: Int, mediaPath: String): List<WatchlistItem> {
        val current = getAllLocal(context).filterNot { it.tmdbId == tmdbId && it.mediaPath == mediaPath }
        saveLocal(context, current)

        val email = LicenseManager.getSavedEmail(context)
        if (email != null) {
            val deviceId = LicenseManager.getDeviceId(context)
            Thread {
                val body = JSONObject().apply {
                    put("email", email)
                    put("deviceId", deviceId)
                    put("tmdbId", tmdbId)
                    put("mediaPath", mediaPath)
                }
                postJson("/api/watchlist-remove", body)
            }.start()
        }
        return current
    }

    fun isInList(context: Context, tmdbId: Int, mediaPath: String): Boolean =
        getAllLocal(context).any { it.tmdbId == tmdbId && it.mediaPath == mediaPath }

    /** Snapshot local (sin red) — para refrescar la fila de "Mi lista" en
     * RecommendationsRowsFragment justo después de un alta/baja, sin tener
     * que volver a pedir la fusión con el backend (ver [fetchMerged]). */
    fun getLocal(context: Context): List<WatchlistItem> = getAllLocal(context)

    private fun getAllLocal(context: Context): List<WatchlistItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WATCHLIST, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> array.getJSONObject(i).toWatchlistItem() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun JSONObject.toWatchlistItem() = WatchlistItem(
        getInt("tmdbId"),
        getString("mediaPath"),
        optString("title", ""),
        if (has("posterPath") && !isNull("posterPath")) getString("posterPath") else null,
        if (has("overview") && !isNull("overview")) getString("overview") else null
    )

    private fun saveLocal(context: Context, items: List<WatchlistItem>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("tmdbId", item.tmdbId)
                put("mediaPath", item.mediaPath)
                put("title", item.title)
                put("posterPath", item.posterPath)
                put("overview", item.overview)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WATCHLIST, array.toString())
            .apply()
    }

    /** Lista fusionada con lo que el backend tiene para este email (lo
     * añadido/quitado desde cualquier otro dispositivo vinculado) — local
     * primero, y si la llamada de red falla se cae a la lista solo local en
     * vez de dejar la pantalla vacía. También actualiza la caché local con
     * lo fusionado, para que altas/bajas hechas en otro TV se reflejen aquí
     * la próxima vez que se abra esta pantalla.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun fetchMerged(context: Context): List<WatchlistItem> {
        val local = getAllLocal(context)
        val remote = fetchRemote(context) ?: return local
        val merged = (local + remote).distinctBy { it.tmdbId to it.mediaPath }
        saveLocal(context, merged)
        return merged
    }

    private fun fetchRemote(context: Context): List<WatchlistItem>? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
        }
        val json = postJson("/api/watchlist-list", body) ?: return null
        if (!json.optBoolean("ok", false)) return null
        val array = json.optJSONArray("watchlist") ?: return emptyList()
        return (0 until array.length()).map { i -> array.getJSONObject(i).toWatchlistItem() }
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) return null
        val request = Request.Builder()
            .url("${apiUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: return null
                JSONObject(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a $path", e)
            null
        }
    }
}

/** Compartido entre RecommendationsRowsFragment (fila/pestaña "Mi lista"
 * dentro de "Recomendado para ti") y WatchlistRowsFragment (pantalla
 * completa desde Ajustes) — ambos reutilizan RecommendationCardPresenter,
 * que no usa popularity, así que un 0.0 fijo basta (no se guarda en el
 * backend, ver watchlist-add.js). */
fun WatchlistItem.toTmdbRecommendation() = TmdbClient.TmdbRecommendation(
    tmdbId = tmdbId,
    mediaPath = mediaPath,
    title = title,
    posterPath = posterPath,
    popularity = 0.0,
    overview = overview
)
