package com.tunombre.tvbridge.mobile

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Una entrada del catalogo de un panel Xtream: una pelicula o una serie (las dos se muestran en la misma
 * rejilla, ver [IptvVodActivity]). [extension] es el `container_extension` de la pelicula (mkv, mp4...);
 * una serie no la tiene (cada episodio trae la suya) y queda vacia.
 */
data class VodMovie(
    val id: Int,
    val name: String,
    val posterUrl: String?,
    val categoryId: String?,
    val extension: String,
    val rating: String?
) {
    /** El titulo sin tildes ni mayusculas, para buscar sin que importen. */
    val searchKey: String = normalize(name)

    companion object {
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
    }
}

/** Lo que el panel cuenta de una pelicula o serie (sinopsis, reparto...); todo opcional, cada panel rellena lo suyo. */
data class VodInfo(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val duration: String? = null,
    /** Valoracion sobre 10, si el panel la da. */
    val rating: Double? = null,
    val posterUrl: String? = null
)

data class SeriesEpisode(
    val id: Int,
    val season: Int,
    val number: Int,
    val title: String,
    val extension: String,
    val duration: String?,
    val plot: String?
)

/** Ficha de una serie: lo que cuenta el panel y todos sus episodios (las temporadas salen de ellos). */
data class SeriesDetail(val info: VodInfo, val episodes: List<SeriesEpisode>) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()
    fun episodesOf(season: Int): List<SeriesEpisode> = episodes.filter { it.season == season }.sortedBy { it.number }
}

/** Las entradas del catalogo y sus categorias (id -> nombre, en el orden del panel). */
data class VodCatalog(val categories: LinkedHashMap<String, String>, val movies: List<VodMovie>)

enum class VodKind(val listAction: String, val categoriesAction: String) {
    MOVIES("get_vod_streams", "get_vod_categories"),
    SERIES("get_series", "get_series_categories")
}

/**
 * Peliculas (VOD) y series de un panel Xtream Codes, por su API (`player_api.php`). Reproducir una pelicula
 * es pedir `/movie/usuario/clave/id.extension`, y un episodio `/series/usuario/clave/idEpisodio.extension`,
 * en el mismo servidor de streams que usan los canales (ver [XtreamApiPlaylist.streamBase]).
 */
object XtreamVod {

    /** Cache en memoria del ultimo catalogo de cada tipo (un panel puede tener miles de entradas). */
    private val cachedKeys = java.util.concurrent.ConcurrentHashMap<VodKind, String>()
    private val cachedCatalogs = java.util.concurrent.ConcurrentHashMap<VodKind, VodCatalog>()

    fun cached(kind: VodKind, server: String, user: String): VodCatalog? =
        cachedCatalogs[kind]?.takeIf { cachedKeys[kind] == "$server|$user" }

    private fun apiBase(server: String, user: String, pass: String) =
        "$server/player_api.php?username=${XtreamApiPlaylist.enc(user)}&password=${XtreamApiPlaylist.enc(pass)}"

    private fun longClient(client: OkHttpClient) =
        client.newBuilder().callTimeout(90, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()

    /** El catalogo del panel; null si no responde o no autentica. Vacio si el panel no ofrece ese contenido. */
    fun fetch(client: OkHttpClient, kind: VodKind, server: String, user: String, pass: String): VodCatalog? = try {
        val api = longClient(client)
        val base = apiBase(server, user, pass)
        val list = XtreamApiPlaylist.get(api, "$base&action=${kind.listAction}")?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (list == null) {
            null
        } else {
            val categories = XtreamApiPlaylist.get(api, "$base&action=${kind.categoriesAction}")
                ?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?.let(::parseCategories) ?: LinkedHashMap()
            val entries = if (kind == VodKind.MOVIES) parseMovies(list) else parseSeries(list)
            VodCatalog(categories, entries).also {
                cachedKeys[kind] = "$server|$user"
                cachedCatalogs[kind] = it
            }
        }
    } catch (e: Exception) {
        null
    }

    /** La ficha de una pelicula (`get_vod_info`); null si el panel no la da. */
    fun fetchMovieInfo(client: OkHttpClient, server: String, user: String, pass: String, id: Int): VodInfo? = try {
        XtreamApiPlaylist.get(longClient(client), "${apiBase(server, user, pass)}&action=get_vod_info&vod_id=$id")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { parseInfo(it.optJSONObject("info")) }
    } catch (e: Exception) {
        null
    }

    /** La ficha y los episodios de una serie (`get_series_info`); null si el panel no responde. */
    fun fetchSeriesDetail(client: OkHttpClient, server: String, user: String, pass: String, id: Int): SeriesDetail? = try {
        XtreamApiPlaylist.get(longClient(client), "${apiBase(server, user, pass)}&action=get_series_info&series_id=$id")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { parseSeriesDetail(it) }
    } catch (e: Exception) {
        null
    }

    internal fun parseCategories(array: JSONArray): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name").trim()
            if (id.isNotEmpty() && name.isNotEmpty()) out[id] = name
        }
        return out
    }

    internal fun parseMovies(array: JSONArray): List<VodMovie> {
        val out = ArrayList<VodMovie>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("stream_id", 0)
            val name = item.optString("name").trim()
            if (id <= 0 || name.isEmpty()) continue
            out.add(
                VodMovie(
                    id = id,
                    name = name,
                    posterUrl = item.optString("stream_icon").takeIf { it.startsWith("http") },
                    categoryId = item.optString("category_id").takeIf { it.isNotEmpty() },
                    extension = item.optString("container_extension").trim().trimStart('.').ifEmpty { "mp4" },
                    rating = item.optString("rating").trim().takeIf { it.isNotEmpty() && it != "0" }
                )
            )
        }
        return out
    }

    internal fun parseSeries(array: JSONArray): List<VodMovie> {
        val out = ArrayList<VodMovie>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("series_id", 0)
            val name = item.optString("name").trim()
            if (id <= 0 || name.isEmpty()) continue
            out.add(
                VodMovie(
                    id = id,
                    name = name,
                    posterUrl = item.optString("cover").takeIf { it.startsWith("http") },
                    categoryId = item.optString("category_id").takeIf { it.isNotEmpty() },
                    extension = "",
                    rating = item.optString("rating").trim().takeIf { it.isNotEmpty() && it != "0" }
                )
            )
        }
        return out
    }

    /** `info` de una pelicula o serie. Algunos paneles mandan un array vacio en vez de un objeto: devuelve null. */
    internal fun parseInfo(info: JSONObject?): VodInfo? {
        if (info == null) return null
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            info.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        }
        val rating10 = text("rating")?.toDoubleOrNull()?.takeIf { it > 0 }
            ?: text("rating_5based")?.toDoubleOrNull()?.takeIf { it > 0 }?.times(2)
        val release = text("releasedate", "releaseDate", "release_date", "year")
        val year = release?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
        val duration = text("duration_secs")?.toLongOrNull()?.takeIf { it > 0 }?.let(::formatSeconds) ?: text("duration")
        return VodInfo(
            plot = text("plot", "description"),
            cast = text("cast", "actors"),
            director = text("director"),
            genre = text("genre"),
            year = year,
            duration = duration,
            rating = rating10,
            posterUrl = (text("movie_image", "cover_big", "cover"))?.takeIf { it.startsWith("http") }
        )
    }

    internal fun formatSeconds(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes >= 60) "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min" else "$minutes min"
    }

    internal fun parseSeriesDetail(root: JSONObject): SeriesDetail {
        val info = parseInfo(root.optJSONObject("info")) ?: VodInfo()
        val episodes = ArrayList<SeriesEpisode>()
        fun add(item: JSONObject, seasonKey: Int?) {
            val id = item.optString("id").toIntOrNull() ?: return
            val season = item.optInt("season", seasonKey ?: 0).let { if (it > 0) it else seasonKey ?: 1 }
            val episodeInfo = item.optJSONObject("info")
            episodes.add(
                SeriesEpisode(
                    id = id,
                    season = season,
                    number = item.optInt("episode_num", 0),
                    title = item.optString("title").trim().ifEmpty { "" },
                    extension = item.optString("container_extension").trim().trimStart('.').ifEmpty { "mp4" },
                    duration = episodeInfo?.optString("duration")?.trim()?.takeIf { it.isNotEmpty() && it != "null" },
                    plot = episodeInfo?.optString("plot")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                )
            )
        }
        // "episodes" llega como objeto {"1": [..], "2": [..]} o, en algunos paneles, como array de arrays.
        when (val raw = root.opt("episodes")) {
            is JSONObject -> raw.keys().forEach { key ->
                val list = raw.optJSONArray(key) ?: return@forEach
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { add(it, key.toIntOrNull()) }
            }
            is JSONArray -> for (s in 0 until raw.length()) {
                val list = raw.optJSONArray(s) ?: continue
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { add(it, s + 1) }
            }
        }
        return SeriesDetail(info, episodes)
    }

    /** `/movie/...` o `/series/...` con usuario, clave, id y extension, en el servidor de streams [streamBase]. */
    fun playUrl(streamBase: String, user: String, pass: String, series: Boolean, id: Int, extension: String): String =
        "$streamBase/${if (series) "series" else "movie"}/${XtreamApiPlaylist.enc(user)}/${XtreamApiPlaylist.enc(pass)}/$id.$extension"

    /** La direccion de reproduccion de [movie] en el servidor de streams [streamBase]. */
    fun movieUrl(streamBase: String, user: String, pass: String, movie: VodMovie): String =
        playUrl(streamBase, user, pass, series = false, id = movie.id, extension = movie.extension)
}
