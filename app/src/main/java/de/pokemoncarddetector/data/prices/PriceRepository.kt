package de.pokemoncarddetector.data.prices

import androidx.room.withTransaction
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.PriceEntity
import de.pokemoncarddetector.data.db.ValueSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Zustand der Preis-Aktualisierung fuer die Oberflaeche. */
sealed interface PriceRefreshState {
    data object Idle : PriceRefreshState
    data class Running(val done: Int, val total: Int) : PriceRefreshState
    data class Done(val updated: Int, val failed: Int) : PriceRefreshState
}

/**
 * Holt Cardmarket-Preise ueber TCGdex (dort taeglich aktualisiert) fuer die Karten der
 * Sammlung und der Wunschliste und merkt sich jeden Tag den Gesamtwert.
 */
class PriceRepository(
    private val db: AppDatabase,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val priceDao = db.priceDao()
    private val statsDao = db.statsDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PriceRefreshState>(PriceRefreshState.Idle)
    val state: StateFlow<PriceRefreshState> = _state.asStateFlow()

    fun observePrices(cardId: String): Flow<List<PriceEntity>> = priceDao.observeFor(cardId)
    fun observeAllPrices(): Flow<List<PriceEntity>> = priceDao.observeAll()
    fun observeLastFetch(): Flow<Long?> = priceDao.observeLastFetch()

    /** Preise einer Karte laden, wenn sie aelter als [maxAgeMs] sind. */
    suspend fun refreshCard(cardId: String, maxAgeMs: Long = SIX_HOURS): Boolean = withContext(Dispatchers.IO) {
        val last = priceDao.fetchTimes(listOf(cardId)).firstOrNull()?.fetchedAt ?: 0L
        if (System.currentTimeMillis() - last < maxAgeMs) return@withContext true
        runCatching { fetchAndStore(cardId) }.isSuccess
    }

    /**
     * Alle Karten der Sammlung + Wunschliste aktualisieren (nur veraltete), danach Tageswert speichern.
     * @param force true = auch frische Preise neu laden
     */
    suspend fun refreshTracked(force: Boolean = false, maxAgeMs: Long = TWELVE_HOURS): PriceRefreshState.Done =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val ids = statsDao.trackedCardIds().distinct()
                val fresh = if (force) emptySet() else priceDao.fetchTimes(ids)
                    .filter { System.currentTimeMillis() - it.fetchedAt < maxAgeMs }
                    .map { it.cardId }.toSet()
                val todo = ids.filter { it !in fresh }
                val done = AtomicInteger(0)
                val failed = AtomicInteger(0)
                _state.value = PriceRefreshState.Running(0, todo.size)
                val semaphore = Semaphore(6)
                coroutineScope {
                    todo.map { id ->
                        async {
                            semaphore.withPermit {
                                if (runCatching { fetchAndStore(id) }.isFailure) failed.incrementAndGet()
                                _state.value = PriceRefreshState.Running(done.incrementAndGet(), todo.size)
                            }
                        }
                    }.awaitAll()
                }
                snapshotValue()
                PriceRefreshState.Done(todo.size - failed.get(), failed.get()).also { _state.value = it }
            }
        }

    /** Heutigen Sammlungswert speichern (ein Eintrag pro Tag, wird im Laufe des Tages ueberschrieben). */
    suspend fun snapshotValue() = withContext(Dispatchers.IO) {
        val metric = settings.settings.first().priceMetric
        val rows = statsDao.collectionRows()
        val value = collectionValue(rows, priceDao.all(), metric)
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        statsDao.upsertSnapshot(
            ValueSnapshot(
                date = today,
                valueCents = Math.round(value.totalEuro * 100),
                uniqueCards = rows.map { it.cardId }.distinct().size,
                copies = rows.sumOf { it.quantity },
                pricedCards = value.pricedCards,
            )
        )
    }

    private suspend fun fetchAndStore(cardId: String) {
        val now = System.currentTimeMillis()
        val prices = if (cardId.startsWith("ja:")) {
            PriceParser.parse(cardId, getJson("ja/cards/${cardId.removePrefix("ja:")}"), emptyList(), canonicalKeys = true, now)
        } else {
            try {
                // Englische Antwort nutzt dieselben Varianten-Schluessel wie der Katalog
                PriceParser.parse(cardId, getJson("en/cards/$cardId"), emptyList(), canonicalKeys = true, now)
            } catch (e: NotFound) {
                // Nur auf Deutsch erschienen: Bezeichnungen sind uebersetzt -> ueber die Reihenfolge zuordnen
                val variants = db.catalogDao().cardsByIds(listOf(cardId)).firstOrNull()?.variantList.orEmpty()
                PriceParser.parse(cardId, getJson("de/cards/$cardId"), variants, canonicalKeys = false, now)
            }
        }
        db.withTransaction {
            priceDao.deleteFor(cardId)
            if (prices.isNotEmpty()) {
                priceDao.upsertAll(prices)
            } else {
                // "kein Preis bekannt" merken, damit nicht bei jedem Start neu gefragt wird
                priceDao.upsertAll(listOf(PriceEntity(cardId, NO_PRICE, null, null, null, null, null, "", now)))
            }
        }
    }

    private class NotFound(path: String) : IOException("Nicht gefunden: $path")

    private suspend fun getJson(path: String): JsonObject {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url("$API/$path").header("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.code == 404) throw NotFound(path)
                    if (resp.isSuccessful) return json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    last = IOException("HTTP ${resp.code} bei $path")
                    if (resp.code != 429 && resp.code < 500) throw last!!
                }
            } catch (e: NotFound) {
                throw e
            } catch (e: IOException) {
                last = e
            }
            delay(800L * (attempt + 1) * (attempt + 1))
        }
        throw last ?: IOException("Fehler bei $path")
    }

    companion object {
        const val API = "https://api.tcgdex.net/v2"
        const val SIX_HOURS = 6L * 3600 * 1000
        const val TWELVE_HOURS = 12L * 3600 * 1000
        /** Platzhalter-Variante: Karte hat bei Cardmarket (noch) keinen Preis */
        const val NO_PRICE = "#kein-preis"
    }
}
