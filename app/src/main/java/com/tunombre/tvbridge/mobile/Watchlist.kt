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

    // Todas las lecturas-modificaciones-escrituras de la copia local van bajo
    // este candado. fetchMerged tarda (red) y antes leia la copia local ANTES
    // de la llamada y la guardaba DESPUES: un alta hecha mientras tanto (p.ej.
    // "+ Mi lista" con la pantalla aun cargando) no estaba ni en lo leido ni en
    // lo que devolvia el servidor, y el guardado final la borraba.
    private val lock = Any()

    /** Añade un título al principio de la lista local (sin duplicados) y lo
     * manda al backend en segundo plano — mejor esfuerzo, igual que
     * RecommendationHistory.record. */
    fun add(context: Context, tmdbId: Int, mediaPath: String, title: String, posterPath: String?, overview: String?) {
        synchronized(lock) {
            val current = getAllLocal(context).toMutableList()
            current.removeAll { it.tmdbId == tmdbId && it.mediaPath == mediaPath }
            current.add(0, WatchlistItem(tmdbId, mediaPath, title, posterPath, overview))
            saveLocal(context, current)
            RecentChanges.markAdded(context, "watchlist", tmdbId, mediaPath)
        }

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
        val current = synchronized(lock) {
            getAllLocal(context).filterNot { it.tmdbId == tmdbId && it.mediaPath == mediaPath }.also {
                saveLocal(context, it)
                RecentChanges.markRemoved(context, "watchlist", tmdbId, mediaPath)
            }
        }

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

    /** Borra la caché local (no toca el backend) — usado al cerrar sesión
     * en el móvil (ver LicenseManager.clear/DevicesScreen) para que probar
     * con una cuenta distinta no arrastre "Mi lista" de la anterior antes
     * de que [fetchMerged] la reemplace por la de la nueva cuenta. */
    fun clearLocal(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_WATCHLIST).apply()
    }

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
        // Sin conexion: se cae a la copia local para no dejar la pantalla vacia.
        val remote = fetchRemote(context) ?: return getAllLocal(context)
        // La copia local se lee DESPUES de la red (y bajo el candado): asi entra
        // lo anadido mientras se esperaba al servidor.
        return synchronized(lock) {
        val local = getAllLocal(context)
        // El servidor es la fuente de verdad (mismo criterio que
        // RecommendationHistory.fetchMerged): lo anadido/quitado en cualquier
        // dispositivo se ve igual en todos. Solo se respetan las altas y bajas
        // de hace un momento hechas aqui, cuyo aviso al servidor va en segundo
        // plano.
        val removedRecently = RecentChanges.recentlyRemoved(context, "watchlist")
        val addedRecently = RecentChanges.recentlyAdded(context, "watchlist")
        val pendingLocal = local.filter { (it.tmdbId to it.mediaPath) in addedRecently }
        val merged = (pendingLocal + remote)
            .filterNot { (it.tmdbId to it.mediaPath) in removedRecently }
            .distinctBy { it.tmdbId to it.mediaPath }
        saveLocal(context, merged)
        merged
        }
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
