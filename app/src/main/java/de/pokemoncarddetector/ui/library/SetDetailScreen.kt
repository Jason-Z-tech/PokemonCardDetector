package de.pokemoncarddetector.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.db.SetEntity
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.card.CardSheet
import de.pokemoncarddetector.ui.components.CardTile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CardFilter(val label: String) { ALL("Alle"), OWNED("Habe ich"), MISSING("Fehlen") }

data class SetDetailUiState(
    val set: SetEntity? = null,
    val cards: List<CardItem> = emptyList(),
    val allCount: Int = 0,
    val ownedCount: Int = 0,
    val copies: Int = 0,
    val filter: CardFilter = CardFilter.ALL,
    val settings: AppSettings = AppSettings(),
)

class SetDetailViewModel(
    setId: String,
    private val repository: CardRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(CardFilter.ALL)

    val state: StateFlow<SetDetailUiState> = combine(
        repository.observeSet(setId),
        repository.observeCardsInSet(setId),
        settingsRepo.settings,
        filter,
    ) { set, cards, settings, f ->
        SetDetailUiState(
            set = set,
            cards = when (f) {
                CardFilter.ALL -> cards
                CardFilter.OWNED -> cards.filter { it.owned }
                CardFilter.MISSING -> cards.filter { !it.owned }
            },
            allCount = cards.size,
            ownedCount = cards.count { it.owned },
            copies = cards.sumOf { it.quantity },
            filter = f,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetDetailUiState())

    fun setFilter(f: CardFilter) { filter.value = f }
    fun toggleImages() = viewModelScope.launch { settingsRepo.setShowImages(!state.value.settings.showImages) }

    /** Langes Druecken auf eine fehlende Karte: direkt als "habe ich" (1x) markieren. */
    fun markOwned(item: CardItem) = viewModelScope.launch { repository.setQuantity(item.card.id, item.defaultVariant, 1) }
    fun unmark(item: CardItem) = viewModelScope.launch { repository.removeCard(item.card.id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SetDetailScreen(setId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "set-$setId") { c, _ -> SetDetailViewModel(setId, c.repository, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var openCard by rememberSaveable { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            },
            title = {
                Column {
                    Text(state.set?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val set = state.set
                    if (set != null) {
                        Text(
                            listOfNotNull(set.releaseDate.take(4), set.abbreviation.ifBlank { null }, "${state.ownedCount}/${state.allCount} Karten")
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                IconButton(onClick = vm::toggleImages) {
                    Icon(
                        if (state.settings.showImages) Icons.Default.Image else Icons.Default.HideImage,
                        contentDescription = if (state.settings.showImages) "Bilder ausschalten" else "Bilder einschalten",
                    )
                }
            },
        )
        LinearProgressIndicator(
            progress = { if (state.allCount == 0) 0f else state.ownedCount.toFloat() / state.allCount },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardFilter.entries.forEach { f ->
                val count = when (f) {
                    CardFilter.ALL -> state.allCount
                    CardFilter.OWNED -> state.ownedCount
                    CardFilter.MISSING -> state.allCount - state.ownedCount
                }
                FilterChip(
                    selected = state.filter == f,
                    onClick = { vm.setFilter(f) },
                    label = { Text("${f.label} ($count)", maxLines = 1, softWrap = false) },
                )
            }
        }
        if (state.copies > state.ownedCount) {
            Text(
                "Insgesamt ${state.copies} Karten aus diesem Set (inkl. Doppelte)",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Text(
            "Tippen = Details & Anzahl · Lange drücken = als „habe ich“ markieren",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.cards, key = { it.card.id }) { item ->
                Column(
                    Modifier.combinedClickable(
                        onClick = { openCard = item.card.id },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (item.owned) {
                                // Anzahl nie aus Versehen loeschen: Besessene Karten oeffnen die Details
                                openCard = item.card.id
                            } else {
                                vm.markOwned(item)
                                scope.launch {
                                    val r = snackbar.showSnackbar("${item.displayName} hinzugefügt", actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
                                    if (r == SnackbarResult.ActionPerformed) vm.unmark(item)
                                }
                            }
                        },
                    ),
                ) {
                    CardTile(item, state.settings.showImages, state.settings.imageLanguage, Modifier.fillMaxWidth())
                    if (state.settings.showImages) {
                        Text(
                            "${item.displayLocalId} · ${if (item.isJapanese) item.card.nameEn.ifBlank { item.card.name } else item.card.name}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }

    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }) }
}
