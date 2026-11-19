package de.pokemoncarddetector.data.prices

import android.content.Context
import android.content.ContextWrapper
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.db.CatalogDao
import de.pokemoncarddetector.data.db.CollectionDao
import de.pokemoncarddetector.data.db.CollectionRow
import de.pokemoncarddetector.data.db.FetchTime
import de.pokemoncarddetector.data.db.OwnedMeta
import de.pokemoncarddetector.data.db.PriceDao
import de.pokemoncarddetector.data.db.PriceEntity
import de.pokemoncarddetector.data.db.StatsDao
import de.pokemoncarddetector.data.db.ValueSnapshot
import de.pokemoncarddetector.data.db.WishlistDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException

/**
 * Netzwerk-Verhalten der Preis-Aktualisierung mit einem OkHttp-Interceptor statt echtem Netz.
 * Die Datenbank ist eine Attrappe: getestet werden nur Abrufe, Abbruch und Zeitgrenzen (nicht das Speichern).
 */
class PriceRepositoryTest {

    private class FakePriceDao(private val stale: List<String>) : PriceDao {
        var lastCutoff: Long? = null
        var lastForce: Boolean? = null
        override suspend fun upsertAll(prices: List<PriceEntity>) = throw UnsupportedOperationException()
        override suspend fun deleteFor(cardId: String) = throw UnsupportedOperationException()
        override fun observeFor(cardId: String): Flow<List<PriceEntity>> = flowOf(emptyList())
        override fun observeAll(): Flow<List<PriceEntity>> = flowOf(emptyList())
        override suspend fun all(): List<PriceEntity> = emptyList()
        override suspend fun fetchTimes(cardIds: List<String>): List<FetchTime> = emptyList()
        override suspend fun staleTrackedIds(cutoff: Long, force: Boolean): List<String> {
            lastCutoff = cutoff
            lastForce = force
            return stale
        }
        override suspend fun staleWishIds(cutoff: Long): List<String> = stale
        override fun observeOldestTrackedFetch(): Flow<Long?> = flowOf(null)
        override fun observeUnfetchedCount(): Flow<Int> = flowOf(0)
    }

    private class FakeStatsDao : StatsDao {
        override fun observeCollectionRows(): Flow<List<CollectionRow>> = flowOf(emptyList())
        override suspend fun collectionRows(): List<CollectionRow> = emptyList()
        override suspend fun trackedCardIds(): List<String> = emptyList()
        override fun observeOwnedMeta(): Flow<List<OwnedMeta>> = flowOf(emptyList())
        override suspend fun upsertSnapshot(snapshot: ValueSnapshot) = throw UnsupportedOperationException()
        override fun observeHistory(): Flow<List<ValueSnapshot>> = flowOf(emptyList())
    }

    private class FakeDb(private val prices: PriceDao, private val stats: StatsDao) : AppDatabase() {
        override fun catalogDao(): CatalogDao = throw UnsupportedOperationException()
        override fun collectionDao(): CollectionDao = throw UnsupportedOperationException()
        override fun priceDao(): PriceDao = prices
        override fun wishlistDao(): WishlistDao = throw UnsupportedOperationException()
        override fun statsDao(): StatsDao = stats
        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper = throw UnsupportedOperationException()
        override fun createInvalidationTracker(): InvalidationTracker = InvalidationTracker(this)
        override fun clearAllTables() {}
    }

    /** Nur fuer den Aufbau von SettingsRepository (die Einstellungen werden hier nie gelesen) */
    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val ids = (1..30).map { "sv01-%03d".format(it) }

    private fun repo(dao: FakePriceDao, error: () -> Exception, calls: AtomicInteger, scope: CoroutineScope = CoroutineScope(SupervisorJob())): PriceRepository {
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw error() }.build()
        return PriceRepository(FakeDb(dao, FakeStatsDao()), client, SettingsRepository(TestContext()), scope, isOnline = { true })
    }

    @Test
    fun tlsFehlerOhneWartezeitUndAbbruchNachFuenfFehlern() = runBlocking {
        // WLAN mit Anmeldeseite: Verbindung "da", aber TLS scheitert. Vorher 3 Versuche mit 4 s Pause je Karte.
        val calls = AtomicInteger()
        val repo = repo(FakePriceDao(ids), { SSLHandshakeException("Anmeldeseite") }, calls)
        val start = System.nanoTime()
        val done = repo.refreshTracked()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("dauerte $ms ms", ms < 3_000)
        assertTrue("${calls.get()} Abrufe", calls.get() <= 12)
        assertTrue(done.offline)
        assertEquals(0, done.updated)
        assertEquals(ids.size, done.failed)
    }

    @Test
    fun tlsFehlerBeiEinzelnerKarteNurEinAbruf() = runBlocking {
        val calls = AtomicInteger()
        val repo = repo(FakePriceDao(emptyList()), { SSLHandshakeException("Anmeldeseite") }, calls)
        val start = System.nanoTime()
        assertFalse(repo.refreshCard("sv01-001"))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 700)
        assertEquals(1, calls.get())
    }

    @Test
    fun verbindungsFehlerZaehlenFuerDenAbbruch() {
        assertTrue(PriceRepository.isConnectionError(SSLHandshakeException("x")))
        assertTrue(PriceRepository.isConnectionError(javax.net.ssl.SSLException("x")))
        assertTrue(PriceRepository.isConnectionError(UnknownHostException("api.tcgdex.net")))
        assertTrue(PriceRepository.isConnectionError(SocketTimeoutException()))
        // Server-Fehler (503, 429) sind keine Verbindungsfehler: einzelne Karte erneut versuchen
        assertFalse(PriceRepository.isConnectionError(IOException("HTTP 503")))
    }

    @Test
    fun knopfLaedtNurPreiseAelterAlsEineStunde() = runBlocking {
        val dao = FakePriceDao(emptyList())
        val job = SupervisorJob()
        val repo = repo(dao, { IOException("nicht erwartet") }, AtomicInteger(), CoroutineScope(job + Dispatchers.Default))
        val before = System.currentTimeMillis()
        repo.refreshAllInBackground(force = false, maxAgeMs = PriceRepository.ONE_HOUR)
        job.children.toList().joinAll()
        val after = System.currentTimeMillis()
        assertEquals(false, dao.lastForce)
        val cutoff = dao.lastCutoff!!
        assertTrue(cutoff in (before - PriceRepository.ONE_HOUR)..(after - PriceRepository.ONE_HOUR))

        // Standard (Start/Hintergrund): 12 Stunden
        repo.refreshAllInBackground()
        job.children.toList().joinAll()
        assertTrue(dao.lastCutoff!! <= System.currentTimeMillis() - PriceRepository.TWELVE_HOURS)
        assertEquals(false, dao.lastForce)
    }
}
