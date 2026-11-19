package de.pokemoncarddetector.ui.scan

import android.content.Context
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pokemoncarddetector.data.AppSettings
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.db.CollectionEntity
import de.pokemoncarddetector.recognition.CardMatcher
import de.pokemoncarddetector.recognition.CardTextRecognizer
import de.pokemoncarddetector.recognition.CatalogIndex
import de.pokemoncarddetector.recognition.FrameFilter
import de.pokemoncarddetector.recognition.FrameGeometry
import de.pokemoncarddetector.recognition.ImageLoading
import de.pokemoncarddetector.recognition.MatchResult
import de.pokemoncarddetector.recognition.OcrLine
import de.pokemoncarddetector.recognition.ScanStabilizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Erkannte Karte inkl. Alternativen. */
data class Detection(
    val best: CardItem,
    val alternatives: List<CardItem>,
    val confident: Boolean,
    /** zaehlt hoch bei jeder neuen Erkennung (fuer Vibration) */
    val serial: Long,
    val fromPhoto: Boolean,
    /** gewaehlte Variante zum Hinzufuegen (Normal/Holo/Reverse/...) */
    val variant: String = best.defaultVariant,
)

data class ScanUiState(
    val detection: Detection? = null,
    /** kurze Statuszeile, z.B. "Nummer erkannt: 025/185" */
    val status: String = "Halte eine Karte in den Rahmen",
    val busy: Boolean = false,
    val paused: Boolean = false,
)

sealed interface ScanEvent {
    data class Message(val text: String) : ScanEvent
    /** Karte hinzugefuegt – "Rueckgaengig" stellt [previous] wieder her */
    data class Added(val text: String, val cardId: String, val previous: List<CollectionEntity>) : ScanEvent
}

class ScanViewModel(
    private val repository: CardRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val recognizer = CardTextRecognizer()

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    @Volatile var frame: FrameGeometry? = null

    private val stabilizer = ScanStabilizer()
    private val matching = AtomicBoolean(false)
    private val matcherLock = Mutex()
    private var matcher: CardMatcher? = null
    private var matcherIndex: CatalogIndex? = null
    private var serial = 0L
    private var lastAutoAdded: String? = null
    private var lastTentativeId: String? = null
    /** Vom Nutzer gewaehlte Karte: bleibt stehen, bis die Karte aus dem Bild genommen wird */
    private var userChoice: String? = null

    private suspend fun matcher(): CardMatcher = matcherLock.withLock {
        val index = repository.catalogIndex(japanese = settings.value.scanJapanese)
        if (matcher == null || matcherIndex !== index) {
            matcher = CardMatcher(index)
            matcherIndex = index
        }
        matcher!!
    }

    // ---------------- Live-Kamera ----------------

    /** Wird fuer jedes ausgewertete Kamerabild aufgerufen. */
    fun onFrame(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int) {
        if (_state.value.paused || _state.value.busy) return
        if (!matching.compareAndSet(false, true)) return
        // Abgleich im Hintergrund, Zustand (Stabilisierer, Erkennung) nur auf dem Main-Thread aendern
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    matcher().match(FrameFilter.filter(lines, imageWidth, imageHeight, frame))
                }
                if (!_state.value.paused && !_state.value.busy) handleLive(result)
            } catch (_: Throwable) {
                // Einzelne Bilder duerfen fehlschlagen
            } finally {
                matching.set(false)
            }
        }
    }

    private suspend fun handleLive(result: MatchResult) {
        val stable = stabilizer.offer(result)
        if (stabilizer.isIdle()) {
            lastAutoAdded = null
            lastTentativeId = null
            userChoice = null
            // Unsichere Vorschlaege verschwinden, wenn keine Karte mehr im Bild ist
            _state.update { s -> if (s.detection?.confident == false && !s.detection.fromPhoto) s.copy(detection = null) else s }
        }
        _state.update { it.copy(status = statusFor(result)) }
        if (userChoice != null) return // Nutzer hat korrigiert – nicht ueberschreiben

        if (stable?.best != null) {
            val id = stable.best.card.id
            val current = _state.value.detection
            if (current == null || current.best.card.id != id || !current.confident) {
                showDetection(stable, fromPhoto = false)
            }
            if (settings.value.autoAdd && lastAutoAdded != id) {
                lastAutoAdded = id
                add(id, _state.value.detection?.takeIf { it.best.card.id == id }?.variant)
            }
        } else if (result.best != null && !result.confident && result.best.nameScore >= 0.85) {
            // Unsicherer Treffer (z.B. Nummer nicht lesbar): als Vorschlag zeigen, wenn nichts Sicheres da ist
            val current = _state.value.detection
            val id = result.best.card.id
            if ((current == null || !current.confident) && id != lastTentativeId) {
                lastTentativeId = id
                showDetection(result, fromPhoto = false)
            }
        }
    }

    private fun statusFor(result: MatchResult): String {
        val p = result.parsed
        return when {
            p.isEmpty -> "Halte eine Karte in den Rahmen"
            result.confident && result.best != null -> "Erkannt: ${result.best.card.name}"
            p.numbers.isNotEmpty() -> "Nummer gelesen: ${p.numbers.first().raw} – suche Karte …"
            else -> "Text erkannt – halte die Karte ruhig, Nummer unten gut sichtbar"
        }
    }

    private suspend fun showDetection(result: MatchResult, fromPhoto: Boolean) {
        val best = result.best ?: return
        val ids = listOf(best.card.id) + result.alternatives.map { it.card.id }
        val items = repository.cardsByIds(ids).associateBy { it.card.id }
        val bestItem = items[best.card.id] ?: return
        val alternatives = result.alternatives.mapNotNull { items[it.card.id] }
        _state.update {
            it.copy(
                detection = Detection(bestItem, alternatives, result.confident, ++serial, fromPhoto),
            )
        }
    }

    // ---------------- Foto / Galerie ----------------

    /** Ausloeser gedrueckt: Live-Erkennung pausieren, bis das Foto ausgewertet ist. */
    fun onShutter() {
        _state.update { it.copy(busy = true, status = "Foto wird aufgenommen …") }
    }

    fun onCaptureFailed(message: String) {
        _state.update { it.copy(busy = false, status = "Halte eine Karte in den Rahmen") }
        viewModelScope.launch { _events.send(ScanEvent.Message(message)) }
    }

    /** Aufgenommenes Foto auswerten. Wird vom Kamera-Thread aufgerufen; schliesst [proxy] immer. */
    fun analyzeCapture(proxy: ImageProxy) {
        val rotation = recognizer.fixedRotationDegrees ?: proxy.imageInfo.rotationDegrees
        analyzeSingle(onDone = { runCatching { proxy.close() } }) {
            // JPEG -> Bitmap im Hintergrund dekodieren (nicht auf dem UI-Thread)
            val bitmap = withContext(Dispatchers.Default) { proxy.toBitmap() }
            val (w, h) = if (rotation % 180 == 0) bitmap.width to bitmap.height else bitmap.height to bitmap.width
            val lines = recognizer.recognize(bitmap, rotation)
            FrameFilter.filter(lines, w, h, frame, fallbackToAll = true)
        }
    }

    fun analyzeUri(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        _state.update { it.copy(busy = true) }
        analyzeSingle {
            val bitmap = withContext(Dispatchers.IO) { ImageLoading.decodeScaled(resolver, uri, maxSize = 2560) }
            recognizer.recognize(bitmap, 0)
        }
    }

    private fun analyzeSingle(onDone: () -> Unit = {}, recognize: suspend () -> List<OcrLine>) {
        _state.update { it.copy(busy = true, status = "Foto wird ausgewertet …") }
        viewModelScope.launch {
            try {
                val lines = recognize()
                val result = withContext(Dispatchers.Default) { matcher().match(lines) }
                if (result.best == null) {
                    _state.update { it.copy(status = "Keine Karte erkannt") }
                    _events.send(
                        ScanEvent.Message("Keine Karte erkannt. Tipp: näher ran, gutes Licht, die Nummer unten auf der Karte muss scharf sein.")
                    )
                } else {
                    showDetection(result, fromPhoto = true)
                    _state.update { it.copy(status = if (result.confident) "Erkannt: ${result.best.card.name}" else "Bitte Treffer prüfen") }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _events.send(ScanEvent.Message("Bild konnte nicht gelesen werden: ${t.message ?: "Fehler"}"))
            } finally {
                onDone()
                stabilizer.reset()
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---------------- Aktionen ----------------

    /** +1 fuer [cardId] in der Variante [variant] (null = Standard-Variante der Karte). */
    fun add(cardId: String, variant: String? = null) {
        viewModelScope.launch {
            val before = repository.snapshot(cardId)
            val item = repository.cardsByIds(listOf(cardId)).firstOrNull() ?: return@launch
            val v = variant ?: item.defaultVariant
            val qty = repository.add(cardId, v, 1)
            refreshDetection()
            val variantText = if (item.variantList.size > 1 || v.isBlank()) " (${Variants.shortLabel(v)})" else ""
            _events.send(ScanEvent.Added("${item.card.name}$variantText hinzugefügt – jetzt ${qty}×", cardId, before))
        }
    }

    fun restore(cardId: String, entries: List<CollectionEntity>) {
        viewModelScope.launch {
            repository.restore(cardId, entries)
            refreshDetection()
        }
    }

    fun selectVariant(variant: String) {
        _state.update { s -> s.copy(detection = s.detection?.copy(variant = variant)) }
    }

    /** Waehlt eine Alternative als richtigen Treffer. */
    fun choose(cardId: String) {
        viewModelScope.launch {
            val d = _state.value.detection ?: return@launch
            val all = (listOf(d.best) + d.alternatives)
            val chosen = all.firstOrNull { it.card.id == cardId } ?: return@launch
            val replaced = d.best.card.id
            stabilizer.reset()
            userChoice = cardId
            _state.update {
                it.copy(detection = d.copy(best = chosen, alternatives = all - chosen, confident = true, variant = chosen.defaultVariant))
            }
            // Wurde die falsche Karte schon automatisch hinzugefuegt? Dann umbuchen.
            if (lastAutoAdded == replaced && replaced != cardId) {
                repository.add(replaced, d.variant, -1)
                lastAutoAdded = cardId
                add(cardId)
            }
        }
    }

    private suspend fun refreshDetection() {
        val d = _state.value.detection ?: return
        val ids = listOf(d.best.card.id) + d.alternatives.map { it.card.id }
        val items = repository.cardsByIds(ids).associateBy { it.card.id }
        _state.update { s ->
            val cur = s.detection ?: return@update s
            s.copy(
                detection = cur.copy(
                    best = items[cur.best.card.id] ?: cur.best,
                    alternatives = cur.alternatives.map { items[it.card.id] ?: it },
                ),
            )
        }
    }

    fun dismissDetection() {
        stabilizer.reset()
        lastTentativeId = null
        userChoice = null
        _state.update { it.copy(detection = null, status = "Halte eine Karte in den Rahmen") }
    }

    fun setPaused(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
        if (!paused) viewModelScope.launch { refreshDetection() }
    }

    fun setAutoAdd(on: Boolean) {
        viewModelScope.launch { settingsRepo.setAutoAdd(on) }
    }

    /** Umschalten zwischen internationalen und japanischen Karten. */
    fun setJapanese(on: Boolean) {
        recognizer.japanese = on
        stabilizer.reset()
        _state.update { it.copy(detection = null, status = if (on) "Japanische Karte in den Rahmen halten" else "Halte eine Karte in den Rahmen") }
        viewModelScope.launch { settingsRepo.setScanJapanese(on) }
    }

    init {
        viewModelScope.launch {
            settings.collect { recognizer.japanese = it.scanJapanese }
        }
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }
}
