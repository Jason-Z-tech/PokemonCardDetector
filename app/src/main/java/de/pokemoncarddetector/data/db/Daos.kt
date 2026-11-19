package de.pokemoncarddetector.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val CARD_ITEM_COLUMNS = """
    c.*, COALESCE(col.quantity, 0) AS quantity, col.variantQuantities AS variantQuantities,
    s.name AS setName, s.nameEn AS setNameEn,
    s.serieId AS serieId, s.officialCount AS setOfficial, s.releaseDate AS setRelease, s.imageSetId AS imageSetId
"""

/** Sammlung je Karte zusammengefasst: Gesamtanzahl + "variante=anzahl;..." */
private const val COLLECTION_PER_CARD = """
    (SELECT cardId, SUM(quantity) AS quantity, MAX(updatedAt) AS updatedAt,
            GROUP_CONCAT(variant || '=' || quantity, ';') AS variantQuantities
     FROM collection WHERE quantity > 0 GROUP BY cardId)
"""

@Dao
interface CatalogDao {

    @Upsert
    suspend fun upsertSeries(series: List<SerieEntity>)

    @Upsert
    suspend fun upsertSets(sets: List<SetEntity>)

    @Upsert
    suspend fun upsertCards(cards: List<CardEntity>)

    /** Entfernt Karten, die es im Set nicht mehr gibt – aber nie Karten, die jemand besitzt. */
    @Query("DELETE FROM cards WHERE setId = :setId AND id NOT IN (:keepIds) AND id NOT IN (SELECT cardId FROM collection)")
    suspend fun deleteCardsNotIn(setId: String, keepIds: List<String>)

    @Query("SELECT * FROM cards WHERE setId = :setId")
    suspend fun cardsOfSet(setId: String): List<CardEntity>

    /** Sets ab [sinceDate], in denen noch Karten ohne Bild oder ohne deutschen Namen sind. */
    @Query(
        """
        SELECT DISTINCT c.setId FROM cards c JOIN sets s ON s.id = c.setId
        WHERE s.releaseDate >= :sinceDate AND ((c.imageFlags & 3) = 0 OR (s.availableInGerman AND (c.imageFlags & 4) = 0))
        """
    )
    suspend fun incompleteSetIds(sinceDate: String): List<String>

    @Query("SELECT COUNT(*) FROM cards")
    suspend fun cardCount(): Int

    @Query("SELECT * FROM sets")
    suspend fun allSets(): List<SetEntity>

    @Query("SELECT * FROM series")
    fun observeSeries(): Flow<List<SerieEntity>>

    @Query(
        """
        SELECT s.*, COALESCE(o.owned, 0) AS owned, COALESCE(o.copies, 0) AS copies
        FROM sets s
        LEFT JOIN (
            SELECT k.setId AS setId, COUNT(DISTINCT col.cardId) AS owned, SUM(col.quantity) AS copies
            FROM collection col JOIN cards k ON k.id = col.cardId
            WHERE col.quantity > 0
            GROUP BY k.setId
        ) o ON o.setId = s.id
        WHERE s.cardCount > 0
        ORDER BY s.releaseDate DESC, s.id DESC
        """
    )
    fun observeSetsWithProgress(): Flow<List<SetWithProgress>>

    @Query("SELECT * FROM sets WHERE id = :setId")
    fun observeSet(setId: String): Flow<SetEntity?>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN $COLLECTION_PER_CARD col ON col.cardId = c.id
        WHERE c.setId = :setId
        ORDER BY c.sortIndex
        """
    )
    fun observeCardsInSet(setId: String): Flow<List<CardItem>>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN $COLLECTION_PER_CARD col ON col.cardId = c.id
        WHERE c.id = :cardId
        """
    )
    fun observeCard(cardId: String): Flow<CardItem?>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN $COLLECTION_PER_CARD col ON col.cardId = c.id
        WHERE c.id IN (:cardIds)
        """
    )
    suspend fun cardsByIds(cardIds: List<String>): List<CardItem>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN $COLLECTION_PER_CARD col ON col.cardId = c.id
        WHERE (:query != '' AND c.searchKey LIKE '%' || :query || '%')
           OR (:numberKey != '' AND c.numberKey = :numberKey AND (:total = 0 OR s.officialCount = :total))
        ORDER BY s.releaseDate DESC, s.id, c.sortIndex
        LIMIT 300
        """
    )
    fun searchCards(query: String, numberKey: String, total: Int): Flow<List<CardItem>>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        JOIN $COLLECTION_PER_CARD col ON col.cardId = c.id
        ORDER BY col.updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyAdded(limit: Int): Flow<List<CardItem>>

    @Query("SELECT id, setId, localId, name, nameEn, hp FROM cards")
    suspend fun indexRows(): List<IndexCardRow>
}

@Dao
abstract class CollectionDao {

    @Query("SELECT * FROM collection WHERE cardId = :cardId AND variant = :variant")
    abstract suspend fun entry(cardId: String, variant: String): CollectionEntity?

    @Upsert
    abstract suspend fun upsert(entry: CollectionEntity)

    @Upsert
    abstract suspend fun upsertAll(entries: List<CollectionEntity>)

    @Query("DELETE FROM collection WHERE cardId = :cardId AND variant = :variant")
    abstract suspend fun delete(cardId: String, variant: String)

    /** Entfernt eine Karte mit allen Varianten. */
    @Query("DELETE FROM collection WHERE cardId = :cardId")
    abstract suspend fun deleteCard(cardId: String)

    @Query("DELETE FROM collection")
    abstract suspend fun clear()

    @Query("SELECT * FROM collection WHERE quantity > 0")
    abstract suspend fun all(): List<CollectionEntity>

    @Query("SELECT * FROM collection WHERE cardId = :cardId AND quantity > 0")
    abstract suspend fun entriesOf(cardId: String): List<CollectionEntity>

    @Query(
        """
        SELECT COUNT(DISTINCT col.cardId) AS uniqueCards, COALESCE(SUM(col.quantity), 0) AS copies
        FROM collection col JOIN cards k ON k.id = col.cardId
        WHERE col.quantity > 0
        """
    )
    abstract fun observeTotals(): Flow<CollectionTotals>

    /**
     * Setzt die Anzahl einer Variante (0 = entfernen). Gibt die neue Anzahl zurueck.
     * Der Zeitpunkt fuer "Zuletzt hinzugefuegt" aendert sich nur, wenn die Anzahl steigt.
     */
    @Transaction
    open suspend fun setQuantity(cardId: String, variant: String, quantity: Int): Int {
        val q = quantity.coerceIn(0, 999)
        if (q == 0) {
            delete(cardId, variant)
        } else {
            val old = entry(cardId, variant)
            val time = if (old == null || q > old.quantity) System.currentTimeMillis() else old.updatedAt
            upsert(CollectionEntity(cardId, variant, q, time))
        }
        return q
    }

    /** Erhoeht/verringert die Anzahl einer Variante. Gibt die neue Anzahl zurueck. */
    @Transaction
    open suspend fun add(cardId: String, variant: String, delta: Int): Int {
        val current = entry(cardId, variant)?.quantity ?: 0
        return setQuantity(cardId, variant, current + delta)
    }

    /** Stellt einen frueheren Stand einer Karte (alle Varianten) wieder her. */
    @Transaction
    open suspend fun restore(cardId: String, entries: List<CollectionEntity>) {
        deleteCard(cardId)
        upsertAll(entries.filter { it.quantity > 0 })
    }
}
