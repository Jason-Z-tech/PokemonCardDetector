package de.pokemoncarddetector.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SHOW_IMAGES = booleanPreferencesKey("show_images")
        val IMAGE_LANG = stringPreferencesKey("image_language")
        val AUTO_ADD = booleanPreferencesKey("auto_add")
        val ONLY_OWNED_SETS = booleanPreferencesKey("only_owned_sets")
        val VIBRATE = booleanPreferencesKey("vibrate")
        val CATALOG_VERSION = stringPreferencesKey("catalog_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            showImages = p[Keys.SHOW_IMAGES] ?: true,
            imageLanguage = ImageLanguage.from(p[Keys.IMAGE_LANG]),
            autoAdd = p[Keys.AUTO_ADD] ?: false,
            onlyOwnedSets = p[Keys.ONLY_OWNED_SETS] ?: false,
            vibrate = p[Keys.VIBRATE] ?: true,
        )
    }

    val catalogVersion: Flow<String?> = context.dataStore.data.map { it[Keys.CATALOG_VERSION] }

    suspend fun currentCatalogVersion(): String? = catalogVersion.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun setShowImages(value: Boolean) = set(Keys.SHOW_IMAGES, value)
    suspend fun setImageLanguage(value: ImageLanguage) = set(Keys.IMAGE_LANG, value.code)
    suspend fun setAutoAdd(value: Boolean) = set(Keys.AUTO_ADD, value)
    suspend fun setOnlyOwnedSets(value: Boolean) = set(Keys.ONLY_OWNED_SETS, value)
    suspend fun setVibrate(value: Boolean) = set(Keys.VIBRATE, value)
    suspend fun setCatalogVersion(value: String) = set(Keys.CATALOG_VERSION, value)
}
