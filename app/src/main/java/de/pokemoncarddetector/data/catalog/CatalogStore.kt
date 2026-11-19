package de.pokemoncarddetector.data.catalog

import android.content.Context
import androidx.room.withTransaction
import de.pokemoncarddetector.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Schreibt Katalogdaten (aus der App-Datei oder online) in die Datenbank. */
class CatalogStore(private val context: Context, private val db: AppDatabase) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Version der mitgelieferten assets/catalog.json */
    suspend fun bundledVersion(): String = withContext(Dispatchers.IO) { readBundled().version }

    suspend fun readBundled(): CatalogFile = withContext(Dispatchers.IO) {
        context.assets.open("catalog.json").bufferedReader(Charsets.UTF_8).use { reader ->
            json.decodeFromString(CatalogFile.serializer(), reader.readText())
        }
    }

    /** Importiert die mitgelieferte Datei. */
    suspend fun importBundled(onProgress: (Float) -> Unit = {}): String {
        val file = readBundled()
        write(file.series, file.sets.map { SetPayload(it, it.parsedCards()) }, removeMissing = false, onProgress)
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
            db.withTransaction { dao.upsertSeries(series.map { it.toEntity() }) }
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
