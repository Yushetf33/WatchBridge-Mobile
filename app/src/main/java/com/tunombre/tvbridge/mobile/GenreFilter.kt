package com.tunombre.tvbridge.mobile

import android.content.Context

/**
 * El genero que el usuario ha elegido para lo que se le recomienda, uno para peliculas y otro para series (los ids
 * de TMDb no coinciden entre los dos, ver [TmdbClient.Genre]). Se guarda en local; null = sin filtro. Igual que en
 * la app de TV.
 */
object GenreFilter {
    private const val PREFS = "genre_filter"

    fun get(context: Context, mediaPath: String): TmdbClient.Genre? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt("${mediaPath}_id", 0)
        val name = prefs.getString("${mediaPath}_name", null)
        return if (id > 0 && !name.isNullOrBlank()) TmdbClient.Genre(id, name) else null
    }

    fun set(context: Context, mediaPath: String, genre: TmdbClient.Genre?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (genre == null) editor.remove("${mediaPath}_id").remove("${mediaPath}_name")
        else editor.putInt("${mediaPath}_id", genre.id).putString("${mediaPath}_name", genre.name)
        editor.apply()
    }

    /** Las tarjetas que son del genero [genre] (todas si no hay filtro). */
    fun filter(items: List<TmdbClient.TmdbRecommendation>, genre: TmdbClient.Genre?): List<TmdbClient.TmdbRecommendation> =
        if (genre == null) items else items.filter { genre.id in it.genreIds }

    /** Las filas "Porque viste X" con solo lo que es del genero; las que se quedan vacias desaparecen. */
    fun filterRows(rows: List<RecommendationEngine.SeedRow>, genre: TmdbClient.Genre?): List<RecommendationEngine.SeedRow> =
        if (genre == null) rows else rows.mapNotNull { row ->
            filter(row.recommendations, genre).takeIf { it.isNotEmpty() }?.let { row.copy(recommendations = it) }
        }
}
