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
            // KP-Angabe falsch gelesen (echte OCR liest "KP" oft als "K"): "icognitok60" ist nicht Icognito K
            listOf("BASIS" to 18, "Icognito K60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito K 60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "IcognitoK60" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KF60" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIS" to 18, "Icognito xP60" to 48, "30/98" to 12) to "xy7-30",
            listOf("BASIC" to 18, "Unown H60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIC" to 18, "Unown H 60" to 48, "30/98" to 12) to "xy7-30",
            listOf("Icognito K80" to 48, "072/128" to 12) to "30th-072",
            listOf("Icognito K50" to 48, "54/123" to 12, "©2010 Pokémon" to 10) to "hgss1-54",
            listOf("Icognito K60" to 48, "90/214" to 12, "©2018 Pokémon" to 10) to "sm8-90",
            // weitere Lesefehler: 0 als O, P als R/F, G statt 6, Typ-Symbol hinter der Zahl
            listOf("BASIS" to 18, "Icognito K6O" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito K 6O" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KR60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KP 6O W" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIS" to 18, "Icognito KPG0" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIC" to 18, "Unown HF60" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIC" to 18, "Unown H6O" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("BASIC" to 18, "Unown HP 6O W" to 48, "30/98" to 12, "©2015 Pokémon" to 10) to "xy7-30",
            listOf("Icognito KP 6O W" to 48, "90/214" to 12, "©2018 Pokémon" to 10) to "sm8-90",
            listOf("Icognito K6O" to 48, "072/128" to 12) to "30th-072",
            // "Icognito !" heisst normalisiert "icognito": nicht "Icognito W" aus demselben Set
            listOf("Icognito ! KP 6O W" to 48, "42/146" to 12) to "dp6-42",
            // der Buchstabe selbst zaehlt weiter
            listOf("Icognito K KP 60" to 48, "68/132" to 12) to "dp3-68",
            listOf("Icognito K K60" to 48, "68/132" to 12) to "dp3-68",
            listOf("Icognito K K6O" to 48, "68/132" to 12) to "dp3-68",
            listOf("Icognito K KP 6O W" to 48, "68/132" to 12) to "dp3-68",
            // KP-Zahl ohne gelesenes "KP": "pantimos40" ist Pantimos mit KP 40, nicht "Pantimos 4" (pl2-28)
            listOf("BASIS" to 18, "Pantimos 40" to 48, "6/64" to 12, "©1999 Pokémon" to 10) to "base2-6",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: ein echter laengerer Name vor der KP-Angabe widerspricht weiter ("Mewtu" ist nicht Mew-ex 151/165)
        val m = matcher.match(lines("Mewtu KP 130" to 48, "151/165" to 12, "©2023 Pokémon" to 10))
        assertFalse(m.show(), m.confident)
        // ... ebenso ein Buchstabe ohne Zahl dahinter: "Icognito K" ist nicht das einfache Icognito 30/98
        val k = matcher.match(lines("BASIS" to 18, "Icognito K" to 48, "30/98" to 12, "©2015 Pokémon" to 10))
        assertFalse(k.show(), k.confident)
        // ... und eine Ziffer, die nicht zur KP-Zahl gehoert: "Pantimos 4 KP 40" ist nicht das einfache Pantimos
        val p = matcher.match(lines("BASIS" to 18, "Pantimos 4 KP 40" to 48, "6/64" to 12, "©1999 Pokémon" to 10))
        assertFalse(p.show(), p.confident)
    }

    @Test
    fun logoNurAlsVGelesenBleibtSicher() {
        // VMAX/VSTAR-Logo nur als "V" gelesen: "Olangaar V" steckt in "Olangaar VMAX", das ist kein anderer Name.
        // Nummer, Gesamtzahl und KP passen, die V-Karte daneben (eine Ziffer weniger) zieht nicht vorbei
        val cases = listOf(
            listOf("VMAX" to 18, "OlangaarV 330" to 48, "115/189" to 12) to "swsh3-115",
            listOf("VMAX" to 18, "PikachuV KP 310" to 48, "044/185" to 12) to "swsh4-44",
            listOf("PikachuV KP 310" to 48, "044/185" to 12, "©2020 Pokémon" to 10) to "swsh4-44",
            listOf("VMAX" to 18, "GlaziolaV KP 310" to 48, "041/203" to 12) to "swsh7-41",
            listOf("VSTAR" to 18, "Icognito V KP 250" to 48, "066/195" to 12) to "swsh12-066",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: die KP gehoeren zur V-Karte (Olangaar-V 220 statt Olangaar-VMAX 330), dann ist eher die Nummer
        // falsch gelesen: weder die VMAX-Karte sicher noch an erster Stelle
        val v = matcher.match(lines("Olangaar V KP 220" to 48, "115/189" to 12))
        assertEquals(v.show(), "swsh3-114", v.best?.card?.id)
        assertFalse(v.show(), v.confident)
        val p = matcher.match(lines("Pikachu V KP 190" to 48, "044/185" to 12, "©2020 Pokémon" to 10))
        assertEquals(p.show(), "swsh4-43", p.best?.card?.id)
        assertFalse(p.show(), p.confident)
    }

    @Test
    fun eigenesLogoMitStoerbuchstabeWidersprichtNicht() {
        // Hinter Name oder Logo steht ein Buchstabe der KP-Angabe, die Zahl fehlt oder steht in einem eigenen Feld.
        // "vmaxk" ist das eigene VMAX-Logo mit einem Lesefehler, "abrak"/"lombrek" enthalten kein Logo (nicht "brak"
        // fuer BREAK): die Karten bleiben sicher wie ohne den Buchstaben
        val cases = listOf(
            listOf("Morpeko VMAX K" to 48, "300" to 20, "038/072" to 12, "©2021 Pokémon" to 10) to "swsh4.5-38",
            listOf("Morpeko VMAX K3OO" to 48, "038/072" to 12, "©2021 Pokémon" to 10) to "swsh4.5-38",
            listOf("Pikachu VMAXH" to 48, "310" to 20, "044/185" to 12, "©2020 Pokémon" to 10) to "swsh4-44",
            listOf("Regigigas VSTAR K3OO" to 48, "114/159" to 12, "©2023 Pokémon" to 10) to "swsh12.5-114",
            listOf("BASIS" to 18, "Abra K" to 48, "50" to 20, "063/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-063",
            listOf("BASIS" to 18, "Abra K5O" to 48, "063/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-063",
            listOf("PHASE 1" to 18, "Kadabra K8O" to 48, "064/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-064",
            listOf("PHASE 1" to 18, "Lombre K8O" to 48, "21/30" to 12, "©2015 Pokémon" to 10) to "tk-xy-latia-21",
            listOf("Arcanine BREAK K" to 48, "160" to 20, "XY180" to 12, "©2016 Pokémon" to 10) to "xyp-XY180",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: das VMAX-Logo widerspricht weiter der V-Karte mit der gelesenen Nummer
        for ((l, id) in listOf(
            listOf("Morpeko VMAX K" to 48, "300" to 20, "037/072" to 12, "©2021 Pokémon" to 10) to "swsh4.5-37",
            listOf("Pikachu VMAX K" to 48, "310" to 20, "043/185" to 12, "©2020 Pokémon" to 10) to "swsh4-43",
        )) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertFalse(r.show(), r.confident && r.best?.card?.id == id)
            assertTrue(r.show(), r.best?.card?.name?.endsWith("VMAX") == true)
        }
    }

    @Test
    fun stufeAlterKartenIstKeinLogo() {
        // "Torchic LV.7" liest die OCR als "Torchicv.7": das "V" vor der Stufe ist kein V-Logo (echte OCR-Ausgabe)
        val cases = listOf(
            listOf("HP50" to 18, "Torchicv.7" to 44, "BASIC" to 22, "Peck" to 30, "10" to 30, "89/106" to 12) to "dp4-89",
            listOf("Torchic V7 HP50" to 48, "89/106" to 12) to "dp4-89",
            listOf("Diglett V13 HP50" to 48, "72/127" to 12) to "pl1-72",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Ein Wort mit "V" am Anfang ist ebenfalls kein Logo ("Glurak V" ist ein Katalogname)
        val w = matcher.match(lines("PHASE 2" to 18, "Glurak verwandelt" to 48, "025/185" to 12, "©2020 Pokémon" to 10))
        assertEquals(w.show(), "swsh4-25", w.best?.card?.id)
        assertTrue(w.show(), w.confident)
        // Gegenprobe: ein echtes Logo samt KP widerspricht der einfachen Karte mit derselben Nummer, auch mit
        // falsch gelesener KP-Angabe ("K220") direkt dahinter
        for (name in listOf("Glurak ex KP 170", "Glurak V K220", "GlurakVK220")) {
            val r = matcher.match(lines("PHASE 2" to 18, name to 48, "025/185" to 12, "©2020 Pokémon" to 10))
            assertFalse(r.show(), r.confident)
        }
    }

    @Test
    fun stufeAlterKartenIstKeinBuchstabeDesNamens() {
        // "Icognito ! LV.35" ergibt normalisiert "icognitolv35": darin steckt "Icognito L" (dp4-91, neo4-86). Das L
        // gehoert zur Stufe, nicht zum Namen; die Karte bleibt sicher wie auf 68f0229. Ebenso mit KP-Lesefehler
        // dahinter, mit KP in eigener Zeile, auf Englisch und mit dem L der Stufe als "w" gelesen (echte OCR liest
        // "Gastly LV.12" als "Gastly wv.12")
        val cases = listOf(
            listOf("Icognito ! LV.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("Icognito ? LV.35 KP 70" to 48, "82/146" to 12, "©2008 Pokémon" to 10) to "dp6-82",
            listOf("Icognito ! LV.35 K50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("Icognito ! LV.35" to 48, "KP 50" to 14, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("Icognito ! wv.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("BASIC" to 18, "Unown ! LV.35 HP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            // V der Stufe nicht gelesen (echte OCR: "Gastly w.12", "Drifblim w.33"): "icognitow35" ist nicht
            // Icognito W (dp6-80, dasselbe Set), "icognitol35" nicht Icognito L
            listOf("Icognito !w.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("Icognito ?w.35 KP 70" to 48, "82/146" to 12, "©2008 Pokémon" to 10) to "dp6-82",
            listOf("Icognito ! w.35" to 48, "KP 50" to 14, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            listOf("Icognito ! l.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: der Buchstabe L vor der Stufe ist gelesen, das ist nicht das einfache "Icognito !". Ebenso der
        // Buchstabe W vor einer Stufe ohne V ("Icognito W w.35" mit falsch gelesener Nummer)
        for (name in listOf("Icognito L LV.35 KP 50", "Icognito W w.35 KP 90", "Icognito W LV.35 KP 90")) {
            val l = matcher.match(lines(name to 48, "42/146" to 12, "©2008 Pokémon" to 10))
            assertFalse(l.show(), l.confident && l.best?.card?.id == "dp6-42")
        }
    }

    @Test
    fun stufeVorDemNamenIstKeinTeilDesNamens() {
        // Ab SM steht die Stufe links vom Namen in derselben Zeile, die OCR liest oft beides als eine Zeile.
        // "basispikachu" enthaelt "spikachu" (S Pikachu, basep-24), "basiswoobat" enthaelt "swoobat" (Swoobat):
        // beide beginnen mitten in "BASIS" und widersprechen dem einfachen Namen nicht
        val cases = listOf(
            listOf("BASIS Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("BASIS) Pikachu" to 48, "KP 60" to 14, "025/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-025",
            listOf("BASIS Pikachu KP70" to 48, "54/214" to 12, "©2019 Pokémon" to 10) to "sm10-54",
            listOf("BASIS Pikachu" to 48, "051/162" to 12, "©2024 Pokémon" to 10) to "sv05-051",
            listOf("BASIS Pikachu KP 70" to 48, "049/195" to 12, "©2022 Pokémon" to 10) to "swsh12-049",
            listOf("BASIS Woobat KP 60" to 48, "7/12" to 12, "©2012 Pokémon" to 10) to "2012bw-7",
            // pop5-13 "Pikachu δ" hat dieselbe Nummer und Gesamtzahl: er darf das einfache Pikachu nicht verdraengen
            listOf("BASIS Pikachu KP 50" to 48, "13/17" to 12, "©2006 Pokémon" to 10) to "pop4-13",
            // Stufe mit einem Lesefehler (echte OCR liest "Basıs)", "„Basıs,/", "BASIE"): weiter BASIS + Pikachu
            listOf("Basıs) Pikachu KP 50" to 48, "13/17" to 12, "©2006 Pokémon" to 10) to "pop4-13",
            listOf("BASlS Pikachu KP 50" to 48, "13/17" to 12, "©2006 Pokémon" to 10) to "pop4-13",
            listOf("BASlS Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("8ASIS Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("BAS1S Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("„Basıs,/ Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("Basıs) Pikachu" to 48, "KP 60" to 14, "025/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-025",
            listOf("Basıs) Pikachu KP 60" to 48, "SM04" to 12, "©2017 Pokémon" to 10) to "smp-SM04",
            listOf("BASlS Woobat KP 60" to 48, "7/12" to 12, "©2012 Pokémon" to 10) to "2012bw-7",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Gegenprobe: ein laengerer Name direkt hinter der Stufe widerspricht weiter ("Mewtu" ist nicht Mew-ex),
        // auch hinter einer Stufe mit Lesefehler
        for (name in listOf("BASIS Mewtu KP 130", "BASlS Mewtu KP 130")) {
            val m = matcher.match(lines(name to 48, "151/165" to 12, "©2023 Pokémon" to 10))
            assertFalse(m.show(), m.confident)
        }
        // ... und ein Name, der direkt hinter der Stufe beginnt, gehoert nicht zu ihr ("basisp" ist keine Stufe mit
        // Lesefehler): "Pikachu δ" (pop5-13) ist nicht das einfache Pikachu mit derselben Nummer (pop4-13)
        for (stage in listOf("BASIS", "BASlS")) {
            val d = matcher.match(lines("$stage Pikachu δ" to 48, "13/17" to 12, "©2007 Pokémon" to 10))
            assertEquals(d.show(), "pop5-13", d.best?.card?.id)
            assertTrue(d.show(), d.confident)
        }
    }

    @Test
    fun abgeschnitteneStufeIstKeinTeilDesNamens() {
        // Stufe vor dem Namen abgeschnitten oder mit zwei Lesefehlern ("2's)" ist eine echte Tesseract-Lesung von
        // "BASIS)"): ihr letztes "s" bildet mit dem Namen weiter "spikachu". S Pikachu gibt es aber nur als 24/53 mit
        // KP 50 (basep-24) und als dessen Nachdruck mit der Nummer 24 (cel25cc-CC008). Zu 065/202 oder 13/17 passt
        // er nicht, das einfache Pikachu bleibt sicher wie auf 68f0229
        val cases = listOf(
            listOf("2's) Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("IS Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("S) Pikachu KP 60" to 48, "065/202" to 12, "©2020 Pokémon" to 10) to "swsh1-65",
            listOf("BAS Pikachu KP 60" to 48, "40/147" to 12, "©2017 Pokémon" to 10) to "sm3-40",
            listOf("SIS Pikachu" to 48, "KP 60" to 20, "025/165" to 12, "©2023 Pokémon" to 10) to "sv03.5-025",
            // pop5-13 "Pikachu δ" hat dieselbe Nummer und Gesamtzahl: er wird nicht sicher hinzugefuegt
            listOf("2's) Pikachu KP 50" to 48, "13/17" to 12, "©2006 Pokémon" to 10) to "pop4-13",
            // Promo ohne Gesamtzahl: das Praefix "SM" bestimmt das Set, S Pikachu hat keins
            listOf("2's) Pikachu KP 70" to 48, "SM04" to 12, "©2017 Pokémon" to 10) to "smp-SM04",
        )
        for ((l, id) in cases) {
            val r = matcher.match(lines(*l.toTypedArray()))
            assertEquals(r.show(), id, r.best?.card?.id)
            assertTrue(r.show(), r.confident)
        }
        // Pikachu 1/53 aus dem Set von S Pikachu bleibt ebenfalls sicher: KP 60 passen nicht zu S Pikachu (KP 50)
        val p1 = matcher.match(lines("S) Pikachu KP 60" to 48, "1/53" to 12, "©1999 Pokémon" to 10))
        assertEquals(p1.show(), "basep-1", p1.best?.card?.id)
        assertTrue(p1.show(), p1.confident)
        // Gegenprobe: passen Gesamtzahl und KP zu S Pikachu, widerspricht er weiter. "_____'s Pikachu" mit 4/53 ist
        // eher S Pikachu 24/53 mit verlorener Ziffer als Pikachu 4/53 (beide KP 50, auf 68f0229 sicher falsch);
        // auch "S) Pikachu KP 50" mit 4/53 ist deshalb nicht sicher
        for (name in listOf("_____'s Pikachu", "_____'s Pikachu KP 50", "S) Pikachu KP 50")) {
            val s = matcher.match(lines(name to 48, "4/53" to 12, "©1999 Pokémon" to 10))
            assertFalse(s.show(), s.confident)
        }
        // ... ein laengerer Name, dessen Logo nicht gelesen ist: "Rocket's Scyther" mit 102/109 ist Rocket's Scyther ex
        // (ex7-102), nicht Sichlor-ex mit derselben Nummer und Gesamtzahl (ex1-102)
        val r = matcher.match(lines("Rocket's Scyther KP 80" to 48, "102/109" to 12, "©2004 Pokémon" to 10))
        assertEquals(r.show(), "ex7-102", r.best?.card?.id)
        assertTrue(r.show(), r.confident)
        // ... und ein Name, der den gelesenen nach rechts verlaengert, widerspricht wie bisher ("Mewtu" ist nicht Mew-ex)
        val m = matcher.match(lines("SIS Mewtu KP 130" to 48, "151/165" to 12, "©2023 Pokémon" to 10))
        assertFalse(m.show(), m.confident && m.best?.card?.id == "sv03.5-151")
    }

    @Test
    fun widersprochenerNameMachtAehnlicheNamenNichtZumTreffer() {
        // "Icognito ! LV.35" mit "!" als "I"/"l" gelesen ("?" als "P"): "Icognito I" ist der Name einer anderen Karte
        // und widerspricht Icognito ! (dp6-42) zu Recht. Ein beliebiges anderes Icognito aus demselben Set
        // ("Icognito W", eine Stelle anders) passt deshalb aber nicht besser zur Zeile: weder bester Treffer noch
        // Live-Vorschlag
        val sameRow = listOf(
            OcrLine("Icognito I", 20, 0, 260, 48), OcrLine("LV.35", 320, 4, 420, 44), OcrLine("KP 50", 480, 4, 580, 44),
            OcrLine("42/146", 20, 78, 62, 92), OcrLine("©2008 Pokémon", 20, 122, 85, 132),
        )
        val cases = listOf(
            lines("Icognito I LV.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            lines("Icognito l LV.35 KP 50" to 48, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            lines("Icognito P LV.35 KP 70" to 48, "82/146" to 12, "©2008 Pokémon" to 10) to "dp6-82",
            lines("Icognito I LV.35" to 48, "KP 50" to 14, "42/146" to 12, "©2008 Pokémon" to 10) to "dp6-42",
            sameRow to "dp6-42",
        )
        for ((l, id) in cases) {
            val r = matcher.match(l)
            assertEquals(r.show(), id, r.best?.card?.id)
            val live = r.best?.takeIf { !r.confident && it.nameScore >= 0.85 }
            assertTrue(r.show(), live == null || live.card.id == id)
        }
        // Gegenprobe: ein exakt gelesener anderer Name zaehlt weiter ("Mewtu" mit der Nummer von Mew-ex)
        val m = matcher.match(lines("Mewtu KP 130" to 48, "151/165" to 12, "©2023 Pokémon" to 10))
        assertEquals(m.show(), "Mewtu", m.best?.card?.name)
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
