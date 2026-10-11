package com.tunombre.tvbridge.mobile

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Lista de canales sacada de la API de un panel Xtream Codes (`player_api.php`) en vez del M3U de
 * `get.php`. Hay paneles que no sirven `get.php` (responde 404) pero si la API; sin esto esa lista
 * no se podia cargar aunque el usuario y la contraseña fueran buenos. La usa [IptvManager] solo
 * cuando la descarga normal falla con 404 o llega vacia.
 */
object XtreamApiPlaylist {

    private const val MAX_BYTES = 80L * 1024 * 1024

    /** Los canales en directo del panel; null si no responde, no autentica o no trae ninguno. */
    fun fetch(client: OkHttpClient, server: String, user: String, pass: String): List<IptvChannel>? = try {
        val api = client.newBuilder().callTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        val base = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val streams = get(api, "$base&action=get_live_streams")?.let { runCatching { JSONArray(it) }.getOrNull() }
        if (streams == null || streams.length() == 0) {
            null
        } else {
            val categories = categoryNames(api, base)
            val streamBase = streamBase(api, base, server)
            parse(streams, categories, streamBase, user, pass).takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }

    /** Un panel Xtream puede servir los streams desde otro host/puerto que la API: lo dice en
     * `server_info` (url, port/https_port, server_protocol), y asi lo hacen los clientes Xtream. Si no
     * lo dice (o no se puede leer), se usa el mismo servidor que la API. */
    internal fun streamBase(client: OkHttpClient, base: String, apiServer: String): String {
        val info = get(client, base)?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("server_info")
            ?: return apiServer
        return serverBase(info, apiServer)
    }

    internal fun serverBase(info: JSONObject, apiServer: String): String {
        val host = info.optString("url").trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (host.isEmpty() || host.contains('/')) return apiServer
        val https = info.optString("server_protocol").equals("https", ignoreCase = true)
        val port = info.optString(if (https) "https_port" else "port").trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return (if (https) "https://" else "http://") + host + (port?.let { ":$it" } ?: "")
    }

    // java.net en vez de android.net.Uri: asi tambien se puede probar con tests de JVM.
    internal fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    internal fun parse(
        streams: JSONArray,
        categories: Map<String, String>,
        server: String,
        user: String,
        pass: String
    ): List<IptvChannel> {
        val out = ArrayList<IptvChannel>(streams.length())
        for (i in 0 until streams.length()) {
            val item = streams.optJSONObject(i) ?: continue
            val id = item.optInt("stream_id", 0)
            val name = item.optString("name").trim()
            if (id <= 0 || name.isEmpty()) continue
            // La principal es la direccion de siempre de Xtream (.ts); si el servidor la rechaza se prueban,
            // por este orden, la de HLS, la que el panel da como "direct_source" (a veces un enlace corto
            // a otro servidor) y la variante sin "/live" ni extension.
            val stream = "$server/live/${enc(user)}/${enc(pass)}/$id"
            val direct = item.optString("direct_source").trim().takeIf { it.startsWith("http") }
            val alternatives = listOfNotNull("$stream.m3u8", direct, "$server/${enc(user)}/${enc(pass)}/$id")
            out.add(
                IptvChannel(
                    name = name,
                    streamUrl = "$stream.ts",
                    altStreamUrls = alternatives,
                    logoUrl = item.optString("stream_icon").takeIf { it.startsWith("http") },
                    groupTitle = categories[item.optString("category_id")],
                    tvgId = item.optString("epg_channel_id").takeIf { it.isNotBlank() }
                )
            )
        }
        return out
    }

    private fun categoryNames(client: OkHttpClient, base: String): Map<String, String> {
        val array = get(client, "$base&action=get_live_categories")?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return emptyMap()
        val names = HashMap<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name").trim()
            if (id.isNotEmpty() && name.isNotEmpty()) names[id] = name
        }
        return names
    }

    /** El cuerpo de la respuesta, probando los mismos User-Agent que el resto de IPTV (ver [IptvHttp]). */
    internal fun get(client: OkHttpClient, url: String): String? {
        for (userAgent in IptvHttp.USER_AGENTS) {
            client.newCall(IptvHttp.request(url, userAgent)).execute().use {
                if (!it.isSuccessful) return@use
                val body = it.body ?: return null
                if (body.contentLength() > MAX_BYTES) return null
                return body.string()
            }
        }
        return null
    }
}
