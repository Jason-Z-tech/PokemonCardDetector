package de.pokemoncarddetector.data.catalog

import de.pokemoncarddetector.data.Variants
import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.SetEntity
import de.pokemoncarddetector.data.prices.PriceParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Online-Abgleich der japanischen Sets (Gegenstueck zu build_japanese in tools/build_catalog.py).
 *
 * - Geladen werden neue Sets und bekannte Sets, deren Gesamtzahl laut Liste gewachsen ist
 *   (gleiche Regel wie international: Gesamtzahl geaendert UND groesser als die gespeicherten Karten).
 * - Gespeichert und als "aktualisiert" gezaehlt wird ein bekanntes Set nur, wenn sich wirklich etwas aendert.
 * - Gespeicherte Kartennamen werden nie durch API-Namen ersetzt: die alten Sets sind bei TCGdex
 *   maschinell uebersetzt ('exeggcute', 'おしっこ'), der mitgelieferte Katalog hat die reparierten Namen.
 * - Sets mit "CS" am Anfang sind vereinfachtes Chinesisch und werden uebersprungen.
 * - Fehlt das Feld "image", wird der Bildserver per HEAD gefragt (wie das Build-Skript).
 */
internal class JapaneseCatalogSync(
    private val client: OkHttpClient,
    private val germanSpecies: () -> Map<Int, String> = { emptyMap() },
    private val apiBase: HttpUrl = API_BASE,
    private val assetsBase: HttpUrl = ASSETS_BASE,
    /** Grundwartezeit zwischen Wiederholungen (Tests: 0) */
    private val retryDelayMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(
        /** Serien der zu speichernden Sets */
        val series: List<CatalogSerie>,
        /** Nur neue oder wirklich geaenderte Sets */
        val payloads: List<SetPayload>,
        val newSets: Int,
        val updatedSets: Int,
        /** Japanische Sets laut Liste (ohne CS*) */
        val totalSets: Int,
        /** Sets, deren Details oder Karten nicht geladen werden konnten */
        val failedSets: Int,
        /** Set-IDs (ohne "ja:"), die laut Liste nachgeladen wurden */
        val fetched: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val apiPermits = Semaphore(6)
    private val headPermits = Semaphore(MAX_IMAGE_PROBES)

    /**
     * @param known alle gespeicherten Sets (IDs mit "ja:"-Praefix fuer japanische)
     * @param storedCards gespeicherte Karten eines Sets (ID mit "ja:"-Praefix)
     */
    suspend fun run(
        known: Map<String, SetEntity>,
        storedCards: suspend (String) -> List<CardEntity>,
        onStatus: (String, Float?) -> Unit = { _, _ -> },
    ): Outcome = withContext(Dispatchers.IO) {
        val list = getJson(api("ja", "sets")).jsonArray.mapNotNull { it as? JsonObject }
            .filter { o -> o.str("id")?.let(::isJapaneseSetId) == true }
        val toFetch = japaneseSetsToFetch(list, known)
        val listTotals = list.associate { it.str("id").orEmpty() to it.cardTotal() }
        val species by lazy { germanSpecies() }
        val payloads = mutableListOf<SetPayload>()
        var failed = 0
        toFetch.forEachIndexed { i, sid ->
            onStatus("Japanische Sets werden geladen … (${i + 1}/${toFetch.size})", (i + 1f) / toFetch.size)
            val k = known["ja:$sid"]
            try {
                val stored = if (k != null) storedCards(k.id).associateBy { it.localId } else emptyMap()
                val payload = fetchSet(sid, listTotals[sid] ?: 0, k, stored, species)
                if (payload != null && japaneseSetChanged(k, payload, stored)) payloads += payload
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
            }
        }
        payloads += backfillImages(list, known, toFetch.toSet(), storedCards, onStatus)
        Outcome(
            series = if (payloads.isEmpty()) emptyList() else seriesFor(payloads, known),
            payloads = payloads,
            newSets = payloads.count { it.meta.id !in known },
            updatedSets = payloads.count { it.meta.id in known },
            totalSets = list.size,
            failedSets = failed,
            fetched = toFetch,
        )
    }

    /** Laedt ein Set mit allen Kartendetails. null = nichts zu tun (keine Kartenliste, nichts gespeichert). */
    private suspend fun fetchSet(
        sid: String,
        listTotal: Int,
        k: SetEntity?,
        stored: Map<String, CardEntity>,
        species: Map<Int, String>,
    ): SetPayload? =
        coroutineScope {
            val detail = getJson(api("ja", "sets", sid)).jsonObject
            val serie = (detail["serie"] as? JsonObject)?.str("id") ?: k?.serieId?.removePrefix("ja:")
                ?: return@coroutineScope null
            val cards = detail.cardList()
            if (cards.isEmpty() && stored.isEmpty()) return@coroutineScope null
            // Ein Fehler bei einer Karte (Netz, 5xx) bricht das ganze Set ab -> wird als fehlgeschlagen gezaehlt
            val details = cards.map { c ->
                async {
                    val lid = c.str("localId")!!
                    apiPermits.withPermit { lid to fetchCardOrNull(c.str("id") ?: "$sid-$lid") }
                }
            }.awaitAll().toMap()
            val toProbe = cards.filter { needsImageProbe(it, stored[it.str("localId")]) }.mapNotNull { it.str("localId") }
            val found = probeImages(serie, sid, toProbe)
            buildJapanesePayload(sid, serie, detail, details, stored, found, species, listTotal)
        }

    /** Kartendetails; null, wenn die Karte dauerhaft fehlt (404 o.ae.). Netzfehler/5xx werfen. */
    private suspend fun fetchCardOrNull(cardId: String): JsonObject? = try {
        getJson(api("ja", "cards", cardId)) as? JsonObject
    } catch (e: HttpStatusException) {
        if (retryable(e.code)) throw e else null
    }

    /**
     * Neuere Sets (2 Jahre), die nicht nachgeladen wurden, aber noch Karten ohne Bild haben:
     * erst nur das erste fehlende Bild pruefen (eine Anfrage pro Set), nur bei einem Treffer alle.
     */
    private suspend fun backfillImages(
        list: List<JsonObject>,
        known: Map<String, SetEntity>,
        fetched: Set<String>,
        storedCards: suspend (String) -> List<CardEntity>,
        onStatus: (String, Float?) -> Unit,
    ): List<SetPayload> {
        val since = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(now() - 2L * 365 * 24 * 3600 * 1000))
        val listed = list.mapNotNull { it.str("id") }.toSet()
        val candidates = known.values.filter { k ->
            val sid = k.id.removePrefix("ja:")
            k.id.startsWith("ja:") && sid in listed && sid !in fetched && k.cardCount > 0 && k.releaseDate >= since
        }.sortedByDescending { it.releaseDate }
        val out = mutableListOf<SetPayload>()
        for (k in candidates) {
            try {
                val cards = storedCards(k.id)
                val missing = cards.filter { it.imageFlags and 16 == 0 }.sortedBy { it.sortIndex }
                if (missing.isEmpty()) continue
                val serie = k.serieId.removePrefix("ja:")
                val folder = k.imageSetId.ifBlank { k.id }.removePrefix("ja:")
                if (head(japaneseImageUrl(assetsBase, serie, folder, missing.first().localId)) != true) continue
                onStatus("Japanische Kartenbilder werden geprüft …", null)
                val found = setOf(missing.first().localId) + probeImages(serie, folder, missing.drop(1).map { it.localId })
                out += imageBackfillPayload(k, cards, found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nur ein Zusatz: ohne Bildpruefung bleibt das Set wie es ist
            }
        }
        return out
    }

    /** HEAD-Anfragen (hoechstens [MAX_IMAGE_PROBES] gleichzeitig) -> localIds mit Bild. */
    private suspend fun probeImages(serie: String, sid: String, localIds: List<String>): Set<String> = coroutineScope {
        localIds.map { lid ->
            async { headPermits.withPermit { lid.takeIf { head(japaneseImageUrl(assetsBase, serie, sid, lid)) == true } } }
        }.awaitAll().filterNotNull().toSet()
    }

    private suspend fun seriesFor(payloads: List<SetPayload>, known: Map<String, SetEntity>): List<CatalogSerie> {
        val ids = payloads.map { it.meta.serie }.distinct()
        val names = try {
            getJson(api("ja", "series")).jsonArray.mapNotNull { it as? JsonObject }
                .associate { (it.str("id") ?: "") to (it.str("name") ?: "") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ohne Serienliste nur unbekannte Serien anlegen, vorhandene Namen nicht durch Kuerzel ersetzen
            val knownSeries = known.values.map { it.serieId }.toSet()
            return ids.filter { it !in knownSeries }.map { CatalogSerie(it, it.removePrefix("ja:"), it.removePrefix("ja:")) }
        }
        return ids.map { id ->
            val name = names[id.removePrefix("ja:")]?.takeIf { it.isNotBlank() } ?: id.removePrefix("ja:")
            CatalogSerie(id, name, name)
        }
    }

    private fun api(vararg segments: String): HttpUrl = japaneseApiUrl(apiBase, *segments)

    /** GET mit Wiederholung bei Netzwerkfehlern, 408, 429 und 5xx. */
    private suspend fun getJson(url: HttpUrl): JsonElement {
        var last: IOException? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(url).header("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: throw IOException("Leere Antwort bei $url")
                        return json.parseToJsonElement(body)
                    }
                    throw HttpStatusException(resp.code, url)
                }
            } catch (e: HttpStatusException) {
                if (!retryable(e.code)) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3) delay(retryDelayMs * (attempt + 1) * (attempt + 1))
        }
        throw last ?: IOException("Fehler bei $url")
    }

    /** true = Bild da (HTTP 200), false = fehlt, null = unbekannt (Server-/Netzfehler). */
    private suspend fun head(url: HttpUrl): Boolean? {
        repeat(3) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                    if (resp.code == 200) return true
                    if (!retryable(resp.code)) return false
                }
            } catch (e: IOException) {
                // erneut versuchen
            }
            if (attempt < 2) delay(retryDelayMs * (attempt + 1))
        }
        return null
    }

    private class HttpStatusException(val code: Int, url: HttpUrl) : IOException("HTTP $code bei $url")

    companion object {
        val API_BASE = "https://api.tcgdex.net/v2/".toHttpUrl()
        val ASSETS_BASE = "https://assets.tcgdex.net/".toHttpUrl()
        const val MAX_IMAGE_PROBES = 8

        private fun retryable(code: Int) = code == 408 || code == 429 || code >= 500
    }
}

/** Bis 2007: bei TCGdex viele maschinell uebersetzte Namen (wie JA_OLD_SERIES in tools/build_catalog.py). */
internal val JA_OLD_SERIES = setOf("PMCG", "neo", "VS", "web", "e", "ADV", "PCG")

/** Bei TCGdex unter "ja" einsortiert, aber vereinfachtes Chinesisch (CS1a, CS4b ...). */
internal fun isJapaneseSetId(id: String): Boolean = id.isNotBlank() && !id.startsWith("CS")

/**
 * Welche Sets der Liste geladen werden muessen: neue Sets mit Karten und bekannte Sets, deren
 * Gesamtzahl sich geaendert hat und mehr Karten verspricht, als gespeichert sind.
 * (Gespeichert ist als Gesamtzahl max(API-Gesamtzahl, Kartenzeilen), siehe build_catalog.py –
 * web1 meldet 48 bei 47 gelisteten Karten und wird deshalb nicht bei jedem Abgleich neu geladen.)
 */
internal fun japaneseSetsToFetch(list: List<JsonObject>, known: Map<String, SetEntity>): List<String> =
    list.mapNotNull { o ->
        val id = o.str("id")?.takeIf(::isJapaneseSetId) ?: return@mapNotNull null
        val total = o.cardTotal()
        val k = known["ja:$id"] ?: return@mapNotNull id.takeIf { total > 0 }
        id.takeIf { total != k.totalCount && total > k.cardCount }
    }.distinct()

private val OLD_ASCII_WORD = Regex("(?:[A-Za-z][^A-Za-z]*){3}") // 'Koffing', 'MUK', 'quagsire'
private val OLD_TRAINER_WORD = Regex("[A-Za-z]*[a-z][A-Za-z]*") // 'Max Revive', 'exp。共有' (nicht 'ESP')
private val OLD_NUMBER_FRAGMENT = Regex("-?\\s*\\d{3}/\\d{3}") // 'MUK -004/092'

/**
 * Name einer japanischen Karte beim Abgleich: ein gespeicherter Name bleibt immer.
 * Neue Karten (oder leere Namen) alter Serien bekommen keinen API-Namen mit lateinischen Woertern
 * ('exeggcute', '暗いweezing', 'Max Revive') – dann zeigt die App den deutschen Hinweis bzw. "Karte Nr. X".
 */
internal fun japaneseCardName(stored: String?, apiName: String, serie: String, isPokemon: Boolean): String {
    stored?.takeIf { it.isNotBlank() }?.let { return it }
    if (serie.removePrefix("ja:") !in JA_OLD_SERIES) return apiName
    val norm = Normalizer.normalize(apiName, Normalizer.Form.NFKC)
    val garbage = OLD_NUMBER_FRAGMENT.containsMatchIn(norm) || if (isPokemon) {
        OLD_ASCII_WORD.containsMatchIn(norm)
    } else {
        OLD_TRAINER_WORD.findAll(norm).any { it.value.length >= 3 }
    }
    return if (garbage) "" else apiName
}

/** Bild per HEAD pruefen, wenn die API kein "image" liefert und die Karte noch kein Bild-Bit hat. */
internal fun needsImageProbe(card: JsonObject, stored: CardEntity?): Boolean =
    !card.containsKey("image") && (stored?.imageFlags ?: 0) and 16 == 0

/**
 * Baut die Zeilen eines japanischen Sets wie tools/build_catalog.py. Gespeicherte Werte bleiben,
 * wo die API nichts liefert; gespeicherte Karten, die die API nicht (mehr) listet, bleiben erhalten
 * (z.B. Platzhalter aus den Bildern), damit die Kartenanzahl des Sets stimmt.
 *
 * @param cardDetails localId -> /ja/cards/{id} (null = nicht vorhanden)
 * @param imagesFound localIds, deren Bild der Bildserver hat (HEAD 200)
 * @param listTotal Gesamtzahl laut Setliste (0 = unbekannt, dann die der Set-Details)
 */
internal fun buildJapanesePayload(
    sid: String,
    serie: String,
    detail: JsonObject,
    cardDetails: Map<String, JsonObject?>,
    stored: Map<String, CardEntity>,
    imagesFound: Set<String>,
    species: Map<Int, String>,
    listTotal: Int = 0,
): SetPayload {
    val rows = LinkedHashMap<String, CatalogCard>()
    for (c in detail.cardList()) {
        val lid = c.str("localId")!!
        if (lid in rows) continue
        val cd = cardDetails[lid]
        val old = stored[lid]
        val dex = cd.dexIds()
        val apiName = c.str("name") ?: cd?.str("name") ?: ""
        val isPokemon = dex.isNotEmpty() || cd?.str("category") == "Pokemon"
        val name = japaneseCardName(old?.name, apiName, serie, isPokemon)
        val hasImage = c.containsKey("image") || lid in imagesFound
        rows[lid] = CatalogCard(
            localId = lid,
            name = name,
            nameEn = old?.nameEn?.takeIf { it.isNotBlank() } ?: japaneseGermanHint(name.ifBlank { apiName }, dex, species),
            // Bild-Bit einer gespeicherten Karte bleibt
            flags = (old?.imageFlags ?: 0) or (if (hasImage) 16 else 0),
            variants = (cd?.get("variants_detailed") as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(PriceParser::variantKey) }.distinct()
                .ifEmpty { old?.variants?.let { Variants.list(it) }.orEmpty() },
            hp = (cd?.get("hp") as? JsonPrimitive)?.intOrNull ?: old?.hp ?: 0,
            regulationMark = cd?.str("regulationMark") ?: old?.regulationMark.orEmpty(),
            rarity = cd?.str("rarity")?.takeIf { it != "None" } ?: old?.rarity.orEmpty(),
        )
    }
    for ((lid, old) in stored) if (lid !in rows) rows[lid] = old.toCatalogCard()
    val cards = rows.values.sortedWith(compareBy(naturalCardOrder()) { it.localId })
    val count = detail["cardCount"] as? JsonObject
    val name = detail.str("name") ?: sid
    return SetPayload(
        CatalogSet(
            id = "ja:$sid",
            name = name,
            nameEn = name,
            serie = "ja:$serie",
            release = detail.str("releaseDate").orEmpty(),
            abbr = sid, // japanische Karten drucken die Set-ID (z.B. 'SV2a') neben die Nummer
            official = (count?.get("official") as? JsonPrimitive)?.intOrNull ?: 0,
            // wie build_catalog.py: max(API-Gesamtzahl, Kartenzeilen). Die Zahl der Setliste hat Vorrang,
            // weil die Aenderungserkennung mit ihr vergleicht – sonst wuerde das Set bei jedem Abgleich neu geladen.
            total = maxOf(listTotal.takeIf { it > 0 } ?: detail.cardTotal(), cards.size),
            de = false,
            logo = detail.str("logo").orEmpty(),
            symbol = detail.str("symbol").orEmpty(),
            img = sid,
        ),
        cards,
    )
}

/** Wie der internationale toWrite-Filter: neues Set, geaenderte Set-Daten, neue Karten oder geaenderte Felder. */
internal fun japaneseSetChanged(k: SetEntity?, payload: SetPayload, stored: Map<String, CardEntity>): Boolean {
    if (k == null) return true
    val m = payload.meta
    if (k.name != m.name || k.nameEn != m.nameEn || k.serieId != m.serie || k.cardCount != payload.cards.size ||
        k.officialCount != m.official || k.totalCount != m.total || k.releaseDate != m.release || k.logoUrl != m.logo
    ) return true
    return payload.cards.any { c ->
        val s = stored[c.localId] ?: return@any true
        s.name != c.name || s.nameEn != c.nameEn || s.imageFlags != c.flags || s.variants != c.variants.joinToString(";") ||
            s.hp != c.hp || s.rarity != c.rarity || s.regulationMark != c.regulationMark
    }
}

/** Gespeichertes Set mit neu gefundenen Bildern (Bit 16) fuer [found]. */
internal fun imageBackfillPayload(k: SetEntity, cards: List<CardEntity>, found: Set<String>): SetPayload = SetPayload(
    CatalogSet(
        id = k.id,
        name = k.name,
        nameEn = k.nameEn,
        serie = k.serieId,
        release = k.releaseDate,
        abbr = k.abbreviation,
        official = k.officialCount,
        total = k.totalCount,
        de = k.availableInGerman,
        logo = k.logoUrl,
        img = k.imageSetId,
    ),
    cards.sortedBy { it.sortIndex }.map { e ->
        e.toCatalogCard().let { if (it.localId in found) it.copy(flags = it.flags or 16) else it }
    },
)

/** Deutscher Hinweis fuer eine japanische Karte (wie tools/build_catalog.py, vereinfacht). */
internal fun japaneseGermanHint(nameJa: String, dex: List<Int>, species: Map<Int, String>): String {
    val names = dex.mapNotNull { species[it] }
    if (names.isEmpty()) return ""
    val suffix = listOf("VMAX" to " VMAX", "VSTAR" to " VSTAR", "GX" to "-GX", "EX" to "-EX", "ex" to " ex", "V" to " V")
        .firstOrNull { nameJa.endsWith(it.first) }?.second.orEmpty()
    return names.joinToString(" & ") + suffix
}

/** API-URL aus Pfadteilen. '+' wird zu %2B (TCGdex macht aus '+' sonst ein Leerzeichen: 'SM1+' -> 404). */
internal fun japaneseApiUrl(base: HttpUrl, vararg segments: String): HttpUrl =
    base.newBuilder().apply { segments.forEach { addEncodedPathSegment(encodePathSegment(it)) } }.build()

/** Bild-URL genau wie in der App (ImageUrls.card), damit die HEAD-Pruefung dasselbe Bild meint. */
internal fun japaneseImageUrl(base: HttpUrl, serie: String, sid: String, localId: String): HttpUrl =
    base.newBuilder().addPathSegment("ja").addPathSegment(serie).addPathSegment(sid)
        .addPathSegment(localId).addPathSegment("low.webp").build()

/** Prozent-Kodierung wie Pythons quote(s, safe=''): nur A-Z a-z 0-9 - . _ ~ bleiben. */
internal fun encodePathSegment(s: String): String =
    URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

private fun CardEntity.toCatalogCard() = CatalogCard(
    localId = localId,
    name = name,
    nameEn = nameEn,
    flags = imageFlags,
    variants = Variants.list(variants),
    hp = hp,
    regulationMark = regulationMark,
    rarity = rarity,
)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.cardTotal(): Int = ((this["cardCount"] as? JsonObject)?.get("total") as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.cardList(): List<JsonObject> =
    (this["cards"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("localId") != null }

/** TCGdex liefert Formen teils als Kommazahl (384.1 = Rayquaza Delta-Spezies). */
private fun JsonObject?.dexIds(): List<Int> =
    (this?.get("dexId") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }
