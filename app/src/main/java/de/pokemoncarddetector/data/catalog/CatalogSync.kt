package de.pokemoncarddetector.data.catalog

import de.pokemoncarddetector.data.Variants
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** Pokedex-Nr. -> deutscher Pokemon-Name (assets/species_de.json), fuer Hinweise bei neuen japanischen Karten */
    private val germanSpecies: () -> Map<Int, String> = { emptyMap() },
) {
    data class Result(val newSets: Int, val updatedSets: Int, val totalSets: Int, val failedSets: Int)

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

        val dao = db.catalogDao()
        val known = dao.allSets().associateBy { it.id }
        val totalsDe = listDe.associate { it.jsonObject.str("id").orEmpty() to it.jsonObject.total() }
        val totalsEn = listEn.associate { it.jsonObject.str("id").orEmpty() to it.jsonObject.total() }
        val allIds = (listEn.mapNotNull { it.jsonObject.str("id") } + listDe.mapNotNull { it.jsonObject.str("id") })
            .distinct()
            .filter { it !in pocketIds }

        // Neuere Sets, in denen noch Bilder oder deutsche Namen fehlen (werden bei TCGdex oft nachgetragen)
        val since = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - 2L * 365 * 24 * 3600 * 1000))
        val incomplete = dao.incompleteSetIds(since).toSet()

        // Neu oder geaendert (mehr Karten / jetzt auf Deutsch verfuegbar / unvollstaendig)
        val changed = allIds.filter { id ->
            val k = known[id] ?: return@filter true
            val remoteTotal = maxOf(totalsDe[id] ?: 0, totalsEn[id] ?: 0)
            (remoteTotal != k.totalCount && remoteTotal > k.cardCount) ||
                (id in totalsDe && !k.availableInGerman) ||
                id in incomplete
        }

        val semaphore = Semaphore(6)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val payloads = coroutineScope {
            changed.map { id ->
                async {
                    semaphore.withPermit {
                        try {
                            // Eine Sprache, die laut Liste existiert, MUSS geladen werden – sonst Set auslassen,
                            // damit keine deutschen Namen durch englische ersetzt oder Karten geloescht werden.
                            val de = if (id in totalsDe) getObjectWithRetry("de/sets/$id") else null
                            val en = if (id in totalsEn) getObjectWithRetry("en/sets/$id") else null
                            // Varianten (Normal/Holo/Reverse/...): wenn das fehlschlaegt, bleiben die bisherigen erhalten
                            val variants = runCatching { fetchVariants(id) }.getOrNull()
                            buildPayload(id, de, en, existing = dao.cardsOfSet(id).associateBy { it.localId }, variants = variants)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            null
                        } finally {
                            val n = done.incrementAndGet()
                            onStatus("Sets werden geladen … ($n/${changed.size})", n.toFloat() / changed.size.coerceAtLeast(1))
                        }
                    }
                }
            }.awaitAll().filterNotNull()
                .filter { it.meta.serie != EXCLUDED_SERIE && it.cards.isNotEmpty() }
        }

        // Nur wirklich geaenderte Sets speichern/zaehlen
        val toWrite = payloads.filter { p ->
            val k = known[p.meta.id] ?: return@filter true
            val stored = dao.cardsOfSet(p.meta.id).associateBy { it.localId }
            k.name != p.meta.name || k.cardCount != p.cards.size || k.officialCount != p.meta.official ||
                k.releaseDate != p.meta.release || k.logoUrl != p.meta.logo ||
                p.cards.any { c ->
                    stored[c.localId]?.let {
                        it.name != c.name || it.nameEn != c.nameEn || it.imageFlags != c.flags ||
                            it.variants != c.variants.joinToString(";") || it.hp != c.hp || it.rarity != c.rarity
                    } ?: true
                }
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
        store.write(series, toWrite, removeMissing = true)

        // Japanische Sets: neue oder gewachsene Sets nachladen
        val ja = runCatching { syncJapanese(known, onStatus) }.getOrNull()
        Result(
            newSets = toWrite.count { it.meta.id !in known } + (ja?.first ?: 0),
            updatedSets = toWrite.count { it.meta.id in known } + (ja?.second ?: 0),
            totalSets = allIds.size + (ja?.third ?: 0),
            failedSets = failed.get() + (if (ja == null) 1 else 0),
        )
    }

    /** @return (neue Sets, aktualisierte Sets, Sets insgesamt) */
    private suspend fun syncJapanese(
        known: Map<String, de.pokemoncarddetector.data.db.SetEntity>,
        onStatus: (String, Float?) -> Unit,
    ): Triple<Int, Int, Int> {
        val list = getArray("ja/sets").mapNotNull { it as? JsonObject }
        val changed = list.filter { o ->
            val id = o.str("id") ?: return@filter false
            val k = known["ja:$id"] ?: return@filter o.total() > 0
            o.total() > k.cardCount
        }
        if (changed.isEmpty()) return Triple(0, 0, list.size)
        val species = germanSpecies()
        val payloads = mutableListOf<SetPayload>()
        val seriesNames = getArray("ja/series").mapNotNull { it as? JsonObject }
            .associate { (it.str("id") ?: "") to (it.str("name") ?: "") }
        changed.forEachIndexed { i, o ->
            val sid = o.str("id") ?: return@forEachIndexed
            onStatus("Japanische Sets werden geladen … (${i + 1}/${changed.size})", (i + 1f) / changed.size)
            val detail = runCatching { getObjectWithRetry("ja/sets/$sid") }.getOrNull() ?: return@forEachIndexed
            val cards = (detail["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            if (cards.isEmpty()) return@forEachIndexed
            val existing = db.catalogDao().cardsOfSet("ja:$sid").associateBy { it.localId }
            val semaphore = Semaphore(6)
            val rows = coroutineScope {
                cards.map { c ->
                    async {
                        semaphore.withPermit {
                            val lid = c.str("localId") ?: return@withPermit null
                            val cd = runCatching { getObjectWithRetry("ja/cards/${sid}-$lid") }.getOrNull()
                            val old = existing[lid]
                            val name = c.str("name").orEmpty()
                            val dex = (cd?.get("dexId") as? JsonArray).orEmpty()
                                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
                            CatalogCard(
                                localId = lid,
                                name = name,
                                nameEn = old?.nameEn?.takeIf { it.isNotBlank() } ?: germanHint(name, dex, species),
                                flags = if (c.containsKey("image")) 16 else (old?.imageFlags ?: 0),
                                variants = (cd?.get("variants_detailed") as? JsonArray).orEmpty()
                                    .mapNotNull { (it as? JsonObject)?.let(::variantKey) }.distinct()
                                    .ifEmpty { old?.variants?.let { Variants.list(it) }.orEmpty() },
                                hp = (cd?.get("hp") as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: old?.hp ?: 0,
                                regulationMark = cd?.str("regulationMark") ?: old?.regulationMark.orEmpty(),
                                rarity = cd?.str("rarity")?.takeIf { it != "None" } ?: old?.rarity.orEmpty(),
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val serie = (detail["serie"] as? JsonObject)?.str("id") ?: "misc"
            val count = detail["cardCount"] as? JsonObject
            payloads += SetPayload(
                CatalogSet(
                    id = "ja:$sid",
                    name = detail.str("name") ?: sid,
                    nameEn = detail.str("name") ?: sid,
                    serie = "ja:$serie",
                    release = detail.str("releaseDate").orEmpty(),
                    abbr = sid,
                    official = count?.get("official")?.jsonPrimitive?.intOrNull ?: 0,
                    total = count?.get("total")?.jsonPrimitive?.intOrNull ?: rows.size,
                    de = false,
                    logo = detail.str("logo").orEmpty(),
                    symbol = detail.str("symbol").orEmpty(),
                    img = sid,
                ),
                rows.sortedWith(compareBy(naturalCardOrder()) { it.localId }),
            )
        }
        val series = payloads.map { it.meta.serie }.distinct().map { id ->
            CatalogSerie(id, seriesNames[id.removePrefix("ja:")] ?: id.removePrefix("ja:"), seriesNames[id.removePrefix("ja:")] ?: id)
        }
        store.write(series, payloads, removeMissing = false)
        return Triple(payloads.count { "ja:" + it.meta.id.removePrefix("ja:") !in known }, payloads.count { it.meta.id in known }, list.size)
    }

    /** Deutscher Hinweis fuer eine japanische Karte (wie tools/build_catalog.py, vereinfacht). */
    private fun germanHint(nameJa: String, dex: List<Int>, species: Map<Int, String>): String {
        val names = dex.mapNotNull { species[it] }
        if (names.isEmpty()) return ""
        val suffix = listOf("VMAX" to " VMAX", "VSTAR" to " VSTAR", "GX" to "-GX", "EX" to "-EX", "ex" to " ex", "V" to " V")
            .firstOrNull { nameJa.endsWith(it.first) }?.second.orEmpty()
        return names.joinToString(" & ") + suffix
    }

    private fun buildPayload(
        id: String,
        de: JsonObject?,
        en: JsonObject?,
        existing: Map<String, de.pokemoncarddetector.data.db.CardEntity>,
        variants: Map<String, VariantInfo>?,
    ): SetPayload? {
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
        val imageSet = imageSetIdFor(id)
        val cardList = cards.entries.sortedWith(compareBy(naturalCardOrder()) { it.key }).map { (lid, a) ->
            var img = (if (a.imgDe) 1 else 0) or (if (a.imgEn) 2 else 0)
            if (imageSet != id && img == 0) {
                // Unter-Sets: API liefert kein Bild-Feld, Bilder liegen im Haupt-Set-Ordner
                img = existing[lid]?.imageFlags?.and(3)?.takeIf { it != 0 } ?: 3
            }
            val old = existing[lid]
            val v = variants?.get(lid)
            CatalogCard(
                localId = lid,
                name = a.de ?: a.en ?: "",
                nameEn = a.en ?: a.de ?: "",
                flags = img or (if (a.de != null) 4 else 0),
                variants = v?.keys?.takeIf { it.isNotEmpty() } ?: old?.variants?.let { Variants.list(it) }.orEmpty(),
                hp = v?.hp ?: old?.hp ?: 0,
                regulationMark = v?.regulationMark ?: old?.regulationMark.orEmpty(),
                rarity = v?.rarity?.takeIf { it.isNotBlank() } ?: old?.rarity.orEmpty(),
            )
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
            img = imageSet,
        )
        return SetPayload(meta, cardList)
    }

    private fun get(path: String): JsonElement {
        val req = Request.Builder().url("$API/$path").header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, path)
            val body = resp.body?.string() ?: throw IOException("Leere Antwort bei $path")
            return json.parseToJsonElement(body)
        }
    }

    data class VariantInfo(val hp: Int, val regulationMark: String, val keys: List<String>, val rarity: String = "")

    /** Varianten aller Karten eines Sets ueber die GraphQL-Schnittstelle (je 100 Karten pro Seite). */
    private suspend fun fetchVariants(setId: String): Map<String, VariantInfo> {
        val out = HashMap<String, VariantInfo>()
        var page = 1
        while (true) {
            val query = "{ cards(filters: {id: \"$setId-\"}, pagination: {page: $page, count: 100}) " +
                "{ id hp rarity regulationMark variants_detailed { type subtype foil stamp size } } }"
            val body = buildJsonObject { put("query", query) }.toString()
            val json = postWithRetry("$API/graphql", body)
            val cards = (json["data"] as? JsonObject)?.get("cards") as? JsonArray ?: break
            for (el in cards) {
                val c = el.jsonObject
                val id = c.str("id") ?: continue
                if (!id.startsWith("$setId-")) continue
                val keys = (c["variants_detailed"] as? JsonArray).orEmpty().mapNotNull { v ->
                    val o = v as? JsonObject ?: return@mapNotNull null
                    variantKey(o)
                }.distinct()
                out[id.removePrefix("$setId-")] = VariantInfo(
                    hp = (c["hp"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0,
                    regulationMark = c.str("regulationMark").orEmpty(),
                    keys = keys,
                    rarity = c.str("rarity")?.takeIf { it != "None" }.orEmpty(),
                )
            }
            if (cards.size < 100) break
            page++
        }
        return out
    }

    /** Gleicher Schluessel wie tools/build_catalog.py: "reverse|foil=pokeball" (auch fuer japanische Antworten) */
    private fun variantKey(v: JsonObject): String = de.pokemoncarddetector.data.prices.PriceParser.variantKey(v)

    private suspend fun postWithRetry(url: String, body: String): JsonObject {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                    if (resp.code != 429 && resp.code < 500) throw HttpException(resp.code, url)
                    last = HttpException(resp.code, url)
                }
            } catch (e: HttpException) {
                if (e.code != 429 && e.code < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            kotlinx.coroutines.delay(1000L * (attempt + 1) * (attempt + 1))
        }
        throw last ?: IOException("Fehler bei $url")
    }

    /** Wiederholt bei Netzwerkfehlern, 429 und 5xx mit wachsender Wartezeit. */
    private suspend fun getObjectWithRetry(path: String): JsonObject {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return getObject(path)
            } catch (e: HttpException) {
                if (e.code != 429 && e.code < 500) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            kotlinx.coroutines.delay(1000L * (attempt + 1) * (attempt + 1))
        }
        throw last ?: IOException("Fehler bei $path")
    }

    private class HttpException(val code: Int, path: String) : IOException("HTTP $code bei $path")

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
