package de.pokemoncarddetector.data.catalog

import de.pokemoncarddetector.data.ImageLanguage
import de.pokemoncarddetector.data.ImageUrls
import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.SetEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Japanischer Online-Abgleich gegen echte TCGdex-Antworten (test/resources/tcgdex/ja, gespeichert am 2026-11-19
 * von https://api.tcgdex.net/v2/ja/..., bei Kartendetails nur die benoetigten Felder) und den mitgelieferten
 * assets/catalog_ja.json. Wird der Katalog neu gebaut, sets.json ebenfalls neu speichern.
 */
class JapaneseCatalogSyncTest {

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private lateinit var known: Map<String, SetEntity>
        private lateinit var storedCards: Map<String, List<CardEntity>>
        private lateinit var species: Map<Int, String>

        private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }
        fun fixture(name: String): String =
            JapaneseCatalogSyncTest::class.java.getResource("/tcgdex/ja/$name")?.readText() ?: error("Fixture $name fehlt")

        @BeforeClass
        @JvmStatic
        fun load() {
            val catalog = json.decodeFromString(CatalogFile.serializer(), asset("catalog_ja.json").readText())
            storedCards = catalog.sets.associate { s -> s.id to s.parsedCards(catalog.variants).mapIndexed { i, c -> c.toEntity(s.id, i) } }
            known = catalog.sets.associate { s -> s.id to s.toEntity(storedCards.getValue(s.id).size) }
            species = (json.parseToJsonElement(asset("species_de.json").readText()) as JsonObject)
                .entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.content }
        }

        private fun liveList(): List<JsonObject> = json.parseToJsonElement(fixture("sets.json")).jsonArray.map { it.jsonObject }

        /** Setliste mit geaenderter Gesamtzahl einzelner Sets. */
        private fun listWithTotals(vararg totals: Pair<String, Int>): String = JsonArray(
            liveList().map { o ->
                val t = totals.toMap()[o["id"]!!.jsonPrimitive.content] ?: return@map o
                JsonObject(o + ("cardCount" to JsonObject(mapOf("total" to JsonPrimitive(t), "official" to JsonPrimitive(t)))))
            }
        ).toString()

        private val web1Cards: Map<String, JsonObject> by lazy {
            json.parseToJsonElement(fixture("cards_web1.json")).jsonObject.mapValues { it.value.jsonObject }
        }
    }

    /** Nachgestellte TCGdex-API + Bildserver. */
    private class FakeTcgdex : Dispatcher() {
        val bodies = ConcurrentHashMap<String, String>()
        val codes = ConcurrentHashMap<String, Int>()
        val images = ConcurrentHashMap.newKeySet<String>()
        val requests = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path!!
            requests += "${request.method} $path"
            if (request.method == "HEAD") return MockResponse().setResponseCode(if (path in images) 200 else 404)
            codes[path]?.let { return MockResponse().setResponseCode(it) }
            val body = bodies[path] ?: return MockResponse().setResponseCode(404)
            return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }

        fun gets(prefix: String) = requests.filter { it.startsWith("GET $prefix") }
        fun heads() = requests.filter { it.startsWith("HEAD ") }
    }

    private lateinit var server: MockWebServer
    private lateinit var api: FakeTcgdex

    @Before
    fun startServer() {
        api = FakeTcgdex()
        server = MockWebServer()
        server.dispatcher = api
        server.start()
        api.bodies["/v2/ja/sets"] = fixture("sets.json")
        api.bodies["/v2/ja/series"] = fixture("series.json")
        api.bodies["/v2/ja/sets/web1"] = fixture("set_web1.json")
        api.bodies["/v2/ja/sets/WCS23"] = fixture("set_WCS23.json")
        api.bodies["/v2/ja/sets/SM1%2B"] = fixture("set_SM1plus.json")
        api.bodies["/v2/ja/cards/WCS23-001"] = fixture("card_WCS23-001.json")
        for ((id, card) in web1Cards) api.bodies["/v2/ja/cards/$id"] = card.toString()
    }

    @After
    fun stopServer() = server.shutdown()

    private fun runSync(knownSets: Map<String, SetEntity> = known): JapaneseCatalogSync.Outcome = runBlocking {
        JapaneseCatalogSync(
            client = OkHttpClient(),
            germanSpecies = { species },
            apiBase = server.url("/v2/"),
            assetsBase = server.url("/assets/"),
            retryDelayMs = 0,
            now = { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse("2026-11-19")!!.time },
        ).run(knownSets, { storedCards[it].orEmpty() })
    }

    private fun stored(setId: String) = storedCards.getValue(setId).associateBy { it.localId }

    @Test
    fun liveListeGegenMitgeliefertenKatalogLaedtNichts() {
        // web1 meldet 48 Karten, listet aber 47; WCS23 meldet 30, listet 1 – frueher bei jedem Abgleich neu geladen
        val web1 = known.getValue("ja:web1")
        assertEquals(48, web1.totalCount)
        assertEquals(47, web1.cardCount)
        assertEquals(1, known.getValue("ja:WCS23").cardCount)
        assertEquals(emptyList<String>(), japaneseSetsToFetch(liveList(), known))

        val out = runSync()
        assertEquals(emptyList<String>(), out.fetched)
        assertEquals(0, out.newSets)
        assertEquals(0, out.updatedSets)
        assertEquals(0, out.failedSets)
        assertTrue(out.payloads.isEmpty())
        // 204 Sets in der Liste, davon 15 vereinfachtes Chinesisch
        assertEquals(189, out.totalSets)
        assertEquals(listOf("GET /v2/ja/sets"), api.gets("/v2/"))
        // Bilder nachtragen: hoechstens eine HEAD-Anfrage pro neuerem Set ohne alle Bilder
        val heads = api.heads()
        assertEquals(heads.toString(), heads.map { it.split('/')[4] }.distinct().size, heads.size)
    }

    @Test
    fun chinesischeSetsWerdenUebersprungen() {
        assertFalse(isJapaneseSetId("CS4b"))
        assertFalse(isJapaneseSetId("CS3.5"))
        assertTrue(isJapaneseSetId("SV2a"))
        val ids = japaneseSetsToFetch(liveList(), emptyMap())
        assertTrue(ids.none { it.startsWith("CS") })
        assertEquals(189, ids.size)
        assertTrue("web1" in ids && "SM1+" in ids)
    }

    @Test
    fun aenderungNurBeiNeuerGesamtzahl() {
        val k = known.getValue("ja:web1")
        fun list(total: Int) = listOf(JsonObject(mapOf("id" to JsonPrimitive("web1"), "cardCount" to JsonObject(mapOf("total" to JsonPrimitive(total))))))
        assertEquals(emptyList<String>(), japaneseSetsToFetch(list(48), known)) // = gespeicherte Gesamtzahl
        assertEquals(emptyList<String>(), japaneseSetsToFetch(list(47), known)) // nicht mehr als gespeichert
        assertEquals(listOf("web1"), japaneseSetsToFetch(list(49), known))
        assertEquals(47, k.cardCount)
        // neue Sets nur mit Karten
        val neu = listOf(JsonObject(mapOf("id" to JsonPrimitive("X1"), "cardCount" to JsonObject(mapOf("total" to JsonPrimitive(0))))))
        assertEquals(emptyList<String>(), japaneseSetsToFetch(neu, known))
    }

    @Test
    fun unveraendertesSetWirdNichtGeschrieben() {
        // Echte Antworten fuer web1 ergeben genau die mitgelieferten Zeilen -> keine Aenderung
        val detail = json.parseToJsonElement(fixture("set_web1.json")).jsonObject
        val details = web1Cards.values.associateBy { it["localId"]!!.jsonPrimitive.content }
        val payload = buildJapanesePayload("web1", "web", detail, details, stored("ja:web1"), emptySet(), species)
        val diff = payload.cards.filter { c ->
            val s = stored("ja:web1").getValue(c.localId)
            c.name != s.name || c.nameEn != s.nameEn || c.flags != s.imageFlags || c.variants.joinToString(";") != s.variants ||
                c.hp != s.hp || c.rarity != s.rarity || c.regulationMark != s.regulationMark
        }
        assertEquals(emptyList<CatalogCard>(), diff)
        assertFalse(japaneseSetChanged(known.getValue("ja:web1"), payload, stored("ja:web1")))

        // einzelne Feldaenderungen werden erkannt
        val hp = payload.copy(cards = payload.cards.map { if (it.localId == "001") it.copy(hp = 80) else it })
        assertTrue(japaneseSetChanged(known.getValue("ja:web1"), hp, stored("ja:web1")))
        val extra = payload.copy(cards = payload.cards + CatalogCard("039", "", "", 0))
        assertTrue(japaneseSetChanged(known.getValue("ja:web1"), extra, stored("ja:web1")))
        assertTrue(japaneseSetChanged(null, payload, emptyMap()))
    }

    @Test
    fun gewachsenesAltesSetBehaeltReparierteNamen() {
        api.bodies["/v2/ja/sets"] = listWithTotals("web1" to 49)
        val out = runSync()
        assertEquals(listOf("web1"), out.fetched)
        assertEquals(47, api.gets("/v2/ja/cards/web1-").size)
        assertEquals(0, out.failedSets)
        // nur die Gesamtzahl hat sich geaendert -> einmal schreiben, damit es beim naechsten Mal nicht erneut geladen wird
        assertEquals(1, out.updatedSets)
        val p = out.payloads.single()
        assertEquals(49, p.meta.total)
        val names = p.cards.associate { it.localId to it.name }
        assertEquals("フシギソウ", names["001"]) // API: 'アイビサウルス'
        assertEquals("タマタマ", names["004"]) // API: 'exeggcute'
        assertEquals("ガーディ", names["006"]) // API: 'うなり声'
        assertEquals("ロコン", names["008"]) // API: 'Vulpix'
        assertEquals("カメール", names["009"]) // API: 'wartortle'
        assertEquals("", names["016"]) // API: '新しいpokedex', im Katalog bewusst leer
        assertEquals(stored("ja:web1").mapValues { it.value.name }, names)
        assertEquals(listOf("ja:web"), out.series.map { it.id })
        assertEquals("web", out.series.single().name)

        // nach dem Speichern (Gesamtzahl 49) wird web1 beim naechsten Abgleich nicht wieder geladen
        val again = runSync(known + ("ja:web1" to p.meta.toEntity(p.cards.size)))
        assertEquals(emptyList<String>(), again.fetched)
        assertEquals(0, again.updatedSets)
        assertTrue(again.payloads.isEmpty())
    }

    @Test
    fun neueKarteAlterSerieOhneMaschinenNamen() {
        // 004 ('exeggcute') und 016 ('新しいpokedex') fehlen in der Datenbank -> neue Karten
        api.bodies["/v2/ja/sets"] = listWithTotals("web1" to 49)
        val without = stored("ja:web1") - setOf("004", "016", "030")
        val out = runBlocking {
            JapaneseCatalogSync(OkHttpClient(), { species }, server.url("/v2/"), server.url("/assets/"), retryDelayMs = 0)
                .run(known, { if (it == "ja:web1") without.values.toList() else storedCards[it].orEmpty() })
        }
        val cards = out.payloads.single().cards.associateBy { it.localId }
        assertEquals("", cards.getValue("004").name)
        assertEquals("Owei", cards.getValue("004").nameEn) // deutscher Hinweis ueber die Pokedex-Nr.
        assertEquals("", cards.getValue("016").name)
        assertEquals("", cards.getValue("030").name) // 'Max Revive'
        assertEquals(47, out.payloads.single().cards.size)
    }

    @Test
    fun namensRegeln() {
        // gespeicherte Namen bleiben immer
        assertEquals("フシギソウ", japaneseCardName("フシギソウ", "アイビサウルス", "web", true))
        assertEquals("リザードンex", japaneseCardName("リザードンex", "Charizard ex", "SV", true))
        // neue Karten alter Serien: lateinische Woerter -> leer
        assertEquals("", japaneseCardName(null, "exeggcute", "web", true))
        assertEquals("", japaneseCardName("", "暗いweezing", "ja:web", true))
        assertEquals("", japaneseCardName(null, "Crobat Ex", "PCG", true))
        assertEquals("", japaneseCardName(null, "MUK -004/092", "e", true))
        assertEquals("", japaneseCardName(null, "Max Revive", "web", false))
        assertEquals("", japaneseCardName(null, "新しいpokedex", "neo", false))
        assertEquals("", japaneseCardName(null, "exp。共有", "VS", false))
        // echte Namen bleiben
        assertEquals("クロバットex", japaneseCardName(null, "クロバットex", "PCG", true))
        assertEquals("ナツメのESP", japaneseCardName(null, "ナツメのESP", "PMCG", false))
        assertEquals("アンノーンA", japaneseCardName(null, "アンノーンA", "neo", true))
        // neuere Serien: API-Name unveraendert
        assertEquals("Pikachu", japaneseCardName(null, "Pikachu", "SV", true))
    }

    @Test
    fun namensRegelLaesstReparierteKatalogNamenStehen() {
        // Keine Fehltreffer: alle (reparierten) Namen der alten Serien im Katalog wuerden auch als neue Karte bleiben
        val wrong = known.values.filter { it.serieId.removePrefix("ja:") in JA_OLD_SERIES }.flatMap { s ->
            storedCards.getValue(s.id).filter { c ->
                japaneseCardName(null, c.name, s.serieId, isPokemon = c.nameEn.isNotBlank()) != c.name
            }.map { "${it.id} ${it.name}" }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun neuesSetBekommtBilderPerHead() {
        api.images += "/assets/ja/SV/WCS23/001/low.webp"
        val out = runSync(known - "ja:WCS23")
        assertEquals(listOf("WCS23"), out.fetched)
        assertEquals(1, out.newSets)
        assertEquals(0, out.updatedSets)
        val p = out.payloads.single()
        assertEquals("ja:WCS23", p.meta.id)
        assertEquals("ja:SV", p.meta.serie)
        assertEquals("WCS23", p.meta.abbr)
        assertEquals(30, p.meta.official)
        assertEquals(30, p.meta.total)
        val c = p.cards.single()
        assertEquals(CatalogCard("001", "ピカチュウex", "Pikachu ex", 16, listOf("normal"), 0, "G", "Special illustration rare"), c)
        assertEquals(listOf(CatalogSerie("ja:SV", "ポケモンカードゲーム スカーレット&バイオレット", "ポケモンカードゲーム スカーレット&バイオレット")), out.series)
        // gleiche Zeile wie im mitgelieferten Katalog (bis auf das dort fehlende Bild)
        val bundled = stored("ja:WCS23").getValue("001")
        assertEquals(bundled.name, c.name)
        assertEquals(bundled.nameEn, c.nameEn)
        assertEquals(bundled.variants, c.variants.joinToString(";"))
        assertTrue(api.heads().contains("HEAD /assets/ja/SV/WCS23/001/low.webp"))
    }

    @Test
    fun bildBitBleibtUndOhneTrefferKeinBild() {
        val card = JsonObject(mapOf("id" to JsonPrimitive("X-001"), "localId" to JsonPrimitive("001"), "name" to JsonPrimitive("ピカチュウ")))
        val withImage = stored("ja:M4").getValue("001")
        assertEquals(16, withImage.imageFlags and 16)
        assertFalse(needsImageProbe(card, withImage))
        assertTrue(needsImageProbe(card, withImage.copy(imageFlags = 0)))
        assertTrue(needsImageProbe(card, null))
        assertFalse(needsImageProbe(JsonObject(card + ("image" to JsonPrimitive("https://x"))), null))

        val detail = JsonObject(mapOf("cards" to JsonArray(listOf(card)), "cardCount" to JsonObject(mapOf("total" to JsonPrimitive(1)))))
        val kept = buildJapanesePayload("M4", "M", detail, emptyMap(), mapOf("001" to withImage), emptySet(), species)
        assertEquals(16, kept.cards.single().flags and 16)
        val none = buildJapanesePayload("X", "M", detail, emptyMap(), emptyMap(), emptySet(), species)
        assertEquals(0, none.cards.single().flags)
        val probed = buildJapanesePayload("X", "M", detail, emptyMap(), emptyMap(), setOf("001"), species)
        assertEquals(16, probed.cards.single().flags)
    }

    @Test
    fun bildUrlWieInDerApp() {
        assertEquals(
            ImageUrls.card("ja:SV", "SV2a", "006", 16, ImageUrls.Quality.LOW, ImageLanguage.GERMAN),
            japaneseImageUrl(JapaneseCatalogSync.ASSETS_BASE, "SV", "SV2a", "006").toString(),
        )
    }

    @Test
    fun plusInSetIdWirdKodiert() {
        // OkHttp laesst '+' in addPathSegment stehen, TCGdex liest es als Leerzeichen ('/ja/sets/SM1 ' -> 404)
        assertTrue(JapaneseCatalogSync.API_BASE.newBuilder().addPathSegment("SM1+").build().encodedPath.endsWith("/SM1+"))
        assertEquals("https://api.tcgdex.net/v2/ja/sets/SM1%2B", japaneseApiUrl(JapaneseCatalogSync.API_BASE, "ja", "sets", "SM1+").toString())
        assertEquals("https://api.tcgdex.net/v2/ja/cards/sm2%2B-001", japaneseApiUrl(JapaneseCatalogSync.API_BASE, "ja", "cards", "sm2+-001").toString())
        assertEquals("https://api.tcgdex.net/v2/ja/sets/SV4a", japaneseApiUrl(JapaneseCatalogSync.API_BASE, "ja", "sets", "SV4a").toString())
        assertEquals("CS3.5", encodePathSegment("CS3.5"))

        // SM1+ hat keine Kartenliste: Platzhalter bleiben, nur die neue Gesamtzahl wird gespeichert
        api.bodies["/v2/ja/sets"] = listWithTotals("SM1+" to 60)
        val out = runSync()
        assertEquals(listOf("SM1+"), out.fetched)
        assertTrue(api.requests.toString(), "GET /v2/ja/sets/SM1%2B" in api.requests)
        val p = out.payloads.single()
        assertEquals(60, p.meta.total)
        assertEquals(stored("ja:SM1+").keys, p.cards.map { it.localId }.toSet())
        assertEquals(1, out.updatedSets)
    }

    @Test
    fun fehlerZaehlenAlsFehlgeschlageneSets() {
        api.bodies["/v2/ja/sets"] = listWithTotals("web1" to 49)
        api.codes["/v2/ja/sets/web1"] = 500
        val noKnownWcs = known - "ja:WCS23"
        val out = runSync(noKnownWcs)
        assertEquals(listOf("web1", "WCS23"), out.fetched)
        assertEquals(1, out.failedSets)
        assertEquals(listOf("ja:WCS23"), out.payloads.map { it.meta.id })
        assertEquals(4, api.gets("/v2/ja/sets/web1").size) // mit Wiederholungen

        // eine Karte mit Serverfehler -> Set nicht speichern, als fehlgeschlagen zaehlen
        api.codes.remove("/v2/ja/sets/web1")
        api.codes["/v2/ja/cards/web1-005"] = 503
        val cardFail = runSync()
        assertEquals(1, cardFail.failedSets)
        assertTrue(cardFail.payloads.isEmpty())

        // eine dauerhaft fehlende Karte (404) ist kein Fehler: gespeicherte Werte bleiben
        api.codes["/v2/ja/cards/web1-005"] = 404
        val missing = runSync()
        assertEquals(0, missing.failedSets)
        val c005 = missing.payloads.single().cards.single { it.localId == "005" }
        val s005 = stored("ja:web1").getValue("005")
        assertEquals(s005.hp, c005.hp)
        assertEquals(s005.variants, c005.variants.joinToString(";"))
    }

    @Test
    fun bilderNeuererSetsWerdenNachgetragen() {
        // M5 (2026-05-22) hat im Katalog keine Bilder; jetzt liegen 001 und 003 auf dem Bildserver
        api.images += "/assets/ja/M/M5/001/low.webp"
        api.images += "/assets/ja/M/M5/003/low.webp"
        val out = runSync()
        assertEquals(emptyList<String>(), out.fetched)
        assertEquals(1, out.updatedSets)
        val p = out.payloads.single()
        assertEquals("ja:M5", p.meta.id)
        assertEquals(known.getValue("ja:M5").totalCount, p.meta.total)
        assertEquals(storedCards.getValue("ja:M5").size, p.cards.size)
        assertEquals(setOf("001", "003"), p.cards.filter { it.flags and 16 != 0 }.map { it.localId }.toSet())
        // M5 komplett geprueft, alle anderen Sets nur mit einer Stichprobe
        val m5 = api.heads().count { it.startsWith("HEAD /assets/ja/M/M5/") }
        assertEquals(storedCards.getValue("ja:M5").count { it.imageFlags and 16 == 0 }, m5)
        assertEquals(listOf("ja:M"), out.series.map { it.id })
    }
}
