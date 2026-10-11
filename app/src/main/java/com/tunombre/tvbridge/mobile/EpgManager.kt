package com.tunombre.tvbridge.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.PushbackInputStream
import java.io.Serializable
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

/**
 * Descarga y parsea el EPG (guía de programación) en formato XMLTV — un
 * archivo aparte del M3U (ver conversación: se pide su propia URL, no se
 * intenta adivinar de la lista). Solo guarda, por canal, los programas que
 * ya han empezado o están por venir (ver [MAX_PROGRAMS_PER_CHANNEL]) para
 * "ahora/después" y la rejilla horaria en la lista.
 *
 * El XMLTV descargado ya parseado se guarda también en disco (ver
 * [loadDiskCache]/[saveDiskCache]), válido para el mismo día y la misma
 * URL — así abrir la app más tarde el mismo día no vuelve a descargar ni
 * parsear un archivo que puede pesar decenas de MB (confirmado real: sin
 * esto, cada vez que se entraba en IPTV había que esperar la descarga
 * entera otra vez). [IptvManager.prefetchEpgIfNeeded] además arranca esta
 * carga en cuanto se abre la app, no solo al entrar en la pantalla de IPTV.
 */
object EpgManager {
    // Antes 16 alcanzaba de sobra para la rejilla de una sola ventana de
    // 3-4h, pero ahora se puede desplazar hacia adelante varias ventanas
    // (ver IptvChannelsGridFragment.shiftWindowForward) — con margen para
    // cubrir bastantes horas incluso en canales con tramos cortos (24h de
    // noticias, 15-20 min por tramo). Con PAST_RETENTION_HOURS ahora también
    // se guardan los que ya han terminado hace poco, así que hace falta
    // margen extra para eso también.
    private const val MAX_PROGRAMS_PER_CHANNEL = 56
    // Antes se descartaba cualquier programa ya terminado (stop < ahora), así
    // que al retroceder en la rejilla (ver
    // IptvChannelsGridFragment.shiftWindowBackward, hasta 2h atrás) esos
    // canales aparecían con "Sin guía disponible" pese a que "ahora/después"
    // sí funcionaban (esos nunca miran al pasado) — confirmado real. Se
    // guarda algo más de lo que se puede retroceder (2h) por margen.
    // 48 h: el catch-up (ver CatchupUrl) necesita poder volver atras en la guia hasta el dia que guarde el proveedor.
    private const val PAST_RETENTION_HOURS = 48
    private const val TAG_PROGRAMME = "programme"
    private const val TAG_TITLE = "title"
    private const val TAG_DESC = "desc"
    private const val TAG_CATEGORY = "category"
    private const val TAG_CHANNEL = "channel"
    private const val TAG_DISPLAY_NAME = "display-name"

    // Para detectar "esto es una película" (ver IptvChannelsFragment,
    // carátula junto al mini-reproductor) — el XMLTV trae <category> en
    // texto libre según la guía, así que se compara por palabras clave en
    // vez de un valor fijo.
    private val MOVIE_CATEGORY_KEYWORDS = listOf("pelicula", "movie", "film", "cine")

    fun isMovieCategory(category: String?): Boolean {
        if (category.isNullOrBlank()) return false
        val normalized = Normalizer.normalize(category, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
        return MOVIE_CATEGORY_KEYWORDS.any { normalized.contains(it) }
    }

    /** [isMovieCategory] mira <category>, pero muchas guías reales (ver
     * conversación: confirmado con M+ Estrenos) no usan esa etiqueta y en
     * vez de eso meten el género como texto suelto en la PRIMERA línea de
     * <desc> — p.ej. "Cine,Animación | 2025 | +7 | *8.2/10" antes de la
     * sinopsis de verdad (que empieza por "· "). Aquí se comprueba también
     * esa primera línea como último recurso — nunca el resto de la
     * sinopsis, para no dar un falso positivo si la palabra aparece
     * mencionada de pasada en el texto. */
    fun isMovieProgram(program: EpgProgram): Boolean {
        if (isSeriesProgram(program)) return false
        if (isMovieCategory(program.category)) return true
        val firstLine = program.synopsis?.lineSequence()?.firstOrNull()?.trim() ?: return false
        if (firstLine.isEmpty() || firstLine.startsWith("·")) return false
        return isMovieCategory(firstLine)
    }

    private val SERIES_CATEGORY_KEYWORDS = listOf("serie", "series", "telenovela")

    // "Malas lenguas T3 E465", "Aquí la Tierra T15 E839", "Dog House T2"
    // (a veces sin número de episodio) — esta guía en concreto no marca las
    // series por categoría de forma fiable (ver isMovieProgram, mismo
    // problema con las películas), pero SÍ pega "T{temporada} E{episodio}"
    // al título de cada episodio, así que esa forma del título es la señal
    // principal para "esto es una serie" — y de paso da el nombre limpio de
    // la serie (ver cleanSeriesTitle) para buscarla en TMDb, que no
    // entendería el título completo con la temporada pegada.
    private val SERIES_TITLE_SUFFIX = Regex("\\s+T\\d+(\\s*E\\d+)?", RegexOption.IGNORE_CASE)

    fun isSeriesCategory(category: String?): Boolean {
        if (category.isNullOrBlank()) return false
        val normalized = Normalizer.normalize(category, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .lowercase()
        return SERIES_CATEGORY_KEYWORDS.any { normalized.contains(it) }
    }

    fun isSeriesProgram(program: EpgProgram): Boolean {
        if (isSeriesCategory(program.category)) return true
        return SERIES_TITLE_SUFFIX.containsMatchIn(program.title)
    }

    /** "Malas lenguas T3 E465" -> "Malas lenguas". Si el título no trae el
     * sufijo de temporada/episodio, se devuelve tal cual. */
    fun cleanSeriesTitle(title: String): String {
        val match = SERIES_TITLE_SUFFIX.find(title) ?: return title
        return title.substring(0, match.range.first).trim().ifBlank { title }
    }

    // Para el resto de programas (magacines, concursos, documentales,
    // realities...) se intenta la carátula igual, PERO el informativo
    // diario no tiene ficha en TMDb y el título suele ser genérico
    // ("Telediario 2", "El tiempo") — buscarlo se arriesga a colar la
    // carátula de una película o serie sin relación real solo por
    // coincidir el nombre. Se excluyen esos por categoría (ver
    // isNewsProgram) en vez de intentarlo y arriesgarse a un resultado
    // falso.
    private val NEWS_CATEGORY_KEYWORDS = listOf("informativo", "informacion", "noticias", "telediario", "tiempo")

    fun isNewsProgram(program: EpgProgram): Boolean {
        val normalizedCategory = program.category?.let {
            Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        }
        if (normalizedCategory != null && NEWS_CATEGORY_KEYWORDS.any { normalizedCategory.contains(it) }) return true
        // Contra el título YA SIN el "T{temporada} E{episodio} · subtítulo"
        // (ver cleanSeriesTitle), no el título completo — "Futurama T3 E14
        // · Tiempo en una botella" contiene "tiempo" en el SUBTÍTULO del
        // episodio, nada que ver con el informativo del tiempo, y con el
        // título completo se excluía por error (confirmado real, dejó de
        // salir la carátula de Futurama).
        val baseTitle = cleanSeriesTitle(program.title)
        val normalizedTitle = Normalizer.normalize(baseTitle, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
        return NEWS_CATEGORY_KEYWORDS.any { normalizedTitle.contains(it) }
    }

    // En español e inglés: canales como DAZN retransmiten fútbol inglés
    // (Premier League) con el título y la categoría en inglés, sin ninguna
    // de las palabras en español (confirmado real: "Fulham vs Manchester
    // United" en DAZN 1 no traía "liga" ni "jornada" en ningún sitio).
    private val SPORTS_CATEGORY_KEYWORDS = listOf(
        "deporte", "futbol", "liga", "champions", "baloncesto", "sport", "jornada",
        "football", "soccer", "premier league", "matchday", "match"
    )

    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    // Muchos partidos NO traen <category> de deporte (confirmado real: el
    // ejemplo real de LaLiga que se usó para probar esto no la traía) —
    // hace falta mirar también el título, no solo la categoría. Aun así,
    // esto por sí solo NO basta para mostrar el panel de escudos: en
    // updateMoviePoster hace falta ADEMÁS que parseMatchTeams saque los dos
    // equipos, así que un falso positivo aquí (p.ej. un título con "liga"
    // que no sea deporte) no hace nada por sí solo si no hay dos nombres
    // que sacar del título.
    // Deportes de equipos que TheSportsDB cubre además del fútbol (nombres tal
    // cual los usa su API en strSport). Se comprueban ANTES que el fútbol:
    // "fútbol americano" contiene la palabra "fútbol".
    private val OTHER_SPORT_KEYWORDS = listOf(
        "American Football" to listOf("nfl", "futbol americano", "american football"),
        "Basketball" to listOf("baloncesto", "basket", "basketball", "nba", "acb", "euroliga", "euroleague"),
        "Handball" to listOf("balonmano", "handball", "asobal"),
        "Ice Hockey" to listOf("nhl", "hockey hielo", "hockey sobre hielo", "ice hockey"),
        "Baseball" to listOf("mlb", "beisbol", "baseball"),
        "Rugby" to listOf("rugby")
    )

    /** El deporte de TheSportsDB de este programa si NO es fútbol (ver
     * [OTHER_SPORT_KEYWORDS]), o null. Busca palabras enteras en categoría +
     * título para que "nba" no salte dentro de otra palabra. */
    fun otherSportOf(program: EpgProgram): String? {
        val text = " " + stripAccents((program.category ?: "") + " " + program.title).replace(Regex("[^a-z0-9]+"), " ") + " "
        return OTHER_SPORT_KEYWORDS.firstOrNull { (_, words) -> words.any { text.contains(" $it ") } }?.first
    }

    /** Deporte a pedir a TheSportsDB para este programa: el de [otherSportOf]
     * o fútbol por defecto. */
    fun sportOf(program: EpgProgram): String = otherSportOf(program) ?: "Soccer"

    fun isSportsProgram(program: EpgProgram): Boolean {
        if (otherSportOf(program) != null) return true
        val normalizedCategory = program.category?.let { stripAccents(it) }
        if (normalizedCategory != null && SPORTS_CATEGORY_KEYWORDS.any { normalizedCategory.contains(it) }) return true
        val normalizedTitle = stripAccents(program.title)
        return SPORTS_CATEGORY_KEYWORDS.any { normalizedTitle.contains(it) }
    }

    /** Saca los dos equipos de un título tipo "LALIGA EA SPORTS T26/27 ·
     * Jornada 4: RCD Espanyol de Barcelona - Sevilla FC" o, en canales en
     * inglés como DAZN, "Fulham vs Manchester United" (sin dos puntos
     * delante) — todo lo de antes de los DOS PUNTOS (la competición/jornada,
     * si la hay) se descarta, y lo que queda se separa por " - " o por
     * " vs "/" vs. " (con espacios a los lados en los dos casos, para no
     * cortar por un guion o un "vs" que sea parte de un nombre). Si el
     * título no tiene esa forma (muchos canales de deporte solo traen su
     * propio nombre, tipo "LALIGA TV HYPERMOTION 3", sin equipos) se
     * devuelve null — no hay nada que sacar de ahí, ni falta que hace
     * forzarlo. */
    fun parseMatchTeams(title: String): Pair<String, String>? {
        // Algunas guías ponen la competición DETRÁS de los equipos ("DIRECTO
        // Jornada 8: Cádiz CF - CD Leganés T26/27 · LALIGA HYPERMOTION") en vez
        // de delante — sin cortar ese final, el segundo equipo salía como "CD
        // Leganés T26/27 · LALIGA HYPERMOTION" y no se reconocía (confirmado
        // real en la guía de esta lista).
        val afterColon = title.substringAfterLast(':', title)
            .replace(Regex("\\s+T\\d{2,4}(?:/\\d{2,4})?\\b.*$"), "")
            .replace(Regex("\\s+[·|].*$"), "")
            .replace(Regex("^(?:EN\\s+)?DIRECTO\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        val parts = afterColon.split(Regex("\\s+(?:-|vs\\.?)\\s+", RegexOption.IGNORE_CASE), limit = 2)
        if (parts.size != 2) return null
        val team1 = parts[0].trim()
        val team2 = parts[1].trim()
        if (team1.length < 2 || team2.length < 2) return null
        return team1 to team2
    }

    // Los XMLTV de guías reales (p.ej. EPG_dobleM) pueden pesar decenas de
    // MB sin comprimir — el cliente HTTP compartido de la app (20s de
    // callTimeout, pensado para APIs normales) cortaba la descarga a media
    // guía sin ningún error visible, dejando "sin guía disponible" en
    // todos los canales (confirmado real con un archivo de 34MB). Cliente
    // aparte solo para esto, con margen de verdad.
    private val epgHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    // lazy: asi el objeto se puede usar en pruebas unitarias (Looper no existe fuera de Android).
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_EPG_URL = "iptv_epg_url"

    fun getEpgUrl(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_EPG_URL, null)

    fun setEpgUrl(context: Context, url: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_EPG_URL, url)
            .apply()
    }

    /** [programsByChannel] indexa por el id de canal tal cual lo trae el
     * XMLTV (channel="..." de <programme>). [channelIdByNormalizedName]
     * es el cruce alternativo: muchas listas M3U (sobre todo las que
     * genera un proxy propio, tipo Movistar vía Raspberry Pi) traen un
     * tvg-id interno del proveedor que NO tiene nada que ver con el id de
     * ningún XMLTV público — el nombre del canal es lo único que de verdad
     * coincide entre los dos, así que se cruza por ahí como alternativa
     * (ver [currentAndNext] y [normalizeChannelName]). */
    data class EpgData(
        val programsByChannel: Map<String, List<EpgProgram>>,
        val channelIdByNormalizedName: Map<String, String>
    ) : Serializable

    fun fetchAsync(context: Context, onResult: (EpgData?) -> Unit) {
        val url = getEpgUrl(context)
        if (url.isNullOrBlank()) {
            onResult(null)
            return
        }
        executor.execute {
            val data = loadDiskCache(context, url) ?: try {
                fetch(url).also { saveDiskCache(context, url, it) }
            } catch (e: Exception) {
                android.util.Log.w("EpgManager", "No se pudo cargar la guia", e)
                null
            }
            mainHandler.post { onResult(data) }
        }
    }

    // "Válido para hoy Y esta URL Y esta versión de formato" — si cambia la
    // URL de EPG o pasa a otro día (la guía es por fechas concretas, la de
    // ayer no sirve) se vuelve a descargar sin más, no hace falta invalidar
    // el archivo a mano en ningún otro sitio (ver setEpgUrl, que ya limpia
    // cachedEpg en memoria pero no necesita tocar este archivo). El campo
    // formatVersion es aparte: un cambio en CÓMO se filtra/parsea el XMLTV
    // (p.ej. cuánto pasado se retiene, ver PAST_RETENTION_HOURS) no cambia
    // la forma de EpgProgram, así que un caché de ayer... no, de HOY mismo
    // pero guardado con la lógica vieja se seguía aceptando igual (mismo
    // url, mismo día) aunque le faltaran datos que la lógica nueva sí
    // habría incluido — confirmado real: la rejilla hacia atrás rellenaba
    // horas pasadas con el programa actual en vez de con el que tocaba,
    // porque el caché de esta misma sesión era de antes del arreglo de
    // retención. Subir CACHE_FORMAT_VERSION cada vez que cambie el parseo
    // fuerza una descarga fresca en vez de reusar un caché con forma
    // correcta pero contenido de una lógica vieja.
    private const val CACHE_FORMAT_VERSION = 4

    private data class DiskCache(val url: String, val dayKey: String, val formatVersion: Int, val data: EpgData) : Serializable

    // Un archivo por URL de guía: con varias listas guardadas, cambiar de una a
    // otra no obliga a descargar la guía de nuevo cada vez.
    private fun cacheFile(context: Context, url: String): File =
        File(context.cacheDir, "epg_cache_${Integer.toHexString(url.hashCode())}.dat")

    fun deleteCache(context: Context, url: String) {
        runCatching { cacheFile(context, url).delete() }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun loadDiskCache(context: Context, url: String): EpgData? {
        val file = cacheFile(context, url)
        if (!file.exists()) return null
        return try {
            ObjectInputStream(FileInputStream(file)).use { stream ->
                val cache = stream.readObject() as? DiskCache
                cache?.takeIf { it.url == url && it.dayKey == todayKey() && it.formatVersion == CACHE_FORMAT_VERSION }?.data
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveDiskCache(context: Context, url: String, data: EpgData) {
        try {
            File(context.cacheDir, "epg_cache.dat").delete() // archivo unico de versiones anteriores
            ObjectOutputStream(FileOutputStream(cacheFile(context, url))).use { stream ->
                stream.writeObject(DiskCache(url, todayKey(), CACHE_FORMAT_VERSION, data))
            }
        } catch (e: Exception) {
            // Best effort: si no se puede escribir en disco, el EPG de esta
            // sesión sigue funcionando igual, solo tocará descargarlo otra
            // vez la próxima vez que se abra la app.
        }
    }

    private fun fetch(url: String): EpgData {
        val response = epgHttpClient.newCall(IptvHttp.request(url)).execute()
        response.use {
            val rawStream = it.body?.byteStream() ?: return EpgData(emptyMap(), emptyMap())
            val pushback = PushbackInputStream(rawStream, 2)
            val header = ByteArray(2)
            val read = pushback.read(header)
            if (read > 0) pushback.unread(header, 0, read)
            val isGzip = read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
            val input = if (isGzip) GZIPInputStream(pushback) else pushback
            return parse(input, System.currentTimeMillis())
        }
    }

    private fun parse(input: InputStream, nowMs: Long): EpgData {
        val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()
        val channelIdByNormalizedName = mutableMapOf<String, String>()
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val pastRetentionMs = TimeUnit.HOURS.toMillis(PAST_RETENTION_HOURS.toLong())
        val parser = Xml.newPullParser()
        parser.setInput(input, null)

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when {
                eventType == XmlPullParser.START_TAG && parser.name == TAG_CHANNEL -> {
                    val channelId = parser.getAttributeValue(null, "id")
                    var depth = 1
                    while (depth > 0) {
                        eventType = parser.next()
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                if (parser.name == TAG_DISPLAY_NAME) {
                                    val displayName = parser.nextText()
                                    if (channelId != null && displayName.isNotBlank()) {
                                        val key = normalizeChannelName(displayName)
                                        if (key.isNotEmpty()) channelIdByNormalizedName.putIfAbsent(key, channelId)
                                    }
                                } else {
                                    depth++
                                }
                            }
                            XmlPullParser.END_TAG -> depth--
                            XmlPullParser.END_DOCUMENT -> depth = 0
                            else -> {}
                        }
                    }
                }
                eventType == XmlPullParser.START_TAG && parser.name == TAG_PROGRAMME -> {
                    val channelId = parser.getAttributeValue(null, "channel")
                    val startStr = parser.getAttributeValue(null, "start")
                    val stopStr = parser.getAttributeValue(null, "stop")
                    var title: String? = null
                    var desc: String? = null
                    // Puede haber varias <category> (género, "Película",
                    // clasificación...) — se guardan TODAS (unidas por coma)
                    // porque no hay garantía de en qué orden trae cada guía
                    // la que diga "película" (ver isMovieCategory).
                    val categories = mutableListOf<String>()

                    var depth = 1
                    while (depth > 0) {
                        eventType = parser.next()
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                when {
                                    parser.name == TAG_TITLE && title == null -> title = parser.nextText()
                                    parser.name == TAG_DESC && desc == null -> desc = parser.nextText()
                                    parser.name == TAG_CATEGORY -> parser.nextText().takeIf { it.isNotBlank() }?.let { categories.add(it) }
                                    else -> depth++
                                }
                            }
                            XmlPullParser.END_TAG -> depth--
                            XmlPullParser.END_DOCUMENT -> depth = 0
                            else -> {}
                        }
                    }

                    if (channelId != null && startStr != null && stopStr != null && !title.isNullOrBlank()) {
                        val start = parseXmltvDate(dateFormat, startStr)
                        val stop = parseXmltvDate(dateFormat, stopStr)
                        if (start != null && stop != null && stop >= nowMs - pastRetentionMs) {
                            val list = programsByChannel.getOrPut(channelId) { mutableListOf() }
                            if (list.size < MAX_PROGRAMS_PER_CHANNEL) {
                                val category = categories.joinToString(",").takeIf { it.isNotBlank() }
                                list.add(EpgProgram(channelId, title, start, stop, desc?.takeIf { it.isNotBlank() }, category))
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }
        programsByChannel.values.forEach { it.sortWith(compareBy { program -> program.startMs }) }
        return EpgData(programsByChannel, channelIdByNormalizedName)
    }

    private fun parseXmltvDate(format: SimpleDateFormat, raw: String): Long? =
        try {
            format.parse(raw.trim())?.time
        } catch (e: Exception) {
            null
        }

    // Reduce "La 1 HD", "LA1.tv", "la 1 (SD)" y similares a la misma clave
    // ("la1") — quita tildes, pasa a minúsculas, quita coletillas de
    // calidad habituales y se queda solo con letras/números.
    private val QUALITY_SUFFIX = Regex("\\b(hd|sd|fhd|uhd|4k|720|1080)\\b")
    private val NON_ALNUM = Regex("[^a-z0-9]")

    private val DIACRITICS = Regex("\\p{M}")
    // Se llama en cada bind de cada fila del EPG y para cada canal: se guarda el resultado.
    private val normalizedNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun normalizeChannelName(name: String): String =
        normalizedNames.getOrPut(name) {
            val noAccents = DIACRITICS.replace(Normalizer.normalize(name, Normalizer.Form.NFD), "")
            NON_ALNUM.replace(QUALITY_SUFFIX.replace(noAccents.lowercase(), ""), "")
        }

    /** [now] y [next] para un canal. Prueba primero por [tvgId] tal cual
     * (cruce exacto, el caso normal); si no hay nada, prueba por
     * [channelName] normalizado contra los display-name del XMLTV — ver
     * comentario de [EpgData], necesario cuando el M3U trae un tvg-id
     * propio del proveedor que no coincide con ningún XMLTV público. */
    fun currentAndNext(data: EpgData?, tvgId: String?, channelName: String?, nowMs: Long): Pair<EpgProgram?, EpgProgram?> {
        if (data == null) return null to null
        val programs = (tvgId?.takeIf { it.isNotBlank() }?.let { data.programsByChannel[it] })
            ?: channelName?.let { data.channelIdByNormalizedName[normalizeChannelName(it)] }
                ?.let { data.programsByChannel[it] }
            ?: return null to null
        val current = programs.firstOrNull { nowMs in it.startMs until it.endMs }
        // >= no > : en una guía real los programas van pegados (el fin de
        // uno es el inicio exacto del siguiente, sin hueco) — con ">"
        // estricto ese "siguiente" real quedaba excluido (su startMs NO es
        // mayor que el endMs de "ahora", es igual) y "después" mostraba el
        // que viene DESPUÉS del que debería (confirmado real).
        val next = programs.firstOrNull { it.startMs >= (current?.endMs ?: nowMs) }
        return current to next
    }

    /** Todos los programas de un canal que se solapan con [windowStart,
     * windowEnd) — para la rejilla horaria (ver IptvChannelsGridFragment),
     * a diferencia de [currentAndNext] que solo da el actual y el
     * siguiente. Mismo cruce tvgId/nombre normalizado que [currentAndNext]. */
    fun programsInWindow(data: EpgData?, tvgId: String?, channelName: String?, windowStart: Long, windowEnd: Long): List<EpgProgram> {
        if (data == null) return emptyList()
        val programs = (tvgId?.takeIf { it.isNotBlank() }?.let { data.programsByChannel[it] })
            ?: channelName?.let { data.channelIdByNormalizedName[normalizeChannelName(it)] }
                ?.let { data.programsByChannel[it] }
            ?: return emptyList()
        return programs.filter { it.startMs < windowEnd && it.endMs > windowStart }
    }
}
