package de.pokemoncarddetector.data

import de.pokemoncarddetector.data.catalog.CatalogStore
import de.pokemoncarddetector.data.catalog.CatalogSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zustand der Kartendatenbank (Ersteinrichtung + Online-Aktualisierung). */
sealed interface CatalogState {
    data object Checking : CatalogState
    data class Importing(val progress: Float) : CatalogState
    data object Ready : CatalogState
    data class Failed(val message: String) : CatalogState
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String, val progress: Float?) : SyncState
    data class Done(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

class CatalogManager(
    private val scope: CoroutineScope,
    private val store: CatalogStore,
    private val sync: CatalogSync,
    private val settings: SettingsRepository,
    private val repository: CardRepository,
) {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Checking)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val mutex = Mutex()

    /**
     * Beim App-Start: mitgelieferten Katalog importieren, falls die DB leer ist (Wartebildschirm)
     * oder die Datei neuer ist als der letzte Import (im Hintergrund, App bleibt bedienbar).
     */
    fun ensureReady() {
        scope.launch {
            mutex.withLock {
                try {
                    val empty = store.isEmpty()
                    if (!empty) _state.value = CatalogState.Ready
                    val bundled = store.bundledVersion()
                    val imported = settings.currentBundledVersion()
                    if (empty || imported == null || bundled > imported) {
                        if (empty) _state.value = CatalogState.Importing(0f)
                        store.importBundled { p -> if (empty) _state.value = CatalogState.Importing(p) }
                        settings.setBundledVersion(bundled)
                        repository.invalidateIndex()
                    }
                    _state.value = CatalogState.Ready
                    // Index im Hintergrund vorbereiten, damit der erste Scan schnell ist
                    repository.catalogIndex()
                } catch (t: Throwable) {
                    if (_state.value != CatalogState.Ready) {
                        _state.value = CatalogState.Failed(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }
    }

    /** Neue Sets von TCGdex laden. */
    fun syncOnline() {
        if (_syncState.value is SyncState.Running) return
        scope.launch {
            mutex.withLock {
                _syncState.value = SyncState.Running("Verbinde …", null)
                try {
                    val result = sync.sync { msg, p -> _syncState.update { SyncState.Running(msg, p) } }
                    settings.setLastOnlineSync(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                    repository.invalidateIndex()
                    repository.catalogIndex()
                    val msg = buildString {
                        if (result.newSets == 0 && result.updatedSets == 0) append("Alles aktuell – ${result.totalSets} Sets.")
                        else append("${result.newSets} neue und ${result.updatedSets} aktualisierte Sets geladen.")
                        if (result.failedSets > 0) append(" ${result.failedSets} Sets konnten nicht geladen werden – bitte später erneut versuchen.")
                    }
                    _syncState.value = SyncState.Done(msg)
                } catch (t: Throwable) {
                    _syncState.value = SyncState.Failed(
                        "Aktualisierung fehlgeschlagen. Bitte Internetverbindung prüfen. (${t.message ?: t.javaClass.simpleName})"
                    )
                }
            }
        }
    }

    fun clearSyncMessage() {
        if (_syncState.value !is SyncState.Running) _syncState.value = SyncState.Idle
    }
}
