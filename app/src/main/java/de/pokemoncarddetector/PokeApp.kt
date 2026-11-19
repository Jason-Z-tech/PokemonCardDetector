package de.pokemoncarddetector

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import de.pokemoncarddetector.data.CardRepository
import de.pokemoncarddetector.data.CatalogManager
import de.pokemoncarddetector.data.SettingsRepository
import de.pokemoncarddetector.data.catalog.CatalogStore
import de.pokemoncarddetector.data.catalog.CatalogSync
import de.pokemoncarddetector.data.CatalogState
import de.pokemoncarddetector.data.db.AppDatabase
import de.pokemoncarddetector.data.prices.PriceRepository
import de.pokemoncarddetector.work.PriceWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Einfache manuelle Abhaengigkeiten fuer die ganze App. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database: AppDatabase = AppDatabase.create(app)
    val settings = SettingsRepository(app)
    val repository = CardRepository(database)
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val priceRepository = PriceRepository(database, httpClient, settings, appScope, isOnline = { isOnline(app) })

    init {
        // Neue Karten bekommen sofort einen Preis, der Tageswert wird nachgezogen
        repository.onCollectionChanged = { cardId -> priceRepository.onCollectionChanged(cardId) }
    }
    private val catalogStore = CatalogStore(app, database)
    val catalogManager = CatalogManager(
        scope = appScope,
        store = catalogStore,
        sync = CatalogSync(database, catalogStore, httpClient, germanSpecies = { loadGermanSpecies(app) }),
        settings = settings,
        repository = repository,
    )
}

/** assets/species_de.json: {"6": "Glurak", ...} (fehlt die Datei, gibt es keine Hinweise) */
fun loadGermanSpecies(context: android.content.Context): Map<Int, String> = runCatching {
    val text = context.assets.open("species_de.json").bufferedReader().use { it.readText() }
    kotlinx.serialization.json.Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }
        .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to (v as kotlinx.serialization.json.JsonPrimitive).content } }
        .toMap()
}.getOrDefault(emptyMap())

/** Gibt es gerade ein Netzwerk mit Internet? (ohne Netz werden Preis-Abrufe gar nicht erst versucht) */
fun isOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

class PokeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.catalogManager.ensureReady()
        // Preise aktuell halten: beim Start (nur veraltete) + taeglich im Hintergrund
        container.appScope.launch {
            val settings = container.settings.settings.first()
            // Ein Problem mit WorkManager darf den Preis-Abruf beim Start nicht verhindern
            runCatching { PriceWorker.schedule(this@PokeApp, settings.autoPrices) }
            container.catalogManager.state.first { it == CatalogState.Ready }
            if (settings.autoPrices) runCatching { container.priceRepository.refreshTracked() }
        }
    }

    /** Kartenbilder werden dauerhaft zwischengespeichert (bis 512 MB), damit die Sammlung offline aussieht. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.httpClient }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("kartenbilder"))
                .maxSizeBytes(512L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
