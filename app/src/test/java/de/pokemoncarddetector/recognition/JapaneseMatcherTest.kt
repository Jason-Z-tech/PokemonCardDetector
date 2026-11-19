package de.pokemoncarddetector.recognition

import de.pokemoncarddetector.data.catalog.CatalogFile
import de.pokemoncarddetector.data.catalog.parsedCards
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** Erkennung japanischer Karten mit dem echten japanischen Katalog. */
class JapaneseMatcherTest {

    companion object {
        private lateinit var matcher: CardMatcher
        private lateinit var index: CatalogIndex

        @BeforeClass
        @JvmStatic
        fun load() {
            val file = listOf("src/main/assets/catalog_ja.json", "app/src/main/assets/catalog_ja.json").map(::File).first { it.exists() }
            val catalog = Json { ignoreUnknownKeys = true }.decodeFromString(CatalogFile.serializer(), file.readText())
            val sets = catalog.sets.map { IndexSet(it.id, it.name, it.nameEn, it.release.take(4).toIntOrNull(), it.abbr, it.official, it.total) }
            val cards = catalog.sets.flatMap { s ->
                s.parsedCards(catalog.variants).map { c -> IndexCard("${s.id}-${c.localId}", s.id, c.localId, c.name, c.nameEn, c.hp) }
            }
            index = CatalogIndex(sets, cards)
            matcher = CardMatcher(index)
        }
    }

    private fun lines(vararg t: Pair<String, Int>): List<OcrLine> {
        var y = 0
        return t.map { (s, h) -> OcrLine(s, 20, y, 20 + s.length * h, y + h).also { y += h + 30 } }
    }

    @Test
    fun normalisierungBehaeltKana() {
        // Dakuten (゛) entfallen beim Vergleich auf beiden Seiten: ザ->サ, ド->ト
        assertEquals("リサートンex", TextNorm.normalize("リザードンex"))
        assertEquals("sv2a", TextNorm.normalize("ＳＶ２ａ"))
        assertEquals("pokemonex", TextNorm.normalize("Pokémon-ex"))
    }

    @Test
    fun glurakExAus151() {
        val r = matcher.match(lines("2進化" to 14, "リザードンex" to 48, "HP330" to 20, "SV2a G 006/165 RR" to 12))
        assertEquals(r.best.toString(), "ja:SV2a-006", r.best?.card?.id)
        assertTrue("sollte sicher sein: ${r.best}", r.confident)
    }

    @Test
    fun deutscherHinweisImKatalog() {
        val c = index.cardsById.getValue("ja:SV2a-006")
        assertEquals("Glurak ex", c.nameEn)
    }

    @Test
    fun unsinnIstNichtSicher() {
        val r = matcher.match(lines("こんにちは" to 40, "テスト" to 20))
        assertFalse(r.confident)
    }
}
