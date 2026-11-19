package de.pokemoncarddetector.ui.search

import androidx.compose.foundation.clickable
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

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val repository: CardRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<SearchUiState> = combine(
        query,
        query.debounce(250).flatMapLatest { repository.search(it) },
        repository.observeRecentlyAdded(30),
        settingsRepo.settings,
    ) { q, results, recent, settings ->
        SearchUiState(q, if (q.trim().length >= 2) results else emptyList(), recent, settings)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(q: String) { query.value = q }
    fun add(cardId: String) = viewModelScope.launch { repository.add(cardId, 1) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initialQuery: String?, onOpenSet: (String) -> Unit) {
    val vm = appViewModel { c, _ -> SearchViewModel(c.repository, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var openCard by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(initialQuery) { if (!initialQuery.isNullOrBlank()) vm.setQuery(initialQuery) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Karte suchen") })
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Name (deutsch/englisch) oder Nummer") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Leeren") }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        val showingRecent = state.query.trim().length < 2
        val list = if (showingRecent) state.recent else state.results
        Text(
            when {
                showingRecent && list.isEmpty() -> "Tipp: Suche z. B. nach „Glurak“, „Pikachu“ oder „TG05“."
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
                        FilledTonalIconButton(onClick = { vm.add(item.card.id) }) { Icon(Icons.Default.Add, contentDescription = "Hinzufügen") }
                    },
                )
            }
        }
    }
    openCard?.let { id -> CardSheet(cardId = id, onDismiss = { openCard = null }, onOpenSet = onOpenSet) }
}
