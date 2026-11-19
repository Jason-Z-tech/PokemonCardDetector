package de.pokemoncarddetector.data

import androidx.room.withTransaction
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.data.db.CollectionEntity
import de.pokemoncarddetector.data.db.CollectionTotals
import de.pokemoncarddetector.data.db.SerieEntity
import de.pokemoncarddetector.data.db.SetEntity
import de.pokemoncarddetector.data.db.SetWithProgress
import de.pokemoncarddetector.recognition.CardTextParser
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

    private companion object {
        /** "25", "025/185", "TG05", "TG05/TG30", "SWSH050", "12a" */
        val NUMBER_QUERY = Regex("^([A-Za-z]{0,4}[0-9]{1,3}[a-zA-Z]?)(/[A-Za-z]{0,4}([0-9]{1,3}))?$")
    }

    private val catalog = db.catalogDao()
    private val collection = db.collectionDao()

    fun observeSets(): Flow<List<SetWithProgress>> = catalog.observeSetsWithProgress()
    fun observeSeries(): Flow<List<SerieEntity>> = catalog.observeSeries()
    fun observeSet(setId: String): Flow<SetEntity?> = catalog.observeSet(setId)
    fun observeCardsInSet(setId: String): Flow<List<CardItem>> = catalog.observeCardsInSet(setId)
    fun observeCard(cardId: String): Flow<CardItem?> = catalog.observeCard(cardId)
    fun observeTotals(): Flow<CollectionTotals> = collection.observeTotals()
    fun observeRecentlyAdded(limit: Int = 30): Flow<List<CardItem>> = catalog.observeRecentlyAdded(limit)

    /** Suche nach Name (deutsch oder englisch) oder Nummer ("25", "025/185", "TG05", "tg05/tg30"). */
    fun search(query: String): Flow<List<CardItem>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return flowOf(emptyList())
        val number = NUMBER_QUERY.matchEntire(trimmed.replace(" ", ""))
        val numberKey = number?.let { CardTextParser.normalizeLocalId(it.groupValues[1]) }.orEmpty()
        // Gesamtzahl nur bei reinen Zahlen pruefen (RC5/RC32 steht z.B. im Set Generationen mit 83 Karten)
        val total = if (numberKey.firstOrNull()?.isDigit() == true) number?.groupValues?.get(3)?.toIntOrNull() ?: 0 else 0
        val norm = TextNorm.normalize(trimmed)
        // Reine Zahlen nicht als Namenssuche verwenden (sonst findet "25" alle Namen mit 25)
        val nameQuery = if (number != null && norm.all { it.isDigit() }) "" else if (norm.length >= 2) norm else ""
        if (nameQuery.isEmpty() && numberKey.isEmpty()) return flowOf(emptyList())
        return catalog.searchCards(nameQuery, numberKey, total)
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
        data class Entry(val id: String, val quantity: Int, val updatedAt: Long = 0)
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val entries = collection.all().map { Backup.Entry(it.cardId, it.quantity, it.updatedAt) }
        json.encodeToString(Backup.serializer(), Backup(cards = entries))
    }

    /** @return Anzahl importierter Karten */
    suspend fun importJson(text: String, replace: Boolean): Int = withContext(Dispatchers.IO) {
        val backup = json.decodeFromString(Backup.serializer(), text)
        val now = System.currentTimeMillis()
        val entries = backup.cards.filter { it.quantity > 0 && it.id.isNotBlank() }
            .map { CollectionEntity(it.id, it.quantity.coerceAtMost(999), if (it.updatedAt > 0) it.updatedAt else now) }
        db.withTransaction {
            if (replace) collection.clear()
            collection.upsertAll(entries)
        }
        entries.size
    }
}
