package de.pokemoncarddetector.ui.wishlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.prices.PriceRepository
import de.pokemoncarddetector.data.prices.priceFor
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.card.CardSheet
import de.pokemoncarddetector.ui.components.CardTile
import de.pokemoncarddetector.ui.components.euro
import de.pokemoncarddetector.ui.theme.OwnedGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WishSort(val label: String) { PRICE("Preis"), SET("Set"), NAME("Name") }

data class WishRow(val item: CardItem, val wishVariant: String, val price: Double?)

data class WishlistUiState(
    val missing: List<WishRow> = emptyList(),
    val received: List<WishRow> = emptyList(),
    val totalMissing: Double = 0.0,
    /** fehlende Karten, fuer die (noch) kein Preis bekannt ist */
    val unpricedMissing: Int = 0,
    val sort: WishSort = WishSort.PRICE,
    val settings: AppSettings = AppSettings(),
    val loading: Boolean = true,
)

class WishlistViewModel(
    private val repository: CardRepository,
    private val prices: PriceRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val sort = MutableStateFlow(WishSort.PRICE)

    val state: StateFlow<WishlistUiState> = combine(
        repository.observeWishlistCards(),
        repository.observeWishes(),
        prices.observeAllPrices(),
        settingsRepo.settings,
        sort,
    ) { cards, wishes, allPrices, settings, s ->
        val wishVariant = wishes.associate { it.cardId to it.variant }
        val byCard = allPrices.groupBy { it.cardId }
        val rows = cards.map { item ->
            val v = wishVariant[item.card.id].orEmpty()
            // "Variante egal" -> guenstigster Preis der Karte
            WishRow(item, v, priceFor(byCard[item.card.id].orEmpty(), v.ifBlank { Variants.UNKNOWN }, settings.priceMetric))
        }
        // "Erhalten" = gewuenschte Variante (oder bei "egal" irgendeine) ist in der Sammlung
        fun received(r: WishRow) = if (r.wishVariant.isBlank()) r.item.owned else (r.item.quantities[r.wishVariant] ?: 0) > 0
        val sorted = when (s) {
            WishSort.PRICE -> rows.sortedByDescending { it.price ?: -1.0 }
            WishSort.SET -> rows.sortedWith(compareByDescending<WishRow> { it.item.setRelease }.thenBy { it.item.card.sortIndex })
            WishSort.NAME -> rows.sortedBy { it.item.displayName.lowercase() }
        }
        val missing = sorted.filterNot(::received)
        WishlistUiState(
            missing = missing,
            received = sorted.filter(::received),
            totalMissing = missing.sumOf { it.price ?: 0.0 },
            unpricedMissing = missing.count { it.price == null },
            sort = s,
            settings = settings,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WishlistUiState())

    init {
        // Nur die Wunschliste auffrischen (nur veraltete) – und nur, wenn automatische Preise an sind
        viewModelScope.launch {
            if (settingsRepo.settings.first().autoPrices) runCatching { prices.refreshWishlist() }
        }
    }

    fun setSort(s: WishSort) { sort.value = s }
    fun remove(cardId: String) = viewModelScope.launch { repository.setWished(cardId, false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(onOpenSet: (String) -> Unit) {
    val vm = appViewModel { c, _ -> WishlistViewModel(c.repository, c.priceRepository, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var openCard by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Wunschliste") })
        if (!state.loading && state.missing.isEmpty() && state.received.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.FavoriteBorder, null, Modifier.padding(8.dp))
                Text(
                    "Noch keine Wünsche. Öffne eine Karte (in einem Set, in der Suche oder beim Scannen) und tippe auf das Herz.",
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            when (state.missing.size) {
                                0 -> "Keine Wunschkarte fehlt mehr"
                                1 -> "1 Karte fehlt noch"
                                else -> "${state.missing.size} Karten fehlen noch"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val noPriceYet = state.missing.isNotEmpty() && state.unpricedMissing == state.missing.size
                        Text(
                            when {
                                state.missing.isEmpty() -> "Alles da!"
                                noPriceYet -> "Preis noch unbekannt"
                                else -> "ca. ${euro(state.totalMissing)}"
                            },
                            style = if (noPriceYet) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            buildString {
                                append("${state.settings.priceMetric.label} bei Cardmarket")
                                if (state.unpricedMissing > 0 && !noPriceYet) {
                                    append(" · ")
                                    append(if (state.unpricedMissing == 1) "1 Karte ohne Preis" else "${state.unpricedMissing} Karten ohne Preis")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WishSort.entries.forEach { s ->
                        FilterChip(selected = state.sort == s, onClick = { vm.setSort(s) }, label = { Text("Nach ${s.label}") })
                    }
                }
            }
            items(state.missing, key = { "m-" + it.item.card.id }) { row ->
                WishItem(row, state.settings, received = false, onOpen = { openCard = row.item.card.id }, onRemove = { vm.remove(row.item.card.id) })
            }
            if (state.received.isNotEmpty()) {
                item {
                    Text(
                        "Schon bekommen",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(state.received, key = { "r-" + it.item.card.id }) { row ->
                    WishItem(row, state.settings, received = true, onOpen = { openCard = row.item.card.id }, onRemove = { vm.remove(row.item.card.id) })
                }
            }
        }
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }, onOpenSet = onOpenSet) }
}

@Composable
private fun WishItem(row: WishRow, settings: AppSettings, received: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val item = row.item
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            CardTile(item, settings.showImages, settings.imageLanguage, Modifier.width(52.dp), dimIfMissing = false, showQuantityBadge = false)
        },
        headlineContent = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("${item.setName} · Nr. ${item.numberLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (row.wishVariant.isBlank()) "Variante egal" else Variants.label(row.wishVariant)) + " · " + euro(row.price),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (received) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = OwnedGreen, modifier = Modifier.padding(end = 4.dp))
                        Text("In deiner Sammlung", style = MaterialTheme.typography.labelMedium, color = OwnedGreen)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onRemove) { Icon(Icons.Default.HeartBroken, contentDescription = "Von der Wunschliste nehmen") }
        },
    )
}
