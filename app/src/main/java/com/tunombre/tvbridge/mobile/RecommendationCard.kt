package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Una fila horizontal de tipo "Continuar viendo"/"Porque viste X" — mismo
 * concepto que las filas de Leanback en RecommendationsRowsFragment, aquí
 * como una LazyRow normal de Compose. */
@Composable
fun RecommendationRow(
    title: String,
    items: List<TmdbClient.TmdbRecommendation>,
    onClick: (TmdbClient.TmdbRecommendation) -> Unit,
    onLongClick: (TmdbClient.TmdbRecommendation) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { "${it.mediaPath}_${it.tmdbId}" }) { rec ->
                PosterCard(rec, onClick = { onClick(rec) }, onLongClick = { onLongClick(rec) })
            }
        }
    }
}

/** Tarjeta de un título — póster con el título superpuesto sobre un
 * degradado en la parte de abajo (mismo lenguaje visual que Netflix/Prime,
 * en vez de un texto suelto debajo del póster). Tocar abre, mantener
 * pulsado alterna Mi lista (o quita de Continuar viendo, según la fila —
 * ver HomeScreen). Ancho adaptable: algo más grande en tablet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    rec: TmdbClient.TmdbRecommendation,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val posterWidth = if (isTablet) 150.dp else 116.dp

    Box(
        modifier = Modifier
            .width(posterWidth)
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = rec.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            contentDescription = rec.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        // Transparente hasta bien entrada la mitad inferior, y
                        // solo entonces se oscurece hacia abajo -- así el
                        // título queda legible sin tapar casi todo el póster
                        // (a diferencia de un degradado que empieza en el borde
                        // superior).
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text(
                    rec.title,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                rec.subtitle?.let {
                    Text(it, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
                }
            }
        }
    }
}
