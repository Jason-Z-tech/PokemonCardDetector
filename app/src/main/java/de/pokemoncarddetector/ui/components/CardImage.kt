package de.pokemoncarddetector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.pokemoncarddetector.data.ImageLanguage
import de.pokemoncarddetector.data.ImageUrls
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.ui.theme.OwnedGreen

/** Seitenverhaeltnis einer Pokemon-Karte (63 x 88 mm). */
const val CARD_ASPECT = 63f / 88f

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Kartenbild bzw. Text-Kachel, wenn Bilder ausgeschaltet sind oder fehlen.
 * Nicht gesammelte Karten werden auf Wunsch grau und blass dargestellt.
 */
@Composable
fun CardTile(
    item: CardItem,
    showImage: Boolean,
    language: ImageLanguage,
    modifier: Modifier = Modifier,
    dimIfMissing: Boolean = true,
    quality: ImageUrls.Quality = ImageUrls.Quality.LOW,
    showQuantityBadge: Boolean = true,
) {
    val shape = RoundedCornerShape(if (quality == ImageUrls.Quality.HIGH) 14.dp else 6.dp)
    val dim = dimIfMissing && !item.owned
    val url = if (showImage) ImageUrls.card(item, quality, language) else null
    var failed by remember(url) { mutableStateOf(false) }

    Box(modifier.aspectRatio(CARD_ASPECT)) {
        if (url != null && !failed) {
            AsyncImage(
                model = url,
                contentDescription = item.card.name,
                contentScale = ContentScale.Fit,
                colorFilter = if (dim) grayscale else null,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (dim) 0.4f else 1f),
            )
        } else {
            TextTile(item, dim, shape)
        }
        if (showQuantityBadge && item.wished && !item.owned) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "Auf der Wunschliste",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(20.dp),
            )
        }
        if (showQuantityBadge && item.quantity > 0) {
            QuantityBadge(
                item.quantity,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun TextTile(item: CardItem, dim: Boolean, shape: RoundedCornerShape) {
    val colors = MaterialTheme.colorScheme
    val bg = if (item.owned) colors.primaryContainer else colors.surfaceVariant
    val fg = if (item.owned) colors.onPrimaryContainer else colors.onSurfaceVariant
    Surface(
        color = bg,
        contentColor = fg,
        shape = shape,
        modifier = Modifier
            .fillMaxSize()
            .border(1.dp, if (item.owned) colors.primary else colors.outlineVariant, shape)
            .alpha(if (dim) 0.7f else 1f),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < 90.dp) {
                // Kleine Kachel (Listen): nur die Nummer
                Text(
                    item.displayLocalId,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(2.dp),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(item.numberLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(
                        item.card.name,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (item.owned) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Im Besitz", tint = OwnedGreen)
                    } else {
                        Text(" ", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun QuantityBadge(quantity: Int, modifier: Modifier = Modifier) {
    Surface(
        color = OwnedGreen,
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Text(
            "${quantity}×",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
