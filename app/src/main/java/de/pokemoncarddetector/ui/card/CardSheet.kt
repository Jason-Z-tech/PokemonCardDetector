package de.pokemoncarddetector.ui.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalContext
import de.pokemoncarddetector.data.db.PriceEntity
import de.pokemoncarddetector.data.db.WishEntity
import de.pokemoncarddetector.data.prices.PriceRepository
import de.pokemoncarddetector.data.prices.priceFor
import de.pokemoncarddetector.data.prices.value
import de.pokemoncarddetector.ui.components.euro
import de.pokemoncarddetector.ui.components.germanDate
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.ImageUrls
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.components.CardTile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CardSheetViewModel(
    cardId: String,
    private val repository: CardRepository,
    private val prices: PriceRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val id = cardId
    val card: StateFlow<CardItem?> = repository.observeCard(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val priceList: StateFlow<List<PriceEntity>> = prices.observePrices(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val wishes: StateFlow<List<WishEntity>> = repository.observeWishesFor(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _loadingPrice = MutableStateFlow(true)
    val loadingPrice: StateFlow<Boolean> = _loadingPrice

    init {
        // Aktuelle Cardmarket-Preise holen (wenn aelter als 6 Stunden)
        viewModelScope.launch {
            prices.refreshCard(cardId)
            _loadingPrice.value = false
        }
    }

    fun add(variant: String, delta: Int) = viewModelScope.launch { repository.add(id, variant, delta) }

    fun toggleWish() = viewModelScope.launch {
        val wished = wishes.value.isNotEmpty()
        repository.setWished(id, !wished)
        if (!wished) prices.refreshCard(id)
    }

    /** Schalter "In meiner Sammlung": an = Standard-Variante 1x, aus = alle Varianten entfernen. */
    fun setOwned(owned: Boolean) = viewModelScope.launch {
        val c = card.value ?: return@launch
        if (owned) {
            if (c.quantity == 0) repository.setQuantity(id, c.defaultVariant, 1)
        } else {
            repository.removeCard(id)
        }
    }
}

/**
 * Detailansicht einer Karte: grosses Bild, Set, Nummer, Schalter "In meiner Sammlung"
 * und Anzahl (+/-).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSheet(cardId: String, onDismiss: () -> Unit, onOpenSet: ((String) -> Unit)? = null) {
    val vm = appViewModel(key = "card-$cardId") { c, _ -> CardSheetViewModel(cardId, c.repository, c.priceRepository, c.settings) }
    val item by vm.card.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsState()
    val prices by vm.priceList.collectAsStateWithLifecycle()
    val wishes by vm.wishes.collectAsStateWithLifecycle()
    val loadingPrice by vm.loadingPrice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmRemove by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val card = item ?: return@ModalBottomSheet
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CardTile(
                item = card,
                showImage = true, // In der Detailansicht immer das Bild zeigen
                language = settings.imageLanguage,
                quality = ImageUrls.Quality.HIGH,
                dimIfMissing = false,
                showQuantityBadge = false,
                modifier = Modifier.fillMaxWidth(0.72f),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val wished = wishes.isNotEmpty()
                IconButton(onClick = vm::toggleWish) {
                    Icon(
                        if (wished) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (wished) "Von der Wunschliste nehmen" else "Auf die Wunschliste",
                        tint = if (wished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (card.card.nameEn.isNotBlank() && card.card.nameEn != card.card.name) {
                Text(
                    if (card.isJapanese) "Deutsch: ${card.card.nameEn}" else card.card.nameEn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${card.setName} · Nr. ${card.numberLabel}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (onOpenSet != null) {
                TextButton(onClick = { onDismiss(); onOpenSet(card.card.setId) }) { Text("Ganzes Set ansehen") }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("In meiner Sammlung") },
                supportingContent = {
                    Text(
                        when {
                            card.quantity == 0 -> "Fehlt dir noch"
                            card.quantity == 1 -> "Du hast 1 Karte"
                            else -> "Du hast ${card.quantity} Karten"
                        }
                    )
                },
                trailingContent = {
                    Switch(
                        checked = card.owned,
                        onCheckedChange = { on -> if (!on && card.quantity > 1) confirmRemove = true else vm.setOwned(on) },
                    )
                },
            )
            Text(
                "Varianten",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp),
            )
            val quantities = card.quantities
            // Alle Varianten laut Katalog + evtl. gesammelte, die (noch) nicht im Katalog stehen ("unbekannt")
            val rows = (card.variantList + quantities.keys.filter { it !in card.variantList }).ifEmpty { listOf(Variants.UNKNOWN) }
            val metric = settings.priceMetric
            rows.forEach { variant ->
                val own = prices.firstOrNull { it.variant == variant }?.value(metric)
                // Ohne eigenen Preis (oder unbekannte Variante): guenstigster Preis der Karte als "ab"
                val fallback = if (own == null) priceFor(prices, variant, metric) else null
                VariantRow(
                    label = Variants.label(variant),
                    price = when {
                        own != null -> "${metric.short} ${euro(own)}"
                        fallback != null -> "ab ${euro(fallback)}"
                        loadingPrice -> "Preis wird geladen …"
                        else -> "kein Preis"
                    },
                    quantity = quantities[variant] ?: 0,
                    onMinus = { vm.add(variant, -1) },
                    onPlus = { vm.add(variant, 1) },
                )
            }
            val priceDate = prices.mapNotNull { it.priceDate.takeIf(String::isNotBlank) }.maxOrNull()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (priceDate != null) "Cardmarket-Preise, Stand ${germanDate(priceDate)}" else "Preise von Cardmarket (über TCGdex)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { openCardmarket(context, card) }) { Text("Cardmarket") }
            }
            Text(
                "Holo und Reverse Holo kann die Kamera nicht unterscheiden – bitte hier die richtige Variante zählen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Aus der Sammlung entfernen?") },
            text = { Text("Du hast ${item?.quantity ?: 0} Exemplare dieser Karte (alle Varianten). Wirklich alle entfernen?") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.setOwned(false) }) { Text("Entfernen") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Abbrechen") } },
        )
    }
}

/** Cardmarket-Suche zur Karte im Browser oeffnen. */
private fun openCardmarket(context: android.content.Context, card: CardItem) {
    val query = if (card.isJapanese) "${card.card.nameEn.ifBlank { card.card.name }} ${card.card.setId.removePrefix("ja:")}"
    else "${card.card.nameEn} ${card.setNameEn}"
    val url = "https://www.cardmarket.com/de/Pokemon/Products/Search?searchString=" + android.net.Uri.encode(query.trim())
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}

@Composable
private fun VariantRow(label: String, price: String, quantity: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (quantity > 0) FontWeight.Bold else FontWeight.Normal,
            )
            Text(price, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onMinus, enabled = quantity > 0) {
            Icon(Icons.Default.Remove, contentDescription = "Eine weniger: $label")
        }
        Text(
            "$quantity",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(44.dp),
        )
        FilledIconButton(onClick = onPlus) {
            Icon(Icons.Default.Add, contentDescription = "Eine mehr: $label")
        }
    }
}
