package com.tunombre.tvbridge.mobile

/** Un canal de una lista IPTV (M3U) — ver [M3uParser]. [groupTitle] es el
 * atributo group-title de la línea #EXTINF (p.ej. "Deportes", "Cine"), null
 * si el canal no lo trae. [tvgId] es el atributo tvg-id, el identificador
 * que cruza este canal con su programación en el XMLTV del EPG (ver
 * EpgManager) — null si la lista no lo trae, en cuyo caso ese canal
 * simplemente no tiene "ahora/después" aunque haya EPG cargado.
 * [userAgent] y [referrer] son las cabeceras HTTP que algunas listas exigen
 * para ese canal (#EXTVLCOPT o sufijo "|User-Agent=..." de Kodi); null en la
 * inmensa mayoría, que usan las de siempre. */
data class IptvChannel(
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val tvgId: String?,
    val userAgent: String? = null,
    val referrer: String? = null,
    // Catch-up (ver CatchupUrl): como pedir al proveedor un programa ya emitido. [catchupType] es el
    // atributo `catchup` de la lista (default/append/shift/flussonic/xc), [catchupDays] cuantos dias
    // guarda el proveedor y [catchupZone] la zona horaria del servidor (Xtream). 0/null = sin catch-up.
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int = 0,
    val catchupZone: String? = null,
    // Solo en la copia del canal que reproduce un programa pasado: cual es.
    val catchupProgram: EpgProgram? = null,
    // Otras formas de pedir el mismo canal (p.ej. ".m3u8" en vez de ".ts", o el enlace directo del panel):
    // el reproductor las prueba en orden si la principal da 403/404 (ver IptvActivity.retryNextCandidate).
    val altStreamUrls: List<String> = emptyList()
) {
    /** Identificador estable del canal para favoritos y bloqueos: no usa la URL del
     * stream porque algunos proveedores le meten un token que cambia. */
    val key: String get() = "${tvgId.orEmpty()}|$name"
}
