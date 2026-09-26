package com.tunombre.tvbridge.mobile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Color de los titulos de fila — gris suave para que mande el contenido
 * (mismo criterio que la app de TV). */
private val RowHeaderColor = Color(0xFFB8BFCC)

/** Una fila horizontal de tipo "Continuar viendo"/"Recomendado para ti" —
 * mismo concepto que las filas de Leanback de la app de TV, aqui como una
 * LazyRow de Compose. [showTypeBadge] marca las series con "SERIE" en listas
 * que mezclan peliculas y series (Continuar viendo, Mi lista, Historial). */
@Composable
fun RecommendationRow(
    title: String,
    items: List<TmdbClient.TmdbRecommendation>,
    onClick: (TmdbClient.TmdbRecommendation) -> Unit,
    onLongClick: (TmdbClient.TmdbRecommendation) -> Unit,
    showTypeBadge: Boolean = false
) {
    Column(modifier = Modifier.padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = RowHeaderColor,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { "${it.mediaPath}_${it.tmdbId}" }) { rec ->
                PosterCard(rec, showTypeBadge = showTypeBadge, onClick = { onClick(rec) }, onLongClick = { onLongClick(rec) })
            }
        }
    }
}

/** Tarjeta de un titulo: solo el poster (esquinas redondeadas y borde fino),
 * con insignias en las esquinas — "SERIE" arriba a la izquierda (si
 * [showTypeBadge]) y el episodio ("T1 · E3") abajo. Sin titulo escrito: el
 * poster ya lo lleva y la ficha lo muestra al abrir. Tocar abre, mantener
 * pulsado alterna Mi lista (o quita de Continuar viendo, segun la fila).
 * Ancho adaptable: algo mas grande en tablet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    rec: TmdbClient.TmdbRecommendation,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showTypeBadge: Boolean = false
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val posterWidth = if (isTablet) 150.dp else 116.dp
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = Modifier
            .width(posterWidth)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = rec.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            contentDescription = rec.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (showTypeBadge && rec.mediaPath == "tv") {
            CardBadge(stringResource(R.string.card_badge_series), Modifier.align(Alignment.TopStart))
        }
        rec.subtitle?.takeIf { it.isNotBlank() }?.let {
            CardBadge(it, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun CardBadge(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xB30B0D12))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
