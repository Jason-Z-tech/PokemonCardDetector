package de.pokemoncarddetector.data.catalog

import de.pokemoncarddetector.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Holt neue Sets/Karten von der freien TCGdex-API (https://tcgdex.dev).
 * Nur Sets, die neu sind oder sich geaendert haben, werden komplett geladen.
 */
class CatalogSync(
    private val db: AppDatabase,
    private val store: CatalogStore,
    private val client: OkHttpClient = defaultClient(),
) {
    data class Result(val newSets: Int, val updatedSets: Int, val totalSets: Int)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(onStatus: (String, Float?) -> Unit): Result = withContext(Dispatchers.IO) {
        onStatus("Setliste wird geladen …", null)
        val listDe = getArray("de/sets")
        val listEn = getArray("en/sets")
        val seriesDe = getArray("de/series")
        val seriesEn = getArray("en/series")
        val pocketIds = runCatching {
            getObject("en/series/$EXCLUDED_SERIE")["sets"]?.jsonArray?.mapNotNull { it.jsonObject.str("id") }?.toSet()
        }.getOrNull().orEmpty()

        val known = db.catalogDao().allSets().associateBy { it.id }
        val totalsDe = listDe.associate { it.jsonObject.str("id").orEmpty() to it.jsonObject.total() }
        val totalsEn = listEn.associate { it.jsonObject.str("id").orEmpty() to it.jsonObject.total() }
        val allIds = (listEn.mapNotNull { it.jsonObject.str("id") } + listDe.mapNotNull { it.jsonObject.str("id") })
            .distinct()
            .filter { it !in pocketIds }

        // Neu oder geaendert (andere Kartenzahl / jetzt auf Deutsch verfuegbar)
        val changed = allIds.filter { id ->
            val k = known[id] ?: return@filter true
            val remoteTotal = maxOf(totalsDe[id] ?: 0, totalsEn[id] ?: 0)
            remoteTotal != k.totalCount && remoteTotal > k.cardCount ||
                (id in totalsDe && !k.availableInGerman) || k.cardCount == 0
        }

        val semaphore = Semaphore(6)
        var done = 0
        val payloads = coroutineScope {
            changed.map { id ->
                async {
                    semaphore.withPermit {
                        val de = if (id in totalsDe) runCatching { getObject("de/sets/$id") }.getOrNull() else null
                        val en = if (id in totalsEn) runCatching { getObject("en/sets/$id") }.getOrNull() else null
                        synchronized(this@CatalogSync) { done++ }
                        onStatus("Sets werden geladen … ($done/${changed.size})", done.toFloat() / changed.size.coerceAtLeast(1))
                        buildPayload(id, de, en)
                    }
                }
            }.awaitAll().filterNotNull().filter { it.meta.serie != EXCLUDED_SERIE }
        }

        val seriesNamesEn = seriesEn.associate { it.jsonObject.str("id").orEmpty() to it.jsonObject.str("name").orEmpty() }
        val series = seriesDe.map { it.jsonObject }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            if (id == EXCLUDED_SERIE) return@mapNotNull null
            CatalogSerie(id, o.str("name") ?: id, seriesNamesEn[id] ?: o.str("name") ?: id)
        } + seriesEn.map { it.jsonObject }.mapNotNull { o ->
            val id = o.str("id") ?: return@mapNotNull null
            if (id == EXCLUDED_SERIE || seriesDe.any { it.jsonObject.str("id") == id }) return@mapNotNull null
            CatalogSerie(id, o.str("name") ?: id, o.str("name") ?: id)
        }

        onStatus("Daten werden gespeichert …", null)
        store.write(series, payloads, removeMissing = true)
        Result(
            newSets = payloads.count { it.meta.id !in known },
            updatedSets = payloads.count { it.meta.id in known },
            totalSets = allIds.size,
        )
    }

    private fun buildPayload(id: String, de: JsonObject?, en: JsonObject?): SetPayload? {
        val base = de ?: en ?: return null
        val serie = base["serie"]?.jsonObjectOrNull()?.str("id") ?: "misc"
        val abbr = base["abbreviation"]?.jsonObjectOrNull()?.str("official")
            ?: base.str("tcgOnline")
            ?: en?.get("abbreviation")?.jsonObjectOrNull()?.str("official")
            ?: en?.str("tcgOnline")
            ?: ""
        val count = base["cardCount"]?.jsonObjectOrNull()

        data class Acc(var de: String? = null, var en: String? = null, var imgDe: Boolean = false, var imgEn: Boolean = false)
        val cards = LinkedHashMap<String, Acc>()
        for ((lang, obj) in listOf("de" to de, "en" to en)) {
            val arr = obj?.get("cards") as? JsonArray ?: continue
            for (el in arr) {
                val c = el.jsonObject
                val lid = c.str("localId") ?: continue
                val acc = cards.getOrPut(lid) { Acc() }
                val name = c.str("name")
                if (lang == "de") acc.de = name else acc.en = name
                if (c.containsKey("image")) { if (lang == "de") acc.imgDe = true else acc.imgEn = true }
            }
        }
        val cardList = cards.map { (lid, a) ->
            val flags = (if (a.imgDe) 1 else 0) or (if (a.imgEn) 2 else 0) or (if (a.de != null) 4 else 0)
            CatalogCard(lid, a.de ?: a.en ?: "", a.en ?: a.de ?: "", flags)
        }
        val meta = CatalogSet(
            id = id,
            name = de?.str("name") ?: en?.str("name") ?: id,
            nameEn = en?.str("name") ?: de?.str("name") ?: id,
            serie = serie,
            release = base.str("releaseDate") ?: en?.str("releaseDate") ?: "",
            abbr = abbr,
            official = count?.get("official")?.jsonPrimitive?.intOrNull ?: 0,
            total = count?.get("total")?.jsonPrimitive?.intOrNull ?: cardList.size,
            de = de != null,
            logo = de?.str("logo") ?: en?.str("logo") ?: "",
            symbol = de?.str("symbol") ?: en?.str("symbol") ?: "",
        )
        return SetPayload(meta, cardList)
    }

    private fun get(path: String): JsonElement {
        val req = Request.Builder().url("$API/$path").header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} bei $path")
            val body = resp.body?.string() ?: throw IOException("Leere Antwort bei $path")
            return json.parseToJsonElement(body)
        }
    }

    private fun getArray(path: String): JsonArray = get(path).jsonArray
    private fun getObject(path: String): JsonObject = get(path).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonObject.total(): Int =
        (this["cardCount"] as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull ?: 0

    companion object {
        const val API = "https://api.tcgdex.net/v2"
        /** Pokemon TCG Pocket – reine App-Karten, nicht sammelbar. */
        const val EXCLUDED_SERIE = "tcgp"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
