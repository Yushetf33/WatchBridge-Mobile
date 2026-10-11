package com.tunombre.tvbridge.mobile

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Una lista IPTV que el usuario ha escrito a mano: un enlace M3U (con su guia XMLTV opcional) o un acceso Xtream Codes. */
data class IptvSource(
    val id: String,
    val name: String,
    /** "URL" (enlace M3U) o "XTREAM" (servidor + usuario + contraseña). */
    val mode: String,
    val playlistUrl: String,
    val epgUrl: String?,
    val xtreamServer: String,
    val xtreamUser: String,
    val xtreamPass: String
) {
    val isXtream: Boolean get() = mode == "XTREAM"

    /** El enlace de la lista que se descarga (para Xtream, el get.php del panel). */
    val effectivePlaylistUrl: String
        get() = if (isXtream) IptvRepository.xtreamPlaylistUrl(xtreamServer, xtreamUser, xtreamPass) else playlistUrl
}

/** Listas guardadas, cual esta activa y los favoritos de cada una (en local; no se sincroniza). */
object IptvStore {
    private const val PREFS = "iptv_store"
    private const val KEY_LISTS = "lists"
    private const val KEY_ACTIVE = "active"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<IptvSource> {
        val raw = prefs(context).getString(KEY_LISTS, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                IptvSource(
                    o.getString("id"), o.optString("name"), o.optString("mode", "URL"), o.optString("url"),
                    o.optStringOrNull("epg"), o.optString("server"), o.optString("user"), o.optString("pass")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(context: Context, lists: List<IptvSource>) {
        val a = JSONArray()
        lists.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode).put("url", it.playlistUrl)
                    .put("epg", it.epgUrl ?: JSONObject.NULL).put("server", it.xtreamServer).put("user", it.xtreamUser).put("pass", it.xtreamPass)
            )
        }
        prefs(context).edit().putString(KEY_LISTS, a.toString()).apply()
    }

    fun active(context: Context): IptvSource? {
        val lists = all(context)
        val id = prefs(context).getString(KEY_ACTIVE, null)
        return lists.firstOrNull { it.id == id } ?: lists.firstOrNull()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Añade la lista y la deja como activa. */
    fun add(context: Context, source: IptvSource): IptvSource {
        val withId = source.copy(id = UUID.randomUUID().toString())
        saveAll(context, all(context) + withId)
        setActive(context, withId.id)
        return withId
    }

    fun remove(context: Context, id: String) {
        saveAll(context, all(context).filterNot { it.id == id })
        prefs(context).edit().remove("fav_$id").apply()
        if (prefs(context).getString(KEY_ACTIVE, null) == id) prefs(context).edit().remove(KEY_ACTIVE).apply()
    }

    fun favorites(context: Context, listId: String): Set<String> =
        prefs(context).getStringSet("fav_$listId", emptySet()) ?: emptySet()

    fun toggleFavorite(context: Context, listId: String, channelKey: String): Boolean {
        val current = favorites(context, listId).toMutableSet()
        val added = if (channelKey in current) { current.remove(channelKey); false } else { current.add(channelKey); true }
        prefs(context).edit().putStringSet("fav_$listId", current).apply()
        return added
    }
}
