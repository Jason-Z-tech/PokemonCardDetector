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
import androidx.compose.material.icons.filled.Remove
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
    settings: SettingsRepository,
) : ViewModel() {
    private val id = cardId
    val card: StateFlow<CardItem?> = repository.observeCard(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun add(delta: Int) = viewModelScope.launch { repository.add(id, delta) }
    fun setOwned(owned: Boolean) = viewModelScope.launch {
        val current = card.value?.quantity ?: 0
        repository.setQuantity(id, if (owned) maxOf(1, current) else 0)
    }
}

/**
 * Detailansicht einer Karte: grosses Bild, Set, Nummer, Schalter "In meiner Sammlung"
 * und Anzahl (+/-).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardSheet(cardId: String, onDismiss: () -> Unit, onOpenSet: ((String) -> Unit)? = null) {
    val vm = appViewModel(key = "card-$cardId") { c, _ -> CardSheetViewModel(cardId, c.repository, c.settings) }
    val item by vm.card.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Text(card.card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (card.card.nameEn.isNotBlank() && card.card.nameEn != card.card.name) {
                Text(card.card.nameEn, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                supportingContent = { Text(if (card.owned) "Du hast diese Karte" else "Fehlt dir noch") },
                trailingContent = { Switch(checked = card.owned, onCheckedChange = { vm.setOwned(it) }) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Anzahl", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 16.dp))
                FilledTonalIconButton(onClick = { vm.add(-1) }, enabled = card.quantity > 0) {
                    Icon(Icons.Default.Remove, contentDescription = "Eine weniger")
                }
                Text(
                    "${card.quantity}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FilledIconButton(onClick = { vm.add(1) }) {
                    Icon(Icons.Default.Add, contentDescription = "Eine mehr")
                }
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
