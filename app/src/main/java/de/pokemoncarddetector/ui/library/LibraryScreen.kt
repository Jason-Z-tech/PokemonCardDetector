package de.pokemoncarddetector.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.SubcomposeAsyncImage
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.ImageUrls
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.CollectionTotals
import de.pokemoncarddetector.data.db.SerieEntity
import de.pokemoncarddetector.data.db.SetWithProgress
import de.pokemoncarddetector.recognition.TextNorm
import de.pokemoncarddetector.ui.appViewModel
import de.pokemoncarddetector.ui.theme.OwnedGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SerieSection(val serie: SerieEntity, val sets: List<SetWithProgress>)

data class LibraryUiState(
    val loading: Boolean = true,
    val sections: List<SerieSection> = emptyList(),
    val totals: CollectionTotals = CollectionTotals(0, 0),
    val settings: AppSettings = AppSettings(),
    val query: String = "",
    val setsStarted: Int = 0,
)

class LibraryViewModel(
    private val repository: CardRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    /** Suchtext separat, damit das Textfeld synchron bleibt */
    val query = MutableStateFlow("")

    val state: StateFlow<LibraryUiState> = combine(
        repository.observeSets(),
        repository.observeSeries(),
        repository.observeTotals(),
        settingsRepo.settings,
        query,
    ) { sets, series, totals, settings, q ->
        val serieById = series.associateBy { it.id }
        val qn = TextNorm.normalize(q)
        val filtered = sets.filter { s ->
            (!settings.onlyOwnedSets || s.owned > 0) &&
                (qn.isEmpty() || TextNorm.normalize(s.set.name).contains(qn) ||
                    TextNorm.normalize(s.set.nameEn).contains(qn) ||
                    s.set.abbreviation.equals(q.trim(), ignoreCase = true))
        }
        // Sets sind nach Erscheinungsdatum sortiert; Serien in der Reihenfolge ihres neuesten Sets
        val sections = filtered.groupBy { it.set.serieId }
            .map { (serieId, list) ->
                SerieSection(serieById[serieId] ?: SerieEntity(serieId, serieId, serieId), list)
            }
            .sortedByDescending { section -> section.sets.maxOf { it.set.releaseDate } }
        LibraryUiState(
            loading = false,
            sections = sections,
            totals = totals,
            settings = settings,
            query = q,
            setsStarted = sets.count { it.owned > 0 },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(q: String) { query.value = q }
    fun toggleImages() = viewModelScope.launch { settingsRepo.setShowImages(!state.value.settings.showImages) }
    fun setOnlyOwned(v: Boolean) = viewModelScope.launch { settingsRepo.setOnlyOwnedSets(v) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpenSet: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { c, _ -> LibraryViewModel(c.repository, c.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Meine Sammlung") },
            actions = {
                IconButton(onClick = vm::toggleImages) {
                    Icon(
                        if (state.settings.showImages) Icons.Default.Image else Icons.Default.HideImage,
                        contentDescription = if (state.settings.showImages) "Bilder ausschalten" else "Bilder einschalten",
                    )
                }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Einstellungen") }
            },
        )
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "summary") { SummaryCard(state) }
            item(key = "filter") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Set suchen, z. B. Farbenschock") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Leeren") }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        FilterChip(selected = !state.settings.onlyOwnedSets, onClick = { vm.setOnlyOwned(false) }, label = { Text("Alle Sets") })
                        FilterChip(selected = state.settings.onlyOwnedSets, onClick = { vm.setOnlyOwned(true) }, label = { Text("Nur meine Sets") })
                    }
                }
            }
            if (!state.loading && state.sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when {
                            state.settings.onlyOwnedSets && state.totals.uniqueCards == 0 ->
                                "Noch keine Karten gesammelt. Scanne deine erste Karte!"
                            state.settings.onlyOwnedSets ->
                                "Keins deiner Sets passt zu „${state.query}“. Tipp: „Alle Sets“ wählen."
                            else -> "Kein Set gefunden."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            for (section in state.sections) {
                stickyHeader(key = "serie-${section.serie.id}") {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            section.serie.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
                items(section.sets, key = { it.set.id }) { set ->
                    SetRow(set, state.settings.showImages, onClick = { onOpenSet(set.set.id) })
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: LibraryUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("${state.totals.uniqueCards}", "verschiedene\nKarten", Modifier.weight(1f))
            Stat("${state.totals.copies}", "Karten\ninsgesamt", Modifier.weight(1f))
            Stat("${state.setsStarted}", "Sets\nangefangen", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SetRow(item: SetWithProgress, showImages: Boolean, onClick: () -> Unit) {
    val set = item.set
    val total = set.cardCount.coerceAtLeast(1)
    val progress = item.owned.toFloat() / total
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 40.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val logo = if (showImages) ImageUrls.logo(set.logoUrl) else null
            if (logo != null) {
                SubcomposeAsyncImage(
                    model = logo,
                    contentDescription = set.name,
                    contentScale = ContentScale.Fit,
                    loading = { SetBadge(set.abbreviation.ifBlank { set.id }) },
                    error = { SetBadge(set.abbreviation.ifBlank { set.id }) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SetBadge(set.abbreviation.ifBlank { set.id })
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(set.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                append(set.releaseDate.take(4))
                if (set.abbreviation.isNotBlank()) append(" · ").append(set.abbreviation)
                if (!set.availableInGerman) append(" · nur Englisch")
                if (item.copies > item.owned) append(" · ${item.copies} Karten inkl. Doppelte")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                color = if (item.owned >= total) OwnedGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            "${item.owned}/${set.cardCount}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (item.owned > 0) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SetBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}
