package de.pokemoncarddetector.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.pokemoncarddetector.data.prices.PriceMetric
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "einstellungen")

enum class ImageLanguage(val code: String, val label: String) {
    GERMAN("de", "Deutsch (sonst Englisch)"),
    ENGLISH("en", "Englisch");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: GERMAN
    }
}

data class AppSettings(
    /** Kartenbilder anzeigen (aus = schnelle Textliste, spart Datenvolumen) */
    val showImages: Boolean = true,
    val imageLanguage: ImageLanguage = ImageLanguage.GERMAN,
    /** Erkannte Karten beim Scannen sofort ohne Bestaetigung hinzufuegen */
    val autoAdd: Boolean = false,
    /** In der Uebersicht nur Sets zeigen, aus denen man Karten hat */
    val onlyOwnedSets: Boolean = false,
    /** Vibration bei erkannter Karte */
    val vibrate: Boolean = true,
    /** Welcher Cardmarket-Wert angezeigt/gerechnet wird */
    val priceMetric: PriceMetric = PriceMetric.TREND,
    /** Preise automatisch aktuell halten (beim Start + taeglich im Hintergrund) */
    val autoPrices: Boolean = true,
    /** Scanner liest japanische Karten */
    val scanJapanese: Boolean = false,
    /** Sammlung zeigt japanische Sets */
    val libraryJapanese: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SHOW_IMAGES = booleanPreferencesKey("show_images")
        val IMAGE_LANG = stringPreferencesKey("image_language")
        val AUTO_ADD = booleanPreferencesKey("auto_add")
        val ONLY_OWNED_SETS = booleanPreferencesKey("only_owned_sets")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val PRICE_METRIC = stringPreferencesKey("price_metric")
        val AUTO_PRICES = booleanPreferencesKey("auto_prices")
        val SCAN_JAPANESE = booleanPreferencesKey("scan_japanese")
        val LIBRARY_JAPANESE = booleanPreferencesKey("library_japanese")
        /** Version der zuletzt importierten mitgelieferten Katalogdatei */
        val BUNDLED_VERSION = stringPreferencesKey("bundled_catalog_version")
        /** Datum der letzten Online-Aktualisierung (nur Anzeige) */
        val LAST_SYNC = stringPreferencesKey("last_online_sync")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            showImages = p[Keys.SHOW_IMAGES] ?: true,
            imageLanguage = ImageLanguage.from(p[Keys.IMAGE_LANG]),
            autoAdd = p[Keys.AUTO_ADD] ?: false,
            onlyOwnedSets = p[Keys.ONLY_OWNED_SETS] ?: false,
            vibrate = p[Keys.VIBRATE] ?: true,
            priceMetric = PriceMetric.from(p[Keys.PRICE_METRIC]),
            autoPrices = p[Keys.AUTO_PRICES] ?: true,
            scanJapanese = p[Keys.SCAN_JAPANESE] ?: false,
            libraryJapanese = p[Keys.LIBRARY_JAPANESE] ?: false,
        )
    }

    val bundledVersion: Flow<String?> = context.dataStore.data.map { it[Keys.BUNDLED_VERSION] }
    val lastOnlineSync: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_SYNC] }

    /** Stand der Kartendaten zur Anzeige: neueres Datum aus Katalogdatei und Online-Abgleich */
    val catalogVersion: Flow<String?> = context.dataStore.data.map { p ->
        listOfNotNull(p[Keys.BUNDLED_VERSION], p[Keys.LAST_SYNC]).maxOrNull()
    }

    suspend fun currentBundledVersion(): String? = bundledVersion.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setShowImages(value: Boolean) = set(Keys.SHOW_IMAGES, value)
    suspend fun setImageLanguage(value: ImageLanguage) = set(Keys.IMAGE_LANG, value.code)
    suspend fun setAutoAdd(value: Boolean) = set(Keys.AUTO_ADD, value)
    suspend fun setOnlyOwnedSets(value: Boolean) = set(Keys.ONLY_OWNED_SETS, value)
    suspend fun setVibrate(value: Boolean) = set(Keys.VIBRATE, value)
    suspend fun setPriceMetric(value: PriceMetric) = set(Keys.PRICE_METRIC, value.code)
    suspend fun setAutoPrices(value: Boolean) = set(Keys.AUTO_PRICES, value)
    suspend fun setScanJapanese(value: Boolean) = set(Keys.SCAN_JAPANESE, value)
    suspend fun setLibraryJapanese(value: Boolean) = set(Keys.LIBRARY_JAPANESE, value)
    suspend fun setBundledVersion(value: String) = set(Keys.BUNDLED_VERSION, value)
    suspend fun setLastOnlineSync(value: String) = set(Keys.LAST_SYNC, value)
}
