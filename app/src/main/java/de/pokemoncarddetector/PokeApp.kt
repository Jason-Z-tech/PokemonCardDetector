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
import de.pokemoncarddetector.data.db.AppDatabase
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
    private val catalogStore = CatalogStore(app, database)
    val catalogManager = CatalogManager(
        scope = appScope,
        store = catalogStore,
        sync = CatalogSync(database, catalogStore, httpClient),
        settings = settings,
        repository = repository,
    )
}

class PokeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.catalogManager.ensureReady()
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
