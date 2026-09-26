package com.tunombre.tvbridge.mobile

import android.content.Context
import org.json.JSONObject

/**
 * Altas y bajas hechas hace muy poco en ESTE dispositivo (ver
 * RecommendationHistory.fetchMerged y Watchlist.fetchMerged): el servidor es
 * la fuente de verdad, pero el aviso al servidor de un alta/baja va en
 * segundo plano, y una lectura que llegue justo antes de que se procese
 * "resucitaria" lo quitado o perderia lo recien anadido. Se recuerdan solo
 * unos minutos, lo justo para cubrir esa ventana.
 */
object RecentChanges {
    private const val PREFS_NAME = "tvbridge_recent_changes"
    private const val WINDOW_MS = 2 * 60 * 1000L

    fun markRemoved(context: Context, scope: String, tmdbId: Int, mediaPath: String) =
        mark(context, "$scope.removed", tmdbId, mediaPath)

    fun markAdded(context: Context, scope: String, tmdbId: Int, mediaPath: String) =
        mark(context, "$scope.added", tmdbId, mediaPath)

    fun recentlyRemoved(context: Context, scope: String): Set<Pair<Int, String>> =
        recent(context, "$scope.removed")

    fun recentlyAdded(context: Context, scope: String): Set<Pair<Int, String>> =
        recent(context, "$scope.added")

    private fun mark(context: Context, kind: String, tmdbId: Int, mediaPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject(prefs.getString(kind, null) ?: "{}")
        val now = System.currentTimeMillis()
        val stale = json.keys().asSequence().filter { now - json.optLong(it, 0L) > WINDOW_MS }.toList()
        stale.forEach { json.remove(it) }
        json.put("$tmdbId:$mediaPath", now)
        // Un alta anula una baja reciente del mismo titulo (y viceversa): si no,
        // quitar y volver a anadir dentro de la ventana hacia que la recarga
        // siguiente diera el titulo por quitado y lo borrara.
        val opposite = if (kind.endsWith(".added")) kind.removeSuffix(".added") + ".removed" else kind.removeSuffix(".removed") + ".added"
        val oppositeJson = JSONObject(prefs.getString(opposite, null) ?: "{}")
        oppositeJson.remove("$tmdbId:$mediaPath")
        prefs.edit().putString(kind, json.toString()).putString(opposite, oppositeJson.toString()).apply()
    }

    private fun recent(context: Context, kind: String): Set<Pair<Int, String>> {
        val json = JSONObject(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(kind, null) ?: "{}")
        val now = System.currentTimeMillis()
        return json.keys().asSequence()
            .filter { now - json.optLong(it, 0L) <= WINDOW_MS }
            .mapNotNull { key ->
                val sep = key.indexOf(':')
                key.substring(0, sep).toIntOrNull()?.let { it to key.substring(sep + 1) }
            }
            .toSet()
    }
}
