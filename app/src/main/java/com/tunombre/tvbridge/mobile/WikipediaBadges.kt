package com.tunombre.tvbridge.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * Ultimo recurso para el escudo de un equipo que ni el catalogo empaquetado ni TheSportsDB tienen: el logo del articulo
 * del equipo en la Wikipedia inglesa (casi todos los clubes de cualquier deporte tienen articulo). Dos peticiones por
 * equipo, resultado guardado en disco (acierto o fallo) para no repetirlas. Devuelve la URL de la miniatura o null.
 */
object WikipediaBadges {
    private const val TAG = "WikiBadges"
    private const val API = "https://en.wikipedia.org/w/api.php"
    private const val PREFS = "wiki_badges"
    private const val MISS_TTL_MS = 3L * 24 * 3600_000

    private var prefs: SharedPreferences? = null
    private val cache = HashMap<String, String>()   // clave -> url ("" = no hay)
    private val missAt = HashMap<String, Long>()

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        try {
            val root = JSONObject(p.getString("data", null) ?: return)
            root.optJSONObject("urls")?.let { o -> o.keys().forEach { cache[it] = o.getString(it) } }
            root.optJSONObject("misses")?.let { o -> o.keys().forEach { missAt[it] = o.getLong(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "Cache ilegible, se ignora", e)
        }
    }

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val urls = JSONObject().also { o -> cache.forEach { (k, v) -> o.put(k, v) } }
        val misses = JSONObject().also { o -> missAt.filter { now - it.value < MISS_TTL_MS }.forEach { (k, v) -> o.put(k, v) } }
        p.edit().putString("data", JSONObject().put("urls", urls).put("misses", misses).toString()).apply()
    }

    // Lo que se anade a la busqueda segun el deporte de TheSportsDB, para llegar al club y no a una ciudad o un jugador.
    private fun termFor(sport: String?) = when (sport) {
        "Soccer" -> "football club"
        "Basketball" -> "basketball team"
        "American Football" -> "American football team"
        "Ice Hockey" -> "ice hockey team"
        "Baseball" -> "baseball team"
        "Rugby" -> "rugby team"
        "Handball" -> "handball club"
        "Volleyball" -> "volleyball club"
        "Cricket" -> "cricket team"
        else -> "team"
    }

    private val LOGO_NAME = Regex("logo|crest|badge|emblem|escudo|escut|shield|wappen|brasao", RegexOption.IGNORE_CASE)
    private val NOT_A_LOGO = Regex(
        "kit|shirt|jersey|stadium|flag|icon|commons-logo|wikidata-logo|wikimedia|wikiquote|wikisource|wiktionary|wikinews|wikibooks|" +
            "wikiversity|wikivoyage|ambox|question|edit|padlock|symbol|united nations|un emblem|semi-protection|portal|\\.jpe?g$|\\.gif$",
        RegexOption.IGNORE_CASE
    )
    private val BAD_TITLE = Regex("season|players|squad|stadium|history|list of|\\bvs\\b|^\\d{4}", RegexOption.IGNORE_CASE)

    /** Palabras que no distinguen un equipo de otro en el titulo de un articulo. */
    private val TITLE_GENERIC = setOf("football", "club", "basketball", "team", "fc", "cf", "bc", "cb", "sc", "ac", "afc", "the", "of", "de", "del", "la", "el")

    private fun words(text: String) = SportsEvents.normalize(text).trim().split(' ').filter { it.isNotEmpty() && it !in TITLE_GENERIC }

    /** Bloquea (red): llamar desde un hilo de fondo. */
    fun find(name: String, sport: String?): String? {
        val key = "${sport.orEmpty()}|$name"
        synchronized(this) {
            cache[key]?.let { return it.ifEmpty { null } }
            missAt[key]?.let { if (System.currentTimeMillis() - it < MISS_TTL_MS) return null }
        }
        val url = try {
            lookUp(SportsDbClient.canonicalName(name, sport ?: ""), sport)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo buscando '$name'", e)
            return null   // un fallo de red no se guarda como "no hay"
        }
        synchronized(this) {
            if (url != null) cache[key] = url else missAt[key] = System.currentTimeMillis()
        }
        persist()
        return url
    }

    private fun lookUp(name: String, sport: String?): String? {
        val query = words(name)
        if (query.isEmpty()) return null
        val search = API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", "$name ${termFor(sport)}")
            .addQueryParameter("gsrlimit", "4").addQueryParameter("prop", "images").addQueryParameter("imlimit", "120")
            .build()
        val pages = get(search)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val ordered = pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.toList()
        for (page in ordered) {
            val title = page.optString("title")
            if (BAD_TITLE.containsMatchIn(title)) continue
            val titleWords = words(title.replace(Regex("\\(.*?\\)"), ""))
            // El articulo tiene que ser del equipo buscado: todas sus palabras distintivas estan en el titulo.
            if (!titleWords.toSet().containsAll(query)) continue
            val images = page.optJSONArray("images") ?: continue
            val names = (0 until images.length()).map { images.getJSONObject(it).optString("title") }
                .filter { !NOT_A_LOGO.containsMatchIn(it) }
            val logo = names.firstOrNull { LOGO_NAME.containsMatchIn(it) }
                // Sin la palabra "logo" en el nombre: el primer archivo que comparte alguna palabra con el titulo.
                ?: names.firstOrNull { file -> words(file.substringAfter(':').substringBeforeLast('.')).any { it in titleWords } }
                ?: continue
            return thumbnail(logo)
        }
        return null
    }

    private fun thumbnail(file: String): String? {
        val info = API.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("titles", file).addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url").addQueryParameter("iiurlwidth", "160")
            .build()
        val pages = get(info)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return null
        return page.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("thumburl")?.takeIf { it.isNotBlank() }
    }

    private fun get(url: okhttp3.HttpUrl): JSONObject? {
        val request = Request.Builder().url(url).header("User-Agent", "WatchBridgeTV/1.0 (https://watchbridge.app)").build()
        return sharedHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let { JSONObject(it) }
        }
    }
}
