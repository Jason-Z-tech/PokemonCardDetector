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

    // ---- Faelle aus echter OCR (RapidOCR PP-OCRv6 auf TCGdex-Kartenbildern) ----

    @Test
    fun seltenheitssymbolHinterGesamtzahl() {
        // bw1-36: "36/114 ●" -> "36/1140"; xy10-117: "117/124 ★" -> "117/1245"
        assertTrue(parse("36/1140").numbers.any { it.key == "36" && it.total == 114 })
        assertTrue(parse("Illus. Ryo Ueda 117/1245").numbers.any { it.key == "117" && it.total == 124 })
    }

    @Test
    fun schraegstrichFehltOderAlsEinsGelesen() {
        // bw7-94: "94/149" -> "941149"; swsh10.5-020: "020/078" -> "020078"
        val a = parse("941149").numbers
        assertTrue(a.toString(), a.any { it.key == "94" && it.total == 149 && it.weak })
        val b = parse("020078").numbers
        assertTrue(b.toString(), b.any { it.key == "20" && it.total == 78 && it.weak })
        // Copyright-Jahr ist keine Nummer
        assertTrue(parse("02024 Pokémon/Nintendo").numbers.isEmpty())
    }

    @Test
    fun kuerzelUndNummerAlsGetrennteKaestchen() {
        // svp-021 (Foto): "021" liegt 3px hoeher als "GSVP DE" und kommt beim Sortieren nach top zuerst
        val p = CardTextParser.parse(listOf(OcrLine("021", 179, 980, 264, 1018), OcrLine("GSVP DE", 114, 983, 195, 1016)))
        assertTrue(p.setCodes.toString(), "SVP" in p.setCodes)
        assertTrue(p.numbers.toString(), p.numbers.any { it.key == "21" && it.total == null })
    }

    @Test
    fun abgeschnittenesKuerzelVorNummer() {
        // sv04-063: "PAR 063/182" -> "AR063/182" (AR ist auch ein Nummern-Praefix)
        assertTrue(parse("AR063/182").numbers.any { it.key == "63" && it.total == 182 })
        // me01-065: "MEG 065/132" -> "MG06S/132"
        assertTrue(parse("MG06S/132").numbers.any { it.key == "65" && it.total == 132 })
    }

    @Test
    fun woerterSindKeineSetKuerzel() {
        val p = parse("Viele verabscheuen und es glauben, es bringe Unglück", "Es lebt in dunklen Höhlen", "It flies at night")
        assertTrue(p.setCodes.toString(), p.setCodes.none { it in setOf("UND", "HL", "FLI") })
    }
}
