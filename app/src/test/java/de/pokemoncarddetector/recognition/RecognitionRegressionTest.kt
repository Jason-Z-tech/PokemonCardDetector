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

/** Adversariale Proben: realistische ML-Kit-Ausgaben, bei denen die Erkennung versagt. */
class RecognitionRegressionTest {

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
    }

    private fun lines(vararg texts: Pair<String, Int>): List<OcrLine> {
        var y = 0
        return texts.map { (t, h) ->
            val l = OcrLine(t, 20, y, 20 + t.length * h / 2, y + h)
            y += h + 30
            l
        }
    }

    private fun MatchResult.show() =
        "best=${best?.card?.id} '${best?.card?.name}' conf=$confident ns=${best?.nameScore} " +
            "nums=${parsed.numbers.map { "${it.number}/${it.total}" }} codes=${parsed.setCodes} alts=${alternatives.take(3).map { it.card.id }}"

    /** Farbenschock-Glurak 025/185, so wie ML Kit die Karte liest. */
    private fun glurakViv(numberLine: String, nameLine: String = "Glurak KP 170") = lines(
        "PHASE 2" to 18,
        nameLine to 48,
        "Entwickelt sich aus Glutexo" to 14,
        "Lodernder Sturm 250" to 20,
        "Schwäche ×2 Resistenz Rückzug" to 12,
        "Illus. Ryuta Fuse" to 10,
        numberLine to 12,
        "©2020 Pokémon / Nintendo / Creatures / GAME FREAK" to 10,
    )

    // ---------- 1) Nicht-Karten-Text wird "sicher" erkannt (Auto-Hinzufuegen!) ----------

    @Test
    fun nonCardTextWithFractionIsNeverConfident() {
        for (t in listOf("Seite 25/185", "Gültig bis 12/24", "Lieferung 45/88")) {
            val r = matcher.match(lines(t to 20))
            println("[nonCard] '$t' -> ${r.show()}")
            assertFalse("'$t' ist keine Karte, wurde aber sicher erkannt: ${r.show()}", r.confident)
        }
    }

    // ---------- 2) Ein falsch gelesenes Zeichen in der Nummer -> falsche Karte "sicher" ----------

    @Test
    fun misreadDigitWithoutNameGivesConfidentWrongCard() {
        // Jungle-Karte 01/64, OCR liest 81/64 (0->8), Name durch Holo-Glanz unlesbar
        val r1 = matcher.match(lines("~~~" to 48, "Tackle 30" to 20, "Illus. Ken Sugimori" to 10, "81/64 ●" to 12,
            "©1995, 96, 98, 99 Nintendo, Creatures, GAMEFREAK. ©1999 Wizards." to 10))
        println("[misread] base2-1 81/64 -> ${r1.show()}")
        assertFalse("Jungle 01/64 als 81/64 gelesen -> sicher ${r1.show()}", r1.confident)
        // Farbenschock 025/185, '5' als 'S' gelesen -> "025/18S"
        val r2 = matcher.match(glurakViv("025/18S", nameLine = "~~~"))
        println("[misread] VIV 025/18S -> ${r2.show()}")
        assertFalse("025/18S ergibt sicher ${r2.show()}", r2.confident && r2.best?.card?.id != "swsh4-25")
    }

    @Test
    fun vmaxNumberMisreadToVIsConfidentWrong() {
        // Maritellit VMAX 186/185 (Regenbogen), OCR liest 8 als 6 -> 166/185 = Maritellit V (Full Art)
        val r = matcher.match(lines("Maritellit VMAX KP 320" to 48, "Illus. aky CG Works" to 10, "166/185" to 12, "©2020 Pokémon" to 10))
        println("[vmax] ${r.show()}")
        assertFalse("Name 'Maritellit VMAX' gelesen, trotzdem sicher ${r.show()}", r.confident && r.best?.card?.id == "swsh4-166")
        // Sabrina's Gengar 29/132, OCR liest 20 -> gym2-20 'Sabrina'
        val r2 = matcher.match(lines("Sabrina's Gengar" to 48, "80 HP" to 20, "20/132" to 12))
        println("[owner] ${r2.show()}")
        assertFalse("Name 'Sabrina's Gengar' gelesen, trotzdem sicher ${r2.show()}", r2.confident && r2.best?.card?.id == "gym2-20")
    }

    // ---------- 3) Parser: realistische OCR-Varianten der Nummer ----------

    @Test
    fun parserHandlesCommonNumberMisreads() {
        val fails = mutableListOf<String>()
        for ((txt, key, total) in listOf(
            Triple("02S/185", "25", 185), Triple("025/18S", "25", 185), Triple("025/1B5", "25", 185),
            Triple("TGO5/TG30", "TG5", 30), Triple("SVO50/SV122", "SV50", 122),
            Triple("025I185", "25", 185), Triple("025 185", "25", 185),
        )) {
            val p = CardTextParser.parse(listOf(OcrLine(txt)))
            val n = p.numbers.firstOrNull { it.total != null }
            if (n?.key != key || n.total != total) fails += "'$txt' -> ${p.numbers.map { "${it.key}/${it.total}" }} (erwartet $key/$total)"
        }
        println("[parser] " + fails.joinToString("; "))
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test
    fun germanTextProducesBogusSetCodes() {
        val bogus = mutableListOf<String>()
        for (t in listOf("Beide Spieler müssen ihre Hand mischen.", "Du darfst 2 Karten wählen.",
            "Mische dein Deck und es", "Lege es auf deine Bank und es zählt", "on your side of the field", "Exit")) {
            val known = CardTextParser.parse(listOf(OcrLine(t))).setCodes.filter { index.setsByAbbreviation.containsKey(it) }
            if (known.isNotEmpty()) bogus += "'$t' -> $known"
        }
        println("[codes] $bogus")
        assertTrue(bogus.joinToString("\n"), bogus.isEmpty())
    }

    @Test
    fun bogusSetCodeFlipsToConfidentWrongCard() {
        // Majestaetischer Morgen (dp5, 100 Karten) 25/100; "müssen" erzeugt Kuerzel "SS" = EX Sandsturm (auch 100 Karten)
        val dp5 = index.cardsById["dp5-25"]!!
        val garbled = dp5.name.dropLast(2) + "xx" // Name nur teilweise gelesen
        val r = matcher.match(lines(garbled to 48, "Beide Spieler müssen ihre Handkarten mischen." to 12, "25/100" to 12))
        println("[bogusCode] dp5-25 '${dp5.name}' read '$garbled' -> ${r.show()}")
        assertFalse("Falsches Set durch 'müssen': ${r.show()}", r.confident && r.best?.card?.setId == "ex2")
    }

    // ---------- 4) Entwicklungszeile / Logo-Zusatz verdraengen den richtigen Nummern-Treffer ----------

    @Test
    fun evolvesFromLineBeatsCorrectlyReadNumber() {
        // Nummer korrekt gelesen, Name durch Glanz unlesbar -> "Entwickelt sich aus Glutexo" gewinnt
        val r = matcher.match(glurakViv("025/185 D", nameLine = "~~~"))
        println("[evolves] ${r.show()}")
        assertEquals("Nummer 025/185 korrekt, aber bester Treffer ${r.show()}", "swsh4-25", r.best?.card?.id)
    }

    @Test
    fun vmaxLogoNotReadPrefersCardWithOtherNumber() {
        // Glurak VMAX 020/189 (Flammende Finsternis); das VMAX-Logo ist eine Grafik und wird nicht gelesen
        val r = matcher.match(lines("Glurak" to 48, "KP 330" to 20, "020/189" to 12, "©2020 Pokémon" to 10))
        println("[vmaxLogo] ${r.show()}")
        assertEquals("Nummer 020/189 korrekt gelesen, aber ${r.show()}", "swsh3-20", r.best?.card?.id)
    }

    // ---------- 4b) Name steckt nur als Teil eines laengeren Namens in der Zeile ----------

    @Test
    fun laengererNameMitFalscherZifferIstNichtSicherDieVorstufe() {
        // M-Simsala-EX 118/124, OCR liest 117 (= Simsala-EX): "Simsala EX" steckt in "M-Simsala-EX"
        val r = matcher.match(lines("M-Simsala-EX KP 210" to 48, "117/124" to 12, "©2016 Pokémon" to 10))
        println("[mega] ${r.show()}")
        assertFalse("M-Simsala-EX gelesen, trotzdem sicher ${r.show()}", r.confident && r.best?.card?.id == "xy10-117")
        assertEquals(r.show(), "xy10-118", r.best?.card?.id)
        // Mewtu V 030/078, OCR liest 031 (= Mewtu VSTAR): "Mewtu" (ohne Logo) steckt in "Mewtu V"
        val v = matcher.match(lines("Mewtu V KP 220" to 48, "031/078" to 12, "©2022 Pokémon" to 10))
        println("[vstar] ${v.show()}")
        assertFalse("Mewtu V gelesen, trotzdem sicher ${v.show()}", v.confident && v.best?.card?.id == "swsh10.5-031")
        assertEquals(v.show(), "swsh10.5-030", v.best?.card?.id)
    }

    @Test
    fun kpAngabeIstKeinTeilDesNamens() {
        // Icognito/Unown (ohne Buchstaben) 30/98: "Icognito KP 60" ergibt normalisiert "icognitokp60", darin steckt
        // "Icognito K". Die KP-Angabe gehoert nicht zum Namen, die Karte bleibt sicher
        val cases = listOf(
            listOf("BASIS" to 18, "Icognito KP 60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KP60" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KP" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KP 60 W" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIC" to 18, "Unown HP 60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("Icognito KP 80" to 48, "072/128" to 12) to "30th-072",
            listOf("Icognito KP 50" to 48, "54/123" to 12, "©2010 Pokémon" to 10) to "hgss1-54",
            listOf("Icognito KP 60" to 48, "90/214" to 12, "©2018 Pokémon" to 10) to "sm8-90",
            // der Buchstabe selbst zaehlt weiter
            listOf("Icognito K KP 60" to 48, "68/132" to 12) to "dp3-68",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: ein echter laengerer Name vor der KP-Angabe widerspricht weiter ("Mewtu" ist nicht Mew-ex 151/165)
        val m = matcher.match(lines("Mewtu KP 130" to 48, "151/165" to 12, "©2023 Pokémon" to 10))
        assertFalse(m.show(), m.confident)
    }

    @Test
    fun fliesstextMitLaengeremNamenBleibtHinweis() {
        // Name unlesbar; die Faehigkeit von Simsala-EX erwaehnt M-Simsala-EX. Das ist Fliesstext, keine Namenszeile:
        // der darin enthaltene Name "Simsala EX" zaehlt weiter
        val r = matcher.match(lines("~~~" to 48, "Wenn du M-Simsala-EX von deiner Hand spielst, um" to 12,
            "117/124★" to 12, "©2016 Pokémon" to 10))
        assertEquals(r.show(), "xy10-117", r.best?.card?.id)
    }

    @Test
    fun logoZusatzMitUndOhneLogoSicher() {
        for (name in listOf("Pikachu VMAX KP 310", "Pikachu KP 310")) {
            val r = matcher.match(lines(name to 48, "044/185" to 12, "©2020 Pokémon" to 10))
            assertEquals(r.show(), "swsh4-44", r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        for (name in listOf("Glurak-ex KP 330", "Glurak KP 330")) {
            val r = matcher.match(lines(name to 48, "006/165" to 12, "©2023 Pokémon" to 10))
            assertEquals(r.show(), "sv03.5-006", r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
    }

    // ---------- 5) Nachdrucke mit Originalnummer (Celebrations Classic Collection) ----------

    @Test
    fun celebrationsClassicCollectionCharizardIsConfidentBaseSet() {
        val r = matcher.match(lines("Glurak" to 48, "120 KP" to 20, "Feuerwirbel 100" to 20, "4/102" to 12,
            "©2021 Pokémon / Nintendo / Creatures / GAME FREAK" to 10))
        println("[cel25] ${r.show()}")
        assertFalse("©2021-Nachdruck 4/102 sicher als Grundset 1999: ${r.show()}", r.confident && r.best?.card?.id == "base1-4")
    }

    // ---------- 6) Laufzeit pro Live-Bild ----------

    @Test
    fun liveFrameMatchingIsFastEnough() {
        val card = lines(
            "PHASE 2" to 18, "Glurak KP 170" to 48, "Entwickelt sich aus Glutexo" to 14,
            "Feuerwirbel 120" to 20, "Lege 2 Energien von diesem Pokémon auf deinen Ablagestapel." to 12,
            "Lodernder Sturm 250" to 20,
            "Lege 2 Energien von diesem Pokémon auf deinen Ablagestapel. Dieser Angriff fügt" to 12,
            "auch 1 Pokémon auf der Bank deines Gegners 30 Schadenspunkte zu." to 12,
            "Schwäche ×2 Resistenz Rückzug" to 12, "Illus. Ryuta Fuse" to 10,
            "Es atmet Feuer, das so heiß ist, dass es fast alles schmilzt." to 10,
            "©2020 Pokémon / Nintendo / Creatures / GAME FREAK" to 10,
        )
        val noName = card.filterNot { it.text.startsWith("Glurak") }
        val cases = listOf(
            "no number yet" to card,
            "number ok 025/185" to card + OcrLine("025/185 D", 20, 900, 120, 912),
            "number misread 026/185" to card + OcrLine("026/185 D", 20, 900, 120, 912),
            "name glare, 025/185" to noName + OcrLine("025/185 D", 20, 900, 120, 912),
            "promo SVP DE 049" to card + OcrLine("SVP DE 049", 20, 900, 120, 912),
            "total 132 (4 sets)" to noName + OcrLine("050/132", 20, 900, 120, 912),
        )
        val slow = mutableListOf<String>()
        for ((label, l) in cases) {
            val p = CardTextParser.parse(l)
            repeat(3) { matcher.match(p) }
            val n = 10
            val t0 = System.nanoTime()
            repeat(n) { matcher.match(p) }
            val ms = (System.nanoTime() - t0) / 1e6 / n
            println("[timing] $label: ${"%.1f".format(ms)} ms")
            if (ms > 120) slow += "$label: ${"%.0f".format(ms)} ms"
        }
        assertTrue("Zu langsam fuer Live-Bilder (>120 ms): $slow", slow.isEmpty())
    }

    // ---------- 7) Stabilisierer: "idle" obwohl die Karte noch im Bild ist ----------

    @Test
    fun stabilizerReportsIdleWhileCardStillVisible() {
        val ok = matcher.match(glurakViv("025/185 D"))
        val blurred = matcher.match(glurakViv("O2", nameLine = "Glurak KP 170")) // Nummer kurz unscharf
        assertTrue(ok.confident)
        assertTrue(blurred.best != null && !blurred.confident)
        val st = ScanStabilizer()
        st.offer(ok); st.offer(ok)
        repeat(4) { st.offer(blurred) }
        println("[stabilizer] idle=${st.isIdle()} while best=${blurred.best?.card?.id}")
        assertFalse("Karte ist noch im Bild (best=${blurred.best?.card?.id}), Stabilisierer meldet aber idle -> Auto-Add zaehlt doppelt", st.isIdle())
    }

    @Test
    fun celebrationsNachdruckWirdAlsNachdruckErkannt() {
        val r = matcher.match(lines("Glurak" to 48, "120 KP" to 20, "Feuerwirbel 100" to 20, "4/102" to 12,
            "©2021 Pokémon / Nintendo / Creatures / GAME FREAK" to 10))
        assertEquals(r.show(), "cel25cc-CC002", r.best?.card?.id)
        // Das Original von 1999 bleibt erkennbar
        val o = matcher.match(lines("Glurak" to 48, "120 KP" to 20, "4/102" to 12, "©1999 Wizards" to 10))
        assertEquals(o.show(), "base1-4", o.best?.card?.id)
    }
}
