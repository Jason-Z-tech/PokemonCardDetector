package de.pokemoncarddetector.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.pokemoncarddetector.PokeApp
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.ImageLanguage
import de.pokemoncarddetector.data.SyncState
import de.pokemoncarddetector.data.prices.PriceMetric
import de.pokemoncarddetector.data.prices.PriceRefreshState
import de.pokemoncarddetector.ui.components.germanDateTime
import de.pokemoncarddetector.work.PriceWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as PokeApp).container
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val catalogVersion by container.settings.catalogVersion.collectAsStateWithLifecycle(null)
    val syncState by container.catalogManager.syncState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingImport by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val text = container.repository.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("Datei nicht beschreibbar")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) "Sammlung gesichert" else "Sichern fehlgeschlagen", Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Einstellungen") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Section("Anzeige")
            SwitchItem("Kartenbilder anzeigen", "Aus = schnelle Textansicht, spart mobile Daten", settings.showImages) {
                scope.launch { container.settings.setShowImages(it) }
            }
            Text(
                "Sprache der Kartenbilder",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            )
            ImageLanguage.entries.forEach { lang ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = settings.imageLanguage == lang,
                        role = Role.RadioButton,
                        onClick = { scope.launch { container.settings.setImageLanguage(lang) } },
                    ),
                    leadingContent = { RadioButton(selected = settings.imageLanguage == lang, onClick = null) },
                    headlineContent = { Text(lang.label) },
                )
            }

            HorizontalDivider()
            Section("Scannen")
            SwitchItem("Automatisch hinzufügen", "Erkannte Karten ohne Tippen zur Sammlung hinzufügen", settings.autoAdd) {
                scope.launch { container.settings.setAutoAdd(it) }
            }
            SwitchItem("Vibrieren bei Erkennung", null, settings.vibrate) {
                scope.launch { container.settings.setVibrate(it) }
            }

            HorizontalDivider()
            Section("Preise (Cardmarket)")
            val priceState by container.priceRepository.state.collectAsStateWithLifecycle()
            val lastFetch by container.priceRepository.observeOldestTrackedFetch().collectAsStateWithLifecycle(null)
            Text(
                "Welcher Preis angezeigt und für den Sammlungswert verwendet wird",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
            PriceMetric.entries.forEach { m ->
                ListItem(
                    modifier = Modifier.selectable(
                        selected = settings.priceMetric == m,
                        role = Role.RadioButton,
                        onClick = { scope.launch { container.settings.setPriceMetric(m) } },
                    ),
                    leadingContent = { RadioButton(selected = settings.priceMetric == m, onClick = null) },
                    headlineContent = { Text(m.label) },
                )
            }
            SwitchItem(
                "Preise automatisch aktuell halten",
                "Beim Start und einmal täglich im Hintergrund (Sammlung + Wunschliste)",
                settings.autoPrices,
            ) {
                scope.launch {
                    container.settings.setAutoPrices(it)
                    PriceWorker.schedule(context, it)
                }
            }
            ListItem(
                headlineContent = { Text("Preise jetzt aktualisieren") },
                supportingContent = {
                    Column {
                        Text(lastFetch?.let { "Ältester Preis-Stand: ${germanDateTime(it)}" } ?: "Noch keine Preise geladen")
                        when (val ps = priceState) {
                            is PriceRefreshState.Running -> LinearProgressIndicator(
                                progress = { if (ps.total == 0) 1f else ps.done.toFloat() / ps.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            is PriceRefreshState.Done -> Text(
                                when {
                                    ps.offline -> "Keine Internetverbindung – Preise bleiben auf dem letzten Stand"
                                    else -> (if (ps.updated == 1) "1 Karte aktualisiert" else "${ps.updated} Karten aktualisiert") +
                                        if (ps.failed > 0) ", ${ps.failed} fehlgeschlagen" else ""
                                },
                                color = if (ps.offline) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            )
                            PriceRefreshState.Idle -> {}
                        }
                    }
                },
                trailingContent = {
                    TextButton(
                        onClick = { container.priceRepository.refreshAllInBackground(force = true) },
                        enabled = priceState !is PriceRefreshState.Running,
                    ) { Text("Laden") }
                },
            )

            HorizontalDivider()
            Section("Kartendatenbank")
            ListItem(
                headlineContent = { Text("Neue Sets herunterladen") },
                supportingContent = {
                    Column {
                        Text("Stand: ${catalogVersion ?: "–"}. Lädt neu erschienene Sets und Karten von TCGdex.")
                        when (val s = syncState) {
                            is SyncState.Running -> {
                                Text(s.message, color = MaterialTheme.colorScheme.primary)
                                if (s.progress != null) {
                                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            }
                            is SyncState.Done -> Text(s.message, color = MaterialTheme.colorScheme.primary)
                            is SyncState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                            SyncState.Idle -> {}
                        }
                    }
                },
                leadingContent = { Icon(Icons.Default.CloudDownload, null) },
                trailingContent = {
                    TextButton(onClick = { container.catalogManager.syncOnline() }, enabled = syncState !is SyncState.Running) {
                        Text("Aktualisieren")
                    }
                },
            )

            HorizontalDivider()
            Section("Sicherung")
            ListItem(
                modifier = Modifier.selectable(selected = false, onClick = {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
                    exportLauncher.launch("karten-sammlung-$date.json")
                }),
                leadingContent = { Icon(Icons.Default.FileDownload, null) },
                headlineContent = { Text("Sammlung sichern") },
                supportingContent = { Text("Speichert deine Karten als Datei (z. B. in Google Drive)") },
            )
            ListItem(
                modifier = Modifier.selectable(selected = false, onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }),
                leadingContent = { Icon(Icons.Default.FileUpload, null) },
                headlineContent = { Text("Sicherung laden") },
                supportingContent = { Text("Stellt eine gesicherte Sammlung wieder her") },
            )

            HorizontalDivider()
            Section("Über")
            Text(
                "Kartendaten, Bilder und Preise: TCGdex (tcgdex.dev), freie Pokémon-TCG-Datenbank; die Preise stammen von Cardmarket " +
                    "und werden dort täglich aktualisiert. Japanische Karten: 121 Sets, soweit bei TCGdex vorhanden. " +
                    "Die Texterkennung läuft komplett auf deinem Handy (Google ML Kit), es werden keine Fotos hochgeladen.\n\n" +
                    "Pokémon und alle zugehörigen Namen sind Marken von Nintendo, Creatures Inc. und GAME FREAK inc. " +
                    "Diese App ist ein privates Fan-Projekt und steht in keiner Verbindung zu diesen Unternehmen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Sicherung laden") },
            text = { Text("Soll die aktuelle Sammlung ersetzt werden, oder sollen die Karten aus der Sicherung ergänzt werden (gleiche Karten bekommen die Anzahl aus der Sicherung)?") },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; scope.launch { doImport(context, container, uri, replace = true) } }) { Text("Ersetzen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null; scope.launch { doImport(context, container, uri, replace = false) } }) { Text("Ergänzen") }
            },
        )
    }
}

private suspend fun doImport(context: android.content.Context, container: de.pokemoncarddetector.AppContainer, uri: Uri, replace: Boolean) {
    val result = runCatching {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Datei nicht lesbar")
        }
        container.repository.importJson(text, replace).also { container.priceRepository.refreshAllInBackground(force = false) }
    }
    val msg = result.fold({ if (it == 1) "1 Karte geladen" else "$it Karten geladen" }, { "Keine gültige Sicherungsdatei" })
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}
