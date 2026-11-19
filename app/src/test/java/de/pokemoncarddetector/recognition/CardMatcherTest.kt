package de.pokemoncarddetector.recognition

import de.pokemoncarddetector.data.catalog.CatalogFile
import de.pokemoncarddetector.data.catalog.parsedCards
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Prueft die Erkennung mit dem echten, mitgelieferten Katalog.
 * Die OCR-Zeilen sind so aufgebaut, wie ML Kit sie typischerweise liefert.
 */
class CardMatcherTest {

    companion object {
        private lateinit var index: CatalogIndex
        private lateinit var matcher: CardMatcher

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = listOf("src/main/assets/catalog.json", "app/src/main/assets/catalog.json")
                .map(::File).first { it.exists() }
            val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(CatalogFile.serializer(), file.readText())
            val sets = catalog.sets.map {
                IndexSet(it.id, it.name, it.nameEn, it.release.take(4).toIntOrNull(), it.abbr, it.official, it.total)
            }
            val cards = catalog.sets.flatMap { s ->
                s.parsedCards(catalog.variants).map { c -> IndexCard("${s.id}-${c.localId}", s.id, c.localId, c.name, c.nameEn, c.hp) }
            }
            index = CatalogIndex(sets, cards)
            matcher = CardMatcher(index)
        }

        fun card(id: String): IndexCard = index.cardsById[id] ?: error("Karte $id fehlt im Katalog")
    }

    /** OCR-Zeilen mit Positionen: Name oben gross, Nummer unten klein. */
    private fun lines(vararg texts: Pair<String, Int>): List<OcrLine> {
        var y = 0
        return texts.map { (t, h) ->
            val l = OcrLine(t, 20, y, 20 + t.length * h / 2, y + h)
            y += h + 30
            l
        }
    }

    private fun assertMatch(expectedId: String, result: MatchResult, confident: Boolean = true) {
        assertNotNull("Kein Treffer. Geparst: ${result.parsed}", result.best)
        assertEquals("Falsche Karte. Alternativen: ${result.alternatives.map { it.card.id to it.score }}", expectedId, result.best!!.card.id)
        if (confident) assertTrue("Treffer sollte sicher sein: ${result.best}", result.confident)
    }

    @Test
    fun farbenschockGlurakDeutsch() {
        val c = card("swsh4-25")
        val r = matcher.match(
            lines(
                "PHASE 2" to 18,
                "${c.name} KP 170" to 48,
                "Entwickelt sich aus Glutexo" to 14,
                "Lodernder Sturm 250" to 20,
                "Schwäche ×2 Resistenz Rückzug" to 12,
                "Illus. Ryuta Fuse" to 10,
                "025/185 D" to 12,
                "©2020 Pokémon / Nintendo / Creatures / GAME FREAK" to 10,
            )
        )
        assertMatch("swsh4-25", r)
        assertEquals("Farbenschock", index.setsById[r.best!!.card.setId]!!.name)
    }

    @Test
    fun nummerOhneNameReichtBeiEindeutigerGesamtzahl() {
        // 185 gibt es nur bei Farbenschock
        val r = matcher.match(lines("unleserlich" to 40, "025/185" to 12))
        assertMatch("swsh4-25", r)
    }

    @Test
    fun ocrVerwechslerInDerNummer() {
        val c = card("swsh4-25")
        val r = matcher.match(lines(c.name to 48, "O25/l85" to 12))
        assertMatch("swsh4-25", r)
    }

    @Test
    fun geheimkarteUeberGesamtzahl() {
        val c = card("swsh4-190")
        val r = matcher.match(lines(c.name to 48, "190/185" to 12))
        assertMatch("swsh4-190", r)
    }

    @Test
    fun grundsetVsTriumphGleicheGesamtzahl102() {
        // 4/102 gibt es im Grundset (Glurak) und in HS Triumph
        val base = card("base1-4")
        val r = matcher.match(lines(base.name to 48, "4/102" to 12, "©1999 Wizards" to 10))
        assertMatch("base1-4", r)
        val triumph = card("hgss4-4")
        val r2 = matcher.match(lines(triumph.name to 48, "4/102" to 12))
        assertMatch("hgss4-4", r2)
    }

    @Test
    fun englischeKarteWirdAuchErkannt() {
        val c = card("base1-4")
        val r = matcher.match(lines(c.nameEn to 48, "4/102" to 12))
        assertMatch("base1-4", r)
    }

    @Test
    fun karmesinPurpurMitSetKuerzel() {
        // Ab Karmesin & Purpur steht das Kuerzel neben der Nummer: "PAL DE 123/193"
        val c = card("sv02-123")
        val r = matcher.match(lines(c.name to 48, "G PAL DE 123/193" to 12))
        assertMatch("sv02-123", r)
        assertTrue(r.best!!.codeMatched)
    }

    @Test
    fun kuerzelEntscheidetBeiGleicherGesamtzahl() {
        // 198 = Schaurige Herrschaft (swsh6) und Karmesin & Purpur (sv01); ohne Namen entscheidet "SVI"
        val r = matcher.match(lines("xxxx" to 40, "SVI DE 050/198" to 12))
        assertMatch("sv01-050", r)
    }

    @Test
    fun trainerGalerie() {
        val c = card("swsh9tg-TG05")
        val r = matcher.match(lines(c.name to 48, "TG05/TG30" to 12))
        assertMatch("swsh9tg-TG05", r)
    }

    @Test
    fun glitzerTresor() {
        val c = card("swsh4.5sv-SV050")
        val r = matcher.match(lines(c.name to 48, "SV050/SV122" to 12))
        assertMatch("swsh4.5sv-SV050", r)
    }

    @Test
    fun promoOhneGesamtzahl() {
        val c = card("swshp-SWSH050")
        val r = matcher.match(lines(c.name to 48, "SWSH050" to 12))
        assertMatch("swshp-SWSH050", r)
    }

    @Test
    fun nurNameIstUnsicher() {
        val r = matcher.match(lines("Pikachu" to 48, "Donnerschock" to 20))
        assertNotNull(r.best)
        assertTrue(r.best!!.card.name.contains("Pikachu") || r.best!!.card.nameEn.contains("Pikachu"))
        assertFalse("Ohne Nummer darf das Ergebnis nicht als sicher gelten", r.confident)
    }

    @Test
    fun kpHelfenBeiGleichemNamen() {
        // Gleicher Name in vielen Sets: mit KP wird der passende Druck bevorzugt
        val c = card("swsh4-25")
        val r = matcher.match(lines("${c.name} KP ${c.hp}" to 48, "Lodernder Sturm" to 20))
        assertNotNull(r.best)
        assertEquals(c.hp, r.best!!.card.hp)
        val p = CardTextParser.parse(listOf(OcrLine("Glurak 170 KP")))
        assertEquals(170, p.hp)
    }

    @Test
    fun leererTextLiefertNichts() {
        val r = matcher.match(emptyList())
        assertEquals(null, r.best)
        assertFalse(r.confident)
    }

    @Test
    fun zufaelligerTextOhneKarte() {
        val r = matcher.match(lines("Einkaufsliste" to 30, "Milch 2 Liter" to 20, "Brot" to 20))
        assertFalse(r.confident)
    }

    @Test
    fun alleSetsMitNummerUndNameWerdenErkannt() {
        // Stichprobe: fuer jedes Set mit Gesamtzahl die erste, mittlere und letzte offizielle Karte
        val failures = mutableListOf<String>()
        var checked = 0
        for (set in index.setsById.values) {
            if (set.officialCount <= 0) continue
            val cards = index.cardsBySet[set.id].orEmpty()
                .filter { it.localId.all(Char::isDigit) && (it.localId.toIntOrNull() ?: 0) in 1..set.officialCount }
            if (cards.isEmpty()) continue
            val sample = listOf(cards.first(), cards[cards.size / 2], cards.last()).distinct()
            for (c in sample) {
                val num = c.localId.padStart(if (set.officialCount >= 100) 3 else 2, '0')
                val r = matcher.match(lines(c.name to 48, "$num/${set.officialCount}" to 12))
                checked++
                val best = r.best
                // Gleicher Name + gleiche Nummer in mehreren Sets (Nachdrucke) gilt als ok, wenn unsicher markiert
                val ok = best != null && (best.card.id == c.id ||
                    (!r.confident && (r.alternatives + best).any { it.card.id == c.id }))
                if (!ok) failures += "${c.id} '${c.name}' -> ${best?.card?.id} (sicher=${r.confident})"
            }
        }
        println("Stichprobe: $checked Karten geprueft, ${failures.size} Fehler")
        failures.take(40).forEach { println("  $it") }
        assertTrue("Geprueft: $checked, Fehler: ${failures.size}\n" + failures.take(40).joinToString("\n"), failures.size <= checked / 50)
    }

    @Test
    fun namensSucheIstSchnell() {
        val parsed = CardTextParser.parse(lines("Glurak" to 48, "Feuerwirbel 120" to 20))
        val start = System.nanoTime()
        repeat(50) { matcher.match(parsed) }
        val ms = (System.nanoTime() - start) / 1_000_000 / 50
        assertTrue("Namenssuche zu langsam: ${ms}ms", ms < 150)
    }
}
