package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historial de títulos que el usuario ha abierto de verdad a través de la
 * app — la base para la fila de "Recomendado para ti" que publicamos en la
 * pantalla de inicio (ver RecommendationChannelWorker). Vive en este
 * dispositivo, y además se sincroniza con el backend propio (por email, no
 * solo este TV) para que [fetchMerged] pueda tener en cuenta lo visto en
 * cualquier otro dispositivo vinculado al mismo email — ver
 * TvRecommendationBridge-backend/api/history-record.js y history-list.js.
 */
/** [season]/[episode] solo se conocen si la app de origen los dio
 * directamente (Nuvio) o se pudieron derivar cruzando [episodeTitle] contra
 * TMDb (Stremio, ver MediaWatchListenerService/TmdbClient.resolveEpisode) —
 * null en cualquier otro caso (películas, o apps como WuPlay que no dan
 * ningún dato de episodio). [watchedAt] en milisegundos epoch. */
data class OpenedTitle(
    val tmdbId: Int,
    val mediaPath: String,
    val title: String,
    val posterPath: String? = null,
    val overview: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val watchedAt: Long = 0L
)

object RecommendationHistory {
    private const val TAG = "RecommendationHistory"
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_HISTORY = "opened_titles_history"

    // Todas las lecturas-modificaciones-escrituras de la copia local van bajo
    // este candado. fetchMerged tarda (red) y antes leia la copia local ANTES
    // de la llamada y la guardaba DESPUES: un alta hecha mientras tanto (p.ej.
    // "+ Mi lista" con la pantalla aun cargando) no estaba ni en lo leido ni en
    // lo que devolvia el servidor, y el guardado final la borraba.
    private val lock = Any()

    // Igual que el límite en el backend (history-record.js) — de sobra para
    // variar las recomendaciones (que solo cogen unas pocas semillas, ver
    // RecommendationEngine) y para una pantalla de Historial real.
    private const val MAX_ENTRIES = 200
    // Lo visto hace menos de esto se conserva aunque el servidor aun no lo tenga.
    private const val PENDING_WINDOW_MS = 2 * 60 * 1000L

    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** Añade un título recién abierto al principio del historial local (lo
     * más reciente primero), sin duplicados — si ya estaba, se mueve arriba
     * en vez de dejar una entrada repetida. También lo manda al backend en
     * segundo plano (mejor esfuerzo: si falla por lo que sea, el historial
     * local ya se guardó igual). */
    fun record(
        context: Context,
        tmdbId: Int,
        mediaPath: String,
        title: String,
        posterPath: String? = null,
        overview: String? = null,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null
    ) {
        val watchedAt = System.currentTimeMillis()
        synchronized(lock) {
            val current = getAll(context).toMutableList()
            current.removeAll { it.tmdbId == tmdbId && it.mediaPath == mediaPath }
            current.add(0, OpenedTitle(tmdbId, mediaPath, title, posterPath, overview, season, episode, episodeTitle, watchedAt))
            saveLocal(context, current.take(MAX_ENTRIES))
        }

        syncRecordToBackend(context, tmdbId, mediaPath, title, posterPath, overview, season, episode, episodeTitle)
    }

    /** Quita una entrada del historial local Y del backend — mismo criterio
     * de mejor esfuerzo que [record]. Usado desde "Continuar viendo"
     * (mantener pulsada una tarjeta ahí, ver
     * RecommendationsRowsFragment.onRemoveFromContinueWatching), no afecta
     * a "Mi lista". Devuelve la lista local ya actualizada — mismo motivo
     * que Watchlist.remove: refrescar la UI al momento sin arriesgarse a
     * que una petición de red posterior "resucite" la entrada recién
     * quitada. */
    fun remove(context: Context, tmdbId: Int, mediaPath: String): List<OpenedTitle> {
        val current = synchronized(lock) {
            getAll(context).filterNot { it.tmdbId == tmdbId && it.mediaPath == mediaPath }.also {
                saveLocal(context, it)
                RecentChanges.markRemoved(context, "history", tmdbId, mediaPath)
            }
        }

        val email = LicenseManager.getSavedEmail(context)
        if (email != null) {
            val deviceId = LicenseManager.getDeviceId(context)
            Thread {
                val body = JSONObject().apply {
                    put("action", "remove")
                    put("email", email)
                    put("deviceId", deviceId)
                    put("tmdbId", tmdbId)
                    put("mediaPath", mediaPath)
                }
                postJson("/api/history", body)
            }.start()
        }
        return current
    }

    private fun saveLocal(context: Context, items: List<OpenedTitle>) {
        val array = JSONArray()
        for (item in items) {
            array.put(JSONObject().apply {
                put("tmdbId", item.tmdbId)
                put("mediaPath", item.mediaPath)
                put("title", item.title)
                put("posterPath", item.posterPath)
                put("overview", item.overview)
                put("season", item.season)
                put("episode", item.episode)
                put("episodeTitle", item.episodeTitle)
                put("watchedAt", item.watchedAt)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY, array.toString())
            .apply()
    }

    /** Solo el historial de este dispositivo — ver [fetchMerged] para el
     * fusionado con otros TVs del mismo email. Rápido y local, sin red: es
     * el que se usa para decisiones síncronas en el hilo principal (p.ej.
     * MainActivity decidiendo a qué pantalla ir al abrir la app). */
    fun getAll(context: Context): List<OpenedTitle> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i -> array.getJSONObject(i).toOpenedTitle() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Borra la caché local (no toca el backend) — ver Watchlist.clearLocal,
     * mismo motivo: al cerrar sesión, para no arrastrar historial de la
     * cuenta anterior. */
    fun clearLocal(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }

    /** Historial local fusionado con el que el backend tiene guardado para
     * este email (lo visto en cualquier otro dispositivo vinculado) — ante
     * el mismo título en ambos lados, gana el que tenga [OpenedTitle.watchedAt]
     * más reciente (no siempre el local: si se siguió viendo la serie desde
     * OTRO TV, el episodio más nuevo puede estar solo en el remoto).
     * Reordenado por fecha al final, de más a menos reciente. Si la llamada
     * de red falla (sin conexión, sin suscripción...) se cae al historial
     * solo local, nunca deja la fila de recomendaciones vacía por un fallo
     * de red.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun fetchMerged(context: Context): List<OpenedTitle> {
        // Sin conexion: se cae a la copia local para no dejar la pantalla vacia.
        val remote = fetchRemote(context) ?: return getAll(context)
        // La copia local se lee DESPUES de la red (y bajo el candado): asi entra
        // lo visto mientras se esperaba al servidor.
        return synchronized(lock) {
        val local = getAll(context)
        // El servidor es la fuente de verdad: lo que ve el movil y lo que ve
        // la TV es lo mismo, y lo quitado en cualquier dispositivo desaparece
        // en todos. La copia local solo aporta lo visto hace un momento (aun
        // sin confirmar en el servidor) y respeta lo quitado hace un momento
        // (el aviso al servidor va en segundo plano).
        val removedRecently = RecentChanges.recentlyRemoved(context, "history")
        val now = System.currentTimeMillis()
        val pendingLocal = local.filter { it.watchedAt > now - PENDING_WINDOW_MS }
        val byKey = LinkedHashMap<Pair<Int, String>, OpenedTitle>()
        for (item in remote + pendingLocal) {
            val key = item.tmdbId to item.mediaPath
            if (key in removedRecently) continue
            val existing = byKey[key]
            if (existing == null || item.watchedAt > existing.watchedAt) byKey[key] = item
        }
        val merged = byKey.values.sortedByDescending { it.watchedAt }.take(MAX_ENTRIES)
        saveLocal(context, merged)
        merged
        }
    }

    private fun fetchRemote(context: Context): List<OpenedTitle>? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("action", "list")
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
        }
        val json = postJson("/api/history", body) ?: return null
        if (!json.optBoolean("ok", false)) return null
        val array = json.optJSONArray("history") ?: return emptyList()
        return (0 until array.length()).map { i -> array.getJSONObject(i).toOpenedTitle() }
    }

    private fun JSONObject.toOpenedTitle() = OpenedTitle(
        tmdbId = getInt("tmdbId"),
        mediaPath = getString("mediaPath"),
        title = optString("title", ""),
        posterPath = if (has("posterPath") && !isNull("posterPath")) getString("posterPath") else null,
        overview = if (has("overview") && !isNull("overview")) getString("overview") else null,
        season = if (has("season") && !isNull("season")) getInt("season") else null,
        episode = if (has("episode") && !isNull("episode")) getInt("episode") else null,
        episodeTitle = if (has("episodeTitle") && !isNull("episodeTitle")) getString("episodeTitle") else null,
        watchedAt = optLong("watchedAt", 0L)
    )

    private fun syncRecordToBackend(
        context: Context,
        tmdbId: Int,
        mediaPath: String,
        title: String,
        posterPath: String?,
        overview: String?,
        season: Int?,
        episode: Int?,
        episodeTitle: String?
    ) {
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
                if (season != null) put("season", season)
                if (episode != null) put("episode", episode)
                if (episodeTitle != null) put("episodeTitle", episodeTitle)
            }
            postJson("/api/history-record", body)
        }.start()
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

/** Para las pantallas de Historial y Continuar viendo (ver
 * HistoryRowsFragment/RecommendationsRowsFragment) — misma conversión que
 * Watchlist.toTmdbRecommendation, reutilizando RecommendationCardPresenter.
 * La temporada/episodio va en [TmdbClient.TmdbRecommendation.subtitle], NO
 * en el título: ese texto viaja también en el deep link al reabrir el
 * título (ver RecommendationOpenActivity) y en el propio historial al
 * volver a grabarlo, así que "ensuciarlo" acabaría filtrándose y
 * acumulándose en otros sitios. */
fun OpenedTitle.toTmdbRecommendation() = TmdbClient.TmdbRecommendation(
    tmdbId = tmdbId,
    mediaPath = mediaPath,
    title = title,
    posterPath = posterPath,
    popularity = 0.0,
    overview = overview,
    subtitle = if (season != null && episode != null) {
        "T$season · E$episode"
    } else null
)
