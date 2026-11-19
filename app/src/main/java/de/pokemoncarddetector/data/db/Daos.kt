package de.pokemoncarddetector.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val CARD_ITEM_COLUMNS = """
    c.*, COALESCE(col.quantity, 0) AS quantity, s.name AS setName, s.nameEn AS setNameEn,
    s.serieId AS serieId, s.officialCount AS setOfficial, s.releaseDate AS setRelease
"""

@Dao
interface CatalogDao {

    @Upsert
    suspend fun upsertSeries(series: List<SerieEntity>)

    @Upsert
    suspend fun upsertSets(sets: List<SetEntity>)

    @Upsert
    suspend fun upsertCards(cards: List<CardEntity>)

    @Query("DELETE FROM cards WHERE setId = :setId AND id NOT IN (:keepIds)")
    suspend fun deleteCardsNotIn(setId: String, keepIds: List<String>)

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
            SELECT k.setId AS setId, COUNT(*) AS owned, SUM(col.quantity) AS copies
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
        LEFT JOIN collection col ON col.cardId = c.id
        WHERE c.setId = :setId
        ORDER BY c.sortIndex
        """
    )
    fun observeCardsInSet(setId: String): Flow<List<CardItem>>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN collection col ON col.cardId = c.id
        WHERE c.id = :cardId
        """
    )
    fun observeCard(cardId: String): Flow<CardItem?>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN collection col ON col.cardId = c.id
        WHERE c.id IN (:cardIds)
        """
    )
    suspend fun cardsByIds(cardIds: List<String>): List<CardItem>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        LEFT JOIN collection col ON col.cardId = c.id
        WHERE c.searchKey LIKE '%' || :query || '%'
           OR (:number != '' AND c.localId = :number)
        ORDER BY (COALESCE(col.quantity, 0) > 0) DESC, s.releaseDate DESC, c.sortIndex
        LIMIT 300
        """
    )
    fun searchCards(query: String, number: String): Flow<List<CardItem>>

    @Query(
        """
        SELECT $CARD_ITEM_COLUMNS
        FROM cards c JOIN sets s ON s.id = c.setId
        JOIN collection col ON col.cardId = c.id
        WHERE col.quantity > 0
        ORDER BY col.updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentlyAdded(limit: Int): Flow<List<CardItem>>

    @Query("SELECT id, setId, localId, name, nameEn FROM cards")
    suspend fun indexRows(): List<IndexCardRow>
}

@Dao
abstract class CollectionDao {

    @Query("SELECT quantity FROM collection WHERE cardId = :cardId")
    abstract suspend fun quantity(cardId: String): Int?

    @Upsert
    abstract suspend fun upsert(entry: CollectionEntity)

    @Upsert
    abstract suspend fun upsertAll(entries: List<CollectionEntity>)

    @Query("DELETE FROM collection WHERE cardId = :cardId")
    abstract suspend fun delete(cardId: String)

    @Query("DELETE FROM collection")
    abstract suspend fun clear()

    @Query("SELECT * FROM collection WHERE quantity > 0")
    abstract suspend fun all(): List<CollectionEntity>

    @Query("SELECT COUNT(*) AS uniqueCards, COALESCE(SUM(quantity), 0) AS copies FROM collection WHERE quantity > 0")
    abstract fun observeTotals(): Flow<CollectionTotals>

    /** Setzt die Anzahl (0 = entfernen). Gibt die neue Anzahl zurueck. */
    @Transaction
    open suspend fun setQuantity(cardId: String, quantity: Int): Int {
        val q = quantity.coerceIn(0, 999)
        if (q == 0) delete(cardId) else upsert(CollectionEntity(cardId, q, System.currentTimeMillis()))
        return q
    }

    /** Erhoeht/verringert die Anzahl. Gibt die neue Anzahl zurueck. */
    @Transaction
    open suspend fun add(cardId: String, delta: Int): Int {
        val current = quantity(cardId) ?: 0
        return setQuantity(cardId, current + delta)
    }
}
