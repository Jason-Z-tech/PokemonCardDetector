package de.pokemoncarddetector.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.Rarity
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.db.CollectionRow
import de.pokemoncarddetector.data.db.OwnedMeta
import de.pokemoncarddetector.data.db.PriceEntity
import de.pokemoncarddetector.data.db.SerieEntity
import de.pokemoncarddetector.data.db.SetWithProgress
import de.pokemoncarddetector.data.db.ValueSnapshot
import de.pokemoncarddetector.data.prices.PriceRefreshState
import de.pokemoncarddetector.data.prices.PriceRepository
import de.pokemoncarddetector.data.prices.centsFor
import de.pokemoncarddetector.data.prices.collectionValue
import de.pokemoncarddetector.data.prices.priceFor
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.card.CardSheet
import de.pokemoncarddetector.ui.components.CardTile
import de.pokemoncarddetector.ui.components.euro
import de.pokemoncarddetector.ui.components.euroRounded
import de.pokemoncarddetector.ui.components.germanDate
import de.pokemoncarddetector.ui.components.germanDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TopCard(val item: CardItem, val value: Double)

data class StatsUiState(
    val loading: Boolean = true,
    val totalValue: Double = 0.0,
    /** Veraenderung gegenueber dem Wert vor ca. 7 Tagen (null = zu wenig Verlauf) */
    val delta: Double? = null,
    val deltaSince: String? = null,
    val pricedCards: Int = 0,
    val unpricedCards: Int = 0,
    val uniqueCards: Int = 0,
    val copies: Int = 0,
    val setsStarted: Int = 0,
    val setsComplete: Int = 0,
    val history: List<ChartPoint> = emptyList(),
    val topCards: List<TopCard> = emptyList(),
    val bySerie: List<BarItem> = emptyList(),
    val byVariant: List<BarItem> = emptyList(),
    val byRarity: List<BarItem> = emptyList(),
    val wishCount: Int = 0,
    val wishCost: Double = 0.0,
    /** aeltester Preis-Stand der Sammlung/Wunschliste */
    val lastFetch: Long? = null,
    /** Karten ohne jeden Preis-Abruf */
    val unfetched: Int = 0,
    val refresh: PriceRefreshState = PriceRefreshState.Idle,
    val settings: AppSettings = AppSettings(),
)

private data class Inputs(
    val rows: List<CollectionRow>,
    val prices: List<PriceEntity>,
    val settings: AppSettings,
    val history: List<ValueSnapshot>,
    val meta: List<OwnedMeta>,
    val series: List<SerieEntity>,
    val sets: List<SetWithProgress>,
    val wishCards: List<CardItem>,
)

class StatsViewModel(
    private val repository: CardRepository,
    private val prices: PriceRepository,
    private val settingsRepo: SettingsRepository,
    db: AppDatabase,
) : ViewModel() {

    private val stats = db.statsDao()

    private val inputs: Flow<Inputs> = combine(
        combine(stats.observeCollectionRows(), prices.observeAllPrices(), settingsRepo.settings, stats.observeHistory()) { a, b, c, d ->
            listOf<Any>(a, b, c, d)
        },
        combine(stats.observeOwnedMeta(), repository.observeSeries(), repository.observeSets(false), repository.observeSets(true), repository.observeWishlistCards()) { a, b, c, d, e ->
            listOf<Any>(a, b, c + d, e)
        },
    ) { x, y ->
        @Suppress("UNCHECKED_CAST")
        Inputs(
            rows = x[0] as List<CollectionRow>,
            prices = x[1] as List<PriceEntity>,
            settings = x[2] as AppSettings,
            history = x[3] as List<ValueSnapshot>,
            meta = y[0] as List<OwnedMeta>,
            series = y[1] as List<SerieEntity>,
            sets = y[2] as List<SetWithProgress>,
            wishCards = y[3] as List<CardItem>,
        )
    }

    // conflate statt mapLatest: jede Berechnung laeuft zu Ende, danach wird nur der neueste Stand gerechnet
    // (beim Preis-Laden aendert sich laufend etwas – mapLatest wuerde dann nie fertig)
    private val computed: Flow<StatsUiState> = inputs.conflate().map { i ->
        val metric = i.settings.priceMetric
        val value = collectionValue(i.rows, i.prices, metric)
        val pricesByCard = i.prices.groupBy { it.cardId }

        // Verlauf (Cent -> Euro) + Veraenderung ggue. ca. 7 Tagen
        // Heutiger Punkt = aktueller (Live-)Wert, damit der Verlauf zur grossen Zahl passt
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        // Nur Tage, an denen der Wert fuer DIESE Preisart gespeichert wurde (sonst Spruenge beim Umschalten)
        val snapshots = i.history.filter { it.date != today && it.centsFor(metric) != null }
        val history = snapshots.map { ChartPoint(germanDate(it.date).take(6), it.centsFor(metric)!! / 100.0) } +
            ChartPoint("heute", value.totalEuro)
        val weekAgo = snapshots.lastOrNull { daysBetween(it.date, today) >= 7 } ?: snapshots.firstOrNull()
        val delta = weekAgo?.let { value.totalEuro - it.centsFor(metric)!! / 100.0 }

        // Top-Karten
        val topIds = value.perCard.entries.sortedByDescending { it.value }.take(10)
        val topItems = repository.cardsByIds(topIds.map { it.key }).associateBy { it.card.id }
        val top = topIds.mapNotNull { (id, v) -> topItems[id]?.let { TopCard(it, v) } }

        // Wert je Serie
        val serieName = i.series.associate { it.id to it.name }
        val metaById = i.meta.associateBy { it.cardId }
        val bySerie = value.perCard.entries.groupBy { metaById[it.key]?.serieId ?: "?" }
            .map { (serie, list) -> serie to list.sumOf { it.value } }
            .sortedByDescending { it.second }
            .take(8)
            .map { (serie, v) ->
                val name = serieName[serie] ?: serie.removePrefix("ja:")
                BarItem(if (serie.startsWith("ja:")) "$name (JP)" else name, v, euroSmart(v))
            }

        // Exemplare je Variantenart
        val byVariant = i.rows.groupBy { variantGroup(it.variant) }
            .map { (g, list) -> g to list.sumOf { it.quantity } }
            .sortedByDescending { it.second }
            .map { (g, n) -> BarItem(g, n.toDouble(), "$n") }

        // Karten je Seltenheit
        val byRarity = i.meta.groupBy { Rarity.label(it.rarity) }
            .map { (r, list) -> r to list.size }
            .sortedByDescending { it.second }
            .take(8)
            .map { (r, n) -> BarItem(r, n.toDouble(), "$n") }

        // Wunschliste: noch fehlende Karten
        val missingWishes = i.wishCards.filter { !it.owned }
        // "Variante egal": guenstigster Preis der Karte
        val wishCost = missingWishes.sumOf { priceFor(pricesByCard[it.card.id].orEmpty(), Variants.UNKNOWN, metric) ?: 0.0 }

        StatsUiState(
            loading = false,
            totalValue = value.totalEuro,
            delta = delta,
            deltaSince = weekAgo?.let { germanDate(it.date) },
            pricedCards = value.pricedCards,
            unpricedCards = value.unpricedCards,
            uniqueCards = i.rows.map { it.cardId }.distinct().size,
            copies = i.rows.sumOf { it.quantity },
            setsStarted = i.sets.count { it.owned > 0 },
            setsComplete = i.sets.count { it.owned > 0 && it.owned >= it.set.cardCount },
            history = history,
            topCards = top,
            bySerie = bySerie,
            byVariant = byVariant,
            byRarity = byRarity,
            wishCount = missingWishes.size,
            wishCost = wishCost,
            settings = i.settings,
        )
    }

    val state: StateFlow<StatsUiState> = combine(
        computed, prices.state, prices.observeOldestTrackedFetch(), prices.observeUnfetchedCount(),
    ) { s, r, oldest, unfetched ->
        s.copy(refresh = r, lastFetch = oldest, unfetched = unfetched)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    init {
        // Fehlende/alte Preise beim Oeffnen nachladen (nicht erzwungen, im App-Bereich)
        viewModelScope.launch {
            if (settingsRepo.settings.first().autoPrices) prices.refreshAllInBackground(force = false)
        }
    }

    /** "Aktualisieren"-Knopf: alles neu laden, laeuft auch weiter, wenn der Bildschirm verlassen wird */
    fun refresh() = prices.refreshAllInBackground(force = true)

    private fun variantGroup(key: String): String {
        if (key.isBlank()) return "Unbekannt"
        val p = Variants.parse(key)
        return when {
            p.stamps.contains("1st-edition") -> "1. Edition"
            p.type == "reverse" && p.foil != null -> "Reverse mit Muster"
            p.type == "reverse" -> "Reverse Holo"
            p.type == "holo" -> "Holo"
            else -> "Normal"
        }
    }

    private fun daysBetween(a: String, b: String?): Long {
        if (b == null) return 0
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return runCatching { (f.parse(b)!!.time - f.parse(a)!!.time) / 86_400_000L }.getOrDefault(0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onOpenSet: (String) -> Unit) {
    val vm = appViewModel { c, _ -> StatsViewModel(c.repository, c.priceRepository, c.settings, c.database) }
    val state by vm.state.collectAsStateWithLifecycle()
    var openCard by rememberSaveable { mutableStateOf<String?>(null) }
    val running = state.refresh as? PriceRefreshState.Running

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Statistik") },
            actions = {
                IconButton(onClick = vm::refresh, enabled = running == null) {
                    Icon(Icons.Default.Refresh, contentDescription = "Preise jetzt aktualisieren")
                }
            },
        )
        if (running != null) {
            LinearProgressIndicator(
                progress = { if (running.total == 0) 1f else running.done.toFloat() / running.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { HeroCard(state) }
            item { KpiRow(state) }
            item {
                Section("Wertverlauf") {
                    ValueLineChart(state.history, format = { euro(it) })
                }
            }
            if (state.topCards.isNotEmpty()) {
                item { Text("Wertvollste Karten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                itemsIndexed(state.topCards, key = { _, t -> t.item.card.id }) { i, t ->
                    ListItem(
                        modifier = Modifier.clickable { openCard = t.item.card.id },
                        leadingContent = {
                            CardTile(t.item, state.settings.showImages, state.settings.imageLanguage, Modifier.width(44.dp), showQuantityBadge = false)
                        },
                        headlineContent = { Text("${i + 1}. ${t.item.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${t.item.setName} · ${t.item.quantity}×", maxLines = 1) },
                        trailingContent = { Text(euro(t.value), fontWeight = FontWeight.Bold) },
                    )
                }
            }
            if (state.bySerie.isNotEmpty()) {
                item { Section("Wert nach Serie") { RankedBars(state.bySerie) } }
            }
            if (state.byVariant.isNotEmpty()) {
                item { Section("Exemplare nach Variante") { RankedBars(state.byVariant) } }
            }
            if (state.byRarity.isNotEmpty()) {
                item { Section("Karten nach Seltenheit") { RankedBars(state.byRarity) } }
            }
            item {
                Text(
                    "Preise: Cardmarket (über TCGdex, täglich aktualisiert), ${state.settings.priceMetric.label}. " +
                        "Fehlt der Preis einer Variante, zählt der niedrigste Preis der Karte. " +
                        (state.lastFetch?.let { "Ältester Preis-Stand: ${germanDateTime(it)}." } ?: "Noch keine Preise geladen.") +
                        (if (state.unfetched > 0) " ${cards(state.unfetched)} warten noch auf einen Preis." else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }, onOpenSet = onOpenSet) }
}

private fun cards(n: Int) = if (n == 1) "1 Karte" else "$n Karten"

/** Euro mit Cent bei kleinen Betraegen (0,40 €), ganze Euro ab 10 € */
private fun euroSmart(v: Double) = if (v < 10.0) euro(v) else euroRounded(v)

/** Einzeiliger Text, der sich verkleinert, bis er in die Breite passt (Geldbetraege nie abschneiden). */
@Composable
private fun FitText(text: String, maxSize: androidx.compose.ui.unit.TextUnit) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        var size = maxSize
        val style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold)
        while (size.value > 20f &&
            measurer.measure(text, style.copy(fontSize = size), softWrap = false, maxLines = 1).size.width > maxPx
        ) {
            size = (size.value - 2f).sp
        }
        Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun HeroCard(state: StatsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Wert deiner Sammlung", style = MaterialTheme.typography.labelLarge)
            FitText(if (state.loading) "…" else euro(state.totalValue), maxSize = 48.sp)
            val d = state.delta
            if (d != null && state.deltaSince != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (d >= 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        (if (d >= 0) "+" else "−") + euro(kotlin.math.abs(d)) + " seit ${state.deltaSince}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (state.unpricedCards > 0) {
                Text(
                    "${cards(state.unpricedCards)} noch ohne Preis",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun KpiRow(state: StatsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("${state.uniqueCards}", "verschiedene Karten", Modifier.weight(1f))
            Kpi("${state.copies}", "Exemplare", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("${state.setsComplete} / ${state.setsStarted}", "Sets komplett / angefangen", Modifier.weight(1f))
            Kpi(
                if (state.wishCount == 0 || state.wishCost == 0.0) "–" else euroSmart(state.wishCost),
                if (state.wishCount == 1) "Wunschliste (1 fehlt)" else "Wunschliste (${state.wishCount} fehlen)",
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Kpi(value: String, label: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
