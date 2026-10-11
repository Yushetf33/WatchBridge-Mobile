package com.tunombre.tvbridge.mobile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * TheSportsDB (API pública gratuita, clave de prueba "3" — sin registro,
 * pensada exactamente para este uso puntual) — escudo de un equipo por
 * nombre y los marcadores de sus últimos partidos ya jugados, para el panel
 * de deportes junto al mini-reproductor de IPTV (ver
 * IptvChannelsFragment.updateSportsPanel). Nada que ver con TMDb
 * ([TmdbClient]): esto es deporte, no cine/series.
 */
object SportsDbClient {
    private const val API_KEY = "3"
    private const val BASE = "https://www.thesportsdb.com/api/v1/json/$API_KEY"

    data class Team(val id: String, val name: String, val badgeUrl: String?, val leagueId: String?)

    private const val TAG = "SportsDb"

    // --- Caché en disco -------------------------------------------------------
    // La clave gratuita da ~30 peticiones por minuto y resolver un partido
    // nuevo cuesta varias — sin guardar nada entre sesiones, cada vez que se
    // abría IPTV había que volver a pagar ese cupo desde cero. Lo ya resuelto
    // (equipos de un partido y su evento) se guarda aquí y sobrevive al cierre
    // de la app; los partidos de la guía se resuelven además por adelantado en
    // segundo plano (ver IptvChannelsFragment.prefetchSportsMatches).
    private const val PREFS_NAME = "sportsdb_cache"
    private const val KEY_DATA = "data"
    private const val MAX_PERSISTED = 400

    // Subir al cambiar la forma de resolver equipos: lo guardado con la logica vieja puede tener homonimos equivocados.
    private const val CACHE_VERSION = 2
    private var prefs: SharedPreferences? = null

    private var appContext: Context? = null

    fun attach(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val root = JSONObject(prefs?.getString(KEY_DATA, null) ?: return)
            if (root.optInt("v", 1) != CACHE_VERSION) return
            root.optJSONObject("matches")?.let { m ->
                m.keys().forEach { k ->
                    val a = m.getJSONArray(k)
                    matchTeamCache[k] = teamFromJson(a.getJSONObject(0)) to teamFromJson(a.getJSONObject(1))
                }
            }
            root.optJSONObject("teams")?.let { t ->
                t.keys().forEach { k -> teamCache[k] = teamFromJson(t.getJSONObject(k)) }
            }
            root.optJSONObject("misses")?.let { m ->
                m.keys().forEach { k -> missCache[k] = m.getLong(k) }
            }
            root.optJSONObject("events")?.let { e ->
                e.keys().forEach { k ->
                    val a = e.getJSONArray(k)
                    eventCache[k] = EventRef(a.getString(0), a.getString(1))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Caché en disco ilegible, se ignora", e)
        }
    }

    private fun teamToJson(t: Team) = JSONObject()
        .put("id", t.id).put("name", t.name).put("badge", t.badgeUrl ?: JSONObject.NULL).put("league", t.leagueId ?: JSONObject.NULL)

    private fun teamFromJson(o: JSONObject) =
        Team(o.getString("id"), o.getString("name"), o.optStringOrNull("badge"), o.optStringOrNull("league"))

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        try {
            val matches = JSONObject()
            matchTeamCache.entries.toList().takeLast(MAX_PERSISTED).forEach { (k, v) ->
                matches.put(k, JSONArray().put(teamToJson(v.first)).put(teamToJson(v.second)))
            }
            val events = JSONObject()
            eventCache.entries.toList().takeLast(MAX_PERSISTED).forEach { (k, v) ->
                events.put(k, JSONArray().put(v.id).put(v.homeTeamId))
            }
            val teams = JSONObject()
            teamCache.entries.toList().takeLast(MAX_PERSISTED * 2).forEach { (k, v) -> teams.put(k, teamToJson(v)) }
            val misses = JSONObject()
            val now = System.currentTimeMillis()
            missCache.entries.toList().filter { now - it.value < MISS_TTL_MS }.takeLast(MAX_PERSISTED).forEach { (k, v) -> misses.put(k, v) }
            p.edit().putString(
                KEY_DATA,
                JSONObject().put("v", CACHE_VERSION).put("matches", matches).put("events", events).put("teams", teams).put("misses", misses).toString()
            ).apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar la caché en disco", e)
        }
    }

    // El precálculo en segundo plano (ver IptvChannelsFragment) no debe
    // acaparar el cupo: solo usa la mitad, y deja el resto para lo que el
    // usuario acaba de enfocar.
    private val lowPriority = ThreadLocal<Boolean>()

    fun <T> runLowPriority(block: () -> T): T {
        lowPriority.set(true)
        try {
            return block()
        } finally {
            lowPriority.set(false)
        }
    }

    // Prefijos genéricos de club español que TheSportsDB muchas veces NO
    // incluye en el nombre real del equipo de FÚTBOL (p.ej. el Barça está
    // indexado tal cual como "Barcelona", no "FC Barcelona") — probado a
    // mano contra la API: buscar "FC Barcelona" solo encuentra su sección
    // de RUGBY (coincidencia exacta de nombre), y da "Ciencias Sevilla"
    // como rival reciente en el panel de un partido de LaLiga — mientras
    // que buscar sin el prefijo sí encuentra al equipo de fútbol real. Se
    // usa solo como REINTENTO si la búsqueda tal cual no encuentra ninguna
    // sección de fútbol, nunca como primer intento (para "Valencia CF", que
    // sí funciona directamente, quitar el sufijo sería innecesario).
    private val CLUB_PREFIXES = listOf("FC ", "CF ", "C.F. ", "SD ", "UD ", "RCD ", "CD ", "AD ")

    private fun stripClubPrefix(name: String): String {
        for (prefix in CLUB_PREFIXES) {
            if (name.startsWith(prefix, ignoreCase = true)) return name.removePrefix(prefix).trim()
        }
        return name
    }

    // Los equipos de un partido se piden varias veces (panel del EPG, pantalla
    // completa, refrescos) — la clave gratuita tiene un límite de peticiones
    // por minuto, así que un equipo ya resuelto no se vuelve a buscar.
    private val teamCache = HashMap<String, Team>()

    // Equipos que la API no conoce: no se vuelve a gastar cupo en ellos durante un dia (se guarda en disco).
    private val missCache = HashMap<String, Long>()
    private const val MISS_TTL_MS = 24L * 3600_000L

    /** Un solo equipo por nombre (ver [resolveMatch], que es lo que se usa
     * para un partido y desambigua mejor mirando a los dos a la vez). */
    fun searchTeam(name: String, sport: String = SOCCER): Team? {
        if (name.isBlank()) return null
        val key = "$sport|$name"
        teamCache[key]?.let { return it }
        missCache[key]?.let { if (System.currentTimeMillis() - it < MISS_TTL_MS) return null }
        val failed = BooleanArray(1)
        val best = candidatesFor(name, sport, failed).firstOrNull()?.team
        if (best != null) {
            teamCache[key] = best
            persist()
        } else if (!failed[0]) {
            // Solo si la API contesto de verdad "no lo conozco" (no si fallo la red o se acabo el cupo).
            missCache[key] = System.currentTimeMillis()
            persist()
        }
        return best
    }

    /** true si ya se sabe (de otras visitas) el resultado de buscar este equipo: encontrado o conocido como ausente. */
    fun isTeamKnown(name: String, sport: String = SOCCER): Boolean {
        val key = "$sport|$name"
        val queryTokens = tokens(name)
        if (queryTokens.isNotEmpty() && catalogCandidate(queryTokens, sport, aliasesFor(sport)[queryTokens.joinToString(" ")]?.let { tokens(it).toSet() }) != null) return true
        return teamCache.containsKey(key) || (missCache[key]?.let { System.currentTimeMillis() - it < MISS_TTL_MS } == true)
    }

    /** Resuelve los dos equipos de un partido ("Valencia CF" - "FC Barcelona")
     * a sus fichas de TheSportsDB. A diferencia de buscar cada uno por separado
     * y quedarse con el primer resultado (que fallaba con "R. Sociedad" →
     * Ejea, "Athletic Club" → un equipo de Brasil, "Betis" → el femenino,
     * "Atlético de Madrid" → nada), aquí cada nombre se normaliza (tildes,
     * "R."/"Atl." expandidos, "FC/CF/de" fuera), se prueban varias variantes de
     * búsqueda, TODOS los resultados de fútbol se puntúan por parecido de
     * nombre (incluidos los alias de la API), y se elige la PAREJA que mejor
     * puntúa — con un empujón si los dos juegan en la misma liga, que es lo
     * habitual y es lo que descarta homónimos de otros países. */
    fun resolveMatch(name1: String, name2: String, sport: String = SOCCER): Pair<Team, Team>? {
        val key = "$sport|$name1|$name2"
        matchTeamCache[key]?.let { return it }
        val foreground = lowPriority.get() != true
        if (foreground) foregroundInFlight.incrementAndGet()
        try {
            return resolveUncached(key, name1, name2, sport)
        } finally {
            if (foreground) foregroundInFlight.decrementAndGet()
        }
    }

    private fun resolveUncached(key: String, name1: String, name2: String, sport: String): Pair<Team, Team>? {
        val c1 = candidatesFor(name1, sport).take(4)
        val c2 = candidatesFor(name2, sport).take(4)
        var best: Pair<Candidate, Candidate>? = null
        var bestScore = -1
        for (a in c1) for (b in c2) {
            if (a.team.id == b.team.id) continue
            val sameLeague = a.team.leagueId != null && a.team.leagueId == b.team.leagueId
            val total = a.score + b.score + if (sameLeague) 25 else 0
            if (total > bestScore) {
                bestScore = total
                best = a to b
            }
        }
        val pair = best?.let { it.first.team to it.second.team }
        if (pair == null) {
            Log.w(TAG, "No se reconocen los equipos: '$name1' / '$name2' ($sport) — candidatos ${c1.size}/${c2.size}")
            return null
        }
        matchTeamCache[key] = pair
        persist()
        teamCache["$sport|$name1"] = pair.first
        teamCache["$sport|$name2"] = pair.second
        return pair
    }

    private val matchTeamCache = HashMap<String, Pair<Team, Team>>()

    /** true si los dos equipos de este partido ya están resueltos (en memoria
     * o en la caché de disco) — la interfaz decide con esto si enseña el panel
     * de partido al instante o el texto normal mientras se resuelve. */
    fun isResolved(name1: String, name2: String, sport: String = SOCCER): Boolean =
        matchTeamCache.containsKey("$sport|$name1|$name2")

    // Mientras el usuario espera una resolución en primer plano, el
    // precálculo se aparta (ver acquireSlot) — antes competían por el mismo
    // cupo y el partido recién enfocado podía quedarse esperando turno.
    private val foregroundInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    private class Candidate(val team: Team, val score: Int)

    // Por debajo de esto el parecido es demasiado flojo para fiarse (un equipo
    // equivocado en pantalla es peor que no mostrar nada).
    private const val MIN_SCORE = 45

    // Un parecido del catalogo empaquetado a partir de aqui se da por bueno sin preguntar a la API.
    private const val CATALOG_TRUST = 55

    private val GENERIC_TOKENS = setOf(
        "fc", "cf", "club", "de", "del", "la", "el", "ud", "sd", "rcd", "rc", "cd", "ad", "sc", "ac", "afc", "as", "the", "futbol",
        // Sufijos de deporte/club de baloncesto y balonmano: la API dice
        // "Hapoel Tel Aviv BC" y "Real Madrid Baloncesto"; la guía, "Hapoel
        // Tel Aviv" y "Real Madrid".
        "bc", "bk", "kk", "basket", "baloncesto", "basketball", "balonmano", "handball"
    )
    private val ABBREVIATIONS = mapOf(
        "r" to "real", "atl" to "atletico", "ath" to "athletic", "dep" to "deportivo",
        "utd" to "united", "man" to "manchester", "sporting" to "sporting"
    )
    // Versiones femeninas/juveniles/filiales — se penalizan salvo que el
    // nombre buscado las pida explícitamente.
    private val OFF_TARGET_TOKENS = setOf(
        "feminas", "femenino", "femenina", "women", "womens", "ladies", "u17", "u18", "u19", "u20", "u21", "u23", "b", "ii", "youth", "reserves"
    )

    private fun tokens(name: String): List<String> {
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return plain.split(" ").filter { it.isNotEmpty() }
            .map { ABBREVIATIONS[it] ?: it }
            .filter { it !in GENERIC_TOKENS }
    }

    // Variantes del nombre a buscar en la API: tal cual, sin prefijo de club
    // y el normalizado (sin tildes ni "de"/"FC") — searchteams.php es muy
    // literal ("Atlético de Madrid" no encuentra nada, "Atletico Madrid" sí).
    //
    // searchteams.php solo devuelve equipos cuyo nombre contiene la búsqueda
    // tal cual: "Betis" da solo el femenino (no "Real Betis"), "RCD Espanyol de
    // Barcelona" no da nada, "Athletic Club" da uno de Brasil — por eso, si lo
    // anterior no ha dado un parecido casi exacto, se prueba también
    // "real <nombre>" (nombres de una palabra), cada palabra larga suelta
    // (el club suele ser una de ellas) y un pequeño diccionario de alias para
    // los pocos casos en que el nombre habitual en TV y el de la base de datos
    // no comparten ni la palabra clave.
    private val ALIASES = mapOf(
        "athletic" to "Athletic Bilbao",
        "deportivo" to "Deportivo La Coruna",
        "celta b" to "Celta Fortuna",
        "celta" to "Celta Vigo",
        "bayern" to "Bayern Munich",
        "inter" to "Inter Milan"
    )

    // En baloncesto/balonmano la base de datos nombra a muchos clubes con el
    // deporte como sufijo ("Real Madrid Baloncesto"), y "Real Madrid" a secas
    // solo devuelve el de fútbol — se prueba también con el sufijo.
    private val SPORT_SUFFIXES = mapOf(
        "Basketball" to listOf("Baloncesto", "Basket"),
        "Handball" to listOf("Balonmano", "Handball")
    )

    // La guía suele decir solo el apodo ("Lakers - Celtics") y la API solo
    // encuentra el nombre completo.
    private val NBA_NICKNAMES = mapOf(
        "hawks" to "Atlanta Hawks", "celtics" to "Boston Celtics", "nets" to "Brooklyn Nets",
        "hornets" to "Charlotte Hornets", "bulls" to "Chicago Bulls", "cavaliers" to "Cleveland Cavaliers",
        "cavs" to "Cleveland Cavaliers", "mavericks" to "Dallas Mavericks", "mavs" to "Dallas Mavericks",
        "nuggets" to "Denver Nuggets", "pistons" to "Detroit Pistons", "warriors" to "Golden State Warriors",
        "rockets" to "Houston Rockets", "pacers" to "Indiana Pacers", "clippers" to "Los Angeles Clippers",
        "lakers" to "Los Angeles Lakers", "grizzlies" to "Memphis Grizzlies", "heat" to "Miami Heat",
        "bucks" to "Milwaukee Bucks", "timberwolves" to "Minnesota Timberwolves", "pelicans" to "New Orleans Pelicans",
        "knicks" to "New York Knicks", "thunder" to "Oklahoma City Thunder", "magic" to "Orlando Magic",
        "76ers" to "Philadelphia 76ers", "sixers" to "Philadelphia 76ers", "suns" to "Phoenix Suns",
        "blazers" to "Portland Trail Blazers", "kings" to "Sacramento Kings", "spurs" to "San Antonio Spurs",
        "raptors" to "Toronto Raptors", "jazz" to "Utah Jazz", "wizards" to "Washington Wizards"
    )

    // Clubes de la ACB que la guia nombra distinto de como los guarda la API (la guia dice "Manresa" o "La Laguna
    // Tenerife"; la API, "Basquet Manresa" y "CB 1939 Canarias"). Clave = nombre de la guia ya normalizado (ver
    // [tokens]); valor = el nombre exacto de la API.
    private val ACB_ALIASES = mapOf(
        "manresa" to "Basquet Manresa", "kids us manresa" to "Basquet Manresa",
        "ucam murcia" to "CB Murcia", "murcia" to "CB Murcia",
        "laguna tenerife" to "CB 1939 Canarias", "tenerife" to "CB 1939 Canarias", "lenovo tenerife" to "CB 1939 Canarias",
        "unicaja" to "Baloncesto Málaga", "malaga" to "Baloncesto Málaga",
        "gran canaria" to "CB Gran Canaria", "dreamland gran canaria" to "CB Gran Canaria",
        "breogan" to "CB Breogan", "rio breogan" to "CB Breogan",
        "obradoiro" to "Obradoiro CAB", "monbus obradoiro" to "Obradoiro CAB"
    )

    /** Los alias exactos de un deporte (nombre de la guia normalizado → nombre de la API). */
    private fun aliasesFor(sport: String): Map<String, String> = when (sport) {
        SOCCER -> ALIASES
        "Basketball" -> NBA_NICKNAMES + ACB_ALIASES
        else -> emptyMap()
    }

    /** Umbral de [nameScore] a partir del cual dos nombres se dan por el mismo equipo. */
    const val NAME_MATCH = 45

    /** Parecido (0-100) entre un nombre de equipo de la guia y otro de cualquier otra fuente. */
    fun nameScore(query: String, candidate: String): Int {
        val tokens = tokens(query)
        return if (tokens.isEmpty()) 0 else similarityNames(tokens, listOf(candidate))
    }

    /** El nombre que la base de datos usa para un nombre de la guia, si hay alias ("Deportivo" -> "Deportivo La Coruna"). */
    fun canonicalName(name: String, sport: String): String =
        aliasesFor(sport)[tokens(name).joinToString(" ")] ?: name

    private fun queryVariants(name: String, sport: String): List<String> {
        val t = tokens(name)
        val joined = t.joinToString(" ")
        val variants = mutableListOf(name, stripClubPrefix(name), joined)
        aliasesFor(sport)[joined]?.let { variants.add(0, it) }
        SPORT_SUFFIXES[sport]?.forEach { suffix ->
            variants.add("$name $suffix")
            variants.add("$joined $suffix")
        }
        if (t.size == 1) variants.add("real $joined")
        variants.addAll(t.filter { it.length >= 5 }.sortedByDescending { it.length }.take(2))
        return variants.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    // Escudos de los equipos de las competiciones mas emitidas, empaquetados en la app (res/raw/team_badges.json, generado
    // desde esta misma API con un script aparte). Resuelve al instante y sin gastar el cupo de peticiones; la API solo se usa
    // para lo que no esta aqui.
    private class CatalogEntry(val sport: String, val id: String, val name: String, val alternates: List<String>, val badge: String, val leagueId: String?)

    private val catalog: List<CatalogEntry> by lazy {
        try {
            val context = appContext ?: return@lazy emptyList()
            val array = JSONArray(context.resources.openRawResource(R.raw.team_badges).bufferedReader().use { it.readText() })
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val alts = o.optJSONArray("a")
                CatalogEntry(
                    o.getString("s"), o.optString("i"), o.getString("n"),
                    (0 until (alts?.length() ?: 0)).map { alts!!.getString(it) },
                    o.getString("b"), o.optStringOrNull("l")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer el catalogo de escudos", e)
            emptyList()
        }
    }

    private fun catalogCandidate(queryTokens: List<String>, sport: String, aliasTokens: Set<String>?): Candidate? {
        var best: CatalogEntry? = null
        var bestScore = 0
        var secondScore = 0
        for (entry in catalog) {
            if (entry.sport != sport) continue
            var score = similarityNames(queryTokens, listOf(entry.name) + entry.alternates)
            if (aliasTokens != null && tokens(entry.name).toSet() == aliasTokens) score = 100
            if (score > bestScore) {
                secondScore = bestScore
                best = entry
                bestScore = score
            } else if (score > secondScore) {
                secondScore = score
            }
        }
        // Mismo criterio que con la API: con alias solo vale el candidato exacto, y por debajo de MIN_SCORE no se fia. Un
        // parecido justo ("Joventut" -> "Joventut Badalona") solo vale si no hay otro equipo que se parezca casi igual.
        if (best == null || bestScore < MIN_SCORE || (aliasTokens != null && bestScore < 100)) return null
        if (bestScore < CATALOG_TRUST && secondScore >= MIN_SCORE) return null
        return Candidate(Team(best.id, best.name, best.badge, best.leagueId), bestScore)
    }

    private fun candidatesFor(name: String, sport: String, failed: BooleanArray? = null): List<Candidate> {
        val queryTokens = tokens(name)
        if (queryTokens.isEmpty()) return emptyList()
        val catalogAlias = aliasesFor(sport)[queryTokens.joinToString(" ")]?.let { tokens(it).toSet() }
        catalogCandidate(queryTokens, sport, catalogAlias)?.let { return listOf(it) }
        val found = LinkedHashMap<String, Candidate>()
        // Un alias de la tabla (ALIASES/NBA_NICKNAMES) es el nombre exacto que
        // se quiere: un candidato que se llame así puntúa como coincidencia
        // exacta, aunque "Deportivo" a secas se parezca igual a Deportivo
        // Cali, Deportivo Alavés...
        val aliasTokens = aliasesFor(sport)[queryTokens.joinToString(" ")]?.let { tokens(it).toSet() }
        var variantsTried = 0
        for (variant in queryVariants(name, sport)) {
            val teams = getArray("$BASE/searchteams.php?t=${java.net.URLEncoder.encode(variant, "UTF-8")}", "teams")
            if (teams == null) {
                // searchteams.php devuelve "teams": null si no hay nada, y eso tambien llega aqui como null; solo una
                // peticion que ni llego a hacerse (red, cupo) cuenta como fallo — ver fetchArray.
                if (lastFetchFailed.get() == true) failed?.set(0, true)
                continue
            }
            for (i in 0 until teams.length()) {
                val t = teams.getJSONObject(i)
                if (t.optString("strSport") != sport) continue
                val id = t.optStringOrNull("idTeam") ?: continue
                var score = similarity(queryTokens, t)
                if (aliasTokens != null && tokens(t.optString("strTeam")).toSet() == aliasTokens) score = 100
                if (score < MIN_SCORE) continue
                val team = Team(id, t.optString("strTeam", name), t.optStringOrNull("strBadge"), t.optStringOrNull("idLeague"))
                val previous = found[id]
                if (previous == null || previous.score < score) found[id] = Candidate(team, score)
            }
            // Si ya hay un parecido casi exacto no hace falta gastar más
            // peticiones con las demás variantes — ni si, pasadas las tres
            // primeras, ya hay ALGÚN candidato aceptable (un nombre que nunca
            // llega a 100, como "Real Sporting" → "Sporting de Gijón", se comía
            // todas las variantes: ~9 peticiones por equipo).
            variantsTried++
            if (found.values.any { it.score >= 100 }) break
            if (variantsTried >= 3 && found.isNotEmpty()) break
        }
        // Con alias, solo vale el candidato exacto: si la API no lo devuelve (o fallo la peticion) mas vale ningun escudo que el
        // de un homonimo ("Deportivo" -> Deportivo Cali).
        if (aliasTokens != null && found.values.none { it.score >= 100 }) return emptyList()
        return found.values.sortedByDescending { it.score }
    }

    private fun similarity(query: List<String>, team: JSONObject): Int =
        similarityNames(query, listOf(team.optString("strTeam")) +
            team.optString("strTeamAlternate").split(",").map { it.trim() }.filter { it.isNotEmpty() })

    private fun similarityNames(query: List<String>, names: List<String>): Int {
        val q = query.toSet()
        var best = 0
        for (candidateName in names) {
            val c = tokens(candidateName)
            if (c.isEmpty()) continue
            val cs = c.toSet()
            val matched = q.intersect(cs).size
            if (matched == 0) continue
            val coverage = matched.toDouble() / q.size
            val extra = (cs - q).size
            var score = if (cs == q) 100.0 else 60.0 * coverage - 12.0 * extra
            // El nombre del título puede ser más largo que el de la base de
            // datos ("RCD Espanyol de Barcelona" vs "Espanyol"): si todas las
            // palabras del candidato están en lo buscado, también es válido.
            // En el nombre largo el club suele ir primero y la ciudad al final
            // ("Espanyol" de "RCD Espanyol de Barcelona"), de ahí el empujón
            // si el candidato incluye la primera palabra.
            if (extra == 0 && cs != q) {
                val firstWordBonus = if (query.first() in cs) 5.0 else 0.0
                score = maxOf(score, 55.0 - 5.0 * (q.size - cs.size) + firstWordBonus)
            }
            if (extra > 0 && (cs - q).any { it in OFF_TARGET_TOKENS }) score -= 40.0
            if (score > best) best = score.toInt()
        }
        return best
    }

    data class NextMatch(val opponent: String, val date: String, val venue: String?)

    /** El próximo partido YA CONFIRMADO de este equipo (cualquier
     * competición, no solo liga) — el rival, la fecha ("yyyy-MM-dd", tal
     * cual la da la API) y el estadio si lo trae. null si no hay ninguno
     * programado. Probado a mano: eventsnext.php da resultado para
     * cualquier equipo, a diferencia de lookuptable.php (ver abajo). */
    fun nextMatch(teamId: String): NextMatch? {
        if (teamId.isBlank()) return null
        val events = getArray("$BASE/eventsnext.php?id=$teamId", "events") ?: return null
        if (events.length() == 0) return null
        return try {
            val e = events.getJSONObject(0)
            val home = e.optStringOrNull("strHomeTeam") ?: return null
            val away = e.optStringOrNull("strAwayTeam") ?: return null
            val date = e.optStringOrNull("dateEvent") ?: return null
            val opponent = if (e.optStringOrNull("idHomeTeam") == teamId) away else home
            NextMatch(opponent = opponent, date = date, venue = e.optStringOrNull("strVenue"))
        } catch (e: Exception) {
            null
        }
    }

    // --- Partido en directo: marcador, minuto y línea de tiempo ---------------
    // Comprobado a mano contra la API con la clave gratuita: livescore.php
    // (marcador + minuto de los partidos en juego), lookupevent.php (estado y
    // resultado final) y lookuptimeline.php (goles, tarjetas y cambios con
    // minuto y jugador) responden todos sin clave de pago.

    enum class MatchPhase { NOT_STARTED, LIVE, HALF_TIME, FINISHED }

    /** [score1]/[score2] ya vienen orientados al equipo 1/2 de quien llama
     * (el orden del título del EPG), no al local/visitante de la base de
     * datos — por si el orden del título no coincide con el de la API. */
    data class MatchState(
        val eventId: String,
        val phase: MatchPhase,
        val score1: Int?,
        val score2: Int?,
        val minute: String?,
        // Periodo tal cual lo da la API cuando no hay minuto corrido
        // (baloncesto "Q3", hockey "P1"...) — ver MatchUi.headline.
        val label: String? = null
    )

    data class EventRef(val id: String, val homeTeamId: String)

    enum class TimelineKind { GOAL, YELLOW, RED }

    data class TimelineItem(
        val id: String,
        val minute: Int,
        val kind: TimelineKind,
        val player: String,
        val assist: String?,
        val teamId: String?,
        val detail: String
    )

    const val SOCCER = "Soccer"

    private val eventCache = HashMap<String, EventRef>()
    private val liveCacheAt = HashMap<String, Long>()
    private val liveCache = HashMap<String, org.json.JSONArray>()

    /** Busca el partido de [team1Id] contra [team2Id] cerca de [startMs] — primero
     * por el día (eventsday, la fecha de la API es UTC), y si no sale, entre los
     * últimos/próximos partidos del primer equipo. null si no se encuentra. */
    fun findEvent(team1Id: String, team2Id: String, startMs: Long, sport: String = SOCCER): EventRef? {
        val key = "$sport-$team1Id-$team2Id-${startMs / 3_600_000L}"
        eventCache[key]?.let { return it }
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(startMs))
        val sportParam = java.net.URLEncoder.encode(sport, "UTF-8")
        val found = matchIn(getArray("$BASE/eventsday.php?d=$day&s=$sportParam", "events"), team1Id, team2Id)
            ?: matchIn(getArray("$BASE/eventsnext.php?id=$team1Id", "events"), team1Id, team2Id)
            ?: matchIn(getArray("$BASE/eventslast.php?id=$team1Id", "results"), team1Id, team2Id)
        if (found != null) {
            eventCache[key] = found
            persist()
        } else {
            Log.w(TAG, "No se encuentra el partido $team1Id vs $team2Id ($sport) del día $day")
        }
        return found
    }

    /** Como [findEvent] por ids, pero mirando primero el marcador de ESPN (cuenta los goles y tarjetas y el minuto, que la
     * clave gratuita de TheSportsDB casi nunca trae) por nombre de equipo. [hint] = texto del programa (la liga). */
    fun findEvent(team1: Team, team2: Team, startMs: Long, sport: String = SOCCER, hint: String = ""): EventRef? {
        val key = "$sport-espn-${team1.id}|${team1.name}-${team2.id}|${team2.name}-${startMs / 3_600_000L}"
        eventCache[key]?.let { return it }
        EspnScores.find(team1.name, team2.name, sport, startMs, hint)?.let { found ->
            val ref = EventRef(found.ref, if (found.homeIsFirst) team1.id else team2.id)
            eventCache[key] = ref
            return ref
        }
        // Equipos que solo se conocen por el nombre de la guia (sin ficha en TheSportsDB): no hay ids que buscar alli.
        if (team1.id.startsWith(GUIDE_ID) || team2.id.startsWith(GUIDE_ID)) return null
        return findEvent(team1.id, team2.id, startMs, sport)
    }

    /** Equipo conocido solo por su nombre en la guia. */
    const val GUIDE_ID = "guide:"

    /**
     * Los dos equipos de un partido para ENSEÑARLOS: los de TheSportsDB/catalogo si se reconocen; si no (selecciones,
     * clubes que esa base no tiene...), con lo que haya: banderas, el logo del equipo en la Wikipedia o solo el nombre de
     * la guia (id que empieza por [GUIDE_ID]). Bloquea (red): llamar desde un hilo de fondo.
     */
    fun resolveForDisplay(home: String, away: String, sport: String = SOCCER): Pair<Team, Team> {
        resolveMatch(home, away, sport)?.let { return it }
        CountryFlags.pair(home, away)?.let { (a, b) ->
            return Team("${GUIDE_ID}1", home, a, null) to Team("${GUIDE_ID}2", away, b, null)
        }
        fun one(name: String, n: Int): Team = searchTeam(name, sport)
            ?: Team("$GUIDE_ID$n", name, runCatching { WikipediaBadges.find(name, sport) }.getOrNull(), null)
        return one(home, 1) to one(away, 2)
    }

    private fun matchIn(events: org.json.JSONArray?, team1Id: String, team2Id: String): EventRef? {
        events ?: return null
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            val home = e.optStringOrNull("idHomeTeam") ?: continue
            val away = e.optStringOrNull("idAwayTeam") ?: continue
            if ((home == team1Id && away == team2Id) || (home == team2Id && away == team1Id)) {
                return EventRef(e.optStringOrNull("idEvent") ?: continue, home)
            }
        }
        return null
    }

    // La clave gratuita tiene un límite de peticiones por minuto: al pasarse la
    // API responde una página HTML en vez de JSON (confirmado a mano). Un
    // único reintento tras una pausa corta evita que un pico de peticiones al
    // enfocar un partido deje el panel sin reconocer los equipos.
    //
    // Dos cosas más contra ese límite (30/min en la clave gratuita; cada
    // partido nuevo que se enfoca cuesta ~10 peticiones, y al recorrer la guía
    // se enfocan varios seguidos — confirmado real: los 2-3 primeros partidos
    // salían con escudos y los siguientes caían al texto normal):
    //  - Un limitador propio (MAX_REQUESTS_PER_MINUTE) que ESPERA su turno en
    //    vez de lanzar la petición y que la API la rechace.
    //  - Caché por URL ([ttlMs]): eventsnext/eventslast los piden a la vez
    //    findEvent, nextMatch y recentResults — ahora es una sola llamada.
    private class CachedArray(val at: Long, val array: org.json.JSONArray?)
    private val responseCache = HashMap<String, CachedArray>()
    private val requestTimes = java.util.ArrayDeque<Long>()
    private const val MAX_REQUESTS_PER_MINUTE = 24

    // true cuando hay hueco; espera hasta [maxWaitMs] a que lo haya.
    private fun acquireSlot(maxWaitMs: Long = 30_000L): Boolean {
        val low = lowPriority.get() == true
        val limit = if (low) MAX_REQUESTS_PER_MINUTE / 2 else MAX_REQUESTS_PER_MINUTE
        val deadline = System.currentTimeMillis() + if (low) 180_000L else maxWaitMs
        while (true) {
            // El precálculo cede el paso mientras haya algo en primer plano.
            if (low && foregroundInFlight.get() > 0) {
                if (System.currentTimeMillis() > deadline) return false
                Thread.sleep(400)
                continue
            }
            var waitMs = 0L
            synchronized(requestTimes) {
                val now = System.currentTimeMillis()
                while (requestTimes.isNotEmpty() && now - requestTimes.first() > 60_000L) requestTimes.removeFirst()
                if (requestTimes.size < limit) {
                    requestTimes.addLast(now)
                    return true
                }
                waitMs = 60_000L - (now - requestTimes.first()) + 50L
            }
            if (System.currentTimeMillis() + waitMs > deadline) return false
            Thread.sleep(waitMs)
        }
    }

    private fun getArray(url: String, field: String, ttlMs: Long = 300_000L): org.json.JSONArray? {
        val key = "$url#$field"
        synchronized(responseCache) {
            responseCache[key]?.let { if (System.currentTimeMillis() - it.at < ttlMs) return it.array }
        }
        val array = fetchArray(url, field, retry = true)
        // Un fallo (null por red o por límite) NO se cachea: así el siguiente
        // intento vuelve a probar en vez de quedarse sin datos durante 5 min.
        if (array != null) synchronized(responseCache) { responseCache[key] = CachedArray(System.currentTimeMillis(), array) }
        return array
    }

    private val lastFetchFailed = ThreadLocal<Boolean>()

    private fun fetchArray(url: String, field: String, retry: Boolean): org.json.JSONArray? {
        lastFetchFailed.set(false)
        if (!acquireSlot()) {
            lastFetchFailed.set(true)
            return null
        }
        val body = try {
            sharedHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            null
        }
        if (body == null) {
            lastFetchFailed.set(true)
            return null
        }
        if (body.trimStart().startsWith("<")) {
            if (!retry) return null
            Thread.sleep(2_000)
            return fetchArray(url, field, retry = false)
        }
        return try {
            JSONObject(body).optJSONArray(field)
        } catch (e: Exception) {
            null
        }
    }

    // livescore.php devuelve TODOS los partidos de fútbol en juego (~15 KB) —
    // una sola descarga sirve para el panel del EPG y la pantalla completa a
    // la vez, de ahí la caché corta.
    private fun liveScores(sport: String): org.json.JSONArray? {
        val now = System.currentTimeMillis()
        val cached = liveCache[sport]
        if (cached != null && now - (liveCacheAt[sport] ?: 0L) < 15_000L) return cached
        val fresh = getArray("$BASE/livescore.php?s=${java.net.URLEncoder.encode(sport, "UTF-8")}", "livescore", ttlMs = 0L)
        if (fresh != null) {
            liveCache[sport] = fresh
            liveCacheAt[sport] = now
        }
        return fresh
    }

    // Estados vistos en la API: "NS" sin empezar; "1H"/"2H" fútbol; "Q1".."Q4",
    // "OT" baloncesto; "P1".."P3" hockey; "HT"/"BT" descanso; "FT"/"AET"/
    // "AOT"/"Final" terminado.
    private fun phaseOf(status: String?): MatchPhase = when (status?.trim()?.uppercase()) {
        null, "", "NS", "TBD", "NOT STARTED", "PST", "POSTPONED" -> MatchPhase.NOT_STARTED
        "HT", "BT" -> MatchPhase.HALF_TIME
        "FT", "AET", "AOT", "PEN", "AWD", "FINAL", "F", "MATCH FINISHED" -> MatchPhase.FINISHED
        else -> MatchPhase.LIVE
    }

    /** Marcador y estado de [ref] ahora mismo. En juego sale de livescore (trae
     * el minuto); si no está ahí, de lookupevent (no empezado o terminado). */
    fun fetchState(ref: EventRef, team1Id: String, sport: String = SOCCER, fresh: Boolean = false): MatchState? {
        val team1IsHome = ref.homeTeamId == team1Id
        if (ref.id.startsWith(EspnScores.PREFIX)) {
            val s = EspnScores.snapshot(ref.id, fresh) ?: return null
            val (a, b) = if (team1IsHome) s.homeScore to s.awayScore else s.awayScore to s.homeScore
            return MatchState(ref.id, s.phase, a, b, s.minute, s.label)
        }
        fun orient(home: Int?, away: Int?) = if (team1IsHome) home to away else away to home
        // Solo el fútbol trae un minuto corrido útil en strProgress; en el
        // resto se enseña el periodo (Q3, P1...) y no un número suelto.
        val soccer = sport == SOCCER

        val live = liveScores(sport)
        if (live != null) {
            for (i in 0 until live.length()) {
                val e = live.getJSONObject(i)
                if (e.optStringOrNull("idEvent") != ref.id) continue
                val (s1, s2) = orient(
                    e.optStringOrNull("intHomeScore")?.toIntOrNull(),
                    e.optStringOrNull("intAwayScore")?.toIntOrNull()
                )
                val status = e.optStringOrNull("strStatus")
                return MatchState(
                    ref.id, phaseOf(status), s1, s2,
                    minute = if (soccer) e.optStringOrNull("strProgress") else null,
                    label = if (soccer) null else status
                )
            }
        }
        val event = getArray("$BASE/lookupevent.php?id=${ref.id}", "events", ttlMs = 20_000L)?.optJSONObject(0) ?: return null
        val (s1, s2) = orient(
            event.optStringOrNull("intHomeScore")?.toIntOrNull(),
            event.optStringOrNull("intAwayScore")?.toIntOrNull()
        )
        return MatchState(ref.id, phaseOf(event.optStringOrNull("strStatus")), s1, s2, null)
    }

    /** Cada cuanto volver a pedir el marcador de [ref]. */
    fun pollIntervalMs(ref: EventRef, default: Long): Long = if (ref.id.startsWith(EspnScores.PREFIX)) EspnScores.POLL_MS else default

    /** Goles y tarjetas de [ref] (de ESPN si el partido viene de ahi, si no de TheSportsDB). */
    fun fetchTimeline(ref: EventRef, team1Id: String, team2Id: String): List<TimelineItem> =
        if (ref.id.startsWith(EspnScores.PREFIX)) {
            EspnScores.timeline(ref.id, ref.homeTeamId, if (ref.homeTeamId == team1Id) team2Id else team1Id)
        } else {
            fetchTimeline(ref.id)
        }

    /** Goles y tarjetas (sin cambios: es ruido para quien mira el partido) en
     * orden cronológico. */
    fun fetchTimeline(eventId: String): List<TimelineItem> {
        val items = getArray("$BASE/lookuptimeline.php?id=$eventId", "timeline", ttlMs = 20_000L) ?: return emptyList()
        val result = mutableListOf<TimelineItem>()
        for (i in 0 until items.length()) {
            val t = items.getJSONObject(i)
            val type = t.optString("strTimeline").lowercase()
            val detail = t.optString("strTimelineDetail")
            val lower = detail.lowercase()
            val kind = when {
                type == "goal" && !lower.contains("missed") -> TimelineKind.GOAL
                type == "card" && (lower.contains("red") || lower.contains("second")) -> TimelineKind.RED
                type == "card" && lower.contains("yellow") -> TimelineKind.YELLOW
                else -> continue
            }
            result.add(
                TimelineItem(
                    id = t.optStringOrNull("idTimeline") ?: "$eventId-$i",
                    minute = t.optString("intTime").toIntOrNull() ?: 0,
                    kind = kind,
                    player = t.optString("strPlayer"),
                    assist = t.optStringOrNull("strAssist"),
                    teamId = t.optStringOrNull("idTeam"),
                    detail = detail
                )
            )
        }
        return result.sortedBy { it.minute }
    }

    enum class MatchOutcome { WIN, DRAW, LOSS }

    data class RecentMatch(val text: String, val outcome: MatchOutcome)

    /** Últimos partidos YA JUGADOS de este equipo (no futuros), del más
     * reciente al más antiguo — el marcador tal cual ("Real Madrid 2 - 1 FC
     * Barcelona") más si ese equipo concreto ganó/empató/perdió (para la
     * "forma" en semáforo, ver IptvChannelsFragment.bindTeamColumn).
     * Lista vacía si no hay partidos recientes registrados para el equipo.
     * eventslast.php ya devuelve como mucho 5, de más reciente a más
     * antiguo, así que "limit" solo recorta esa lista. */
    fun recentResults(teamId: String, limit: Int = 3): List<RecentMatch> {
        if (teamId.isBlank()) return emptyList()
        val results = getArray("$BASE/eventslast.php?id=$teamId", "results") ?: return emptyList()
        return try {
            (0 until minOf(results.length(), limit)).mapNotNull { i ->
                val e = results.getJSONObject(i)
                val home = e.optStringOrNull("strHomeTeam") ?: return@mapNotNull null
                val away = e.optStringOrNull("strAwayTeam") ?: return@mapNotNull null
                val homeScore = e.optStringOrNull("intHomeScore")?.toIntOrNull() ?: return@mapNotNull null
                val awayScore = e.optStringOrNull("intAwayScore")?.toIntOrNull() ?: return@mapNotNull null
                val isHome = e.optStringOrNull("idHomeTeam") == teamId
                val teamScore = if (isHome) homeScore else awayScore
                val opponentScore = if (isHome) awayScore else homeScore
                val outcome = when {
                    teamScore > opponentScore -> MatchOutcome.WIN
                    teamScore < opponentScore -> MatchOutcome.LOSS
                    else -> MatchOutcome.DRAW
                }
                RecentMatch("$home $homeScore - $awayScore $away", outcome)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
