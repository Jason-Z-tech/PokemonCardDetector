package de.pokemoncarddetector.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.card.CardSheet
import de.pokemoncarddetector.ui.components.CardTile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<CardItem> = emptyList(),
    val recent: List<CardItem> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

/**
 * Haelt die Reihenfolge einer Liste stabil, waehrend sich Inhalte (Anzahl) aendern:
 * bekannte Eintraege behalten ihren Platz, neue kommen nach vorne. So springt beim Tippen auf "+"
 * keine andere Karte unter den Finger.
 */
internal class StableOrder {
    private var order: List<String> = emptyList()
    fun apply(list: List<CardItem>): List<CardItem> {
        val byId = list.associateBy { it.card.id }
        val kept = order.filter { it in byId }
        val fresh = list.map { it.card.id }.filter { it !in kept.toSet() }
        order = fresh + kept
        return order.mapNotNull { byId[it] }
    }
    fun reset() { order = emptyList() }
}

sealed interface SearchEvent {
    data class Added(val text: String, val cardId: String, val previousQuantity: Int) : SearchEvent
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: CardRepository,
    settingsRepo: SettingsRepository,
    savedState: SavedStateHandle,
) : ViewModel() {
    /** Suchbegriff (aus dem Scanner per Route "q" vorbelegt) – direkt fuer das Textfeld */
    val query = MutableStateFlow(savedState.get<String>("q").orEmpty())

    private val recentOrder = StableOrder()
    private val resultOrder = StableOrder()

    val state: StateFlow<SearchUiState> = combine(
        query.debounce(250).flatMapLatest { q -> resultOrder.reset(); repository.search(q).map { q to it } },
        repository.observeRecentlyAdded(30),
        settingsRepo.settings,
    ) { (q, results), recent, settings ->
        SearchUiState(q, if (q.trim().isNotEmpty()) resultOrder.apply(results) else emptyList(), recentOrder.apply(recent), settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private val _events = Channel<SearchEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setQuery(q: String) { query.value = q }

    fun add(item: CardItem) = viewModelScope.launch {
        val before = item.quantity
        val now = repository.add(item.card.id, 1)
        _events.send(SearchEvent.Added("${item.card.name} (${item.setName}) – jetzt ${now}×", item.card.id, before))
    }

    fun undo(cardId: String, quantity: Int) = viewModelScope.launch { repository.setQuantity(cardId, quantity) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpenSet: (String) -> Unit) {
    val vm = appViewModel { c, saved -> SearchViewModel(c.repository, c.settings, saved) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var openCard by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            if (e is SearchEvent.Added) {
                val r = snackbar.showSnackbar(e.text, actionLabel = "Rückgängig", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) vm.undo(e.cardId, e.previousQuantity)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Karte suchen") })
        OutlinedTextField(
            value = query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Name (deutsch/englisch) oder Nummer") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Leeren") }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        val showingRecent = state.query.isBlank() || query.isBlank()
        val list = if (showingRecent) state.recent else state.results
        Text(
            when {
                showingRecent && list.isEmpty() -> "Tipp: Suche z. B. nach „Glurak“, „Pikachu“, „025/185“ oder „TG05“."
                showingRecent -> "Zuletzt hinzugefügt"
                list.isEmpty() -> "Keine Karte gefunden."
                list.size >= 300 -> "Mehr als 300 Treffer – bitte genauer suchen"
                else -> "${list.size} Treffer"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.card.id }) { item ->
                ListItem(
                    modifier = Modifier.clickable { openCard = item.card.id },
                    leadingContent = {
                        CardTile(item, state.settings.showImages, state.settings.imageLanguage, Modifier.width(56.dp), showQuantityBadge = false)
                    },
                    headlineContent = { Text(item.card.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${item.setName} · ${item.setRelease.take(4)} · Nr. ${item.numberLabel}" +
                                if (item.quantity > 0) "\n${item.quantity}× im Besitz" else "",
                        )
                    },
                    trailingContent = {
                        FilledTonalIconButton(onClick = { vm.add(item) }) { Icon(Icons.Default.Add, contentDescription = "Hinzufügen") }
                    },
                )
            }
        }
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }, onOpenSet = onOpenSet) }
}
