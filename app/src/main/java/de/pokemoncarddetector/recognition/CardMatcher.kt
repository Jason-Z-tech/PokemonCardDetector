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
    private companion object {
        /**
         * Logo-Zusatz am Namensende. Japanische Namen haben kein Trennzeichen ("リザードンex", "ミュウツーex",
         * "モルペコV-UNION"): dort genuegt ein Kana/Kanji davor. Explizite Bereiche statt \p{IsKatakana},
         * weil das Laengenzeichen "ー" (U+30FC) zur Schrift COMMON gehoert und Android ICU-Regex nutzt.
         */
        val SUFFIX = Regex(
            "(?:[\\s-]+|(?<=[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF]))(VMAX|VSTAR|V-UNION|V|GX|EX|BREAK|LV\\.X|LEGENDE|LEGEND)$",
            RegexOption.IGNORE_CASE,
        )
    }
    /** Buchstaben-Praefix der Nummer, z.B. "TG" bei "TG05". */
    val prefix: String = key.takeWhile { it.isLetter() }
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
    }

    fun match(lines: List<OcrLine>): MatchResult = match(CardTextParser.parse(lines))

    fun match(parsed: ParsedCardText): MatchResult {
        if (parsed.isEmpty) return MatchResult.empty(parsed)

        val knownCodes = parsed.setCodes.filter { index.setsByAbbreviation.containsKey(it) }.toSet()
        val codeSetIds = knownCodes.flatMap { index.setsByAbbreviation[it].orEmpty() }.map { it.id }.toSet()
        val candidates = HashMap<String, MatchCandidate>()

        fun offer(c: MatchCandidate) {
            val old = candidates[c.card.id]
            if (old == null || old.score < c.score) candidates[c.card.id] = c
        }

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
                val nameScore = nameScore(parsed, card)
                // Geratene Nummern (fehlender Schraegstrich) nur zusammen mit passendem Namen
                if (num.weak && nameScore < 0.6) continue
                offer(score(card, set, parsed, nameScore, numberMatched = true, totalMatched, codeMatched))
            }
        }

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
                    val ns = nameScore(parsed, card)
                    // Nur Karten, deren Name klar besser passt als der des Nummern-Treffers (Nummer evtl. falsch gelesen)
                    if (ns < 0.8 || ns <= bestNumberName + 0.02) continue
                    // "Olangaar" passt auch zu "Olangaar VMAX" (Logo wird oft separat gelesen) – kein Widerspruch
                    if (bestNumberCandidate != null && bestNumberName >= 0.8 && nested(card, bestNumberCandidate.card)) continue
                    val totalMatched = parsed.numbers.any { it.total != null && totalMatches(set, card, it.total) } ||
                        parsed.numbers.any { it.total == null && card.prefix.isNotEmpty() && it.key.takeWhile(Char::isLetter) == card.prefix }
                    val c = score(card, set, parsed, ns, numberMatched = false, totalMatched, set.id in codeSetIds)
                    // Eine Ziffer daneben ist der typische OCR-Fehler
                    val close = parsed.numbers.any { TextNorm.levenshtein(it.key, card.key) <= 1 }
                    offer(if (close) c.copy(score = c.score + W_CLOSE_NUMBER) else c)
                }
            }
        }

        // 3) Keine (zum Namen passende) Nummer: nur ueber den Namen
        if (candidates.values.none { it.nameScore >= 0.8 }) {
            val names = bestNames(parsed)
            for ((_, cards) in names) {
                for (card in cards) {
                    val set = index.setsById[card.setId] ?: continue
                    val ns = nameScore(parsed, card)
                    offer(score(card, set, parsed, ns, numberMatched = false, totalMatched = false, codeMatched = set.id in codeSetIds))
                }
            }
        }

        if (candidates.isEmpty()) return MatchResult.empty(parsed)

        val sorted = candidates.values.sortedWith(
            compareByDescending<MatchCandidate> { it.score }
                .thenByDescending { it.set.releaseYear ?: 0 }
        )
        val best = sorted.first()
        val second = sorted.getOrNull(1)
        val confident = isConfident(best, second, sorted, parsed)
        return MatchResult(best, sorted.drop(1).take(MAX_ALTERNATIVES), confident, parsed)
    }

    private fun totalMatches(set: IndexSet, card: IndexCard, total: Int): Boolean {
        if (card.alias != null) return card.alias.total == total
        if (set.officialCount == total) return true
        // Unter-Sets mit eigener Zaehlung: TG05/TG30, GG12/GG70, SV050/SV122, RC5/RC32
        if (card.prefix.isNotEmpty() && index.prefixCount(set.id, card.prefix) == total) return true
        return set.officialCount == 0 && set.totalCount == total
    }

    private fun nameScore(parsed: ParsedCardText, card: IndexCard): Double {
        var best = 0.0
        for (line in parsed.nameLines) {
            var s = TextNorm.nameSimilarity(line, card.nameNorm)
            if (card.nameEnNorm != card.nameNorm) s = maxOf(s, TextNorm.nameSimilarity(line, card.nameEnNorm))
            if (card.baseNorm != card.nameNorm) s = maxOf(s, 0.9 * TextNorm.nameSimilarity(line, card.baseNorm))
            if (card.baseEnNorm != card.nameEnNorm) s = maxOf(s, 0.9 * TextNorm.nameSimilarity(line, card.baseEnNorm))
            if (card.aliasNameNorm.isNotEmpty()) s = maxOf(s, TextNorm.nameSimilarity(line, card.aliasNameNorm))
            if (s > best) best = s
            if (best >= 1.0) break
        }
        return best
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

    private fun bestNames(parsed: ParsedCardText): List<Pair<String, List<IndexCard>>> {
        val lines = parsed.nameLines.take(6).filter(TextNorm::isSearchableName)
        if (lines.isEmpty()) return emptyList()
        val best = HashMap<String, Double>()
        for (line in lines) {
            for (name in index.nameCandidates(line)) {
                val s = TextNorm.nameSimilarity(line, name)
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

    private fun isConfident(best: MatchCandidate, second: MatchCandidate?, all: List<MatchCandidate>, parsed: ParsedCardText): Boolean {
        // Eine andere Karte passt per Name klar besser -> Nummer vermutlich falsch gelesen
        if (all.any { it !== best && it.nameScore >= 0.8 && it.nameScore > best.nameScore + 0.02 && !nested(it.card, best.card) }) return false
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
