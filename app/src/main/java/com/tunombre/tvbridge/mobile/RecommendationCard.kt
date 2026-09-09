package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Tarjeta de un título — tocar abre, mantener pulsado alterna Mi lista (o
 * quita de Continuar viendo, según la fila — ver HomeScreen). Ancho
 * adaptable: algo más grande en tablet, igual criterio que el resto de la
 * pantalla (ver plan, sección 4). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    rec: TmdbClient.TmdbRecommendation,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val posterWidth = if (isTablet) 140.dp else 108.dp

    Column(
        modifier = Modifier
            .width(posterWidth)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = rec.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            contentDescription = rec.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
        )
        Text(
            rec.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        rec.subtitle?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
