package com.tunombre.tvbridge.mobile

/**
 * Parser de listas IPTV. Lo habitual es M3U/M3U8 extendido:
 * ```
 * #EXTM3U
 * #EXTINF:-1 tvg-logo="https://..." group-title="Deportes",Nombre del canal
 * http://servidor/stream.m3u8
 * ```
 * Solo lee lo que hace falta para pintar la lista (nombre, logo, grupo) y la
 * URL del stream — ignora cualquier otra etiqueta en vez de fallar si
 * aparecen, ya que cada proveedor de IPTV las usa de forma distinta y no
 * todas son necesarias para reproducir. Además de lo estándar tolera las
 * variantes que se ven en listas reales: BOM al principio, canales sin
 * nombre tras la coma (se usa tvg-name), grupo en una línea #EXTGRP aparte,
 * cabeceras User-Agent/Referer (#EXTVLCOPT o el sufijo "|User-Agent=..." de
 * Kodi) y listas PLS ([playlist] / File1= / Title1=).
 */
object M3uParser {

    private val LOGO_ATTR = Regex("tvg-logo=[\"']([^\"']*)[\"']")
    private val GROUP_ATTR = Regex("group-title=[\"']([^\"']*)[\"']")
    private val TVG_ID_ATTR = Regex("tvg-id=[\"']([^\"']*)[\"']")
    private val TVG_NAME_ATTR = Regex("tvg-name=[\"']([^\"']*)[\"']")

    // Catch-up (ver CatchupUrl). `tvg-rec` y `timeshift` son los alias de "dias de archivo" que usan
    // algunas listas en lugar de catchup-days.
    private val CATCHUP_ATTR = Regex("(?<![\\w-])catchup=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val CATCHUP_SOURCE_ATTR = Regex("catchup-source=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
    private val CATCHUP_DAYS_ATTR = Regex("(?:catchup-days|tvg-rec|timeshift)=[\"']?(\\d+)[\"']?", RegexOption.IGNORE_CASE)

    /** Catch-up declarado en la cabecera `#EXTM3U` (vale para todos los canales que no traigan el suyo). */
    private data class CatchupAttrs(val type: String?, val source: String?, val days: Int) {
        /** Resultado final: sin tipo ni fuente no hay catch-up; con fuente y sin tipo es "default"; sin dias, 7. */
        fun resolved(): CatchupAttrs {
            val t = type?.ifBlank { null } ?: if (!source.isNullOrBlank()) "default" else null
            return if (t == null) CatchupAttrs(null, null, 0) else CatchupAttrs(t, source?.ifBlank { null }, if (days > 0) days else DEFAULT_CATCHUP_DAYS)
        }
    }

    private fun readCatchup(line: String) = CatchupAttrs(
        CATCHUP_ATTR.find(line)?.groupValues?.get(1),
        CATCHUP_SOURCE_ATTR.find(line)?.groupValues?.get(1),
        CATCHUP_DAYS_ATTR.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    )

    private const val DEFAULT_CATCHUP_DAYS = 7

    private val EPG_ATTR = Regex("(?:url-tvg|x-tvg-url)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    /** URL de guia (XMLTV) que la propia lista declara en su cabecera:
     * `#EXTM3U url-tvg="https://..."` (o x-tvg-url). Es el mecanismo estandar
     * por el que un proveedor dice "mi guia esta aqui", asi que usarlo no
     * depende de ninguna fuente de terceros elegida por nosotros. Si trae
     * varias separadas por coma se toma la primera. */
    fun parseDeclaredEpgUrl(rawContent: String): String? {
        val header = rawContent.removePrefix("\uFEFF").lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) return null
        return EPG_ATTR.find(header)?.groupValues?.get(1)?.split(',')?.firstOrNull()?.trim()
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
    }

    fun parse(rawContent: String): List<IptvChannel> {
        val content = rawContent.removePrefix("﻿")
        if (content.trimStart().startsWith("[playlist]", ignoreCase = true)) return parsePls(content)

        val channels = mutableListOf<IptvChannel>()
        val header = content.lineSequence().firstOrNull { it.isNotBlank() }?.takeIf { it.startsWith("#EXTM3U", ignoreCase = true) }
        val globalCatchup = header?.let { readCatchup(it) } ?: CatchupAttrs(null, null, 0)
        var pendingCatchup = globalCatchup
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null
        var hasExtinf = false

        fun reset() {
            pendingName = null
            pendingLogo = null
            pendingGroup = null
            pendingTvgId = null
            pendingUserAgent = null
            pendingReferrer = null
            pendingCatchup = globalCatchup
            hasExtinf = false
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    reset()
                    hasExtinf = true
                    // El nombre del canal es lo que hay tras la ÚLTIMA coma
                    // que queda FUERA de las comillas (los atributos tvg-*
                    // van antes y pueden traer comas dentro de sus valores).
                    // Si no hay ninguna, se usa tvg-name.
                    val afterQuotes = line.lastIndexOf(',').takeIf { it > line.lastIndexOfAny(charArrayOf('"', '\'')) }
                    val titled = afterQuotes?.let { line.substring(it + 1).trim() }
                    pendingName = titled?.ifBlank { null }
                        ?: TVG_NAME_ATTR.find(line)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                    pendingLogo = LOGO_ATTR.find(line)?.groupValues?.get(1)?.ifBlank { null }
                    pendingGroup = GROUP_ATTR.find(line)?.groupValues?.get(1)?.ifBlank { null }
                    pendingTvgId = TVG_ID_ATTR.find(line)?.groupValues?.get(1)?.ifBlank { null }
                    // Los atributos del propio canal mandan sobre los de la cabecera.
                    val own = readCatchup(line)
                    pendingCatchup = CatchupAttrs(
                        own.type ?: globalCatchup.type,
                        own.source ?: globalCatchup.source,
                        if (own.days > 0) own.days else globalCatchup.days
                    )
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (pendingGroup == null) pendingGroup = line.substringAfter(':').trim().ifBlank { null }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val value = option.substringAfter('=', "").trim().ifBlank { null }
                    when (option.substringBefore('=').trim().lowercase()) {
                        "http-user-agent" -> pendingUserAgent = value
                        "http-referrer", "http-referer" -> pendingReferrer = value
                    }
                }
                line.isBlank() || line.startsWith("#") -> {
                    // Otras etiquetas (#EXTM3U, #KODIPROP...) o línea en
                    // blanco — no son la URL del stream, se ignoran.
                }
                else -> {
                    // Primera línea que no es ni comentario ni blanco tras un
                    // #EXTINF: es la URL del stream de ese canal.
                    if (hasExtinf) {
                        val (url, userAgent, referrer) = splitKodiHeaders(line)
                        val name = pendingName ?: url.substringAfterLast('/').substringBefore('?').ifBlank { null }
                        if (name != null) {
                            val catchup = pendingCatchup.resolved()
                            channels.add(
                                IptvChannel(
                                    name, url, pendingLogo, pendingGroup, pendingTvgId,
                                    userAgent ?: pendingUserAgent, referrer ?: pendingReferrer,
                                    catchupType = catchup.type, catchupSource = catchup.source, catchupDays = catchup.days
                                )
                            )
                        }
                    }
                    reset()
                }
            }
        }
        return channels
    }

    /** Kodi añade las cabeceras al final de la URL: `http://x/y.m3u8|User-Agent=Foo&Referer=Bar`. */
    private fun splitKodiHeaders(line: String): Triple<String, String?, String?> {
        val pipe = line.indexOf('|')
        if (pipe < 0 || !line.startsWith("http", ignoreCase = true)) return Triple(line, null, null)
        var userAgent: String? = null
        var referrer: String? = null
        line.substring(pipe + 1).split('&').forEach { pair ->
            val value = pair.substringAfter('=', "").trim().ifBlank { null }
            when (pair.substringBefore('=').trim().lowercase()) {
                "user-agent" -> userAgent = value
                "referer", "referrer" -> referrer = value
            }
        }
        return Triple(line.substring(0, pipe), userAgent, referrer)
    }

    private fun parsePls(content: String): List<IptvChannel> {
        val urls = sortedMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            val key = line.substringBefore('=').trim().lowercase()
            val value = line.substringAfter('=', "").trim()
            if (value.isEmpty()) return@forEach
            when {
                key.startsWith("file") -> key.removePrefix("file").toIntOrNull()?.let { urls[it] = value }
                key.startsWith("title") -> key.removePrefix("title").toIntOrNull()?.let { titles[it] = value }
            }
        }
        return urls.map { (index, url) ->
            IptvChannel(titles[index] ?: url.substringAfterLast('/').ifBlank { "Canal $index" }, url, null, null, null)
        }
    }
}
