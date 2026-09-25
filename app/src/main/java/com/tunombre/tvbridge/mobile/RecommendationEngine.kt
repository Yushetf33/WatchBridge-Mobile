package com.tunombre.tvbridge.mobile

/**
 * Calcula las filas de "Porque viste X" (y la de "Recomendado para ti", ver
 * [buildTopPicks]) a partir del historial local — compartido entre
 * [RecommendationChannelManager] (fila de Android TV, que en la práctica
 * Google TV no llega a mostrar en todos los launchers) y
 * [RecommendationsRowsFragment] (filas propias dentro de la app, que sí
 * funcionan siempre). Nada de LLM: solo TMDb, así que no hay riesgo de
 * "recomendar" un título que no existe.
 */
object RecommendationEngine {
    // Cuántos de los últimos títulos vistos (por separado en películas y en
    // series) se usan como semilla — una fila "Porque viste X" por cada
    // uno, y todas juntas alimentan "Recomendado para ti" (ver
    // buildTopPicks). Más semillas que antes (eran 5): con más historial
    // real disponible ahora (ver RecommendationHistory, hasta 200
    // entradas), cuantas más semillas, mejor señal cruzada para saber qué
    // se repite entre varias.
    private const val SEEDS_PER_TYPE = 8
    private const val MAX_RESULTS_PER_SEED = 20
    private const val TOP_PICKS_MAX = 20
    // Un título tiene que salir recomendado por al menos DOS semillas
    // distintas para entrar en "Recomendado para ti" — si solo lo
    // recomienda una, no aporta nada sobre la fila "Porque viste X" de esa
    // misma semilla, y con una sola semilla vista la fila saldría vacía o
    // redundante casi siempre.
    private const val TOP_PICKS_MIN_SEED_AGREEMENT = 2

    /** Una fila "Porque viste [seedTitle]" con sus recomendaciones (ya sin
     * nada que el usuario haya visto ya, y ya ordenadas por popularidad). */
    data class SeedRow(val seedTitle: String, val recommendations: List<TmdbClient.TmdbRecommendation>)

    data class Recommendations(
        val movieTopPicks: List<TmdbClient.TmdbRecommendation>,
        val seriesTopPicks: List<TmdbClient.TmdbRecommendation>,
        val movieRows: List<SeedRow>,
        val seriesRows: List<SeedRow>
    ) {
        val isEmpty: Boolean get() =
            movieRows.isEmpty() && seriesRows.isEmpty() && movieTopPicks.isEmpty() && seriesTopPicks.isEmpty()
    }

    private data class SeedResult(val seedTitle: String, val recommendations: List<TmdbClient.TmdbRecommendation>)

    /** Bloqueante (red: TMDb + el historial fusionado con otros
     * dispositivos, ver [RecommendationHistory.fetchMerged]) — llamar
     * siempre desde un hilo de fondo. Todo vacío si todavía no hay
     * historial. */
    fun compute(context: android.content.Context): Recommendations {
        val history = RecommendationHistory.fetchMerged(context)
        val (movieTopPicks, movieRows) = computeForType(history, "movie")
        val (seriesTopPicks, seriesRows) = computeForType(history, "tv")
        return Recommendations(movieTopPicks, seriesTopPicks, movieRows, seriesRows)
    }

    /** Recomendaciones de UN tipo ("movie" o "tv") a partir de un historial
     * ya cargado — para que la pantalla pueda pintar películas en cuanto
     * estén, sin esperar a las series, y sin volver a pedir el historial
     * (ver HomeViewModel.refresh). Bloqueante (red: TMDb). */
    fun computeForType(
        history: List<OpenedTitle>,
        mediaPath: String
    ): Pair<List<TmdbClient.TmdbRecommendation>, List<SeedRow>> {
        val seeds = history.filter { it.mediaPath == mediaPath }.take(SEEDS_PER_TYPE)
        if (seeds.isEmpty()) return emptyList<TmdbClient.TmdbRecommendation>() to emptyList()
        val seen = history.map { it.tmdbId to it.mediaPath }.toSet()
        // Una sola llamada a TMDb por semilla, reutilizada tanto para la
        // fila propia de esa semilla como para el cruce de
        // "Recomendado para ti" — antes se pedía dos veces lo mismo.
        val results = fetchForSeeds(seeds, seen)
        return buildTopPicks(results) to buildRows(results)
    }

    // Las llamadas a TMDb de las semillas van en paralelo: antes eran una
    // detrás de otra (hasta 16 seguidas), que era el grueso de la espera.
    private val seedExecutor = java.util.concurrent.Executors.newFixedThreadPool(6) { r ->
        Thread(r).apply { isDaemon = true }
    }

    private fun fetchForSeeds(seeds: List<OpenedTitle>, seen: Set<Pair<Int, String>>): List<SeedResult> {
        return seeds.map { seed ->
            seedExecutor.submit<SeedResult> {
                val recs = TmdbClient.fetchRecommendations(seed.tmdbId, seed.mediaPath)
                    .filter { (it.tmdbId to it.mediaPath) !in seen }
                    .sortedByDescending { it.popularity }
                SeedResult(seed.title, recs)
            }
        }.map { it.get() }
    }

    private fun buildRows(seedResults: List<SeedResult>): List<SeedRow> {
        return seedResults.mapNotNull { result ->
            val capped = result.recommendations.take(MAX_RESULTS_PER_SEED)
            // Sin resultados nuevos para esta semilla (poco frecuente, pero
            // pasa con títulos muy de nicho) — no tiene sentido una fila
            // vacía, se omite en vez de mostrarla en blanco.
            if (capped.isEmpty()) null else SeedRow(result.seedTitle, capped)
        }
    }

    /** Cruza las recomendaciones de TODAS las semillas de un tipo: un
     * título que sale recomendado por varias cosas distintas que el
     * usuario ha visto es una señal mucho más fuerte que cualquier fila
     * "Porque viste X" por sí sola. Empate a nº de semillas que lo
     * recomiendan se resuelve por popularidad. */
    private fun buildTopPicks(seedResults: List<SeedResult>): List<TmdbClient.TmdbRecommendation> {
        val bestByCandidate = LinkedHashMap<Pair<Int, String>, TmdbClient.TmdbRecommendation>()
        val timesRecommended = HashMap<Pair<Int, String>, Int>()
        for (result in seedResults) {
            for (rec in result.recommendations) {
                val key = rec.tmdbId to rec.mediaPath
                bestByCandidate.putIfAbsent(key, rec)
                timesRecommended[key] = (timesRecommended[key] ?: 0) + 1
            }
        }
        return bestByCandidate.entries
            .filter { (key, _) -> (timesRecommended[key] ?: 0) >= TOP_PICKS_MIN_SEED_AGREEMENT }
            .sortedWith(
                compareByDescending<Map.Entry<Pair<Int, String>, TmdbClient.TmdbRecommendation>> { timesRecommended[it.key] ?: 0 }
                    .thenByDescending { it.value.popularity }
            )
            .map { it.value }
            .take(TOP_PICKS_MAX)
    }
}
