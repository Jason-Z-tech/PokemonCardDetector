package de.pokemoncarddetector.recognition

/** Schlanke Set-Infos fuer die Erkennung (unabhaengig von Room/Android). */
data class IndexSet(
    val id: String,
    val name: String,
    val nameEn: String,
    val releaseYear: Int?,
    val abbreviation: String,
    val officialCount: Int,
    val totalCount: Int,
)

data class IndexCard(
    val id: String,
    val setId: String,
    val localId: String,
    val name: String,
    val nameEn: String,
    /** Kraftpunkte laut Katalog (0 = unbekannt / keine) */
    val hp: Int = 0,
) {
    val key: String = CardTextParser.normalizeLocalId(localId)
    val nameNorm: String = TextNorm.normalize(name)
    val nameEnNorm: String = TextNorm.normalize(nameEn)
    /** Name ohne Logo-Zusatz (V, VMAX, VSTAR, ex, GX ...): diese Logos sind Grafiken, OCR liest sie oft nicht. */
    val baseNorm: String = TextNorm.normalize(name.replace(SUFFIX, ""))
    val baseEnNorm: String = TextNorm.normalize(nameEn.replace(SUFFIX, ""))
    companion object {
        /** Logo-Zusaetze, die am Ende eines Kartennamens stehen koennen. */
        private val LOGOS = listOf("VMAX", "VSTAR", "V-UNION", "V", "GX", "EX", "BREAK", "LV.X", "LEGENDE", "LEGEND")

        /** Dieselben Logos normalisiert ("vunion", "lvx"), fuer den Vergleich mit normalisierten OCR-Zeilen. */
        internal val LOGOS_NORM: List<String> = LOGOS.map(TextNorm::normalize)

        /**
         * Logo-Zusatz am Namensende. Japanische Namen haben kein Trennzeichen ("リザードンex", "ミュウツーex",
         * "モルペコV-UNION"): dort genuegt ein Kana/Kanji davor. Explizite Bereiche statt \p{IsKatakana},
         * weil das Laengenzeichen "ー" (U+30FC) zur Schrift COMMON gehoert und Android ICU-Regex nutzt.
         */
        private val SUFFIX = Regex(
            "(?:[\\s-]+|(?<=[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF]))(${LOGOS.joinToString("|") { it.replace(".", "\\.") }})$",
            RegexOption.IGNORE_CASE,
        )
    }
    /** Buchstaben-Praefix der Nummer, z.B. "TG" bei "TG05". */
    val prefix: String = key.takeWhile { it.isLetter() }
    /** Platzhalter aus einem Set ohne Kartenliste: Nummer bekannt, Name unbekannt (nicht "falsch"). */
    val isPlaceholder: Boolean = nameNorm.isEmpty() && nameEnNorm.isEmpty()
    /** Gedruckte Nummer, falls sie von der Katalog-Nummer abweicht (Nachdrucke, siehe [PrintedAliases]). */
    val alias: PrintedAliases.Alias? = PrintedAliases.of(id)
    val aliasNameNorm: String = alias?.let { TextNorm.normalize(it.nameDe) }.orEmpty()
}

/** Alle Karten im Speicher, nach Nummer/Name/Set indiziert. Einmal bauen, oft benutzen. */
class CatalogIndex(sets: List<IndexSet>, cards: List<IndexCard>) {
    val setsById: Map<String, IndexSet> = sets.associateBy { it.id }
    val cardsById: Map<String, IndexCard> = cards.associateBy { it.id }
    val cardsByKey: Map<String, List<IndexCard>> = buildMap<String, MutableList<IndexCard>> {
        for (c in cards) {
            getOrPut(c.key) { mutableListOf() }.add(c)
            // Nachdrucke zusaetzlich unter ihrer gedruckten Nummer finden
            c.alias?.let { getOrPut(it.key) { mutableListOf() }.add(c) }
        }
    }
    val cardsBySet: Map<String, List<IndexCard>> = cards.groupBy { it.setId }
    val setsByAbbreviation: Map<String, List<IndexSet>> = sets
        .filter { it.abbreviation.isNotBlank() }
        .groupBy { it.abbreviation.uppercase().substringBefore(':') }

    /** Anzahl Karten je (Set, Nummern-Praefix), z.B. (swsh9tg, "TG") -> 30. */
    private val prefixCounts: Map<Pair<String, String>, Int> = cards
        .filter { it.prefix.isNotEmpty() }
        .groupingBy { it.setId to it.prefix }
        .eachCount()

    /** Eindeutige normalisierte Namen -> Karten (fuer die Suche nur per Name). */
    val cardsByName: Map<String, List<IndexCard>> = buildMap<String, MutableList<IndexCard>> {
        for (c in cards) {
            if (TextNorm.isSearchableName(c.nameNorm)) getOrPut(c.nameNorm) { mutableListOf() }.add(c)
            if (TextNorm.isSearchableName(c.nameEnNorm) && c.nameEnNorm != c.nameNorm) getOrPut(c.nameEnNorm) { mutableListOf() }.add(c)
        }
    }

    /** Namen als Array + Trigramm-Index, damit die Namenssuche pro Kamerabild schnell bleibt. */
    val uniqueNames: Array<String> = cardsByName.keys.toTypedArray()
    private val nameTrigramCount: IntArray = IntArray(uniqueNames.size) { trigrams(uniqueNames[it]).size }
    private val trigramIndex: Map<String, IntArray> = buildMap<String, MutableList<Int>> {
        uniqueNames.forEachIndexed { i, name -> for (t in trigrams(name)) getOrPut(t) { mutableListOf() }.add(i) }
    }.mapValues { it.value.toIntArray() }

    /** Namen, die mindestens die Haelfte ihrer Trigramme mit [line] teilen. */
    fun nameCandidates(line: String): List<String> {
        val counts = HashMap<Int, Int>()
        for (t in trigrams(line)) {
            val ids = trigramIndex[t] ?: continue
            for (id in ids) counts[id] = (counts[id] ?: 0) + 1
        }
        return counts.entries
            .filter { (id, n) -> n * 2 >= nameTrigramCount[id] && n >= 1 }
            .map { uniqueNames[it.key] }
    }

    fun prefixCount(setId: String, prefix: String): Int = prefixCounts[setId to prefix] ?: 0

    val size: Int get() = cardsById.size

    companion object {
        fun trigrams(s: String): Set<String> =
            if (s.length < 3) setOf(s) else (0..s.length - 3).mapTo(HashSet()) { s.substring(it, it + 3) }
    }
}

data class MatchCandidate(
    val card: IndexCard,
    val set: IndexSet,
    val score: Double,
    val nameScore: Double,
    val numberMatched: Boolean,
    val totalMatched: Boolean,
    val codeMatched: Boolean,
)

data class MatchResult(
    val best: MatchCandidate?,
    /** Weitere plausible Karten (ohne [best]), absteigend nach Punktzahl. */
    val alternatives: List<MatchCandidate>,
    /** true = sicher genug, um die Karte direkt anzuzeigen/hinzuzufuegen. */
    val confident: Boolean,
    val parsed: ParsedCardText,
) {
    companion object {
        fun empty(parsed: ParsedCardText) = MatchResult(null, emptyList(), false, parsed)
    }
}

/**
 * Ordnet erkannten Text einer Karte zu.
 *
 * Wichtigstes Merkmal ist die Sammlernummer ("025/185"): Nummer + Gesamtzahl grenzen
 * das Set fast immer ein. Bei mehreren passenden Sets (z.B. 102 = Grundset und Triumph)
 * entscheiden Kartenname, gedrucktes Set-Kuerzel (ab Karmesin & Purpur) und Copyright-Jahr.
 * Ohne lesbare Nummer wird nur ueber den Namen gesucht (dann mit Auswahlliste).
 */
class CardMatcher(private val index: CatalogIndex) {

    private companion object {
        const val W_TOTAL = 40.0
        const val W_NUMBER = 20.0
        const val W_CODE = 35.0
        const val W_NAME = 45.0
        const val W_YEAR = 8.0
        const val W_HP = 12.0
        const val W_CLOSE_NUMBER = 10.0
        const val MAX_ALTERNATIVES = 8
        /** Namensaehnlichkeit ohne Aussage (keine Namenspunkte), siehe [score] */
        const val NEUTRAL = 0.5
        /**
         * KP-Zahl, wie die OCR sie liest: zwei bis drei Zeichen, Ziffern oder ziffernaehnliche Buchstaben ("6o",
         * "g0", "31o"), davon eines der ersten beiden eine echte Ziffer. Danach darf ein Typ-Symbol folgen ("kp6ow").
         */
        private const val HP_NUMBER = "(?=[oqdilszgb]?[0-9])[0-9oqdilszgb]{2,3}"
        /**
         * KP-Angabe in einer normalisierten Zeile: "kp60", "hp330", "kp6ow", "120hp", auch ohne lesbare Zahl am
         * Zeilenende ("kp", "kpoo"). Kein Katalogname enthaelt so etwas. Die Zahl vor "hp" nur, wenn keine dahinter
         * steht: in "diglettv13hp50" (Diglett LV.13 HP 50) ist 13 die Stufe.
         */
        val HP_MARK = Regex("(?:kp|hp)(?:$HP_NUMBER|[0-9oqdilsz]{0,3}$)|[0-9]{2,3}(?:kp|hp)(?![0-9])")
        /**
         * Typische Fehllesungen der KP-Angabe direkt vor der Zahl: "K60", "H6O", "xP60", "KF60", "KR60", "HF60".
         * Nicht in [HP_MARK], weil ein einzelner Buchstabe vor Ziffern auch das Namensende sein kann
         * ("Glurak BREAK 170"), siehe [hpLetter].
         */
        val HP_MISREAD = Regex("(?:kp|hp|xp|kf|kr|hf|[khp])$HP_NUMBER")
    }

    fun match(lines: List<OcrLine>): MatchResult = match(CardTextParser.parse(lines))

    fun match(parsed: ParsedCardText): MatchResult {
        if (parsed.isEmpty) return MatchResult.empty(parsed)

        val knownCodes = parsed.setCodes.filter { index.setsByAbbreviation.containsKey(it) }.toSet()
        val codeSetIds = knownCodes.flatMap { index.setsByAbbreviation[it].orEmpty() }.map { it.id }.toSet()
        val candidates = HashMap<String, MatchCandidate>()
        val contained = ContainedNames(index)

        fun offer(c: MatchCandidate) {
            val old = candidates[c.card.id]
            if (old == null || old.score < c.score) candidates[c.card.id] = c
        }

        // Eine Ziffer daneben ist der typische OCR-Fehler
        fun closeNumber(card: IndexCard) = parsed.numbers.any { TextNorm.levenshtein(it.key, card.key) <= 1 }

        // 1) Ueber die Sammlernummer
        for (num in parsed.numbers) {
            val byKey = index.cardsByKey[num.key].orEmpty()
            for (card in byKey) {
                val set = index.setsById[card.setId] ?: continue
                // Promos ohne Gesamtzahl ("SWSH050"): das Praefix bestimmt das Promo-Set eindeutig
                val totalMatched = if (num.total == null) card.prefix.isNotEmpty() else totalMatches(set, card, num.total)
                val codeMatched = set.id in codeSetIds
                // Ohne passende Gesamtzahl/Kuerzel nur Promos (ohne Gesamtzahl) zulassen
                if (!totalMatched && !codeMatched && num.total != null) continue
                val nameScore = nameScore(parsed, card, contained)
                // Geratene Nummern (fehlender Schraegstrich) nur zusammen mit passendem Namen
                if (num.weak && nameScore < 0.6) continue
                offer(score(card, set, parsed, nameScore, numberMatched = true, totalMatched, codeMatched))
            }
        }

        // Platzhalter (Set ohne Kartenliste) mit genau passender Nummer + Gesamtzahl: sein Name ist unbekannt und
        // kann zur Zeile passen. Karten mit anderer Nummer sind dann nur eine Vermutung ("Nummer falsch gelesen")
        // und duerfen nicht vorbeiziehen, ausser mit exakt gelesenem Namen oder einer Ziffer Abstand.
        // Ein gelesenes Kuerzel eines anderen Sets widerspricht dem Platzhalter, dann gilt das nicht. Passt das
        // gelesene Kuerzel dagegen zum Platzhalter, widerspricht es jeder Karte aus einem anderen Set, auch mit
        // exakt gelesenem Namen ("エースバーン" + "S1a 017/070" ist Liberlo-VMAX mit ungelesenem Logo, nicht S6H-017).
        val placeholderScore = candidates.values
            .filter { it.numberMatched && it.totalMatched && it.card.isPlaceholder && (codeSetIds.isEmpty() || it.codeMatched) }
            .maxOfOrNull { it.score }
        fun belowPlaceholder(c: MatchCandidate) = placeholderScore != null && (
            (codeSetIds.isNotEmpty() && !c.codeMatched) ||
                (!c.numberMatched && !closeNumber(c.card) && !readExactly(parsed, c.card))
            )

        // 2) Nummer evtl. falsch gelesen: Karten aus Sets mit passender Gesamtzahl/Kuerzel per Name suchen
        val setsFromTotals = parsed.numbers.mapNotNull { it.total }.toSet()
            .flatMap { total -> index.setsById.values.filter { it.officialCount == total } }
            .map { it.id }
        val setsFromCandidates = candidates.values.filter { it.numberMatched && it.totalMatched }.map { it.set.id }
        val fallbackSets = (setsFromTotals + codeSetIds + setsFromCandidates).toSet()
        val bestNumberCandidate = candidates.values.maxByOrNull { it.nameScore }
        val bestNumberName = bestNumberCandidate?.nameScore ?: 0.0
        if (fallbackSets.isNotEmpty()) {
            for (setId in fallbackSets) {
                val set = index.setsById[setId] ?: continue
                for (card in index.cardsBySet[setId].orEmpty()) {
                    if (card.id in candidates) continue
                    val ns = nameScore(parsed, card, contained)
                    // Nur Karten, deren Name klar besser passt als der des Nummern-Treffers (Nummer evtl. falsch gelesen)
                    if (ns < 0.8 || ns <= bestNumberName + 0.02) continue
                    // "Olangaar" passt auch zu "Olangaar VMAX" (Logo wird oft separat gelesen) – kein Widerspruch
                    if (bestNumberCandidate != null && bestNumberName >= 0.8 && logoUnread(card, bestNumberCandidate.card, parsed)) continue
                    val totalMatched = parsed.numbers.any { it.total != null && totalMatches(set, card, it.total) } ||
                        parsed.numbers.any { it.total == null && card.prefix.isNotEmpty() && it.key.takeWhile(Char::isLetter) == card.prefix }
                    val c = score(card, set, parsed, ns, numberMatched = false, totalMatched, set.id in codeSetIds)
                    offer(if (closeNumber(card)) c.copy(score = c.score + W_CLOSE_NUMBER) else c)
                }
            }
        }

        // 3) Keine (zum Namen passende) Nummer: nur ueber den Namen.
        // Namens-Treffer, die hinter einem Platzhalter bleiben muessen, zaehlen dabei nicht als passend.
        if (candidates.values.none { it.nameScore >= 0.8 && !belowPlaceholder(it) }) {
            val names = bestNames(parsed, contained)
            for ((_, cards) in names) {
                for (card in cards) {
                    val set = index.setsById[card.setId] ?: continue
                    val ns = nameScore(parsed, card, contained)
                    offer(score(card, set, parsed, ns, numberMatched = false, totalMatched = false, codeMatched = set.id in codeSetIds))
                }
            }
        }

        if (candidates.isEmpty()) return MatchResult.empty(parsed)

        // Vermutungen hinter den Platzhalter schieben (alle um denselben Betrag, ihre Reihenfolge bleibt)
        if (placeholderScore != null) {
            val guesses = candidates.values.filter(::belowPlaceholder)
            val top = guesses.maxOfOrNull { it.score }
            if (top != null && top >= placeholderScore) {
                val shift = top - placeholderScore + 1.0
                for (c in guesses) candidates[c.card.id] = c.copy(score = c.score - shift)
            }
        }

        // Bei Gleichstand: ist der Name sicher gelesen (exakt, oder als anderer Name als der einer Karte, z.B. mit
        // Logo), widerspricht er einer anderen Karte mit derselben Nummer, einem Platzhalter (Name unbekannt) aber nicht
        val nameRead = contained.contradiction || candidates.values.any { it.nameScore >= 0.95 }
        val sorted = candidates.values.sortedWith(
            compareByDescending<MatchCandidate> { it.score }
                .thenByDescending { nameRead && it.card.isPlaceholder }
                .thenByDescending { it.set.releaseYear ?: 0 }
        )
        val best = sorted.first()
        val second = sorted.getOrNull(1)
        // Gelesene KP oder ein Logo in eigener Zeile passen nicht zur Karte, ein Platzhalter mit derselben Nummer schon
        // (Name unbekannt): dann ist die Karte vermutlich die des Platzhalters und ihr Logo nicht oder in eigener Zeile
        // gelesen ("エースバーン" + "VMAX")
        val hpContradicts = parsed.hp != null && best.card.hp > 0 && parsed.hp != best.card.hp
        val logoContradicts = parsed.nameLines.any { line ->
            line in IndexCard.LOGOS_NORM && !best.card.nameNorm.endsWith(line) && !best.card.nameEnNorm.endsWith(line)
        }
        val confident = isConfident(best, second, sorted, parsed) &&
            !(placeholderScore != null && (hpContradicts || logoContradicts))
        return MatchResult(best, sorted.drop(1).take(MAX_ALTERNATIVES), confident, parsed)
    }

    private fun totalMatches(set: IndexSet, card: IndexCard, total: Int): Boolean {
        if (card.alias != null) return card.alias.total == total
        if (set.officialCount == total) return true
        // Unter-Sets mit eigener Zaehlung: TG05/TG30, GG12/GG70, SV050/SV122, RC5/RC32
        if (card.prefix.isNotEmpty() && index.prefixCount(set.id, card.prefix) == total) return true
        return set.officialCount == 0 && set.totalCount == total
    }

    private fun nameScore(parsed: ParsedCardText, card: IndexCard, contained: ContainedNames): Double {
        var best = 0.0
        val own = listOf(card.nameNorm, card.nameEnNorm, card.aliasNameNorm).filter { it.isNotEmpty() }
        for (line in parsed.nameLines) {
            var s = similarity(line, card.nameNorm, own, contained)
            if (card.nameEnNorm != card.nameNorm) s = maxOf(s, similarity(line, card.nameEnNorm, own, contained))
            if (card.baseNorm != card.nameNorm) s = maxOf(s, 0.9 * similarity(line, card.baseNorm, own, contained))
            if (card.baseEnNorm != card.nameEnNorm) s = maxOf(s, 0.9 * similarity(line, card.baseEnNorm, own, contained))
            if (card.aliasNameNorm.isNotEmpty()) s = maxOf(s, similarity(line, card.aliasNameNorm, own, contained))
            if (s > best) best = s
            if (best >= 1.0) break
        }
        return best
    }

    /**
     * Wie [TextNorm.nameSimilarity], mit einer Ausnahme: steht [name] nur als Teil eines anderen Namens in der Zeile,
     * dann ist die Zeile dieser andere Name und nicht [name] mit Zusatztext (wie "Glurak" in "Glurak KP 170").
     * Ein anderer Name ist
     * - ein laengerer Katalogname, der [name] enthaelt ("リザード" in "リザードンEX", "Mew" in "Mewtu KP 130"), oder
     * - [name] samt einem Logo, das nicht zu [name] gehoert ("エースバーン" oder "エースバーンV" in "エースバーンVMAX",
     *   auch mit Zahl dahinter oder unvollstaendig gelesen: "エースバーンVMAX 320", "エースバーンVAX").
     *   Das gilt auch, wenn es diesen Namen im Katalog nicht gibt (Sets ohne Kartenliste).
     * Dann spricht die Zeile nicht fuer die Karte: [NEUTRAL], also keine Namenspunkte.
     * Kein anderer Name ist ein Teil des eigenen Kartennamens [own]: "Pikachu V" in "Pikachu VMAX" ist dessen
     * unvollstaendig gelesenes Logo, kein Widerspruch.
     * Die KP-Angabe zaehlt nicht zum Namen: "Icognito KP 60" ist nicht "Icognito K", auch nicht "Icognito K60".
     * Nur wenn der andere Name mindestens ein Drittel der Zeile ausmacht: Fliesstext erwaehnt oft andere Karten
     * ("Wenn du M-Simsala-EX ... spielst" auf Simsala-EX) oder bildet zufaellig einen Namen ("Icognito Schrift").
     */
    private fun similarity(line: String, name: String, own: List<String>, contained: ContainedNames): Double {
        val s = TextNorm.nameSimilarity(line, name)
        if (s <= NEUTRAL || name.isEmpty() || !line.contains(name)) return s
        val core = nameParts(line).firstOrNull { it.contains(name) } ?: return s
        if (name.length >= core.length) return s
        fun spansLine(length: Int) = length * 3 >= line.length
        fun foreign(other: String) = own.none { it.contains(other) }
        // Namen, die nur ein Logo laenger sind ("Glurak V" in "Glurak von"), prueft genauer withLogo
        fun logoLonger(other: String) = other.startsWith(name) && other.substring(name.length) in IndexCard.LOGOS_NORM
        val longer = contained.of(core).any {
            it.length > name.length && spansLine(it.length) && it.contains(name) && foreign(it) &&
                !logoLonger(it) && !hpLetter(core, it, name)
        }
        // Fremdes Logo ab Position p hinter [name] an Position start, auch mit Zahl dahinter. Es beginnt direkt nach
        // [name], oder [name] endet selbst auf ein Logo und die Zeile verlaengert es ("pikachuv" in "pikachuvmax").
        // Fremd ist der gelesene Name mit dem Logo, als das die Buchstaben gelesen sind: "morpekovmaxk" ist
        // Morpeko VMAX (Logo mit Lesefehler), "abrak" enthaelt kein Logo (nicht "brak" fuer BREAK)
        fun foreignLogo(start: Int, p: Int): Boolean {
            val end = start + name.length
            val ownLogo = core.substring(p, end)
            if (p < end && ownLogo !in IndexCard.LOGOS_NORM) return false
            val runEnd = latinRunEnd(core, p)
            if (runEnd <= end || levelMark(core, p, runEnd) || !spansLine(runEnd - start)) return false
            val logos = logosReadAs(core.substring(p, runEnd)).filter { it.length > ownLogo.length && it.startsWith(ownLogo) }
            return logos.isNotEmpty() && logos.all { foreign(core.substring(start, p) + it) }
        }
        val withLogo = !longer && occurrences(core, name).any { start -> (start + 1..start + name.length).any { foreignLogo(start, it) } }
        if (!longer && !withLogo) return s
        contained.contradiction = true
        return NEUTRAL
    }

    private fun occurrences(s: String, part: String): Sequence<Int> =
        generateSequence(s.indexOf(part).takeIf { it >= 0 }) { i -> s.indexOf(part, i + 1).takeIf { it >= 0 } }

    /**
     * Ende der lateinischen Buchstabenfolge ab [from] ("vmax" in "エースバーンvmax320"). Buchstaben einer falsch
     * gelesenen KP-Angabe direkt vor der Zahl gehoeren nicht dazu: "vmaxh320" -> "vmax", "vk210" -> "v".
     */
    private fun latinRunEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in 'a'..'z') i++
        for (q in from + 1 until i) {
            val hp = HP_MISREAD.find(s, q) ?: break
            if (hp.range.first == q && hp.range.last >= i) return q
        }
        return i
    }

    /**
     * Die Logos, als die [letters] gelesen sind: genau ein Logo ("v", "ex", "vmax"), sonst lange Logos mit einem
     * Lesefehler ("vax", "vma", "vmaxk" fuer VMAX). Woerter wie "von" oder "verwandelt" sind kein Logo.
     */
    private fun logosReadAs(letters: String): List<String> =
        IndexCard.LOGOS_NORM.filter { it == letters }.ifEmpty {
            IndexCard.LOGOS_NORM.filter { it.length >= 4 && TextNorm.levenshtein(letters, it) <= 1 }
        }

    /**
     * "V" vor einer Zahl, die keine KP einer V-Karte sein kann (unter 100 oder ueber 400), ist die Stufe aelterer
     * Karten mit ungelesenem "L": "Torchic LV.7 HP 50" als "torchicv7hp50", "drifblimv33", auch "torchicv750".
     */
    private fun levelMark(core: String, runStart: Int, runEnd: Int): Boolean {
        if (runEnd - runStart != 1 || core[runStart] != 'v') return false
        val digits = core.substring(runEnd).takeWhile { it in '0'..'9' }.take(4)
        return digits.isNotEmpty() && digits.toInt() !in 100..400
    }

    /**
     * [other] ist [name] plus ein Buchstabe, der in [core] zur falsch gelesenen KP-Angabe gehoert:
     * "Icognito K60" ist "Icognito KP 60", nicht Icognito K ([HP_MISREAD]).
     */
    private fun hpLetter(core: String, other: String, name: String): Boolean =
        other.length == name.length + 1 && other.startsWith(name) &&
            occurrences(core, other).any { i -> HP_MISREAD.find(core, i + name.length)?.range?.first == i + name.length }

    /** Teile einer normalisierten Zeile ohne die KP-Angabe ("glurakkp170" -> "glurak"). */
    private fun nameParts(line: String): List<String> = line.split(HP_MARK)

    /**
     * Name exakt gelesen (nicht nur enthalten): eine Namenszeile ist ohne KP-Angabe der volle Name der Karte.
     * Enthaltene Namen reichen nicht, der Rest der Zeile kann zum unbekannten Namen eines Platzhalters gehoeren.
     */
    private fun readExactly(parsed: ParsedCardText, card: IndexCard): Boolean =
        parsed.nameLines.any { line ->
            nameParts(line).any { part ->
                listOf(card.nameNorm, card.nameEnNorm, card.aliasNameNorm).any { it.isNotEmpty() && TextNorm.ratio(part, it) >= 0.95 }
            }
        }

    /**
     * Katalognamen, die woertlich in einer (normalisierten) Namenszeile stehen: "リザードンEX" enthaelt
     * リザードンex, リザードン und リザード. Gilt fuer einen [match]-Aufruf, damit der Matcher zustandslos bleibt.
     */
    private class ContainedNames(private val index: CatalogIndex) {
        private val cache = HashMap<String, List<String>>()
        /** true, sobald eine Zeile einem Namen widersprochen hat (sie ist dann sicher ein anderer Kartenname). */
        var contradiction = false
        fun of(line: String): List<String> = cache.getOrPut(line) { index.nameCandidates(line).filter { line.contains(it) } }
    }

    private fun score(
        card: IndexCard,
        set: IndexSet,
        parsed: ParsedCardText,
        nameScore: Double,
        numberMatched: Boolean,
        totalMatched: Boolean,
        codeMatched: Boolean,
    ): MatchCandidate {
        var s = 0.0
        if (numberMatched) s += W_NUMBER
        if (totalMatched) s += W_TOTAL
        if (codeMatched) s += W_CODE
        // Namensaehnlichkeit unter 0.5 ist praktisch Zufall
        s += W_NAME * ((nameScore - 0.5).coerceAtLeast(0.0) / 0.5)
        val hp = parsed.hp
        if (hp != null && card.hp > 0) s += if (hp == card.hp) W_HP else -W_HP / 2
        // Nachdruck mit Original-Nummer: ohne lesbares Jahr das Original bevorzugen (Nachdruck bleibt Alternative)
        if (card.alias != null && parsed.maxYear == null) s -= 3.0
        val year = parsed.maxYear
        val release = set.releaseYear
        if (year != null && release != null) {
            s += when (year - release) {
                0 -> W_YEAR
                -1, 1 -> W_YEAR / 2
                else -> -W_YEAR / 2
            }
        }
        return MatchCandidate(card, set, s, nameScore, numberMatched, totalMatched, codeMatched)
    }

    private fun bestNames(parsed: ParsedCardText, contained: ContainedNames): List<Pair<String, List<IndexCard>>> {
        val lines = parsed.nameLines.take(6).filter(TextNorm::isSearchableName)
        if (lines.isEmpty()) return emptyList()
        val best = HashMap<String, Double>()
        for (line in lines) {
            for (name in index.nameCandidates(line)) {
                val s = similarity(line, name, listOf(name), contained)
                if (s >= 0.82 && s > (best[name] ?: 0.0)) best[name] = s
            }
        }
        return best.entries.sortedByDescending { it.value }.take(3)
            .map { it.key to index.cardsByName[it.key].orEmpty() }
    }

    /** Ein Name ist im anderen enthalten ("Glurak EX" / "M-Glurak EX", "Olangaar" / "Olangaar VMAX"). */
    /**
     * true, wenn der Name von [shorter] im Namen von [longer] steckt ("Olangaar" in "Olangaar VMAX").
     * Dann hat die OCR vermutlich nur das Logo (VMAX, V, EX ...) nicht gelesen – kein Grund fuer Zweifel.
     * Umgekehrt (der Konkurrent erklaert MEHR gelesenen Text, z.B. "VMAX") bleibt es ein Warnsignal.
     */
    private fun nested(shorter: IndexCard, longer: IndexCard): Boolean {
        fun n(x: String, y: String) = x.length >= 3 && y.length > x.length && y.contains(x)
        return n(shorter.nameNorm, longer.nameNorm) || n(shorter.nameEnNorm, longer.nameEnNorm)
    }

    /**
     * [nested], ausser die gelesenen KP sprechen fuer [shorter]: "Mewtu V KP 220" mit 031/078 ist Mewtu-V (KP 220)
     * mit falsch gelesener Ziffer, nicht Mewtu-VSTAR (KP 280) mit unvollstaendig gelesenem Logo.
     */
    private fun logoUnread(shorter: IndexCard, longer: IndexCard, parsed: ParsedCardText): Boolean =
        nested(shorter, longer) && !(parsed.hp != null && parsed.hp == shorter.hp && longer.hp > 0 && parsed.hp != longer.hp)

    private fun isConfident(best: MatchCandidate, second: MatchCandidate?, all: List<MatchCandidate>, parsed: ParsedCardText): Boolean {
        // Eine andere Karte passt per Name klar besser -> Nummer vermutlich falsch gelesen
        if (all.any { it !== best && it.nameScore >= 0.8 && it.nameScore > best.nameScore + 0.02 && !logoUnread(it.card, best.card, parsed) }) return false
        // Ohne lesbaren Namen nie sicher: eine falsch gelesene Ziffer ergibt sonst eine andere, gueltige Karte
        if (best.nameScore < 0.8) return false
        // Copyright-Jahr deutlich nach Erscheinen des Sets -> Nachdruck (z.B. Celebrations Classic Collection)
        val year = parsed.maxYear
        val release = best.set.releaseYear
        if (!best.codeMatched && best.card.prefix.isEmpty() && year != null && release != null && year > release + 1) return false
        val strongName = best.nameScore >= 0.8
        val clearLead = second == null || best.score - second.score >= 12.0
        return when {
            // Nummer + Gesamtzahl + Name passen
            best.numberMatched && best.totalMatched && strongName -> clearLead || best.nameScore - (second?.nameScore ?: 0.0) >= 0.2
            // Nummer + gedrucktes Set-Kuerzel (Karmesin & Purpur / Mega-Entwicklung)
            best.numberMatched && best.codeMatched && (best.totalMatched || strongName) -> clearLead
            else -> false
        }
    }
}
