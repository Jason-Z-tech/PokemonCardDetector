package de.pokemoncarddetector.recognition

import de.pokemoncarddetector.data.catalog.CatalogCard
import de.pokemoncarddetector.data.catalog.CatalogFile
import de.pokemoncarddetector.data.catalog.CatalogSet
import de.pokemoncarddetector.data.catalog.parsedCards
import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.CardItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Inhalt von assets/catalog_ja.json und species_de.json (erzeugt von tools/build_catalog.py). */
class JapaneseCatalogTest {

    companion object {
        private lateinit var sets: Map<String, CatalogSet>
        private lateinit var cards: Map<String, CatalogCard>
        private lateinit var species: JsonObject

        private fun asset(name: String) = listOf("src/main/assets/$name", "app/src/main/assets/$name").map(::File).first { it.exists() }

        @BeforeClass
        @JvmStatic
        fun load() {
            val json = Json { ignoreUnknownKeys = true }
            val catalog = json.decodeFromString(CatalogFile.serializer(), asset("catalog_ja.json").readText())
            sets = catalog.sets.associateBy { it.id }
            cards = catalog.sets.flatMap { s -> s.parsedCards(catalog.variants).map { "${s.id}-${it.localId}" to it } }.toMap()
            species = json.parseToJsonElement(asset("species_de.json").readText()) as JsonObject
        }

        private fun card(id: String) = cards[id] ?: error("$id fehlt im Katalog")
    }

    @Test
    fun bilderAuchOhneImageFeld() {
        // Die API laesst "image" fuer ganze Sets weg (S8b, SM12a, M4 ...), der Bildserver hat sie trotzdem
        for (id in listOf("ja:S8b-001", "ja:SM12a-001", "ja:M4-001", "ja:SV5M-001", "ja:SM8b-001", "ja:SV2a-006")) {
            assertTrue("$id ohne Bild", card(id).flags and 16 != 0)
        }
        val withImage = cards.values.count { it.flags and 16 != 0 }
        assertTrue("nur $withImage Karten mit Bild", withImage > 8000)
    }

    @Test
    fun maschinenNamenAlterSetsRepariert() {
        assertEquals("マタドガス", card("ja:PCG2-004").name) // war 'おしっこ'
        assertEquals("メタモン", card("ja:PCG6-007").name) // war '同上'
        assertEquals("フシギバナ", card("ja:PCG8-003").name) // war '金星'
        assertEquals("ドガース", card("ja:E1-001").name) // war 'Koffing'
        assertEquals("トゲチック", card("ja:VS1-005").name) // war 'フォークナーはトイッチです'
        assertEquals("クロバットex", card("ja:PCG2-005").name) // war 'Crobat Ex'
        assertEquals("ベトベトン", card("ja:E2-004").name) // war 'MUK -004/092'
        assertEquals("カビゴン（デルタ種）", card("ja:PCG9-001").name) // war 'Snorlax（デルタ種）'
        assertEquals("Smogmog", card("ja:PCG2-004").nameEn)
        // richtige Namen bleiben
        assertEquals("フシギダネ", card("ja:PMCG1-001").name)
        val old = sets.values.filter { s -> listOf("ja:PMCG", "ja:neo", "ja:VS", "ja:web", "ja:e", "ja:ADV", "ja:PCG").any { s.serie == it } }
        val latin = old.flatMap { s -> s.parsedCards().filter { Regex("[a-z]{3}").containsMatchIn(it.name) }.map { "${s.id}-${it.localId} ${it.name}" } }
        assertTrue(latin.toString(), latin.isEmpty())
    }

    @Test
    fun setsOhneKartenlisteSindDa() {
        for (id in listOf("ja:S4a", "ja:S6a", "ja:S8a", "ja:S10b", "ja:S1a", "ja:XY2", "ja:BW1a", "ja:ADV1", "ja:SM1+")) {
            assertTrue("$id fehlt", id in sets)
        }
        // Shiny Star V: Geheimkarten ueber der Gesamtzahl (308/190) mit Bild, Name leer
        val s4a = sets.getValue("ja:S4a")
        assertEquals(190, s4a.official)
        assertTrue(s4a.cards.size.toString(), s4a.cards.size >= 300)
        assertEquals("", card("ja:S4a-308").name)
        assertTrue(card("ja:S4a-308").flags and 16 != 0)
        // XY hat keine Bilder: Nummern 001..offiziell ohne Bild-Bit
        val xy2 = sets.getValue("ja:XY2")
        assertEquals(xy2.official, xy2.cards.size)
        assertTrue(xy2.parsedCards().all { it.flags == 0 })
        // vereinfachtes Chinesisch (CS*) ist kein japanisches Set
        assertTrue(sets.keys.none { it.startsWith("ja:CS") })
    }

    @Test
    fun deutscheHinweise() {
        assertEquals("Meganie", card("ja:SM8-005").nameEn) // メガニウム ist keine Mega-Entwicklung
        assertEquals("Meganie", card("ja:M1S-005").nameEn)
        assertEquals("Yanmega", card("ja:S11a-003").nameEn)
        assertEquals("Mega-Glurak X ex", cards.values.first { it.name == "メガリザードンXex" }.nameEn)
        assertEquals("Ofenmaske-Ogerpon ex", card("ja:MC-149").nameEn)
        assertEquals("Fokussierter-Angriff-Wulaosu V", card("ja:S5I-036").nameEn) // いちげき = Fokussierter Angriff
        assertEquals("Fließender-Angriff-Wulaosu V", card("ja:S5R-050").nameEn)
        assertEquals("Hisui-Snieboss", card("ja:S10P-043").nameEn) // keine V-Karte
        assertEquals("Morpeko V-UNION", card("ja:S8b-056").nameEn)
        assertEquals("Hisui-Arkani V", card("ja:S12-046").nameEn)
        // ohne Pokedex-Nr. bei TCGdex: ueber den Namen zugeordnet
        assertEquals("Glurak ex", card("ja:SV4a-115").nameEn)
        assertEquals("Mew ex", card("ja:SV4a-076").nameEn)
        assertEquals("Glurak V", card("ja:S9-014").nameEn)
        assertEquals("Glurak VSTAR", card("ja:S9-015").nameEn)
        val doubled = cards.entries.filter { (_, c) -> Regex("( V| ex| VMAX| VSTAR|-GX|-EX)\\1$").containsMatchIn(c.nameEn) || c.nameEn.contains("-ex ex") }
        assertTrue(doubled.map { it.key + " " + it.value.nameEn }.toString(), doubled.isEmpty())
        val missing = cards.filterValues { it.hp > 0 && it.nameEn.isBlank() }
        assertTrue("${missing.size} Pokemon ohne Hinweis: ${missing.keys.take(20)}", missing.size < 20)
    }

    @Test
    fun deutscheArtnamenFuerOnlineAbgleich() {
        assertEquals("Glurak", species["6"]?.jsonPrimitive?.content)
        assertEquals("Pikachu", species["25"]?.jsonPrimitive?.content)
        assertEquals("Ogerpon", species["1017"]?.jsonPrimitive?.content)
        assertEquals("Wulaosu", species["892"]?.jsonPrimitive?.content)
        assertTrue(species.size.toString(), species.size >= 1000)
    }

    @Test
    fun nummernDreistelligWieGedruckt() {
        fun item(localId: String, serie: String, official: Int) = CardItem(
            card = CardEntity("x-$localId", "ja:x", localId, "Name", "Name", 16, 0, "name", localId),
            quantity = 0, setName = "Set", setNameEn = "Set", serieId = serie, setOfficial = official, setRelease = "2020-01-01",
            imageSetId = "x",
        )
        assertEquals("006/165", item("006", "ja:SV", 165).numberLabel)
        assertEquals("001/184", item("001", "ja:S", 184).numberLabel)
        assertEquals("308/190", item("308", "ja:S", 190).numberLabel)
        assertEquals("001/128", item("001", "ja:e", 128).numberLabel)
        // Grundserie und neo drucken keine Nummer
        assertEquals("1/102", item("001", "ja:PMCG", 102).numberLabel)
        assertEquals("5/111", item("005", "ja:neo", 111).numberLabel)
    }
}
