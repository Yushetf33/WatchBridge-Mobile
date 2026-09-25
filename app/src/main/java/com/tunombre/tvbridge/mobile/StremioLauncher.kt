package com.tunombre.tvbridge.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Lanza la app de destino elegida por el usuario (ver [Preferences]) en la
 * ficha de detalle de una película o serie.
 *
 * Nuvio: nuvio://movie/{imdbId} (películas), nuvio://detail/tv/{imdbId} (series).
 * Stremio: stremio:///detail/movie/{imdbId}, stremio:///detail/series/{imdbId}.
 * WuPlay: wuplay://movie/{imdbId}, wuplay://series/{imdbId}.
 * Todos confirmados por ADB en dispositivo real.
 */
object StremioLauncher {

    private const val TAG = "StremioLauncher"

    // Nuvio se distribuye con dos paquetes distintos según de dónde venga:
    // com.nuvio.app es el flavor "playstore", com.nuvio.tv es el flavor
    // "full" que se publica en las releases de GitHub (con más funciones
    // activadas) — confirmado leyendo su build.gradle.kts. Antes solo
    // comprobábamos com.nuvio.app, así que cualquiera con la build de
    // GitHub salía como "no instalado" aunque lo tuviera.
    // No privado: MediaWatchListenerService también necesita reconocer
    // ambos paquetes al vigilar sesiones de media activas.
    val NUVIO_PACKAGES = listOf("com.nuvio.app", "com.nuvio.tv")

    /**
     * @param overrideApp Si no es null, abre SIEMPRE en esa app en concreto
     * en vez de en la app por defecto (ver Preferences.getAppFor) — usado
     * por el selector "Cambiar reproductor" de DetailScreen, para una
     * apertura puntual sin tocar la preferencia guardada. Al venir de una
     * elección explícita del usuario, se saltan también las comprobaciones
     * automáticas de Jellyfin/Plex personales (tryOpenInPersonalJellyfin/
     * tryOpenInPlexCatalog) — esas solo tienen sentido para el flujo por
     * defecto, no cuando el usuario ya ha dicho exactamente dónde quiere
     * verlo.
     */
    fun open(service: Context, match: TmdbMatch, overrideApp: PlayerApp? = null) {
        // Aviso al programa de referidos (ver ReferralManager): si a este
        // dispositivo lo trajo el código de un amigo, esta es la señal de
        // "uso real" que desbloquea sus días de bonus — independiente del
        // ajuste de historial de abajo, es un aviso propio y de una sola
        // vez por dispositivo.
        ReferralManager.reportActivationIfNeeded(service)

        // Base del historial para "Recomendado para ti" — se registra aquí,
        // en el único punto por el que pasan todos los caminos de apertura.
        // A diferencia de la app de TV (donde esto es un extra opt-in sobre
        // su función principal de redirigir clics del launcher), en esta
        // app ES la función principal, así que siempre se registra, sin
        // interruptor de por medio.
        val mediaPath = if (match.type == MediaType.SERIES) "tv" else "movie"
        RecommendationHistory.record(service, match.tmdbId, mediaPath, match.title, match.posterPath, match.overview)

        if (overrideApp == null) {
            if (tryOpenInPersonalJellyfin(service, match)) return
            if (tryOpenInPlexCatalog(service, match)) return
        }
        when (val app = overrideApp ?: Preferences.getAppFor(service, match.type)) {
            PlayerApp.NUVIO -> {
                val uri = if (match.type == MediaType.MOVIE) {
                    Uri.parse("nuvio://movie/${match.imdbId}")
                } else {
                    Uri.parse("nuvio://detail/tv/${match.imdbId}")
                }
                openWithFallback(service, uri, NUVIO_PACKAGES, app.label)
            }
            PlayerApp.STREMIO -> {
                val stremioType = if (match.type == MediaType.SERIES) "series" else "movie"
                val uri = Uri.parse("stremio:///detail/$stremioType/${match.imdbId}")
                openWithFallback(service, uri, listOf(app.packageName), app.label)
            }
            PlayerApp.PLEX -> {
                // A diferencia de Nuvio/Stremio, Plex no tiene un esquema de
                // deep link por IMDb ID directo — hay que resolver antes la
                // URL de watch.plex.tv vía PlexClient (puede no encontrarse:
                // el catálogo gratuito de Plex no tiene todo lo que hay en
                // TMDb).
                val watchUrl = PlexClient.findWatchUrl(service, match)
                if (watchUrl == null) {
                    Log.w(TAG, "No encontrado en el catálogo gratuito de Plex: ${match.imdbId}")
                    return
                }
                openWithFallback(service, Uri.parse(watchUrl), listOf(app.packageName), app.label)
            }
            PlayerApp.JELLYFIN -> {
                // Jellyfin es autoalojado: no hay un ID de contenido
                // universal como el IMDb ID, así que se abre con una
                // búsqueda del título en vez de ir directo a la ficha.
                JellyfinLauncher.openSearch(service, match.title)
            }
            PlayerApp.WUPLAY -> {
                val wuplayType = if (match.type == MediaType.SERIES) "series" else "movie"
                val uri = Uri.parse("wuplay://$wuplayType/${match.imdbId}")
                openWithFallback(service, uri, listOf(app.packageName), app.label)
            }
            PlayerApp.WHOLPHIN -> {
                // Mismo caso que JELLYFIN: autoalojado, sin ID compartido,
                // se abre con búsqueda del título.
                WholphinLauncher.openSearch(service, match.title)
            }
        }
    }

    /**
     * Comprobación opcional, independiente de qué [PlayerApp] tenga elegida
     * el usuario: si ha configurado su propio servidor Jellyfin en Ajustes
     * (ver Preferences.isJellyfinCheckEnabled), consulta primero si el
     * título ya está en su biblioteca personal y, si es así, abre esa ficha
     * directamente en vez del flujo normal — útil incluso para quien usa
     * Nuvio/Stremio/etc. como destino principal, o para búsqueda por voz,
     * ya que ambos pasan por [open]. Si no está configurado, o el título no
     * está en su biblioteca, devuelve false y el llamador sigue con el
     * flujo normal de abajo.
     */
    private fun tryOpenInPersonalJellyfin(service: Context, match: TmdbMatch): Boolean {
        if (!Preferences.isJellyfinCheckEnabled(service)) return false
        val serverUrl = Preferences.getJellyfinServerUrl(service)
        val apiKey = Preferences.getJellyfinApiKey(service)
        if (serverUrl.isNullOrBlank() || apiKey.isNullOrBlank()) return false

        val itemId = JellyfinApiClient.findExactItemId(serverUrl, apiKey, match.title) ?: return false
        // Si Wholphin es la app elegida, abrir ahí en vez de en la app
        // oficial de Jellyfin — algunos usuarios usan Wholphin como único
        // cliente y puede que ni tengan la oficial instalada.
        return if (Preferences.getSelectedApp(service) == PlayerApp.WHOLPHIN) {
            WholphinLauncher.openItem(service, itemId)
        } else {
            JellyfinLauncher.openItem(service, itemId)
        }
    }

    /**
     * Segundo escalón del enrutado inteligente (ver tryOpenInPersonalJellyfin
     * para el primero): comprobación opcional e independiente de qué
     * [PlayerApp] tenga elegida el usuario — a diferencia de Jellyfin, esto
     * no necesita ninguna cuenta ni servidor propio (ver PlexClient, mismo
     * token compartido para todo el mundo), así que basta con un interruptor
     * simple. Si el título está en el catálogo gratuito de Plex, se abre ahí
     * directamente; si no, el llamador sigue con el flujo normal de abajo.
     */
    private fun tryOpenInPlexCatalog(service: Context, match: TmdbMatch): Boolean {
        if (!Preferences.isPlexCheckEnabled(service)) return false
        val watchUrl = PlexClient.findWatchUrl(service, match) ?: return false
        openWithFallback(service, Uri.parse(watchUrl), listOf(PlayerApp.PLEX.packageName), PlayerApp.PLEX.label)
        return true
    }

    private fun openWithFallback(service: Context, uri: Uri, candidatePackages: List<String>, appLabel: String) {
        // FLAG_ACTIVITY_CLEAR_TASK + NEW_TASK: tanto Nuvio como Stremio
        // declaran su Activity como launchMode="singleTask". Sin CLEAR_TASK,
        // si la app ya estaba abierta en segundo plano, Android a veces solo
        // trae su task existente al frente sin volver a procesar el nuevo
        // deep link (se queda en el último título abierto).
        //
        // CLEAR_TASK por sí solo no basta — confirmado en dispositivo real
        // (Nuvio) reproduciendo el mismo deep link por ADB con las mismas
        // flags: se quedaba en la ficha anterior sin más. Matar el proceso
        // en segundo plano antes de lanzar fuerza un arranque en frío real,
        // a costa de un pequeño parpadeo al abrir. En un hilo aparte porque
        // este método se llama a veces desde el hilo principal (p.ej. el
        // botón de ConfirmOpenActivity) y no debe bloquearlo.
        Thread {
            // Puede haber más de un paquete válido (ver NUVIO_PACKAGES) —
            // se usa el primero que esté realmente instalado, no
            // necesariamente el primero de la lista.
            val targetPackage = candidatePackages.firstOrNull { isPackageInstalled(service, it) }
            if (targetPackage == null) {
                Log.w(TAG, "$appLabel (${candidatePackages.joinToString()}) no está instalado — abriendo su ficha en la Play Store")
                openPlayStoreListing(service, candidatePackages.first(), appLabel)
                return@Thread
            }

            val activityManager = service.getSystemService(ActivityManager::class.java)
            try {
                activityManager.killBackgroundProcesses(targetPackage)
                Thread.sleep(200)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo matar el proceso en segundo plano de $targetPackage", e)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = uri
                setPackage(targetPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

            try {
                service.startActivity(intent)
                Log.d(TAG, "Abriendo $appLabel: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo abrir $appLabel con $targetPackage, reintentando sin package", e)
                // Fallback: deja que Android elija la app que resuelva el esquema.
                try {
                    val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    service.startActivity(fallbackIntent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Tampoco funcionó el fallback. ¿$appLabel está instalado?", e2)
                    openPlayStoreListing(service, targetPackage, appLabel)
                }
            }
        }.start()
    }

    fun isPackageInstalled(service: Context, targetPackage: String): Boolean {
        return try {
            service.packageManager.getPackageInfo(targetPackage, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** $targetPackage no está instalado (comprobado de antemano, o los dos
     * intentos de abrir el deep link fallaron) — en vez de quedarse en
     * silencio como antes, llevamos al usuario directamente a la ficha de
     * la Play Store de esa app. */
    fun openPlayStoreListing(service: Context, targetPackage: String, appLabel: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                service,
                service.getString(R.string.target_app_not_installed, appLabel),
                Toast.LENGTH_LONG
            ).show()
        }
        // Mismo problema que con Nuvio/Stremio/WuPlay (ver openWithFallback):
        // confirmado en dispositivo real que si la Play Store ya estaba en
        // segundo plano, se queda con una ventana colgada de tamaño cero y
        // no se ve nada, aunque Android la dé por "resumida". Matar su
        // proceso antes de abrirla lo arregla.
        try {
            val activityManager = service.getSystemService(ActivityManager::class.java)
            activityManager.killBackgroundProcesses(PLAY_STORE_PACKAGE)
            Thread.sleep(200)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo matar el proceso en segundo plano de la Play Store", e)
        }
        try {
            val storeIntent = Intent(
                Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$targetPackage")
            ).apply {
                setPackage(PLAY_STORE_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(storeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir la Play Store para $targetPackage", e)
        }
    }

    private const val PLAY_STORE_PACKAGE = "com.android.vending"
}
