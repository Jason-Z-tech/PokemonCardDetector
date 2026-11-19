package de.pokemoncarddetector.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextParserTest {

    private fun parse(vararg lines: String) = CardTextParser.parse(lines.map { OcrLine(it) })

    @Test
    fun einfacheNummer() {
        val p = parse("025/185")
        assertEquals(1, p.numbers.size)
        assertEquals("25", p.numbers[0].key)
        assertEquals(185, p.numbers[0].total)
    }

    @Test
    fun nummerMitLeerzeichenUndVerwechslern() {
        val p = parse("O25 / l85")
        assertEquals("25", p.numbers.first().key)
        assertEquals(185, p.numbers.first().total)
    }

    @Test
    fun praefixNummern() {
        assertEquals("TG5", parse("TG05/TG30").numbers.first().key)
        assertEquals("SV50", parse("SV050/SV122").numbers.first().key)
        assertEquals("GG12", parse("GG12/GG70").numbers.first().key)
        assertEquals("RC5", parse("RC5/RC32").numbers.first().key)
    }

    @Test
    fun setKuerzelUndSprache() {
        val p = parse("G PAL DE 123/193")
        assertTrue(p.setCodes.toString(), "PAL" in p.setCodes)
        assertEquals("123", p.numbers.first().number)
    }

    @Test
    fun kpIstKeinSetKuerzel() {
        val p = parse("Glurak KP 170", "HP 120")
        assertTrue(p.setCodes.isEmpty())
    }

    @Test
    fun promoNummern() {
        assertEquals("SWSH50", parse("SWSH050").numbers.first().key)
        val svp = parse("SVP DE 049")
        assertTrue("SVP" in svp.setCodes)
        assertEquals("49", svp.numbers.first().key)
    }

    @Test
    fun copyrightJahr() {
        val p = parse("©2021 Pokémon/Nintendo/Creatures/GAME FREAK")
        assertEquals(2021, p.maxYear)
        // Andere Zahlen sind keine Jahre
        assertEquals(null, parse("Feuerwirbel 2020").maxYear)
    }

    @Test
    fun nummerUeberZweiZeilen() {
        val p = parse("025/", "185")
        assertEquals("25", p.numbers.first().key)
    }

    @Test
    fun normalisierung() {
        assertEquals("25", CardTextParser.normalizeLocalId("025"))
        assertEquals("TG5", CardTextParser.normalizeLocalId("TG05"))
        assertEquals("0", CardTextParser.normalizeLocalId("000"))
        assertEquals("12A", CardTextParser.normalizeLocalId("12a"))
        assertEquals("pokemonex", TextNorm.normalize("Pokémon-ex"))
        assertEquals("flabebe", TextNorm.normalize("Flabébé"))
    }

    @Test
    fun namensAehnlichkeit() {
        assertEquals(1.0, TextNorm.nameSimilarity("glurak", "glurak"), 0.0001)
        assertTrue(TextNorm.nameSimilarity("phase2glurakkp170", "glurak") > 0.85)
        assertTrue(TextNorm.nameSimilarity("gluruk", "glurak") > 0.8)
        assertTrue(TextNorm.nameSimilarity("bisasam", "glurak") < 0.5)
    }
}
