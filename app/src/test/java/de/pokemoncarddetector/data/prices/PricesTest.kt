package de.pokemoncarddetector.data.prices

import de.pokemoncarddetector.data.Rarity
import de.pokemoncarddetector.data.db.CollectionRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(290.28, p.getValue("holo|sub=unlimited").trend!!, 0.001)
        assertEquals(3489.25, p.getValue("holo|sub=shadowless").trend!!, 0.001)
        // 1. Edition ist bei Cardmarket kein eigenes Produkt (gleiche ID wie "schattenlos") -> geteilter Preis
        val first = p.getValue("holo|sub=shadowless|stamp=1st-edition")
        assertTrue(first.shared)
        assertEquals(660224L, first.idProduct)
        assertFalse(p.getValue("holo|sub=shadowless").shared)
        assertFalse(p.getValue("holo|sub=unlimited").shared)
        // Variante ohne Cardmarket-Daten bekommt keinen (falschen) Preis
        assertNull(p["holo|sub=1999-2000-copyright"])
    }

    @Test
    fun ersteEditionOhneEigenesProdukt() {
        val p = parse("neo1-1")
        assertEquals(22.89, p.getValue("holo").trend!!, 0.001)
        assertTrue(p.getValue("holo|stamp=1st-edition").shared)
        // Wert = Preis des geteilten Produkts, aber nur als Untergrenze ("ab …")
        assertEquals(VariantPrice(22.89, exact = false), variantPrice(p.values.toList(), "holo|stamp=1st-edition", PriceMetric.TREND))
        assertEquals(VariantPrice(22.89, exact = true), variantPrice(p.values.toList(), "holo", PriceMetric.TREND))
        assertEquals(22.89, priceFor(p.values.toList(), "holo|stamp=1st-edition", PriceMetric.TREND)!!, 0.001)
    }

    @Test
    fun reverseOhneEigenePreisangabeNutztHoloPreisDesProdukts() {
        // Karmesin & Purpur / Schwert & Schild: Preise nur auf Kartenebene
        val sv = parse("sv01-001")
        assertEquals(0.03, sv.getValue("normal").trend!!, 0.001)
        assertEquals(0.15, sv.getValue("reverse").trend!!, 0.001)
        val sw = parse("swsh1-146")
        assertEquals(0.06, sw.getValue("normal").trend!!, 0.001)
        assertEquals(0.49, sw.getValue("reverse").trend!!, 0.001)
        // Japanisch: Reverse ohne Preis, Normal-Produkt hat den Holo-Preis
        val ja = parse("ja:S12a-023", "ja-S12a-023")
        assertEquals(0.02, ja.getValue("normal").trend!!, 0.001)
        assertEquals(1.85, ja.getValue("reverse").trend!!, 0.001)
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
    fun holoRareMitNebenDruckNormalOhneVariantenPreise() {
        // dp6-5: Varianten ohne Preisangabe und ohne Produkt-ID, Kartenpreis = Holo-Produkt (TCGdex listet Holo zuerst)
        val p = parse("dp6-5")
        assertEquals(1.28, p.getValue("holo").trend!!, 0.001)
        assertEquals(278154L, p.getValue("holo").idProduct)
        assertEquals(3.47, p.getValue("reverse").trend!!, 0.001)
        // "Normal" ist nicht der Haupt-Druck: kein eigener Preis, Anzeige nur "ab …"
        assertNull(p["normal"])
        assertEquals(VariantPrice(1.28, exact = false), variantPrice(p.values.toList(), "normal", PriceMetric.TREND))
        assertEquals(VariantPrice(1.28, exact = true), variantPrice(p.values.toList(), "holo", PriceMetric.TREND))
    }

    @Test
    fun holoRareHauptProduktUeberProduktId() {
        // hgss3-1: Holo traegt die Produkt-ID des Kartenpreises, Normal hat weder Preis noch Cardmarket-ID
        val p = parse("hgss3-1")
        assertEquals(3.64, p.getValue("holo").trend!!, 0.001)
        assertEquals(3.29, p.getValue("reverse").trend!!, 0.001)
        assertNull(p["normal"])
        assertEquals(setOf("holo", "reverse"), p.keys)
    }

    @Test
    fun hauptVarianteNachProduktIdSonstReihenfolge() {
        // Produkt-ID des Kartenpreises entscheidet (Reverse zaehlt nicht, einfache Varianten zuerst)
        assertEquals("holo", PriceParser.primaryVariant(listOf("normal", "holo"), listOf(1L, 2L), 2L))
        assertEquals("normal", PriceParser.primaryVariant(listOf("reverse", "normal"), listOf(5L, 5L), 5L))
        assertEquals("normal", PriceParser.primaryVariant(listOf("normal", "reverse", "reverse|foil=league|stamp=staff"), listOf(7L, 7L, 7L), 7L))
        assertEquals("holo|sub=unlimited", PriceParser.primaryVariant(listOf("holo|sub=unlimited", "holo|sub=shadowless"), listOf(1L, 2L), 1L))
        // Ohne Produkt-ID: erstes Normal/Holo in TCGdex-Reihenfolge, sonst "normal"
        assertEquals("holo", PriceParser.primaryVariant(listOf("holo", "reverse", "normal"), listOf(null, null, null), 9L))
        assertEquals("normal", PriceParser.primaryVariant(listOf("reverse", "normal"), listOf(null, null), null))
        assertEquals("normal", PriceParser.primaryVariant(listOf("reverse|foil=pokeball"), listOf(null), null))
    }

    @Test
    fun ersteEditionSchattenlosMitPreisDesSchattenlosProdukts() {
        val prices = parse("base1-4").values.toList()
        val first = "holo|sub=shadowless|stamp=1st-edition"
        // Untergrenze = Produkt "schattenlos" (3.489,25 €), nicht das guenstigere Unlimited (290,28 €)
        assertEquals(3489.25, priceFor(prices, first, PriceMetric.TREND)!!, 0.001)
        assertEquals(VariantPrice(3489.25, exact = false), variantPrice(prices, first, PriceMetric.TREND))
        val v = collectionValue(listOf(CollectionRow("base1-4", first, 1)), prices, PriceMetric.TREND)
        assertEquals(3489.25, v.totalEuro, 0.001)
        // Variante ohne jede Cardmarket-Angabe: guenstigster Preis der Karte, ebenfalls nur "ab"
        assertEquals(VariantPrice(290.28, exact = false), variantPrice(prices, "holo|sub=1999-2000-copyright", PriceMetric.TREND))
    }

    @Test
    fun ersteEditionNichtMitPrereleaseProdukt() {
        // base5-8: 1. Edition teilt das Produkt mit Holo (101,47 €), Prerelease ist ein eigenes, billigeres Produkt
        val p = parse("base5-8")
        assertFalse(p.getValue("holo").shared)
        assertFalse(p.getValue("holo|stamp=pre-release").shared)
        assertTrue(p.getValue("holo|stamp=1st-edition").shared)
        val prices = p.values.toList()
        assertEquals(101.47, priceFor(prices, "holo|stamp=1st-edition", PriceMetric.TREND)!!, 0.001)
        assertEquals(63.86, priceFor(prices, "holo|stamp=pre-release", PriceMetric.TREND)!!, 0.001)
        assertEquals(VariantPrice(63.86, exact = true), variantPrice(prices, "holo|stamp=pre-release", PriceMetric.TREND))
    }

    @Test
    fun staffReverseMitPreisDesReverseProdukts() {
        // pl3-106: Staff-Liga-Reverse hat die Produkt-ID von Normal/Reverse -> Reverse-Preis (9,26 €) als Untergrenze
        val p = parse("pl3-106")
        assertEquals(0.3, p.getValue("normal").trend!!, 0.001)
        assertEquals(9.26, p.getValue("reverse").trend!!, 0.001)
        assertFalse(p.getValue("reverse|foil=league").shared)
        val staff = p.getValue("reverse|foil=league|stamp=staff")
        assertTrue(staff.shared)
        assertEquals(9.26, staff.trend!!, 0.001)
        val prices = p.values.toList()
        assertEquals(VariantPrice(9.26, exact = false), variantPrice(prices, "reverse|foil=league|stamp=staff", PriceMetric.TREND))
        val v = collectionValue(
            listOf(CollectionRow("pl3-106", "reverse|foil=league|stamp=staff", 1), CollectionRow("pl3-106", "normal", 2)),
            prices, PriceMetric.TREND,
        )
        assertEquals(9.26 + 2 * 0.3, v.totalEuro, 0.001)
    }

    @Test
    fun eigenerPreisVorGeteiltemPreisVorGuenstigstem() {
        fun row(variant: String, trend: Double, shared: Boolean = false) =
            de.pokemoncarddetector.data.db.PriceEntity("x-1", variant, trend, null, null, null, 1L, "", 1L, shared = shared)
        val prices = listOf(row("normal", 1.0), row("normal|stamp=staff", 5.0, shared = true), row("holo", 0.5))
        assertEquals(VariantPrice(1.0, exact = true), variantPrice(prices, "normal", PriceMetric.TREND))
        assertEquals(VariantPrice(5.0, exact = false), variantPrice(prices, "normal|stamp=staff", PriceMetric.TREND))
        assertEquals(VariantPrice(0.5, exact = false), variantPrice(prices, "reverse", PriceMetric.TREND))
        assertNull(variantPrice(emptyList(), "normal", PriceMetric.TREND))
    }

    @Test
    fun seltenheitenAufDeutsch() {
        assertEquals("Selten", Rarity.label("Rare"))
        assertEquals("Holo-Selten V", Rarity.label("Holo Rare V"))
        assertEquals("Doppelselten", Rarity.label("Double rare"))
        assertEquals("Ohne Angabe", Rarity.label("None"))
    }
}
