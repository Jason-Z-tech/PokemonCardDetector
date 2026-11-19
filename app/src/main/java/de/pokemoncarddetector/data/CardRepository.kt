package de.pokemoncarddetector.data

import androidx.room.withTransaction
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.db.CollectionEntity
import de.pokemoncarddetector.data.db.CollectionTotals
import de.pokemoncarddetector.data.db.SerieEntity
import de.pokemoncarddetector.data.db.SetEntity
import de.pokemoncarddetector.data.db.SetWithProgress
import de.pokemoncarddetector.recognition.CatalogIndex
import de.pokemoncarddetector.recognition.IndexCard
import de.pokemoncarddetector.recognition.IndexSet
import de.pokemoncarddetector.recognition.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class CardRepository(private val db: AppDatabase) {

    private val catalog = db.catalogDao()
    private val collection = db.collectionDao()

    fun observeSets(): Flow<List<SetWithProgress>> = catalog.observeSetsWithProgress()
    fun observeSeries(): Flow<List<SerieEntity>> = catalog.observeSeries()
    fun observeSet(setId: String): Flow<SetEntity?> = catalog.observeSet(setId)
    fun observeCardsInSet(setId: String): Flow<List<CardItem>> = catalog.observeCardsInSet(setId)
    fun observeCard(cardId: String): Flow<CardItem?> = catalog.observeCard(cardId)
    fun observeTotals(): Flow<CollectionTotals> = collection.observeTotals()
    fun observeRecentlyAdded(limit: Int = 30): Flow<List<CardItem>> = catalog.observeRecentlyAdded(limit)

    /** Suche nach Name (deutsch oder englisch) oder Nummer. */
    fun search(query: String): Flow<List<CardItem>> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return flowOf(emptyList())
        val number = Regex("^[A-Za-z]{0,4}[0-9]{1,3}[a-z]?$").matchEntire(trimmed)?.value.orEmpty()
        val norm = TextNorm.normalize(trimmed)
        return catalog.searchCards(if (norm.length >= 2) norm else trimmed.lowercase(), number)
    }

    suspend fun cardsByIds(ids: List<String>): List<CardItem> =
        if (ids.isEmpty()) emptyList() else catalog.cardsByIds(ids)

    suspend fun setQuantity(cardId: String, quantity: Int): Int = collection.setQuantity(cardId, quantity)
    suspend fun add(cardId: String, delta: Int = 1): Int = collection.add(cardId, delta)

    // ---------- Index fuer die Erkennung ----------

    private val indexMutex = Mutex()
    @Volatile private var cachedIndex: CatalogIndex? = null

    suspend fun catalogIndex(): CatalogIndex = indexMutex.withLock {
        cachedIndex ?: withContext(Dispatchers.Default) {
            val sets = catalog.allSets().map {
                IndexSet(
                    id = it.id,
                    name = it.name,
                    nameEn = it.nameEn,
                    releaseYear = it.releaseDate.take(4).toIntOrNull(),
                    abbreviation = it.abbreviation,
                    officialCount = it.officialCount,
                    totalCount = it.totalCount,
                )
            }
            val cards = catalog.indexRows().map { IndexCard(it.id, it.setId, it.localId, it.name, it.nameEn) }
            CatalogIndex(sets, cards)
        }.also { cachedIndex = it }
    }

    /** Nach Katalog-Aenderungen aufrufen. */
    fun invalidateIndex() {
        cachedIndex = null
    }

    // ---------- Sicherung ----------

    @Serializable
    data class Backup(val app: String = "PokemonCardDetector", val version: Int = 1, val cards: List<Entry>) {
        @Serializable
        data class Entry(val id: String, val quantity: Int)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val entries = collection.all().map { Backup.Entry(it.cardId, it.quantity) }
        json.encodeToString(Backup.serializer(), Backup(cards = entries))
    }

    /** @return Anzahl importierter Karten */
    suspend fun importJson(text: String, replace: Boolean): Int = withContext(Dispatchers.IO) {
        val backup = json.decodeFromString(Backup.serializer(), text)
        val now = System.currentTimeMillis()
        val entries = backup.cards.filter { it.quantity > 0 }.map { CollectionEntity(it.id, it.quantity.coerceAtMost(999), now) }
        db.withTransaction {
            if (replace) collection.clear()
            collection.upsertAll(entries)
        }
        entries.size
    }
}
