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

    private fun MatchResult.show() =
        "best=${best?.card?.id} '${best?.card?.name}' conf=$confident ns=${best?.nameScore} " +
            "nums=${parsed.numbers.map { "${it.number}/${it.total}" }} codes=${parsed.setCodes} names=${parsed.nameLines} " +
            "alts=${alternatives.take(3).map { it.card.id }}"

    private fun parse(vararg t: String) = CardTextParser.parse(t.map { OcrLine(it) })

    // ---------- Logo-Zusatz ohne Trennzeichen ("リザードンex") ----------

    @Test
    fun zusatzOhneTrennzeichenWirdAbgetrennt() {
        fun base(name: String) = IndexCard("x", "s", "1", name, "").baseNorm
        assertEquals(TextNorm.normalize("リザードン"), base("リザードンex"))
        // "ー" (U+30FC) vor dem Zusatz gehoert zur Schrift COMMON, nicht Katakana
        assertEquals(TextNorm.normalize("ミュウツー"), base("ミュウツーex"))
        assertEquals(TextNorm.normalize("スターミー"), base("スターミーV"))
        assertEquals(TextNorm.normalize("ルギア"), base("ルギアV"))
        assertEquals(TextNorm.normalize("モルペコ"), base("モルペコV-UNION"))
        assertEquals(TextNorm.normalize("ヒスイ オオニューラ"), base("ヒスイ オオニューラV"))
        assertEquals(TextNorm.normalize("ポリゴンZ"), base("ポリゴンZ"))
        assertEquals("glurak", base("Glurak VMAX"))
        assertEquals("glurakex", base("Glurakex")) // lateinisch weiter nur mit Trennzeichen
    }

    @Test
    fun exLogoNichtGelesenIstTrotzdemSicher() {
        // Glurak-ex aus 151, das stilisierte "ex" liest die OCR nicht
        val r = matcher.match(lines("2進化" to 14, "リザードン" to 48, "HP330" to 20, "G sv2a 006/165 RR" to 12))
        assertEquals(r.show(), "ja:SV2a-006", r.best?.card?.id)
        assertTrue(r.show(), r.confident)
        assertTrue("Live-Vorschlag braucht >= 0.85: ${r.show()}", r.best!!.nameScore >= 0.85)
        val mew = matcher.match(lines("たね" to 14, "ミュウ" to 48, "HP180" to 20, "G sv2a 151/165 RR" to 12))
        assertEquals(mew.show(), "ja:SV2a-151", mew.best?.card?.id)
        assertTrue(mew.show(), mew.confident)
    }

    // ---------- "ヒトカゲから進化" nennt die Vorstufe ----------

    @Test
    fun entwicklungsZeileIstKeinName() {
        val p = CardTextParser.parse(lines("1進化" to 14, "リザード" to 48, "ヒトカゲから進化" to 12, "ヒトカゲから進化 ." to 12))
        assertTrue(p.nameLines.toString(), p.nameLines.none { it.contains(TextNorm.normalize("ヒトカゲ")) })
        // Name unter Glanz unlesbar, Nummer richtig: die Nummer entscheidet, nicht die Vorstufe
        val r = matcher.match(lines("1進化" to 14, "~~~" to 48, "ヒトカゲから進化" to 12, "HP100" to 20, "G sv2a 005/165 U" to 12))
        assertEquals(r.show(), "ja:SV2a-005", r.best?.card?.id)
        // Ohne Nummer darf die Vorstufe nicht als Live-Vorschlag erscheinen
        val n = matcher.match(lines("1進化" to 14, "~~~" to 48, "ヒトカゲから進化" to 12))
        assertFalse(n.show(), n.best?.card?.name == "ヒトカゲ" && n.best!!.nameScore >= 0.85)
    }

    // ---------- Zwei-Zeichen-Namen (パモ, ネモ) ----------

    @Test
    fun zweiZeichenNamenWerdenErkannt() {
        val pamo = matcher.match(lines("たね" to 14, "パモ" to 48, "HP50" to 20, "G sv1V 033/078 C" to 12))
        assertEquals(pamo.show(), "ja:SV1V-033", pamo.best?.card?.id)
        assertTrue(pamo.show(), pamo.confident)
        assertTrue(pamo.show(), pamo.best!!.nameScore >= 0.85)
        val nemo = matcher.match(lines("サポート" to 14, "ネモ" to 48, "G sv4a 343/190 SAR" to 12))
        assertEquals(nemo.show(), "ja:SV4a-343", nemo.best?.card?.id)
        assertTrue(nemo.show(), nemo.confident)
        // Kartenart-Zeilen sind keine Namen, auch nicht mit zwei Zeichen
        val p = CardTextParser.parse(lines("たね" to 14, "特性" to 14, "ワザ" to 14, "サポート" to 14, "グッズ" to 14, "スタジアム" to 14))
        assertTrue(p.nameLines.toString(), p.nameLines.isEmpty())
    }

    @Test
    fun zweiZeichenNameNurOhneWeitereKana() {
        val kai = TextNorm.normalize("カイ")
        // "カイ" (Unterstuetzerin) steckt in "カイリュー": das ist kein Treffer
        assertEquals(0.0, TextNorm.nameSimilarity(TextNorm.normalize("カイリコー"), kai), 0.0)
        assertEquals(0.0, TextNorm.nameSimilarity(TextNorm.normalize("カイリュー"), kai), 0.0)
        assertEquals(1.0, TextNorm.nameSimilarity(kai, kai), 0.0)
        assertTrue(TextNorm.nameSimilarity(TextNorm.normalize("パモ HP50"), TextNorm.normalize("パモ")) >= 0.85)
        // lateinisch bleibt es bei mindestens drei Zeichen
        assertEquals(0.0, TextNorm.nameSimilarity("nxx", "n"), 0.0)
        assertFalse(TextNorm.isSearchableName("io"))
        assertTrue(TextNorm.isSearchableName(TextNorm.normalize("ネモ")))
    }

    // ---------- Set-Kuerzel japanischer Karten ----------

    @Test
    fun kuerzelMitRegelzeichenDazwischen() {
        assertTrue(parse("s8b E 001/184").setCodes.toString(), "S8B" in parse("s8b E 001/184").setCodes)
        val sm = parse("sm12a C 001/173 RR")
        assertTrue(sm.setCodes.toString(), "SM12A" in sm.setCodes)
        assertTrue(sm.numbers.toString(), sm.numbers.any { it.key == "1" && it.total == 173 })
        assertTrue(parse("H sv11B 001/086 C").setCodes.toString(), "SV11B" in parse("H sv11B 001/086 C").setCodes)
        assertTrue(parse("s12a F 001/172 C").setCodes.toString(), "S12A" in parse("s12a F 001/172 C").setCodes)
        assertTrue("SV2A" in parse("G sv2a 006/165 RR").setCodes)
    }

    @Test
    fun gleicheGesamtzahlWirdUeberKuerzelGetrennt() {
        // S5I/S5R/S6H haben alle 70 Karten, Name und KP unlesbar: nur das Kuerzel trennt
        val r = matcher.match(lines("~~~" to 48, "s5R E 050/070 RR" to 12))
        assertEquals(r.show(), "ja:S5R-050", r.best?.card?.id)
        assertTrue(r.show(), r.best!!.codeMatched)
        val b = matcher.match(lines("~~~" to 48, "I sv11B 001/086 C" to 12))
        assertEquals(b.show(), "ja:SV11B-001", b.best?.card?.id)
        assertTrue(b.show(), b.best!!.codeMatched)
    }

    @Test
    fun japanischePromos() {
        val p = parse("001/SV-P")
        assertTrue(p.setCodes.toString(), "SV-P" in p.setCodes)
        assertTrue(p.numbers.toString(), p.numbers.any { it.key == "1" && it.total == null })
        assertTrue("M-P" in parse("123 / M-P").setCodes)
        assertTrue("S-P" in parse("045/S-P").setCodes)
        assertTrue("SM-P" in parse("045/SM-P").setCodes)
        val r = matcher.match(lines("たね" to 14, "ピカチュウ" to 48, "HP60" to 20, "001/SV-P" to 12))
        assertEquals(r.show(), "ja:SV-P-001", r.best?.card?.id)
        assertTrue(r.show(), r.confident)
        val m = matcher.match(lines("チコリータ" to 48, "001/M-P" to 12))
        assertEquals(m.show(), "ja:M-P-001", m.best?.card?.id)
    }

    @Test
    fun gesamtzahlUeber500BeideLesarten() {
        // MC hat 742 Karten; "12/640" bleibt auch 12/64 (Seltenheitssymbol als Ziffer gelesen)
        val p = parse("149/742")
        assertTrue(p.numbers.toString(), p.numbers.any { it.key == "149" && it.total == 742 })
        assertTrue(p.numbers.toString(), p.numbers.any { it.key == "149" && it.total == 74 })
        assertTrue(parse("12/640").numbers.any { it.key == "12" && it.total == 64 })
        val r = matcher.match(lines("オーガポン かまどのめんex" to 48, "HP210" to 20, "149/742" to 12))
        assertEquals(r.show(), "ja:MC-149", r.best?.card?.id)
        assertTrue(r.show(), r.best!!.totalMatched)
    }

    // ---------- Sets ohne Kartenliste bei TCGdex (Platzhalter) ----------

    @Test
    fun platzhalterSetsWerdenGefunden() {
        // Shiny Star V: Glurak-VMAX 308/190 (ueber der Gesamtzahl), SV4a hat auch 190 Karten
        val r = matcher.match(lines("リザードンVMAX" to 48, "HP330" to 20, "s4a D 308/190 SSR" to 12))
        assertEquals(r.show(), "ja:S4a-308", r.best?.card?.id)
        // Eevee Heroes Nachtara-VMAX 048/069
        val u = matcher.match(lines("ブラッキーVMAX" to 48, "s6a E 048/069 RRR" to 12))
        assertEquals(u.show(), "ja:S6a-048", u.best?.card?.id)
    }

    /** Wie ScanViewModel: unsicherer Treffer mit Name >= 0.85 wird live als Vorschlag gezeigt. */
    private fun MatchResult.liveSuggestion() = best?.takeIf { !confident && it.nameScore >= 0.85 }

    @Test
    fun platzhalterOhneKuerzelBleibtVorFremdemSet() {
        // Shiny Star V Glurak-VMAX 308/190, Kuerzel nicht gelesen (Regelzeichen klebt an Nummer/Kuerzel, Punkt dazwischen).
        // SV4a hat auch 190 Karten: dessen Glurak-ex 115/190 ist nur eine Vermutung und darf nicht vorbeiziehen
        for (code in listOf("s4a D308/190 SSR", "s4aD 308/190", "s4a・D 308/190")) {
            val r = matcher.match(lines("リザードン" to 48, "HP330" to 20, code to 12))
            assertEquals(r.show(), "ja:S4a-308", r.best?.card?.id)
            assertFalse(r.show(), r.confident)
            assertEquals(r.show(), null, r.liveSuggestion()?.card?.id)
            // als Auswahl bleibt Glurak-ex erhalten
            assertTrue(r.show(), r.alternatives.any { it.card.id == "ja:SV4a-115" })
        }
        val vmax = matcher.match(lines("リザードンVMAX" to 48, "HP330" to 20, "s4a D308/190 SSR" to 12))
        assertEquals(vmax.show(), "ja:S4a-308", vmax.best?.card?.id)
        assertEquals(vmax.show(), null, vmax.liveSuggestion()?.card?.id)
    }

    @Test
    fun platzhalterLaesstTypischeFehllesungDurch() {
        // Eine Ziffer daneben (115 als 116 gelesen) bleibt der typische OCR-Fehler: Glurak-ex vor Platzhalter S4a-116
        val r = matcher.match(lines("リザードン" to 48, "HP330" to 20, "116/190" to 12))
        assertEquals(r.show(), "ja:SV4a-115", r.best?.card?.id)
        // Mit exakt gelesenem Namen ebenso
        val ex = matcher.match(lines("リザードンex" to 48, "HP330" to 20, "308/190" to 12))
        assertEquals(ex.show(), "リザードンex", ex.best?.card?.name)
        // Kuerzel "sv4a" gelesen: der Platzhalter S4a-308 passt nicht dazu und haelt Glurak-ex nicht auf
        val sv = matcher.match(lines("リザードン" to 48, "HP330" to 20, "G sv4a 308/190" to 12))
        assertEquals(sv.show(), "リザードンex", sv.best?.card?.name)
        assertEquals(sv.show(), "ja:SV4a", sv.best?.card?.setId)
    }

    @Test
    fun enthaltenerNameEinesAnderenPokemonGewinntNicht() {
        // XY2 Glurak-EX 011/080 (Platzhalter ohne Namen, kein Kuerzel auf XY-Karten). "リザード" (Glutexo, M2-012)
        // steckt in "リザードンEX", ist aber ein anderes Pokemon: weder bester Treffer noch Live-Vorschlag
        for (hp in listOf(emptyList(), listOf("HP180" to 20))) {
            val r = matcher.match(lines("リザードンEX" to 48, *hp.toTypedArray(), "011/080" to 12))
            val all = listOfNotNull(r.best) + r.alternatives
            assertTrue(r.show(), all.none { it.card.name == "リザード" })
            assertFalse(r.show(), r.confident)
            assertEquals(r.show(), null, r.liveSuggestion()?.card?.id)
            assertTrue(r.show(), r.best!!.card.isPlaceholder)
            assertTrue(r.show(), all.any { it.card.id == "ja:XY2-011" })
            // den gelesenen Namen gibt es im Katalog: diese Karten erscheinen zumindest als Auswahl
            assertTrue(r.show(), all.any { it.card.name == "リザードンex" })
        }
        // Nummer der Glutexo-Karte selbst gelesen (012/080, auch XY2 Glurak-EX): nicht sicher, also kein automatisches
        // Hinzufuegen, und Glutexo steht auch nicht vor den Platzhaltern mit derselben Nummer
        for (hp in listOf(emptyList(), listOf("HP180" to 20))) {
            val g = matcher.match(lines("リザードンEX" to 48, *hp.toTypedArray(), "012/080" to 12))
            assertFalse(g.show(), g.confident)
            assertEquals(g.show(), null, g.liveSuggestion()?.card?.id)
            assertTrue(g.show(), g.best!!.card.isPlaceholder)
            assertTrue(g.show(), (g.alternatives + g.best!!).any { it.card.id == "ja:XY2-012" })
        }
    }

    @Test
    fun platzhalterSchlaegtNamenOhneDessenLogo() {
        // S1a Liberlo-VMAX 017/070 (HP320) und Liberlo-V 016/070 (HP210): S1a hat keine Kartenliste, "エースバーンVMAX"
        // und "エースバーンV" gibt es im Katalog nicht. Das Logo in der Zeile widerspricht trotzdem dem einfachen
        // Liberlo S6H-017 (gleiche Gesamtzahl 70): weder sicher noch Live-Vorschlag noch bester Treffer
        val cases = listOf(
            listOf("エースバーンVMAX" to 48, "HP320" to 20, "017/070" to 12) to "ja:S1a-017",
            listOf("エースバーンVMAX" to 48, "HP320" to 20, "s1aD 017/070" to 12) to "ja:S1a-017",
            listOf("エースバーンVMAX" to 48, "017/070" to 12) to "ja:S1a-017",
            listOf("エースバーンV" to 48, "HP210" to 20, "016/070" to 12) to "ja:S1a-016",
            listOf("エースバーンV HP210" to 48, "016/070" to 12) to "ja:S1a-016",
            // XY3 Skaraborn-EX 001/096 gegen neo1 Skaraborn (96 Karten)
            listOf("ヘラクロスEX" to 48, "HP180" to 20, "001/096" to 12) to "ja:XY3-001",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertFalse(r.show(), r.confident)
            assertEquals(r.show(), null, r.liveSuggestion()?.card?.id)
            assertTrue(r.show(), r.best!!.card.isPlaceholder)
            assertTrue(r.show(), (r.alternatives + r.best!!).any { it.card.id == id })
        }
        // Glurak-VMAX 002/100 (S3/S4 ohne Kartenliste): Glurak-V aus S9 (auch 100 Karten) ist nur eine Vermutung
        val v = matcher.match(lines("リザードンVMAX" to 48, "HP330" to 20, "002/100" to 12))
        assertTrue(v.show(), v.best!!.card.isPlaceholder)
        assertEquals(v.show(), null, v.liveSuggestion()?.card?.id)
        // Gegenprobe: ohne Logo bleibt das einfache Liberlo sicher
        val plain = matcher.match(lines("エースバーン" to 48, "HP170" to 20, "017/070" to 12))
        assertEquals(plain.show(), "ja:S6H-017", plain.best?.card?.id)
        assertTrue(plain.show(), plain.confident)
    }

    @Test
    fun nurExaktGelesenerNameZiehtAmPlatzhalterVorbei() {
        // Exakt gelesener Name, auch mit KP in derselben Zeile: Glurak-ex trotz anderer Nummer vor dem Platzhalter
        val ex = matcher.match(lines("リザードンex HP330" to 48, "308/190" to 12))
        assertEquals(ex.show(), "リザードンex", ex.best?.card?.name)
        // "リザードンex" steckt nur in der Zeile; der Rest ("M") kann zum unbekannten Namen des Platzhalters gehoeren
        val mega = matcher.match(lines("Mリザードンex" to 48, "HP330" to 20, "308/190" to 12))
        assertEquals(mega.show(), "ja:S4a-308", mega.best?.card?.id)
        assertEquals(mega.show(), null, mega.liveSuggestion()?.card?.id)
    }

    @Test
    fun nurNameOhneEnthalteneVorstufe() {
        // Ohne Nummer, KP der Glutexo-Karten (90) gelesen: "リザードンEX" ist trotzdem Glurak-ex, nicht Glutexo
        val r = matcher.match(lines("リザードンEX" to 48, "HP90" to 20))
        assertEquals(r.show(), "リザードンex", r.best?.card?.name)
        assertTrue(r.show(), (r.alternatives + r.best!!).none { it.card.name == "リザード" })
    }

    @Test
    fun vorstufeMitFalscherZifferWirdNichtVorgeschlagen() {
        // Zoroark 059/086, OCR liest 058 (= Zorua): "ゾロア" steckt in "ゾロアーク", ist aber die Vorstufe
        val r = matcher.match(lines("ゾロアーク" to 48, "HP120" to 20, "058/086" to 12))
        assertEquals(r.show(), "ja:SV11W-059", r.best?.card?.id)
        assertFalse(r.show(), r.confident)
        // Mewtu 086/128 als 087 gelesen (= Mew)
        val m = matcher.match(lines("ミュウツー" to 48, "087/128" to 12))
        assertFalse(m.show(), m.best?.card?.name == "ミュウ")
        assertEquals(m.show(), null, m.liveSuggestion()?.takeIf { it.card.name == "ミュウ" })
    }
}
