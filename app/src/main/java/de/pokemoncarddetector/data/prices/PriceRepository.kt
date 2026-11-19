package de.pokemoncarddetector.data.prices

import androidx.room.withTransaction
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.PriceEntity
import de.pokemoncarddetector.data.db.ValueSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** Zustand der Preis-Aktualisierung fuer die Oberflaeche. */
sealed interface PriceRefreshState {
    data object Idle : PriceRefreshState
    data class Running(val done: Int, val total: Int) : PriceRefreshState
    data class Done(val updated: Int, val failed: Int, val offline: Boolean = false) : PriceRefreshState
}

/**
 * Holt Cardmarket-Preise ueber TCGdex (dort taeglich aktualisiert) fuer die Karten der
 * Sammlung und der Wunschliste und merkt sich jeden Tag den Gesamtwert.
 */
class PriceRepository(
    private val db: AppDatabase,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** true, wenn ein Netzwerk verfuegbar ist (sonst wird gar nicht erst gefragt) */
    private val isOnline: () -> Boolean = { true },
) {
    private val priceDao = db.priceDao()
    private val statsDao = db.statsDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _state = MutableStateFlow<PriceRefreshState>(PriceRefreshState.Idle)
    val state: StateFlow<PriceRefreshState> = _state.asStateFlow()

    fun observePrices(cardId: String): Flow<List<PriceEntity>> = priceDao.observeFor(cardId)
    fun observeAllPrices(): Flow<List<PriceEntity>> = priceDao.observeAll()

    /** Aeltester Preis-Stand der Sammlung + Wunschliste */
    fun observeOldestTrackedFetch(): Flow<Long?> = priceDao.observeOldestTrackedFetch()

    /** Karten der Sammlung/Wunschliste ohne jeden Preis-Abruf */
    fun observeUnfetchedCount(): Flow<Int> = priceDao.observeUnfetchedCount()

    /** Preise einer Karte laden, wenn sie aelter als [maxAgeMs] sind. */
    suspend fun refreshCard(cardId: String, maxAgeMs: Long = SIX_HOURS): Boolean = withContext(Dispatchers.IO) {
        val last = priceDao.fetchTimes(listOf(cardId)).firstOrNull()?.fetchedAt ?: 0L
        if (System.currentTimeMillis() - last < maxAgeMs) return@withContext true
        if (!isOnline()) return@withContext false
        try {
            fetchAndStore(cardId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Karte kam neu in die Sammlung: Preis holen (falls noch keiner/alt) und den heutigen Wert nachziehen.
     * Laeuft im App-Bereich, damit Verlassen des Bildschirms es nicht abbricht.
     */
    fun onCollectionChanged(cardId: String?) {
        scope.launch {
            if (cardId != null && settings.settings.first().autoPrices) refreshCard(cardId)
            scheduleSnapshot()
        }
    }

    /**
     * Alle veralteten Karten der Sammlung + Wunschliste aktualisieren, danach Tageswert speichern.
     * @param force true = auch frische Preise neu laden
     */
    suspend fun refreshTracked(force: Boolean = false, maxAgeMs: Long = TWELVE_HOURS): PriceRefreshState.Done =
        refreshIds(force) { priceDao.staleTrackedIds(System.currentTimeMillis() - maxAgeMs, force) }

    /** Nur die Wunschliste aktualisieren (beim Oeffnen der Wunschliste). */
    suspend fun refreshWishlist(maxAgeMs: Long = TWELVE_HOURS): PriceRefreshState.Done =
        refreshIds(force = false) { priceDao.staleWishIds(System.currentTimeMillis() - maxAgeMs) }

    /**
     * Im App-Bereich aktualisieren, damit es beim Verlassen des Bildschirms weiterlaeuft.
     * @param maxAgeMs nur Preise neu laden, die aelter sind (TCGdex aktualisiert ohnehin nur einmal am Tag)
     */
    fun refreshAllInBackground(force: Boolean = false, maxAgeMs: Long = TWELVE_HOURS) {
        scope.launch { runCatching { refreshTracked(force, maxAgeMs) } }
    }

    private suspend fun refreshIds(force: Boolean, ids: suspend () -> List<String>): PriceRefreshState.Done =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!isOnline()) {
                    return@withLock PriceRefreshState.Done(0, 0, offline = true).also { _state.value = it }
                }
                val todo = ids().distinct()
                val done = AtomicInteger(0)
                val failed = AtomicInteger(0)
                val connectionErrors = AtomicInteger(0)
                try {
                    _state.value = PriceRefreshState.Running(0, todo.size)
                    val semaphore = Semaphore(6)
                    coroutineScope {
                        todo.map { id ->
                            async {
                                semaphore.withPermit {
                                    // Nach mehreren Verbindungsfehlern in Folge abbrechen (offline/Server weg)
                                    if (connectionErrors.get() >= 5) {
                                        failed.incrementAndGet()
                                        return@withPermit
                                    }
                                    try {
                                        fetchAndStore(id)
                                        connectionErrors.set(0)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failed.incrementAndGet()
                                        if (isConnectionError(e)) connectionErrors.incrementAndGet()
                                    }
                                    _state.value = PriceRefreshState.Running(done.incrementAndGet(), todo.size)
                                }
                            }
                        }.awaitAll()
                    }
                    snapshotValue()
                    PriceRefreshState.Done(todo.size - failed.get(), failed.get(), offline = connectionErrors.get() >= 5)
                        .also { _state.value = it }
                } finally {
                    // Abbruch (z.B. Bildschirm verlassen): Anzeige nicht auf "laeuft" stehen lassen
                    if (_state.value is PriceRefreshState.Running) _state.value = PriceRefreshState.Idle
                }
            }
        }

    private var snapshotJob: Job? = null

    /** Tageswert nach Aenderungen an der Sammlung kurz verzoegert neu berechnen (mehrere Scans = eine Rechnung). */
    fun scheduleSnapshot() {
        snapshotJob?.cancel()
        snapshotJob = scope.launch {
            delay(3_000)
            runCatching { snapshotValue() }
        }
    }

    /**
     * Heutigen Sammlungswert speichern – fuer jede Preisart, damit der Verlauf beim Umschalten stimmt.
     * Ohne Sammlung oder ohne einen einzigen Preis wird nichts gespeichert (sonst entstuende ein 0-Euro-Punkt).
     */
    suspend fun snapshotValue() = withContext(Dispatchers.IO) {
        val rows = statsDao.collectionRows()
        if (rows.isEmpty()) return@withContext
        val prices = priceDao.all()
        val values = PriceMetric.entries.associateWith { collectionValue(rows, prices, it) }
        val trend = values.getValue(PriceMetric.TREND)
        if (values.values.all { it.pricedCards == 0 }) return@withContext
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        fun cents(m: PriceMetric) = Math.round(values.getValue(m).totalEuro * 100)
        statsDao.upsertSnapshot(
            ValueSnapshot(
                date = today,
                valueCents = cents(PriceMetric.TREND),
                uniqueCards = rows.map { it.cardId }.distinct().size,
                copies = rows.sumOf { it.quantity },
                pricedCards = trend.pricedCards,
                trendCents = cents(PriceMetric.TREND),
                avg30Cents = cents(PriceMetric.AVG30),
                lowCents = cents(PriceMetric.LOW),
            )
        )
    }

    private suspend fun fetchAndStore(cardId: String) {
        val now = System.currentTimeMillis()
        val prices = try {
            if (cardId.startsWith("ja:")) {
                PriceParser.parse(cardId, getJson("ja", cardId.removePrefix("ja:")), emptyList(), canonicalKeys = true, now)
            } else {
                try {
                    // Englische Antwort nutzt dieselben Varianten-Schluessel wie der Katalog
                    PriceParser.parse(cardId, getJson("en", cardId), emptyList(), canonicalKeys = true, now)
                } catch (e: NotFound) {
                    // Nur auf Deutsch erschienen: Bezeichnungen sind uebersetzt -> ueber die Reihenfolge zuordnen
                    val variants = db.catalogDao().cardsByIds(listOf(cardId)).firstOrNull()?.variantList.orEmpty()
                    PriceParser.parse(cardId, getJson("de", cardId), variants, canonicalKeys = false, now)
                }
            }
        } catch (e: NotFound) {
            // Karte gibt es bei TCGdex (nicht mehr): "kein Preis" merken statt jeden Tag neu zu fragen
            emptyList()
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

    /** Nicht wiederholbarer HTTP-Fehler (4xx ausser 429) – bewusst keine IOException, damit er nicht erneut versucht wird */
    private class HttpStatus(val code: Int, path: String) : Exception("HTTP $code bei $path")

    /** /v2/{lang}/cards/{id} – die ID wird als Pfadsegment kodiert ("exu-%3F" -> "exu-%253F"). */
    private fun cardUrl(lang: String, id: String): HttpUrl = API.toHttpUrl().newBuilder()
        .addPathSegment(lang).addPathSegment("cards").addPathSegment(id).build()

    private suspend fun getJson(lang: String, id: String): JsonObject {
        val url = cardUrl(lang, id)
        var last: Exception? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(800L * attempt * attempt)
            val result = runCatching {
                val req = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> throw NotFound(url.toString())
                        resp.isSuccessful -> json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                        resp.code == 429 || resp.code >= 500 -> throw IOException("HTTP ${resp.code} bei $url")
                        else -> throw HttpStatus(resp.code, url.toString())
                    }
                }
            }
            result.getOrNull()?.let { return it }
            val e = result.exceptionOrNull()
            when (e) {
                is CancellationException, is NotFound, is HttpStatus -> throw e
                // Kein Netz bzw. TLS-Fehler (WLAN-Anmeldeseite, abgefangene Verbindung): nicht 3x mit Wartezeit probieren
                is UnknownHostException, is ConnectException, is SSLException -> throw e
                is Exception -> last = e
                else -> throw e ?: IOException("Fehler bei $url")
            }
        }
        throw last ?: IOException("Fehler bei $url")
    }

    companion object {
        /** Verbindung kommt gar nicht zustande – nach mehreren davon wird die Aktualisierung abgebrochen */
        internal fun isConnectionError(e: Throwable): Boolean =
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException || e is SSLException

        const val API = "https://api.tcgdex.net/v2"
        const val SIX_HOURS = 6L * 3600 * 1000
        const val TWELVE_HOURS = 12L * 3600 * 1000
        /** Knopf "Preise aktualisieren": nur Preise, die aelter als eine Stunde sind */
        const val ONE_HOUR = 3600L * 1000
        /** Platzhalter-Variante: Karte hat bei Cardmarket (noch) keinen Preis */
        const val NO_PRICE = "#kein-preis"
    }
}
