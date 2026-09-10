package com.tunombre.tvbridge.mobile

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * Cliente para TMDb: resuelve una película o serie por título a su IMDb
 * ID (formato "tt1234567"), que es lo que necesita el deep link de Nuvio.
 *
 * La resolución (resolve/resolveCandidate — lo que de verdad hace falta
 * para que el redirect funcione) pasa por el backend propio, NO por TMDb
 * directamente: así el backend puede exigir una suscripción activa en
 * cada petición. Un APK parcheado que se salte el chequeo local
 * (LicenseManager.isLikelyValid) no consigue nada, porque el servidor
 * sigue negándose a resolver sin una suscripción válida — ver
 * TvRecommendationBridge-backend/api/resolve.js y resolve-candidate.js.
 *
 * Las recomendaciones y los tráilers SÍ siguen llamando a TMDb
 * directamente (BuildConfig.TMDB_API_KEY) — no son la función de pago en
 * sí, así que no necesitan ese gate.
 */
/** Tipo de contenido resuelto en TMDb, usado para elegir el deep link correcto en Nuvio. */
enum class MediaType { MOVIE, SERIES }

data class TmdbMatch(
    val imdbId: String,
    val type: MediaType,
    val title: String,
    val tmdbId: Int,
    val posterPath: String? = null,
    val overview: String? = null
)

/** Un resultado de TMDb todavía sin resolver a IMDb ID — solo lo que hace
 * falta para mostrárselo al usuario en [MatchPickerActivity] cuando hay
 * ambigüedad (ver [TmdbResolution.Ambiguous]). */
data class TmdbCandidate(
    val tmdbId: Int,
    val mediaPath: String,
    val title: String,
    val year: String?,
    val posterPath: String? = null,
    val overview: String? = null
) {
    val type: MediaType get() = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
}

/** Resultado de [TmdbClient.resolveEpisode]: a qué temporada/episodio
 * corresponde un título de episodio detectado en reproducción (ver
 * MediaWatchListenerService) cuando la app de origen no da el número
 * directamente. */
data class EpisodeMatch(val season: Int, val episode: Int, val title: String)

/** Resultado de [TmdbClient.fetchNextEpisode]: el próximo episodio anunciado
 * de una serie (ver UpcomingEpisodesEngine) — null si la serie ya terminó o
 * TMDb todavía no tiene fecha anunciada. */
data class NextEpisodeInfo(val season: Int, val episode: Int, val airDate: String)

/** Ficha completa de una película/serie para [TmdbClient.fetchDetails] — a
 * diferencia de [TmdbRecommendation] (pensado solo para una fila de
 * tarjetas), esto es lo que necesita la pantalla de detalle propia:
 * backdrop, nota media, año y duración además de lo básico. Llamada
 * directa a TMDb (igual que fetchRecommendations) — es metadata para
 * mostrar, no la resolución a imdb_id que sí exige suscripción. */
data class TmdbDetails(
    val tmdbId: Int,
    val mediaPath: String,
    val title: String,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val voteAverage: Double,
    val year: String?,
    val runtimeMinutes: Int?
) {
    val type: MediaType get() = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
}

/** Resultado de [TmdbClient.resolve]: o bien se resolvió directamente a un
 * único [TmdbMatch], o bien hay varios títulos EXACTOS con años distintos
 * (p.ej. un remake) y hace falta que el usuario elija cuál — ver
 * [MatchPickerActivity]. */
sealed class TmdbResolution {
    data class Resolved(val match: TmdbMatch) : TmdbResolution()
    data class Ambiguous(val query: String, val candidates: List<TmdbCandidate>) : TmdbResolution()
}

/** `optString(name, fallback)` de org.json NO usa [fallback] cuando la
 * clave existe con valor JSON `null` — solo cuando falta del todo. En ese
 * caso devuelve literalmente el texto "null" (el propio `JSONObject.NULL`
 * pasado por `toString()`), así que campos opcionales como year/posterPath/
 * overview (que TMDb sí puede mandar como `null` explícito) se colaban como
 * el texto "null" en vez de vacío — confirmado real: un año sin fecha
 * aparecía como "Título (null)" en el selector de coincidencias
 * ambiguas. [isNull] cubre tanto "falta la clave" como "está a null". */
fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

object TmdbClient {

    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private const val TAG = "TmdbClient"
    private val VOSE_MARKER_REGEX = Regex("VOSE|subtitulad", RegexOption.IGNORE_CASE)

    private val httpClient = sharedHttpClient
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    init {
        if (TMDB_API_KEY.isBlank()) {
            Log.w(TAG, "TMDB_API_KEY vacía: añade TMDB_API_KEY=tu_key en local.properties")
        }
    }

    fun findImdbId(context: Context, title: String): TmdbMatch? {
        // Wrapper de compatibilidad para llamadores que no soportan el
        // selector de ambigüedad (ver FireTvCaptureService, que ya tiene su
        // propio diálogo de confirmación y no necesita uno segundo encima):
        // si resolve() devuelve Ambiguous, coge el primer candidato
        // resoluble — mismo comportamiento que tenía esta función antes de
        // existir el picker.
        return when (val resolution = resolve(context, title)) {
            is TmdbResolution.Resolved -> resolution.match
            is TmdbResolution.Ambiguous -> resolveFirstAvailable(context, resolution.candidates)
            null -> null
        }
    }

    /** Prueba los candidatos ambiguos en orden hasta que uno resuelva a un
     * imdb_id de verdad — un candidato exacto puede ser una entrada de TMDb
     * incompleta/duplicada sin external_ids (confirmado real: "Fight or
     * Flight (Sicarios en el aire)" con dos títulos exactos de años
     * distintos, el primero sin imdb_id), y quedarse solo con el primero
     * hacía fallar la resolución entera aunque el segundo candidato sí
     * tuviera uno válido. */
    fun resolveFirstAvailable(context: Context, candidates: List<TmdbCandidate>): TmdbMatch? {
        for (candidate in candidates) {
            resolveCandidate(context, candidate)?.let { return it }
        }
        return null
    }

    /**
     * Resuelve el título contra el backend propio (POST /api/resolve, ver
     * TvRecommendationBridge-backend), que hace la búsqueda en TMDb y
     * DEVUELVE null/vacío si este dispositivo no tiene una suscripción
     * activa — comprobado ahí en el servidor, no aquí. Sin ese chequeo en
     * el cliente que un APK parcheado pueda saltarse, cambiar el APK no
     * sirve de nada: sin resolución no hay redirect.
     *
     * Igual que antes: sin colapsar automáticamente el caso ambiguo, para
     * llamadores que sí quieren ofrecerle el selector al usuario (ver
     * TvRecommendationAccessibilityService.handleMovieClick y
     * MatchPickerActivity).
     *
     * OJO: esto hace una petición de red SÍNCRONA (bloqueante). Debe
     * llamarse siempre desde un hilo de fondo, nunca desde el principal.
     */
    fun resolve(context: Context, title: String): TmdbResolution? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("title", title)
            // El idioma del dispositivo, no fijo a español: el título que
            // TMDb devuelve depende de este parámetro, y la comparación de
            // título exacto en el servidor necesita que venga en el mismo
            // idioma que la consulta o nunca puede coincidir.
            put("language", Locale.getDefault().toLanguageTag())
        }
        val json = postJson("/api/resolve", body) ?: return null
        if (!json.optBoolean("ok", false)) return null

        json.optJSONObject("resolved")?.let { return TmdbResolution.Resolved(parseMatch(it)) }
        json.optJSONObject("ambiguous")?.let { ambiguous ->
            val candidatesJson = ambiguous.optJSONArray("candidates")
            val candidates = candidatesJson?.let { array ->
                (0 until array.length()).map { i ->
                    val c = array.getJSONObject(i)
                    TmdbCandidate(
                        tmdbId = c.getInt("tmdbId"),
                        mediaPath = c.getString("mediaPath"),
                        title = c.getString("title"),
                        year = c.optStringOrNull("year"),
                        posterPath = c.optStringOrNull("posterPath"),
                        overview = c.optStringOrNull("overview")
                    )
                }
            } ?: emptyList()
            return TmdbResolution.Ambiguous(ambiguous.optString("query", title), candidates)
        }
        return null
    }

    /** Segunda llamada (POST /api/resolve-candidate) para el candidato que
     * el usuario ha elegido en [MatchPickerActivity], para el primero
     * cuando se colapsa un [TmdbResolution.Ambiguous] automáticamente, o
     * para una recomendación de TMDb que aún no tenía imdb_id resuelto
     * (ver RecommendationOpenActivity). Misma comprobación de suscripción
     * en el servidor que [resolve]. */
    fun resolveCandidate(context: Context, candidate: TmdbCandidate): TmdbMatch? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("tmdbId", candidate.tmdbId)
            put("mediaPath", candidate.mediaPath)
            put("title", candidate.title)
            if (candidate.posterPath != null) put("posterPath", candidate.posterPath)
            if (candidate.overview != null) put("overview", candidate.overview)
        }
        val json = postJson("/api/resolve-candidate", body) ?: return null
        if (!json.optBoolean("ok", false)) return null
        return json.optJSONObject("resolved")?.let { parseMatch(it) }
    }

    /** Cruza el nombre de un episodio (sin número — algunas apps como
     * Stremio solo dan eso en su MediaSession, ver
     * MediaWatchListenerService) contra la lista de episodios de la serie
     * en TMDb para averiguar a qué temporada/episodio corresponde. null si
     * no se encuentra — no es un error, simplemente no se pudo determinar.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun resolveEpisode(context: Context, tmdbId: Int, episodeTitle: String): EpisodeMatch? {
        val email = LicenseManager.getSavedEmail(context) ?: return null
        val body = JSONObject().apply {
            put("email", email)
            put("deviceId", LicenseManager.getDeviceId(context))
            put("tmdbId", tmdbId)
            put("episodeTitle", episodeTitle)
            // El idioma del dispositivo como pista extra — Stremio no da
            // siempre el nombre del episodio en el mismo idioma (a veces el
            // original de la serie, a veces el del dispositivo, confirmado
            // con la misma serie dando resultados distintos), así que el
            // backend prueba varios en vez de asumir uno solo.
            put("language", Locale.getDefault().toLanguageTag())
        }
        val json = postJson("/api/resolve-episode", body) ?: return null
        if (!json.optBoolean("ok", false)) return null
        val episode = json.optJSONObject("episode") ?: return null
        return EpisodeMatch(
            season = episode.getInt("season"),
            episode = episode.getInt("episode"),
            title = episode.optString("title", episodeTitle)
        )
    }

    private fun parseMatch(json: JSONObject): TmdbMatch {
        val type = if (json.optString("type") == "SERIES") MediaType.SERIES else MediaType.MOVIE
        return TmdbMatch(
            imdbId = json.getString("imdbId"),
            type = type,
            title = json.optString("title", ""),
            tmdbId = json.getInt("tmdbId"),
            posterPath = json.optStringOrNull("posterPath"),
            overview = json.optStringOrNull("overview")
        )
    }

    private fun postJson(path: String, body: JSONObject): JSONObject? {
        val apiUrl = BuildConfig.LICENSE_API_URL
        if (apiUrl.isBlank()) {
            Log.w(TAG, "LICENSE_API_URL vacía: añade LICENSE_API_URL en local.properties")
            return null
        }
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

    /** Una recomendación de TMDb para la fila de "Recomendado para ti" que
     * publicamos en la pantalla de inicio (ver RecommendationChannelWorker)
     * — no necesita imdb_id hasta que el usuario la pulsa de verdad
     * (RecommendationOpenActivity resuelve eso al vuelo con
     * [resolveCandidate]), así que aquí basta con lo que trae /recommendations
     * directamente, sin la llamada extra a external_ids. */
    data class TmdbRecommendation(
        val tmdbId: Int,
        val mediaPath: String,
        val title: String,
        val posterPath: String?,
        val popularity: Double,
        val overview: String?,
        // Segunda línea opcional en la tarjeta (ver RecommendationCardPresenter)
        // — p.ej. "T1 · E3" en Historial/Continuar viendo. A propósito
        // separado del título: el título viaja también en el deep link al
        // reabrir (RecommendationOpenActivity) y al volver a grabar en el
        // historial, así que no debe llevar nada añadido.
        val subtitle: String? = null
    ) {
        val type: MediaType get() = if (mediaPath == "tv") MediaType.SERIES else MediaType.MOVIE
    }

    /** Títulos "más como este" para [tmdbId] (una entrada del historial de
     * lo que el usuario ya ha abierto) — usa el propio endpoint de
     * recomendaciones de TMDb, sin ningún LLM: es gratis, no alucina
     * títulos que no existen, y ya tenemos la key. */
    fun fetchRecommendations(tmdbId: Int, mediaPath: String): List<TmdbRecommendation> {
        val language = Locale.getDefault().toLanguageTag()
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId/recommendations?api_key=$TMDB_API_KEY&language=$language"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "recommendations ($mediaPath/$tmdbId) falló: ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
                val out = mutableListOf<TmdbRecommendation>()
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    val title = r.optString("title", r.optString("name", "")).takeIf { it.isNotBlank() } ?: continue
                    val posterPath = r.optStringOrNull("poster_path")
                    val overview = r.optStringOrNull("overview")
                    out.add(TmdbRecommendation(r.getInt("id"), mediaPath, title, posterPath, r.optDouble("popularity", 0.0), overview))
                }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo recomendaciones ($mediaPath/$tmdbId)", e)
            emptyList()
        }
    }

    /** El próximo episodio anunciado de una serie (ver UpcomingEpisodesEngine,
     * el calendario de "Próximos episodios") — null si ya terminó o TMDb
     * todavía no ha anunciado fecha. Llamada directa a TMDb, igual que
     * [fetchRecommendations]: no es la función de pago, no pasa por el
     * backend.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun fetchNextEpisode(tmdbId: Int): NextEpisodeInfo? {
        val language = Locale.getDefault().toLanguageTag()
        val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_API_KEY&language=$language"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val next = JSONObject(body).optJSONObject("next_episode_to_air") ?: return null
                val airDate = next.optStringOrNull("air_date") ?: return null
                NextEpisodeInfo(
                    season = next.optInt("season_number", 0),
                    episode = next.optInt("episode_number", 0),
                    airDate = airDate
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo next_episode_to_air ($tmdbId)", e)
            null
        }
    }

    /** Ficha completa para la pantalla de detalle propia (ver DetailScreen) —
     * llamada directa a TMDb, igual que [fetchRecommendations]: es solo
     * metadata para mostrar (póster, backdrop, nota, año, duración), no la
     * resolución a imdb_id que sí pasa por el backend y exige suscripción.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun fetchDetails(tmdbId: Int, mediaPath: String): TmdbDetails? {
        val language = Locale.getDefault().toLanguageTag()
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId?api_key=$TMDB_API_KEY&language=$language"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "details ($mediaPath/$tmdbId) falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val title = json.optString("title", json.optString("name", "")).takeIf { it.isNotBlank() } ?: return null
                val date = json.optStringOrNull("release_date") ?: json.optStringOrNull("first_air_date")
                val year = date?.takeIf { it.length >= 4 }?.substring(0, 4)
                // Películas traen "runtime" directo; series traen
                // "episode_run_time" (lista, puede venir vacía si TMDb no lo
                // tiene catalogado) — se usa el primer valor como duración
                // típica de un episodio.
                val runtime = if (mediaPath == "tv") {
                    json.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.getInt(0) else null }
                } else {
                    json.optInt("runtime", 0).takeIf { it > 0 }
                }
                TmdbDetails(
                    tmdbId = tmdbId,
                    mediaPath = mediaPath,
                    title = title,
                    overview = json.optStringOrNull("overview"),
                    posterPath = json.optStringOrNull("poster_path"),
                    backdropPath = json.optStringOrNull("backdrop_path"),
                    voteAverage = json.optDouble("vote_average", 0.0),
                    year = year,
                    runtimeMinutes = runtime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo detalles ($mediaPath/$tmdbId)", e)
            null
        }
    }

    /** Busca en TMDb el equivalente a un IMDb ID (formato "tt1234567") —
     * usado cuando se comparte un enlace de IMDb o el esquema propio
     * `tvbridge://movie/{imdbId}` desde otra app (ver ShareTargetActivity):
     * ahí se tiene el IMDb ID pero hace falta el TMDb ID para todo lo demás
     * (ficha de detalle, resolución final). Llamada directa a TMDb, no
     * gated — es solo una búsqueda de equivalencia de IDs, no la resolución
     * de pago.
     *
     * Bloqueante (red) — llamar siempre desde un hilo de fondo. */
    fun findByImdbId(imdbId: String): TmdbCandidate? {
        val url = "https://api.themoviedb.org/3/find/$imdbId?api_key=$TMDB_API_KEY&external_source=imdb_id"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "find ($imdbId) falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val movieResults = json.optJSONArray("movie_results")
                if (movieResults != null && movieResults.length() > 0) {
                    return toCandidate(movieResults.getJSONObject(0), "movie")
                }
                val tvResults = json.optJSONArray("tv_results")
                if (tvResults != null && tvResults.length() > 0) {
                    return toCandidate(tvResults.getJSONObject(0), "tv")
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error buscando por IMDb ID ($imdbId)", e)
            null
        }
    }

    private fun toCandidate(json: JSONObject, mediaPath: String): TmdbCandidate {
        val title = json.optString("title", json.optString("name", ""))
        val date = json.optStringOrNull("release_date") ?: json.optStringOrNull("first_air_date")
        val year = date?.takeIf { it.length >= 4 }?.substring(0, 4)
        return TmdbCandidate(
            tmdbId = json.getInt("id"),
            mediaPath = mediaPath,
            title = title,
            year = year,
            posterPath = json.optStringOrNull("poster_path"),
            overview = json.optStringOrNull("overview")
        )
    }

    /** ID de vídeo de YouTube del tráiler de [tmdbId], o null si no hay
     * ninguno publicado — se pide al vuelo cuando el usuario enfoca una
     * recomendación (ver RecommendationsHomeFragment), no de golpe para
     * toda la fila, mismo motivo que [fetchImdbId]. Prioriza un tráiler
     * marcado "official" si hay varios.
     *
     * TMDb filtra /videos por idioma (por defecto en-US si no se manda
     * ninguno) en vez de devolver todos y traducir solo el nombre — muchos
     * títulos no tienen tráiler cargado en el idioma del dispositivo, así
     * que se prueba primero con ese idioma y, si no hay nada, se repite sin
     * parámetro (cae al inglés) antes de darse por vencido. */
    fun fetchTrailerKey(tmdbId: Int, mediaPath: String): String? {
        val language = Locale.getDefault().toLanguageTag()
        return fetchTrailerKey(tmdbId, mediaPath, language) ?: fetchTrailerKey(tmdbId, mediaPath, language = null)
    }

    private fun fetchTrailerKey(tmdbId: Int, mediaPath: String, language: String?): String? {
        val languageParam = if (language != null) "&language=$language" else ""
        val url = "https://api.themoviedb.org/3/$mediaPath/$tmdbId/videos?api_key=$TMDB_API_KEY$languageParam"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "videos ($mediaPath/$tmdbId) falló: ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val results = JSONObject(body).optJSONArray("results") ?: return null
                val trailers = (0 until results.length())
                    .map { results.getJSONObject(it) }
                    .filter { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }
                // TMDb etiqueta con el mismo idioma tanto un tráiler doblado
                // como su versión "VOSE" (audio original, solo subtítulos
                // traducidos) — sin este filtro, un VOSE en la respuesta
                // antes que el doblado real hace que se abra con audio en el
                // idioma original igualmente. No hay otro campo que los
                // distinga, así que se descarta por el nombre.
                val dubbed = trailers.filterNot {
                    VOSE_MARKER_REGEX.containsMatchIn(it.optString("name"))
                }
                val candidates = dubbed.ifEmpty { trailers }
                val best = candidates.firstOrNull { it.optBoolean("official", false) } ?: candidates.firstOrNull()
                best?.optString("key")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo tráiler ($mediaPath/$tmdbId)", e)
            null
        }
    }

}
