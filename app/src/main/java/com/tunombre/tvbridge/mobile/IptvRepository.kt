package com.tunombre.tvbridge.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Carga los canales de una lista IPTV y su guia (EPG), con cache en memoria. */
object IptvRepository {
    private val channelCache = HashMap<String, List<IptvChannel>>()

    @Volatile
    var cachedEpg: EpgManager.EpgData? = null
        private set

    /** Los canales de la lista activa tal como estan cargados ahora (el reproductor los usa para cambiar de canal). */
    @Volatile
    var currentChannels: List<IptvChannel> = emptyList()
        private set

    fun xtreamPlaylistUrl(server: String, user: String, pass: String): String =
        "$server/get.php?username=${XtreamApiPlaylist.enc(user)}&password=${XtreamApiPlaylist.enc(pass)}&type=m3u_plus&output=ts"

    fun xtreamEpgUrl(server: String, user: String, pass: String): String =
        "$server/xmltv.php?username=${XtreamApiPlaylist.enc(user)}&password=${XtreamApiPlaylist.enc(pass)}"

    /** "mi.servidor.com:8080" o "http://mi.servidor.com:8080/..." -> "http://mi.servidor.com:8080". */
    fun normalizeXtreamServer(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { java.net.URI(if (text.contains("://")) text else "http://$text") }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "${uri.scheme ?: "http"}://$host$port"
    }

    /** Si lo pegado es un acceso Xtream (get.php / player_api.php con username y password), devuelve (servidor, usuario,
     * contraseña); null si no lo es. */
    fun detectXtream(url: String): Triple<String, String, String>? {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty()
        if (!path.endsWith("/get.php") && !path.endsWith("/player_api.php")) return null
        val user = uri.getQueryParameter("username") ?: return null
        val pass = uri.getQueryParameter("password") ?: return null
        val server = normalizeXtreamServer(url) ?: return null
        return Triple(server, user, pass)
    }

    class LoadException(message: String) : Exception(message)

    /** Los canales de [source] (descarga la lista; con [force] ignora la cache). Bloqueante de red: usa Dispatchers.IO. */
    suspend fun channels(context: Context, source: IptvSource, force: Boolean = false): List<IptvChannel> = withContext(Dispatchers.IO) {
        if (!force) channelCache[source.id]?.let { currentChannels = it; return@withContext it }
        val url = source.effectivePlaylistUrl
        var body = ""
        var parsed: List<IptvChannel> = emptyList()
        var failure: LoadException? = null
        try {
            body = download(url)
            parsed = M3uParser.parse(body)
        } catch (e: LoadException) {
            failure = e
        } catch (e: Exception) {
            failure = LoadException(context.getString(R.string.iptv_error_network))
        }
        // Panel Xtream sin get.php (404) o con la lista vacia: la API del panel tambien da los canales.
        if (parsed.isEmpty() && source.isXtream) {
            XtreamApiPlaylist.fetch(sharedHttpClient, source.xtreamServer, source.xtreamUser, source.xtreamPass)?.let {
                parsed = it
                failure = null
            }
        }
        if (parsed.isEmpty()) throw failure ?: LoadException(context.getString(R.string.iptv_error_empty))
        channelCache[source.id] = parsed
        currentChannels = parsed
        // Guia: la que escribio el usuario; si no, la que declara la propia lista; si no, la del panel Xtream.
        val epg = source.epgUrl?.takeIf { it.isNotBlank() }
            ?: M3uParser.parseDeclaredEpgUrl(body)
            ?: if (source.isXtream) xtreamEpgUrl(source.xtreamServer, source.xtreamUser, source.xtreamPass) else null
        if (EpgManager.getEpgUrl(context) != epg) {
            EpgManager.setEpgUrl(context, epg)
            cachedEpg = null
        }
        parsed
    }

    private fun download(url: String): String {
        var lastCode = 0
        for (userAgent in IptvHttp.USER_AGENTS) {
            sharedHttpClient.newBuilder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(IptvHttp.request(url, userAgent)).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: return ""
                        if (bytes.size > 120L * 1024 * 1024) throw LoadException("Lista demasiado grande")
                        return String(bytes, Charsets.UTF_8)
                    }
                    lastCode = response.code
                }
        }
        throw LoadException("Error $lastCode al descargar la lista")
    }

    /** Descarga la guia de la lista activa (o la lee de la cache del dia). El resultado llega en el hilo principal. */
    suspend fun loadEpg(context: Context, force: Boolean = false): EpgManager.EpgData? {
        if (!force) cachedEpg?.let { return it }
        return suspendCancellableCoroutine { cont ->
            EpgManager.fetchAsync(context.applicationContext) { data ->
                cachedEpg = data
                if (cont.isActive) cont.resume(data)
            }
        }
    }

    /** Programa de ahora y siguiente de un canal. */
    fun nowNext(channel: IptvChannel, nowMs: Long = System.currentTimeMillis()): Pair<EpgProgram?, EpgProgram?> =
        EpgManager.currentAndNext(cachedEpg, channel.tvgId, channel.name, nowMs)
}
