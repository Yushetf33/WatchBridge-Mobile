package com.tunombre.tvbridge.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Catch-up: pedir al PROVEEDOR un programa ya emitido (el que empezo hace una hora, o ayer). Cada
 * proveedor lo expone a su manera; la lista M3U lo declara con `catchup` (tipo), `catchup-days` (cuantos
 * dias guarda) y `catchup-source` (plantilla de URL), el mismo convenio que usan TiviMate, Kodi
 * (pvr.iptvsimple) y OTT Navigator. En Xtream Codes se descubre por la API (ver XtreamArchive).
 *
 * Tipos soportados:
 *  - `default`: la propia `catchup-source` es la URL completa (con marcadores).
 *  - `append`: URL del canal + `catchup-source`.
 *  - `shift`: URL del canal + `?utc=...&lutc=...`.
 *  - `flussonic` / `flussonic-hls` / `flussonic-ts`: se deriva de la URL del canal.
 *  - `xc` / `xtream`: `/timeshift/usuario/clave/minutos/AAAA-MM-DD:HH-MM/id.ts` derivado de la URL del canal.
 *
 * Marcadores en las plantillas (formato Kodi y TiviMate): {utc} {start} ${start} (inicio, segundos unix),
 * {utcend} {end} ${end} (fin), {lutc} {timestamp} ${timestamp} (ahora), {duration} ${duration} (segundos;
 * {duration:60} = minutos), {offset} (segundos desde el inicio hasta ahora), {Y} {m} {d} {H} {M} {S}
 * (inicio en UTC), {utc:YmdHMS} {utcend:...} {lutc:...} y ${(b)yyyyMMddHHmmss:utc} ${(e)...} ${(now)...}.
 *
 * Todo es logica pura y esta cubierta por tests; NO se ha podido probar contra un proveedor real.
 */
object CatchupUrl {
    private val DAY_MS = TimeUnit.DAYS.toMillis(1)

    /** El canal tiene catch-up y este programa cae dentro de los dias que guarda el proveedor (y ya empezo). */
    fun isPlayable(channel: IptvChannel, program: EpgProgram, nowMs: Long): Boolean =
        channel.catchupType != null && channel.catchupDays > 0 &&
            program.startMs <= nowMs && nowMs - program.startMs <= channel.catchupDays * DAY_MS

    /** URL para reproducir de [startMs] a [endMs] (milisegundos UTC); null si el tipo no se puede construir. */
    fun build(channel: IptvChannel, startMs: Long, endMs: Long, nowMs: Long): String? {
        val type = channel.catchupType?.lowercase(Locale.ROOT) ?: return null
        val times = Times(startMs, endMs, nowMs)
        return when (type) {
            "default" -> channel.catchupSource?.let { fill(it, times) }
            "append" -> channel.catchupSource?.let { channel.streamUrl + fill(it, times) }
            "shift" -> channel.streamUrl + (if ('?' in channel.streamUrl) "&" else "?") + fill("utc={utc}&lutc={lutc}", times)
            "flussonic", "flussonic-hls", "flussonic-ts" -> flussonic(channel.streamUrl, times)
            "xc", "xtream" -> xtream(channel, times)
            else -> null
        }
    }

    private class Times(val startMs: Long, val endMs: Long, val nowMs: Long) {
        val start get() = startMs / 1000
        val end get() = endMs / 1000
        val now get() = nowMs / 1000
        val duration get() = ((endMs - startMs) / 1000).coerceAtLeast(0)
    }

    private fun flussonic(url: String, t: Times): String? = when {
        "/index.m3u8" in url -> url.replace("/index.m3u8", "/index-${t.start}-${t.duration}.m3u8")
        "/video.m3u8" in url -> url.replace("/video.m3u8", "/video-${t.start}-${t.duration}.m3u8")
        "/mpegts" in url -> url.replace("/mpegts", "/timeshift_abs-${t.start}.ts")
        else -> null
    }

    // http(s)://host:puerto[/live]/usuario/clave/ID[.ext]
    private val XTREAM_STREAM = Regex("^(https?://[^/]+)(?:/live)?/([^/]+)/([^/]+)/(\\d+)(?:\\.\\w+)?(?:\\?.*)?$")

    /** Pieza util para otros sitios: el id de stream de una URL de Xtream, o null si no lo es. */
    fun xtreamStreamId(url: String): Int? = XTREAM_STREAM.matchEntire(url)?.groupValues?.get(4)?.toIntOrNull()

    private fun xtream(channel: IptvChannel, t: Times): String? {
        val m = XTREAM_STREAM.matchEntire(channel.streamUrl) ?: return null
        val (base, user, pass, id) = m.destructured
        // El servidor interpreta la hora de inicio en SU zona horaria (la que da server_info.timezone).
        val zone = channel.catchupZone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
        val start = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = zone }.format(Date(t.startMs))
        val minutes = ((t.duration + 59) / 60).coerceAtLeast(1)
        return "$base/timeshift/$user/$pass/$minutes/$start/$id.ts"
    }

    private val UTC = TimeZone.getTimeZone("UTC")
    private val KODI_FORMAT = Regex("\\{(utc|utcend|lutc):([YmdHMS\\-:T _.]+)\\}")
    private val TIVIMATE_FORMAT = Regex("\\$\\{\\((b|e|now|n)\\)([^:}]+):utc\\}")
    private val DURATION_DIV = Regex("\\{duration:(\\d+)\\}")

    private fun format(pattern: String, ms: Long) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(Date(ms))

    /** Letras de formato de Kodi (Y m d H M S) -> patron de SimpleDateFormat. */
    private fun kodiPattern(spec: String): String {
        val out = StringBuilder()
        for (c in spec) {
            when (c) {
                'Y' -> out.append("yyyy")
                'm' -> out.append("MM")
                'd' -> out.append("dd")
                'H' -> out.append("HH")
                'M' -> out.append("mm")
                'S' -> out.append("ss")
                'T' -> out.append("'T'")
                else -> if (c.isLetter()) out.append("'$c'") else out.append(c)
            }
        }
        return out.toString()
    }

    private fun fill(template: String, t: Times): String {
        var s = template
        s = KODI_FORMAT.replace(s) { m ->
            val ms = when (m.groupValues[1]) { "utc" -> t.startMs; "utcend" -> t.endMs; else -> t.nowMs }
            format(kodiPattern(m.groupValues[2]), ms)
        }
        s = TIVIMATE_FORMAT.replace(s) { m ->
            val ms = when (m.groupValues[1]) { "b" -> t.startMs; "e" -> t.endMs; else -> t.nowMs }
            format(m.groupValues[2], ms)
        }
        s = DURATION_DIV.replace(s) { m -> (t.duration / m.groupValues[1].toLong().coerceAtLeast(1)).toString() }
        // Las variantes con "$" primero: si no, "{start}" se come la llave de "${start}" y deja un "$" suelto.
        val simple = listOf(
            "\${start}" to t.start, "\${end}" to t.end, "\${timestamp}" to t.now, "\${duration}" to t.duration,
            "{utc}" to t.start, "{start}" to t.start,
            "{utcend}" to t.end, "{end}" to t.end,
            "{lutc}" to t.now, "{timestamp}" to t.now,
            "{duration}" to t.duration,
            "{offset}" to (t.now - t.start)
        )
        for ((key, value) in simple) s = s.replace(key, value.toString())
        val start = Date(t.startMs)
        mapOf("{Y}" to "yyyy", "{m}" to "MM", "{d}" to "dd", "{H}" to "HH", "{M}" to "mm", "{S}" to "ss").forEach { (key, pattern) ->
            if (key in s) s = s.replace(key, SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }.format(start))
        }
        return s
    }
}
