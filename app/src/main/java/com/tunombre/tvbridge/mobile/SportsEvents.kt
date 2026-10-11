package com.tunombre.tvbridge.mobile

import androidx.annotation.StringRes
import java.text.Normalizer

/**
 * Deportes que se reconocen en la guia (EPG) de una lista IPTV. El ORDEN importa: cuando un programa encaja con
 * varios deportes gana el primero ("futbol americano" antes que "futbol", "us open" de golf antes que el de tenis...).
 *
 * [versus] = el programa suele ser "A - B" (equipos o dos rivales): se intenta sacar los nombres del titulo.
 * [sportsDb] = nombre del deporte en TheSportsDB, solo si la API tiene escudos de sus equipos (ver [SportsDbClient]).
 */
enum class Sport(
    @StringRes val labelRes: Int,
    val versus: Boolean,
    val sportsDb: String?,
    val keywords: List<String>
) {
    AMERICAN_FOOTBALL(R.string.sport_american_football, true, "American Football",
        listOf("nfl", "futbol americano", "american football", "super bowl", "ncaa football")),
    BASKETBALL(R.string.sport_basketball, true, "Basketball",
        listOf("baloncesto", "basket", "basketball", "nba", "wnba", "acb", "liga endesa", "euroliga", "euroleague", "eurocup", "fiba")),
    BASEBALL(R.string.sport_baseball, true, "Baseball", listOf("mlb", "beisbol", "baseball", "world series")),
    ICE_HOCKEY(R.string.sport_ice_hockey, true, "Ice Hockey",
        listOf("nhl", "hockey sobre hielo", "hockey hielo", "ice hockey", "khl")),
    RUGBY(R.string.sport_rugby, true, "Rugby", listOf("rugby", "six nations", "seis naciones", "top 14")),
    HANDBALL(R.string.sport_handball, true, "Handball", listOf("balonmano", "handball", "asobal")),
    VOLLEYBALL(R.string.sport_volleyball, true, "Volleyball", listOf("voleibol", "volleyball", "volley", "voley")),
    F1(R.string.sport_f1, false, null,
        listOf("formula 1", "formula uno", "f1", "formula 2", "formula 3", "fia formula")),
    MOTOGP(R.string.sport_motogp, false, null,
        listOf("motogp", "moto gp", "moto2", "moto3", "motoe", "superbike", "wsbk", "motociclismo", "motocross", "mxgp", "supercross")),
    MOTORSPORT(R.string.sport_motorsport, false, null,
        listOf("rally", "wrc", "nascar", "indycar", "indy 500", "formula e", "dakar", "le mans", "wec", "dtm", "imsa", "automovilismo",
            "gran premio", "grand prix", "gp", "karting", "extreme e", "motorsport", "motor", "mundial de resistencia", "fia")),
    GOLF(R.string.sport_golf, false, null,
        listOf("golf", "pga", "lpga", "ryder cup", "augusta", "liv golf", "dp world tour")),
    TENNIS(R.string.sport_tennis, true, null,
        listOf("tenis", "tennis", "atp", "wta", "roland garros", "wimbledon", "us open", "australian open", "open de australia",
            "masters 1000", "copa davis", "davis cup", "billie jean king")),
    PADEL(R.string.sport_padel, true, null, listOf("padel", "premier padel", "world padel tour")),
    CYCLING(R.string.sport_cycling, false, null,
        listOf("ciclismo", "cycling", "tour de francia", "tour de france", "giro de italia", "giro d italia", "vuelta a espana",
            "vuelta ciclista", "uci", "ciclocross", "paris roubaix", "milan san remo")),
    COMBAT(R.string.sport_combat, true, null,
        listOf("boxeo", "boxing", "ufc", "mma", "kickboxing", "muay thai", "lucha libre", "wwe", "aew", "glory", "pfl", "bellator",
            "one championship", "judo", "taekwondo", "karate")),
    ATHLETICS(R.string.sport_athletics, false, null,
        listOf("atletismo", "athletics", "diamond league", "maraton", "marathon")),
    WINTER(R.string.sport_winter, false, null,
        listOf("esqui", "skiing", "biatlon", "biathlon", "snowboard", "patinaje", "figure skating", "bobsleigh", "curling")),
    SWIMMING(R.string.sport_swimming, false, null,
        listOf("natacion", "swimming", "waterpolo", "water polo", "saltos de trampolin", "clavados", "nado sincronizado")),
    CRICKET(R.string.sport_cricket, true, "Cricket", listOf("cricket", "ipl", "t20", "test match", "the ashes", "bbl")),
    CUE_SPORTS(R.string.sport_cue_sports, true, null, listOf("dardos", "darts", "snooker", "billar", "billiards")),
    OLYMPICS(R.string.sport_olympics, false, null,
        listOf("juegos olimpicos", "olympic games", "olimpiadas", "paralimpicos", "juegos paralimpicos", "jjoo")),
    OTHER_SPORTS(R.string.sport_other_sports, false, null,
        listOf("global champions tour", "concurso de saltos", "triatlon", "triathlon", "gimnasia", "gymnastics", "esgrima", "hipica", "equitacion", "surf", "skateboarding",
            "escalada", "remo", "piraguismo", "vela", "halterofilia", "pentatlon", "hockey sobre cesped", "hockey hierba")),
    FOOTBALL(R.string.sport_football, true, "Soccer",
        listOf("futbol", "football", "soccer", "futsal", "laliga", "la liga", "liga", "champions", "champions league", "europa league",
            "conference league", "uefa", "fifa", "premier league", "premier", "serie a", "bundesliga", "ligue 1", "eredivisie",
            "primeira liga", "copa del rey", "supercopa", "libertadores", "sudamericana", "mundial", "eurocopa", "copa america", "mls",
            "hypermotion", "primera federacion", "segunda division", "liga f", "nations league", "liga de naciones", "amistoso", "calcio",
            "matchday", "carabao", "fa cup", "coppa italia", "dfb pokal", "copa de la reina", "liga mx", "brasileirao")),

    /** Programas de una categoria generica de deporte ("Deportes") sin ningun deporte concreto en el titulo: solo
     * cuentan si el titulo es un "A - B". */
    GENERIC(R.string.sport_generic, true, null, emptyList())
}

/** Un evento deportivo de la guia: [channels] son todos los canales que lo emiten. */
data class SportEvent(
    val sport: Sport,
    val title: String,
    val competition: String?,
    val home: String?,
    val away: String?,
    val startMs: Long,
    val endMs: Long,
    val channels: List<IptvChannel>
) {
    val isVersus: Boolean get() = home != null && away != null

    /** Clave estable para pedir los escudos y para identificar la tarjeta. */
    val key: String get() = "${sport.name}|${home ?: title}|${away.orEmpty()}|${startMs / 60_000}"
}

/** Busca en la guia de la lista IPTV los eventos deportivos de los proximos dias, de todos los deportes. */
object SportsEvents {
    /** Solo se enseñan los eventos que ya estan en directo o empiezan en las proximas 3 horas. */
    const val HORIZON_MS = 3L * 3600_000L

    /** Menos que esto no es un evento (avances, noticias, resumenes cortos). */
    private const val MIN_DURATION_MS = 25L * 60_000L

    /** Dos emisiones del mismo evento cuyas horas de inicio caen en la misma franja de 10 min son el mismo. */
    private const val SAME_EVENT_BUCKET_MS = 10L * 60_000L

    /** Otra emision del mismo evento antes de tanto tiempo se considera una repeticion. */
    private const val REPLAY_WINDOW_MS = 30L * 3600_000L

    private val DIACRITICS = Regex("\\p{M}")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    /** Minusculas, sin tildes y solo letras/numeros separados por un espacio, con un espacio delante y detras
     * (para buscar palabras enteras con `contains(" palabra ")`). */
    fun normalize(text: String): String {
        val folded = if (text.all { it.code < 128 }) text else DIACRITICS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
        return " " + NON_ALNUM.replace(folded.lowercase(), " ").trim() + " "
    }

    // Las palabras sueltas se buscan en un mapa (rapido) y las frases con varias palabras en una lista corta: una guia
    // grande puede tener cientos de miles de programas en la ventana.
    private val singleWords: Map<String, Sport> = HashMap<String, Sport>().also { map ->
        Sport.values().forEach { sport -> sport.keywords.filter { ' ' !in it }.forEach { map.putIfAbsent(it, sport) } }
    }
    private val phrases: List<Pair<String, Sport>> =
        Sport.values().flatMap { sport -> sport.keywords.filter { ' ' in it }.map { " $it " to sport } }

    private val EXCLUDED_WORDS = setOf(
        "resumen", "resumenes", "highlights", "magazine", "noticias", "noticiero", "news", "informativo", "debate", "tertulia",
        "analisis", "repeticion", "replay", "diferido", "reportaje", "documental", "entrevista", "previa", "preview", "postpartido",
        "prepartido", "previo", "post", "codigo", "campus", "programa", "podcast"
    )
    private val EXCLUDED_PHRASES = listOf(" box box ", " learning center ", " rueda de prensa ", " mejores momentos ", " post partido ", " pre partido ", " post match ", " pre match ")

    // Un partido de un deporte de equipos sin dos nombres en el titulo solo es un evento si se dice que es en directo o
    // una fase concreta; si no suele ser el nombre del canal o de un espacio ("LALIGA TV HYPERMOTION 3").
    private val LIVE_WORDS = setOf("directo", "live", "partido", "game", "final", "semifinal", "semifinales", "cuartos", "playoff", "playoffs")

    // Categorias de la guia que no son deporte en directo aunque el titulo hable de deporte (un concierto, un
    // documental, un programa de entretenimiento sobre coches...).
    private val NON_SPORT_CATEGORY_WORDS = setOf(
        "entretenimiento", "documental", "musica", "concierto", "cultura", "cine", "pelicula", "serie", "series", "infantil",
        "telenovela", "humor", "concurso", "movie", "documentary", "music", "entertainment", "kids"
    )

    // "T3", "E23": temporada y episodio de una serie o programa (no la "T26/27" de una liga).
    private val EPISODE_MARKER = Regex("(?<![A-Za-z0-9])(?:T\\d{1,2}|E\\d{1,3})(?![\\d/])")

    private val GENERIC_CATEGORY_WORDS = setOf("deporte", "deportes", "sport", "sports")

    /** El deporte de un programa (por categoria + titulo), o null si no es de ningun deporte conocido. */
    fun sportOf(program: EpgProgram): Sport? {
        val title = normalize(program.title)
        val category = program.category?.let { normalize(it) }.orEmpty()
        val text = if (category.isBlank()) title else title + category.trim() + " "
        var best: Sport? = null
        for (word in text.trim().split(' ')) {
            val sport = singleWords[word] ?: continue
            if (best == null || sport.ordinal < best.ordinal) best = sport
        }
        for ((phrase, sport) in phrases) {
            if ((best == null || sport.ordinal < best.ordinal) && text.contains(phrase)) best = sport
        }
        if (best != null) return best
        val generic = category.trim().split(' ').any { it in GENERIC_CATEGORY_WORDS }
        return if (generic) Sport.GENERIC else null
    }

    private fun isExcluded(title: String): Boolean =
        title.trim().split(' ').any { it in EXCLUDED_WORDS } || EXCLUDED_PHRASES.any { title.contains(it) }

    private class Classified(val sport: Sport, val title: String, val competition: String?, val home: String?, val away: String?)

    private fun classify(program: EpgProgram, channelName: String): Classified? {
        if (program.endMs - program.startMs < MIN_DURATION_MS) return null
        val sport = sportOf(program) ?: return null
        val title = normalize(program.title)
        if (isExcluded(title) || EPISODE_MARKER.containsMatchIn(program.title)) return null
        program.category?.let { normalize(it).replace(" serie a ", " ").trim().split(' ') }
            ?.let { words -> if (words.any { it in NON_SPORT_CATEGORY_WORDS }) return null }
        if (EpgManager.normalizeChannelName(program.title) == EpgManager.normalizeChannelName(channelName)) return null
        val teams = if (sport.versus) EpgManager.parseMatchTeams(program.title) else null
        if (sport == Sport.GENERIC && teams == null) return null
        if (sport.versus && teams == null && title.trim().split(' ').none { it in LIVE_WORDS }) return null
        val competition = competitionOf(program.title, teams != null)
        return Classified(sport, cleanTitle(program.title), competition, teams?.first, teams?.second)
    }

    private val LEADING_LIVE = Regex("^(?:EN\\s+)?(?:DIRECTO|LIVE)\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val SEASON = Regex("\\s+T\\d{2,4}(?:/\\d{2,4})?\\b")

    private fun cleanTitle(title: String): String =
        LEADING_LIVE.replace(SEASON.replace(title, ""), "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { title.trim() }

    /** La competicion de un partido: lo que va antes de los dos puntos ("LALIGA EA SPORTS · Jornada 4: A - B") o, si
     * la guia la pone detras de los equipos, lo que va tras el "·". */
    private fun competitionOf(title: String, hasTeams: Boolean): String? {
        if (!hasTeams) return null
        val cleaned = SEASON.replace(title, "")
        val before = cleaned.substringBeforeLast(':', "").replace(LEADING_LIVE, "").replace(Regex("\\s*[·|]\\s*"), " · ").trim()
        // Tras los equipos ("Jornada 3: A - B · Liga Endesa") la guia pone a veces la competicion de verdad.
        val after = cleaned.substringAfterLast(':').substringAfter('·', "").trim()
        return listOf(after, before).filter { it.isNotEmpty() }.joinToString(" · ").take(70).ifEmpty { null }
    }

    private fun letters(text: String): String = normalize(text).replace(" ", "")

    /**
     * Todos los eventos deportivos que empiezan antes de [nowMs] + [HORIZON_MS] (o ya han empezado) y todavia no han terminado, ordenados
     * por hora de inicio, con los canales que emiten el mismo evento agrupados.
     */
    fun build(channels: List<IptvChannel>, epg: EpgManager.EpgData?, nowMs: Long): List<SportEvent> {
        if (epg == null) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<Pair<Classified, EpgProgram>>>()
        val channelsByKey = HashMap<String, LinkedHashMap<String, IptvChannel>>()
        for (channel in channels) {
            val programs = EpgManager.programsInWindow(epg, channel.tvgId, channel.name, nowMs, nowMs + HORIZON_MS)
            for (program in programs) {
                if (program.endMs <= nowMs) continue
                val c = classify(program, channel.name) ?: continue
                val bucket = program.startMs / SAME_EVENT_BUCKET_MS
                val id = if (c.home != null && c.away != null) "${c.sport.name}|${letters(c.home)}|${letters(c.away)}|$bucket"
                else "${c.sport.name}|${letters(c.title)}|$bucket"
                grouped.getOrPut(id) { ArrayList() }.add(c to program)
                channelsByKey.getOrPut(id) { LinkedHashMap() }.putIfAbsent(channel.key, channel)
            }
        }
        val all = grouped.map { (id, items) ->
            val (c, program) = items.first()
            SportEvent(
                c.sport, c.title, c.competition, c.home, c.away, program.startMs, program.endMs,
                channelsByKey.getValue(id).values.sortedBy { it.name.lowercase() }
            )
        }.sortedWith(compareBy({ it.startMs }, { it.sport.ordinal }, { it.title.lowercase() }))
        // Las repeticiones (el mismo partido o carrera otra vez en las horas siguientes) no son otro evento: se
        // queda la primera emision.
        val lastStart = HashMap<String, Long>()
        return all.filter { event ->
            val base = if (event.isVersus) "${event.sport.name}|${letters(event.home!!)}|${letters(event.away!!)}" else "${event.sport.name}|${letters(event.title)}"
            val previous = lastStart[base]
            lastStart[base] = event.startMs
            previous == null || event.startMs - previous >= REPLAY_WINDOW_MS
        }
    }
}
