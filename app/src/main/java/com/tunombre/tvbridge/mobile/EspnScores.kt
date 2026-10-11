package com.tunombre.tvbridge.mobile

import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Marcadores en directo desde el marcador publico de ESPN (JSON abierto, sin clave ni cupo estricto): marcador, minuto
 * y goles/tarjetas de los partidos en juego. TheSportsDB (clave gratuita) no trae la linea de tiempo de la mayoria de
 * partidos ni el marcador en directo de varios deportes, asi que esta es la fuente principal y TheSportsDB el respaldo
 * (ver [SportsDbClient.findEvent], [SportsDbClient.fetchState] y [SportsDbClient.fetchTimeline]).
 */
object EspnScores {
    private const val TAG = "EspnScores"
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
    // Cache corta: un gol tiene que verse en segundos. ESPN no limita peticiones de forma estricta y una misma respuesta
    // sirve a todos los paneles (mini-reproductor y pantalla completa).
    private const val CACHE_MS = 6_000L

    /** Cada cuanto conviene volver a pedir un partido de ESPN (los de TheSportsDB siguen a 30 s por su cupo). */
    const val POLL_MS = 10_000L
    const val PREFIX = "espn:"

    // Ligas de futbol donde buscar, por orden. Las que dice el texto del programa van primero.
    private val SOCCER_DEFAULT = listOf(
        "esp.1", "esp.2", "eng.1", "ita.1", "ger.1", "fra.1", "uefa.champions", "uefa.europa", "uefa.europa.conf",
        "ned.1", "por.1", "eng.2", "esp.copa_del_rey", "uefa.nations", "fifa.friendly", "usa.1", "bra.1", "mex.1", "tur.1", "sco.1", "bel.1"
    )
    private val SOCCER_HINTS = listOf(
        "hypermotion" to "esp.2", "laliga" to "esp.1", "la liga" to "esp.1", "premier league" to "eng.1", "championship" to "eng.2",
        "serie a" to "ita.1", "bundesliga" to "ger.1", "ligue 1" to "fra.1", "champions" to "uefa.champions",
        "europa league" to "uefa.europa", "conference league" to "uefa.europa.conf", "copa del rey" to "esp.copa_del_rey",
        "eredivisie" to "ned.1", "primeira" to "por.1", "liga portugal" to "por.1", "mls" to "usa.1", "brasileirao" to "bra.1",
        "liga mx" to "mex.1", "nations league" to "uefa.nations", "liga de naciones" to "uefa.nations"
    )
    private val OTHER_LEAGUES = mapOf(
        "Basketball" to listOf("basketball/nba", "basketball/wnba", "basketball/fiba"),
        "American Football" to listOf("football/nfl"),
        "Ice Hockey" to listOf("hockey/nhl"),
        "Baseball" to listOf("baseball/mlb")
    )

    private fun leagues(sport: String, hint: String): List<String> {
        if (sport != SportsDbClient.SOCCER) return OTHER_LEAGUES[sport] ?: emptyList()
        val text = SportsEvents.normalize(hint)
        val first = SOCCER_HINTS.filter { (word, _) -> text.contains(" ${SportsEvents.normalize(word).trim()} ") }.map { "soccer/${it.second}" }
        return (first + SOCCER_DEFAULT.map { "soccer/$it" }).distinct()
    }

    private class Cached(val at: Long, val json: JSONObject?)

    private val cache = HashMap<String, Cached>()

    private fun scoreboard(path: String, fresh: Boolean = false): JSONObject? {
        synchronized(cache) {
            cache[path]?.let { if (!fresh && System.currentTimeMillis() - it.at < CACHE_MS) return it.json }
        }
        val json = try {
            val request = Request.Builder().url("$BASE/$path/scoreboard").header("User-Agent", "Mozilla/5.0 (WatchBridgeTV)").build()
            sharedHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sin marcador de $path", e)
            null
        }
        synchronized(cache) { cache[path] = Cached(System.currentTimeMillis(), json) }
        return json
    }

    /** Un partido de ESPN identificado por nombre. [homeIsFirst]: el local de ESPN es el primer nombre buscado. */
    class Found(val ref: String, val homeIsFirst: Boolean)

    private val notFound = HashMap<String, Long>()

    /** Busca en ESPN el partido [first] - [second] que empieza cerca de [startMs]; null si no esta (otra liga, otro
     * deporte sin cobertura...). [hint] = texto del programa, para mirar primero la liga que dice. Bloquea (red). */
    fun find(first: String, second: String, sport: String, startMs: Long, hint: String): Found? {
        val key = "$sport|$first|$second|${startMs / 3_600_000L}"
        synchronized(notFound) { notFound[key]?.let { if (System.currentTimeMillis() - it < 5 * 60_000L) return null } }
        for (path in leagues(sport, hint)) {
            val events = scoreboard(path)?.optJSONArray("events") ?: continue
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val date = parseDate(event.optString("date")) ?: continue
                if (kotlin.math.abs(date - startMs) > 6 * 3_600_000L) continue
                val competitors = event.optJSONArray("competitions")?.optJSONObject(0)?.optJSONArray("competitors") ?: continue
                val home = competitor(competitors, "home") ?: continue
                val away = competitor(competitors, "away") ?: continue
                val direct = score(first, home) >= SportsDbClient.NAME_MATCH && score(second, away) >= SportsDbClient.NAME_MATCH
                val swapped = score(first, away) >= SportsDbClient.NAME_MATCH && score(second, home) >= SportsDbClient.NAME_MATCH
                if (direct || swapped) return Found("$PREFIX$path:${event.optString("id")}", direct)
            }
        }
        synchronized(notFound) { notFound[key] = System.currentTimeMillis() }
        return null
    }

    private fun competitor(competitors: JSONArray, side: String): JSONObject? =
        (0 until competitors.length()).map { competitors.getJSONObject(it) }.firstOrNull { it.optString("homeAway") == side }

    private fun score(name: String, competitor: JSONObject): Int {
        val team = competitor.optJSONObject("team") ?: return 0
        val names = listOf("displayName", "shortDisplayName", "name", "location").map { team.optString(it) }
        // Selecciones: la guia dice "España" y ESPN "Spain"; se comparan por pais.
        val country = CountryFlags.codeOf(name)
        if (country != null && names.any { CountryFlags.codeOf(it) == country }) return 100
        return names.maxOf { SportsDbClient.nameScore(name, it) }
    }

    private fun parseDate(iso: String): Long? = try {
        // "2026-10-11T12:00Z" (sin segundos)
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(iso)?.time
    } catch (e: Exception) {
        null
    }

    /** Lo que ESPN dice de un partido ahora mismo. [home]/[away] son los del propio ESPN. */
    class Snapshot(
        val phase: SportsDbClient.MatchPhase,
        val homeScore: Int?,
        val awayScore: Int?,
        val minute: String?,
        val label: String?,
        val homeTeamId: String,
        val details: List<JSONObject>
    )

    private fun parse(ref: String): Pair<String, String>? {
        val body = ref.removePrefix(PREFIX)
        val cut = body.lastIndexOf(':')
        return if (cut < 0) null else body.substring(0, cut) to body.substring(cut + 1)
    }

    fun snapshot(ref: String, fresh: Boolean = false): Snapshot? {
        val (path, id) = parse(ref) ?: return null
        val events = scoreboard(path, fresh)?.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            if (event.optString("id") != id) continue
            val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: return null
            val competitors = competition.optJSONArray("competitors") ?: return null
            val home = competitor(competitors, "home") ?: return null
            val away = competitor(competitors, "away") ?: return null
            val type = event.optJSONObject("status")?.optJSONObject("type")
            val state = type?.optString("state").orEmpty()
            val name = type?.optString("name").orEmpty()
            val phase = when {
                state == "post" -> SportsDbClient.MatchPhase.FINISHED
                state == "in" && name.contains("HALFTIME") -> SportsDbClient.MatchPhase.HALF_TIME
                state == "in" -> SportsDbClient.MatchPhase.LIVE
                else -> SportsDbClient.MatchPhase.NOT_STARTED
            }
            val soccer = path.startsWith("soccer/")
            val clock = event.optJSONObject("status")?.optString("displayClock").orEmpty().replace("'", "").trim()
            val details = competition.optJSONArray("details")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            return Snapshot(
                phase, home.optString("score").toIntOrNull(), away.optString("score").toIntOrNull(),
                minute = if (soccer && clock.isNotEmpty()) clock else null,
                label = if (soccer) null else type?.optString("shortDetail")?.takeIf { it.isNotBlank() },
                homeTeamId = home.optJSONObject("team")?.optString("id").orEmpty(),
                details = details
            )
        }
        return null
    }

    /** Goles y tarjetas del partido, con [homeId]/[awayId] = los ids (de [SportsDbClient]) de quien juega en casa y fuera. */
    fun timeline(ref: String, homeId: String, awayId: String): List<SportsDbClient.TimelineItem> {
        val snapshot = snapshot(ref) ?: return emptyList()   // misma respuesta (en cache) que acaba de usar fetchState
        val items = ArrayList<SportsDbClient.TimelineItem>()
        for (detail in snapshot.details) {
            val kind = when {
                detail.optBoolean("scoringPlay") -> SportsDbClient.TimelineKind.GOAL
                detail.optBoolean("redCard") -> SportsDbClient.TimelineKind.RED
                detail.optBoolean("yellowCard") -> SportsDbClient.TimelineKind.YELLOW
                else -> continue
            }
            val clock = detail.optJSONObject("clock")?.optString("displayValue").orEmpty()
            val minute = Regex("\\d+").find(clock)?.value?.toIntOrNull() ?: 0
            val player = detail.optJSONArray("athletesInvolved")?.optJSONObject(0)?.optString("displayName").orEmpty()
            val typeText = detail.optJSONObject("type")?.optString("text").orEmpty()
            val teamEspnId = detail.optJSONObject("team")?.optString("id").orEmpty()
            items.add(
                SportsDbClient.TimelineItem(
                    id = "$clock|$typeText|$player", minute = minute, kind = kind, player = player, assist = null,
                    teamId = if (teamEspnId == snapshot.homeTeamId) homeId else awayId,
                    detail = typeText
                )
            )
        }
        return items.sortedBy { it.minute }
    }
}
