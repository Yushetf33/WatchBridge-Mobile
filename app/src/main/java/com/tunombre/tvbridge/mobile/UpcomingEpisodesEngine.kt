package com.tunombre.tvbridge.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calendario de "Próximos episodios": para las series que el usuario sigue
 * — Mi lista (interés explícito) o ya está viendo, según Continuar
 * viendo/Historial (interés implícito) — consulta en TMDb la fecha del
 * próximo episodio y las ordena de más próxima a más lejana. Solo series con
 * next_episode_to_air ya anunciado; las terminadas o sin fecha no salen.
 * Todo cliente, sin pasar por el backend, igual que RecommendationEngine.
 */
object UpcomingEpisodesEngine {

    private val ISO_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val DISPLAY_DATE_FORMAT = SimpleDateFormat("d MMM", Locale.getDefault())

    private data class Candidate(val airDate: String, val rec: TmdbClient.TmdbRecommendation)

    /** [watchlistItems]/[historyItems] ya deben venir fusionados con el
     * backend (ver Watchlist.fetchMerged/RecommendationHistory.fetchMerged)
     * — se reciben ya calculados en vez de pedirlos aquí otra vez para no
     * duplicar esas llamadas de red con las que ya hace
     * RecommendationsRowsFragment.onResume para "Mi lista"/"Continuar
     * viendo".
     *
     * Bloqueante (red: una llamada TMDb por cada serie distinta entre ambas
     * listas) — llamar siempre desde un hilo de fondo. */
    fun compute(watchlistItems: List<WatchlistItem>, historyItems: List<OpenedTitle>): List<TmdbClient.TmdbRecommendation> {
        val seriesById = LinkedHashMap<Int, Pair<String, String?>>()
        for (item in watchlistItems) {
            if (item.mediaPath == "tv") seriesById.putIfAbsent(item.tmdbId, item.title to item.posterPath)
        }
        for (item in historyItems) {
            if (item.mediaPath == "tv") seriesById.putIfAbsent(item.tmdbId, item.title to item.posterPath)
        }
        if (seriesById.isEmpty()) return emptyList()

        val today = ISO_DATE_FORMAT.format(Date())
        val candidates = seriesById.entries.mapNotNull { (tmdbId, info) ->
            val next = TmdbClient.fetchNextEpisode(tmdbId) ?: return@mapNotNull null
            // next_episode_to_air en TMDb ya debería ser siempre futuro,
            // pero por si el episodio emite justo hoy/hay desfase horario,
            // se compara igualmente en vez de asumirlo.
            if (next.airDate < today) return@mapNotNull null
            val (title, posterPath) = info
            Candidate(
                airDate = next.airDate,
                rec = TmdbClient.TmdbRecommendation(
                    tmdbId = tmdbId,
                    mediaPath = "tv",
                    title = title,
                    posterPath = posterPath,
                    popularity = 0.0,
                    overview = null,
                    subtitle = "T${next.season} · E${next.episode} — ${formatDate(next.airDate)}"
                )
            )
        }
        return candidates.sortedBy { it.airDate }.map { it.rec }
    }

    private fun formatDate(iso: String): String = try {
        ISO_DATE_FORMAT.parse(iso)?.let { DISPLAY_DATE_FORMAT.format(it) } ?: iso
    } catch (e: Exception) {
        iso
    }
}
