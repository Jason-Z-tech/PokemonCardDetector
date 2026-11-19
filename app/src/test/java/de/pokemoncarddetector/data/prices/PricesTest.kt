package de.pokemoncarddetector.data.prices

import de.pokemoncarddetector.data.Rarity
import de.pokemoncarddetector.data.db.CollectionRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preis-Zuordnung mit echten (gekuerzten) TCGdex-Antworten aus src/test/resources/prices. */
class PricesTest {

    private fun load(name: String) = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("prices/$name.json")!!.readText()
    ).jsonObject

    private fun parse(id: String, file: String = id) =
        PriceParser.parse(id, load(file), emptyList(), canonicalKeys = true, now = 1L).associateBy { it.variant }

    @Test
    fun normalHoloReverseFarbenschock() {
        val p = parse("swsh4-25")
        // Normal = Normalpreis des Produkts, Reverse = Holo-Preis desselben Produkts, Holo = eigenes Produkt
        assertEquals(13.8, p.getValue("normal").trend!!, 0.001)
        assertEquals(3.3, p.getValue("reverse").trend!!, 0.001)
        assertEquals(17.37, p.getValue("holo|foil=cracked-ice").trend!!, 0.001)
        assertEquals(547881L, p.getValue("holo|foil=cracked-ice").idProduct)
    }

    @Test
    fun pokeballUndMeisterballReverse() {
        val p = parse("sv08.5-001")
        assertEquals(0.03, p.getValue("normal").trend!!, 0.001)
        assertEquals(0.08, p.getValue("reverse").trend!!, 0.001)
        assertEquals(0.29, p.getValue("reverse|foil=pokeball").trend!!, 0.001)
        // Meisterball: Normalpreis 0 (= keine Daten), Holo-Preis zaehlt
        assertEquals(2.21, p.getValue("reverse|foil=masterball").trend!!, 0.001)
    }

    @Test
    fun grundsetVarianten() {
        val p = parse("base1-4")
        assertTrue(p.containsKey("holo|sub=unlimited"))
        assertTrue(p.getValue("holo|sub=shadowless|stamp=1st-edition").trend!! > p.getValue("holo|sub=unlimited").trend!!)
        // Variante ohne Cardmarket-Daten bekommt keinen (falschen) Preis
        assertNull(p["holo|sub=1999-2000-copyright"])
    }

    @Test
    fun japanischeKarte() {
        val p = parse("ja:SV2a-006", "ja-SV2a-006")
        assertTrue(p.isNotEmpty())
        assertTrue(p.values.first().trend!! > 0)
    }

    @Test
    fun sammlungswertMitRueckfallAufGuenstigstenPreis() {
        val prices = parse("swsh4-25").values.toList()
        val rows = listOf(
            CollectionRow("swsh4-25", "normal", 2),      // 2 x 13,80
            CollectionRow("swsh4-25", "reverse", 1),     // 1 x 3,30
            CollectionRow("swsh4-25", "", 1),            // unbekannt -> guenstigster Preis (3,30)
            CollectionRow("xx-1", "normal", 5),          // kein Preis
        )
        val v = collectionValue(rows, prices, PriceMetric.TREND)
        assertEquals(2 * 13.8 + 3.3 + 3.3, v.totalEuro, 0.001)
        assertEquals(1, v.pricedCards)
        assertEquals(1, v.unpricedCards)
    }

    @Test
    fun preisArten() {
        val p = parse("swsh4-25").getValue("normal")
        assertEquals(13.8, p.value(PriceMetric.TREND)!!, 0.001)
        assertEquals(9.04, p.value(PriceMetric.AVG30)!!, 0.001)
        assertEquals(0.5, p.value(PriceMetric.LOW)!!, 0.001)
    }

    @Test
    fun seltenheitenAufDeutsch() {
        assertEquals("Selten", Rarity.label("Rare"))
        assertEquals("Holo-Selten V", Rarity.label("Holo Rare V"))
        assertEquals("Doppelselten", Rarity.label("Double rare"))
        assertEquals("Ohne Angabe", Rarity.label("None"))
    }
}
