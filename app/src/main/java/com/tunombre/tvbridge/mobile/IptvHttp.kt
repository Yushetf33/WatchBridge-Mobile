package com.tunombre.tvbridge.mobile

import okhttp3.Request

/**
 * Peticiones a listas, guias y paneles IPTV. Muchos proveedores rechazan el User-Agent de serie de
 * OkHttp ("okhttp/4.x"), a veces con codigos propios que no son HTTP (un usuario vio "error 884"), y
 * solo aceptan lo que parece un reproductor o un navegador; la reproduccion de canales ya mandaba uno
 * de navegador (ver IptvActivity), asi que la descarga de la lista tambien lo manda y, si el proveedor
 * la rechaza, prueba con el de un reproductor generico antes de rendirse.
 */
object IptvHttp {
    private const val CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val PLAYER = "VLC/3.0.20 LibVLC/3.0.20"

    /** En este orden: se prueba el siguiente solo si el proveedor rechaza el anterior. */
    val USER_AGENTS = listOf(CHROME, PLAYER)

    fun request(url: String, userAgent: String = USER_AGENTS[0]): Request =
        Request.Builder().url(url).header("User-Agent", userAgent).build()

    /** Los codigos 4xx/5xx normales se muestran tal cual; el resto (p.ej. 884) o un 401/403 son del proveedor. */
    fun isProviderRejection(code: Int): Boolean = code !in 400..599 || code == 401 || code == 403
}
