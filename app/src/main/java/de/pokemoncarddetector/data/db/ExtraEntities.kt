package de.pokemoncarddetector.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Cardmarket-Preis einer Karten-Variante (Quelle: TCGdex, dort taeglich von Cardmarket uebernommen). */
@Entity(tableName = "prices", primaryKeys = ["cardId", "variant"])
data class PriceEntity(
    val cardId: String,
    /** Varianten-Schluessel wie in der Sammlung; "" = Preis der Karte ohne Variantenangabe */
    @ColumnInfo(defaultValue = "''")
    val variant: String,
    /** Trend-Preis in EUR */
    val trend: Double?,
    /** Durchschnitt der letzten 30 Tage in EUR */
    val avg30: Double?,
    /** Durchschnitt der letzten 7 Tage in EUR */
    val avg7: Double?,
    /** guenstigstes Angebot ("ab") in EUR */
    val low: Double?,
    /** Cardmarket-Produkt-ID */
    val idProduct: Long?,
    /** Stand der Cardmarket-Daten (ISO-Datum von TCGdex) */
    val priceDate: String,
    /** wann die App den Preis geholt hat (ms) */
    val fetchedAt: Long,
)

/** Karte auf der Wunschliste. variant = "" bedeutet "egal welche Variante". */
@Entity(tableName = "wishlist", primaryKeys = ["cardId", "variant"])
data class WishEntity(
    val cardId: String,
    @ColumnInfo(defaultValue = "''")
    val variant: String,
    val addedAt: Long,
)

/** Tageswert der Sammlung (fuer den Verlauf in der Statistik). */
@Entity(tableName = "value_history")
data class ValueSnapshot(
    /** yyyy-MM-dd */
    @PrimaryKey val date: String,
    /** Gesamtwert in Cent (Trendpreis; aelteres Feld, bleibt fuer Kompatibilitaet) */
    val valueCents: Long,
    val uniqueCards: Int,
    val copies: Int,
    /** Karten mit bekanntem Preis */
    val pricedCards: Int,
    /** Wert je Preisart in Cent – so bleibt der Verlauf beim Umschalten der Preisart stimmig (null = unbekannt) */
    val trendCents: Long? = null,
    val avg30Cents: Long? = null,
    val lowCents: Long? = null,
)

/** Set/Serie/Seltenheit einer gesammelten Karte (fuer die Statistik). */
data class OwnedMeta(
    val cardId: String,
    val setId: String,
    val serieId: String,
    val rarity: String,
)

/** Eine Zeile der Sammlung (fuer Wert-Berechnungen). */
data class CollectionRow(
    val cardId: String,
    val variant: String,
    val quantity: Int,
)

@Dao
interface PriceDao {
    @Upsert
    suspend fun upsertAll(prices: List<PriceEntity>)

    @Query("DELETE FROM prices WHERE cardId = :cardId")
    suspend fun deleteFor(cardId: String)

    @Query("SELECT * FROM prices WHERE cardId = :cardId")
    fun observeFor(cardId: String): Flow<List<PriceEntity>>

    @Query("SELECT * FROM prices")
    fun observeAll(): Flow<List<PriceEntity>>

    @Query("SELECT * FROM prices")
    suspend fun all(): List<PriceEntity>

    /** Juengster Abruf je Karte (hoechstens ~900 IDs pro Aufruf – SQLite-Grenze auf Android 8-11). */
    @Query("SELECT cardId, MAX(fetchedAt) AS fetchedAt FROM prices WHERE cardId IN (:cardIds) GROUP BY cardId")
    suspend fun fetchTimes(cardIds: List<String>): List<FetchTime>

    /** Sammlung + Wunschliste, deren Preis fehlt oder aelter als [cutoff] ist (ohne gebundene ID-Liste). */
    @Query(
        """
        SELECT t.cardId FROM (SELECT cardId FROM collection WHERE quantity > 0 UNION SELECT cardId FROM wishlist) t
        LEFT JOIN (SELECT cardId, MAX(fetchedAt) AS f FROM prices GROUP BY cardId) p ON p.cardId = t.cardId
        WHERE :force OR p.f IS NULL OR p.f < :cutoff
        """
    )
    suspend fun staleTrackedIds(cutoff: Long, force: Boolean): List<String>

    /** Wunschlisten-Karten mit fehlendem/altem Preis */
    @Query(
        """
        SELECT w.cardId FROM (SELECT DISTINCT cardId FROM wishlist) w
        LEFT JOIN (SELECT cardId, MAX(fetchedAt) AS f FROM prices GROUP BY cardId) p ON p.cardId = w.cardId
        WHERE p.f IS NULL OR p.f < :cutoff
        """
    )
    suspend fun staleWishIds(cutoff: Long): List<String>

    /**
     * Aeltester Preis-Stand der Sammlung + Wunschliste (null = noch keiner). Ehrlicher als der neueste Abruf:
     * zeigt, wie aktuell die Werte insgesamt sind.
     */
    @Query(
        """
        SELECT MIN(f) FROM (
            SELECT MAX(fetchedAt) AS f FROM prices
            WHERE cardId IN (SELECT cardId FROM collection WHERE quantity > 0 UNION SELECT cardId FROM wishlist)
            GROUP BY cardId
        )
        """
    )
    fun observeOldestTrackedFetch(): Flow<Long?>

    /** Wie viele Karten der Sammlung/Wunschliste noch gar keinen Preis-Abruf hatten */
    @Query(
        """
        SELECT COUNT(*) FROM (SELECT cardId FROM collection WHERE quantity > 0 UNION SELECT cardId FROM wishlist) t
        WHERE t.cardId NOT IN (SELECT cardId FROM prices)
        """
    )
    fun observeUnfetchedCount(): Flow<Int>
}

data class FetchTime(val cardId: String, val fetchedAt: Long)

@Dao
interface WishlistDao {
    @Upsert
    suspend fun upsert(wish: WishEntity)

    @Upsert
    suspend fun upsertAll(wishes: List<WishEntity>)

    @Query("DELETE FROM wishlist WHERE cardId = :cardId")
    suspend fun removeCard(cardId: String)

    @Query("SELECT * FROM wishlist")
    suspend fun all(): List<WishEntity>

    @Query("SELECT * FROM wishlist")
    fun observeAll(): Flow<List<WishEntity>>

    @Query("SELECT * FROM wishlist WHERE cardId = :cardId")
    fun observeFor(cardId: String): Flow<List<WishEntity>>
}

@Dao
interface StatsDao {
    @Query("SELECT cardId, variant, quantity FROM collection WHERE quantity > 0")
    fun observeCollectionRows(): Flow<List<CollectionRow>>

    @Query("SELECT cardId, variant, quantity FROM collection WHERE quantity > 0")
    suspend fun collectionRows(): List<CollectionRow>

    /** Karten, fuer die Preise interessant sind: Sammlung + Wunschliste */
    @Query("SELECT cardId FROM collection WHERE quantity > 0 UNION SELECT cardId FROM wishlist")
    suspend fun trackedCardIds(): List<String>

    @Query(
        """
        SELECT c.id AS cardId, s.id AS setId, s.serieId AS serieId, c.rarity AS rarity
        FROM cards c JOIN sets s ON s.id = c.setId
        WHERE c.id IN (SELECT cardId FROM collection WHERE quantity > 0)
        """
    )
    fun observeOwnedMeta(): Flow<List<OwnedMeta>>

    @Upsert
    suspend fun upsertSnapshot(snapshot: ValueSnapshot)

    @Query("SELECT * FROM value_history ORDER BY date")
    fun observeHistory(): Flow<List<ValueSnapshot>>
}
