package com.tunombre.tvbridge.mobile

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cabecera destacada de Inicio (igual que la de la app de TV, adaptada a
 * tactil): carrusel que rota solo (y se desliza con el dedo) por los primeros
 * titulos de la fila, con su imagen de fondo, logo oficial (o texto si no
 * hay), nota/anio/generos/duracion y dos botones. Los datos extra (fondo,
 * logo...) llegan de TMDb en segundo plano y se cachean (ver
 * TmdbClient.fetchDetails); mientras llegan se ve el poster.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(
    items: List<TmdbClient.TmdbRecommendation>,
    onOpen: (TmdbClient.TmdbRecommendation) -> Unit,
    onToggleWatchlist: (TmdbClient.TmdbRecommendation) -> Unit,
    onSurprise: (() -> Unit)?
) {
    if (items.isEmpty()) return
    val details = remember { mutableStateMapOf<String, TmdbDetails>() }
    LaunchedEffect(items) {
        items.forEach { rec ->
            launch(Dispatchers.IO) {
                val d = TmdbClient.fetchDetails(rec.tmdbId, rec.mediaPath) ?: return@launch
                withContext(Dispatchers.Main) { details["${rec.mediaPath}:${rec.tmdbId}"] = d }
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { items.size })
    // Momento del ultimo toque: la rotacion automatica se pausa unos segundos
    // tras tocar la cabecera (un toque a un boton justo cuando la pagina
    // cambia sola se perdia).
    val lastTouch = remember { LongArray(1) }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(HERO_ROTATE_MS)
            val idle = System.currentTimeMillis() - lastTouch[0] > HERO_TOUCH_PAUSE_MS
            if (idle && !pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(HERO_HEIGHT).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch[0] = System.currentTimeMillis()
                }
            }
        }
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val rec = items[page]
            val d = details["${rec.mediaPath}:${rec.tmdbId}"]
            HeroPage(rec, d, onOpen, onToggleWatchlist)
        }
        if (onSurprise != null) {
            FilledTonalButton(
                onClick = onSurprise,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0x990B0D12), contentColor = Color.White)
            ) {
                Text(stringResource(R.string.surprise_me_button), fontSize = 13.sp)
            }
        }
        if (items.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(items.size) { i ->
                    Box(
                        Modifier.size(if (i == pagerState.currentPage) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (i == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(
    rec: TmdbClient.TmdbRecommendation,
    d: TmdbDetails?,
    onOpen: (TmdbClient.TmdbRecommendation) -> Unit,
    onToggleWatchlist: (TmdbClient.TmdbRecommendation) -> Unit
) {
    val bg = MaterialTheme.colorScheme.background
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Estado real de "Mi lista" (el boton alterna, asi que hay que verlo).
    var inList by androidx.compose.runtime.remember(rec.tmdbId, rec.mediaPath) {
        androidx.compose.runtime.mutableStateOf(Watchlist.isInList(ctx, rec.tmdbId, rec.mediaPath))
    }
    Box(Modifier.fillMaxSize().clickable { onOpen(rec) }) {
        val image = d?.backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }
            ?: rec.posterPath?.let { "https://image.tmdb.org/t/p/w780$it" }
        AsyncImage(
            model = image,
            contentDescription = rec.title,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )
        // Velo: oscurece abajo (donde va el texto) y se funde con el fondo.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0x660B0D12),
                        0.35f to Color.Transparent,
                        0.75f to bg.copy(alpha = 0.85f),
                        1f to bg
                    )
                )
            )
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 22.dp)
        ) {
            // El titulo en texto NO se pinta hasta saber si hay logo (evita el
            // salto texto -> logo); sin datos aun no se muestra nada.
            Crossfade(targetState = d, label = "hero-title") { data ->
                when {
                    data == null -> Spacer(Modifier.height(44.dp))
                    data.logoPath != null -> AsyncImage(
                        model = "https://image.tmdb.org/t/p/w500${data.logoPath}",
                        contentDescription = rec.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.height(56.dp).fillMaxWidth(0.6f)
                    )
                    else -> Text(
                        rec.title,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                metaLine(rec, d),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onOpen(rec) },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B0D12))
                ) { Text(stringResource(R.string.hero_view), fontWeight = FontWeight.SemiBold) }
                FilledTonalButton(
                    onClick = {
                        onToggleWatchlist(rec)
                        inList = Watchlist.isInList(ctx, rec.tmdbId, rec.mediaPath)
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0x66FFFFFF), contentColor = Color.White)
                ) {
                    Text(stringResource(if (inList) R.string.detail_in_watchlist else R.string.detail_add_to_watchlist))
                }
            }
        }
    }
}

/** "T1 · E3 · ★ 7,4 · 2023 · Accion · 2 h 5 min" — lo que haya. */
fun metaLine(rec: TmdbClient.TmdbRecommendation, d: TmdbDetails?): String {
    val parts = mutableListOf<String>()
    rec.subtitle?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    if (d != null) {
        if (d.voteAverage > 0) parts.add("\u2605 " + "%.1f".format(d.voteAverage))
        d.year?.let { parts.add(it) }
        parts.addAll(d.genres)
        d.runtimeMinutes?.let { m -> parts.add(if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min") }
    }
    return parts.joinToString("  \u00b7  ")
}

private val HERO_HEIGHT = 380.dp
private const val HERO_ROTATE_MS = 6000L
private const val HERO_TOUCH_PAUSE_MS = 10_000L
