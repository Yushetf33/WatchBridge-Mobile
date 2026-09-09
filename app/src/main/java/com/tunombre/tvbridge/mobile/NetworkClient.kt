package com.tunombre.tvbridge.mobile

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Un único OkHttpClient (con timeouts explícitos) para toda la app, en vez
 * de que cada objeto (TmdbClient, Watchlist, RecommendationHistory...) cree
 * el suyo propio con `OkHttpClient()` a secas. Sin [callTimeout] explícito
 * (por defecto 0 = sin límite), una llamada que se cuelgue de verdad puede
 * dejar el dispatcher/pool de conexiones interno degradado para TODAS las
 * llamadas siguientes que compartan ese cliente, sin ningún error ni log
 * que lo delate — confirmado en dispositivo real: una detección de
 * MediaWatchListenerService se quedó colgada sin excepción ni timeout, y
 * las siguientes en el mismo proceso también, hasta reiniciar la app.
 */
val sharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()
