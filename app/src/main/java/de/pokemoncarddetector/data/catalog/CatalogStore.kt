package de.pokemoncarddetector.data.catalog

import android.content.Context
import androidx.room.withTransaction
import de.pokemoncarddetector.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Verstaendlichere deutsche Serien-Namen als die Rohdaten von TCGdex (dort z.B. "Grund"). */
fun germanSerieName(id: String, name: String): String = when (id) {
    "base" -> "Grundset-Ära"
    "gym" -> "Gym-Serie"
    "neo" -> "Neo-Serie"
    "lc" -> "Legendary Collection"
    "ecard" -> "e-Card-Serie"
    "ex" -> "EX-Serie"
    "pop" -> "POP-Serie"
    "tk" -> "Trainer-Kits"
    "mc" -> "McDonald's-Kollektionen"
    "misc" -> "Sonstiges"
    else -> name
}

/** Schreibt Katalogdaten (aus der App-Datei oder online) in die Datenbank. */
class CatalogStore(private val context: Context, private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Version der mitgelieferten assets/catalog.json (aus der kleinen Datei catalog_version.txt). */
    suspend fun bundledVersion(): String = withContext(Dispatchers.IO) {
        runCatching { context.assets.open("catalog_version.txt").bufferedReader().use { it.readText().trim() } }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: readBundled().version
    }

    suspend fun readBundled(name: String = "catalog.json"): CatalogFile = withContext(Dispatchers.IO) {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString(CatalogFile.serializer(), reader.readText())
        }
    }

    /** Importiert die mitgelieferten Dateien (international + japanisch). */
    suspend fun importBundled(onProgress: (Float) -> Unit = {}): String {
        val file = readBundled()
        write(file.series, file.sets.map { SetPayload(it, it.parsedCards(file.variants)) }, removeMissing = false) { onProgress(it * 0.6f) }
        val ja = runCatching { readBundled("catalog_ja.json") }.getOrNull()
        if (ja != null) {
            write(ja.series, ja.sets.map { SetPayload(it, it.parsedCards(ja.variants)) }, removeMissing = false) { onProgress(0.6f + it * 0.4f) }
        }
        return file.version
    }

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { db.catalogDao().cardCount() == 0 }

    /**
     * Uebernimmt Sets und Karten. Die Sammlung (Anzahlen) bleibt immer unangetastet.
     * @param removeMissing Karten loeschen, die es im Set nicht mehr gibt (nur bei vollstaendigen Daten).
     */
    suspend fun write(series: List<CatalogSerie>, sets: List<SetPayload>, removeMissing: Boolean, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val dao = db.catalogDao()
            db.withTransaction { dao.upsertSeries(series.map { it.copy(name = germanSerieName(it.id, it.name)).toEntity() }) }
            // In Bloecken schreiben, damit der Fortschritt angezeigt werden kann
            sets.chunked(20).forEachIndexed { chunkIndex, chunk ->
                db.withTransaction {
                    for (payload in chunk) {
                        val set = payload.meta
                        val entities = payload.cards.mapIndexed { i, c -> c.toEntity(set.id, i) }
                        dao.upsertSets(listOf(set.toEntity(entities.size)))
                        dao.upsertCards(entities)
                        if (removeMissing && entities.isNotEmpty()) {
                            dao.deleteCardsNotIn(set.id, entities.map { it.id })
                        }
                    }
                }
                onProgress(((chunkIndex + 1) * 20f / sets.size).coerceAtMost(1f))
            }
        }
}
