package com.tunombre.tvbridge.mobile

import java.io.Serializable

/** Un programa de la guía EPG (XMLTV) — ver [EpgManager]. [channelId] es el
 * atributo channel="..." del <programme>, que cruza con [IptvChannel.tvgId].
 * Serializable para poder guardar el EPG ya parseado en disco (ver
 * EpgManager.loadDiskCache/saveDiskCache) y no tener que volver a
 * descargar/parsear el XMLTV (puede pesar decenas de MB) cada vez que se
 * abre la app el mismo día. */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    // <desc> del XMLTV — se muestra en el panel del mini-reproductor en vez
    // de "Después: ..." cuando la hay (ver IptvChannelsFragment.showNowPlaying),
    // que en la práctica es casi siempre para las guías reales (no todas
    // las incluyen, de ahí que sea opcional).
    val synopsis: String? = null,
    // Todas las <category> del XMLTV, unidas por coma (género, "Película",
    // clasificación...) — ver EpgManager.isMovieCategory, que decide si
    // mostrar la carátula junto al mini-reproductor (ver
    // IptvChannelsFragment.updateMoviePoster).
    val category: String? = null
) : Serializable
