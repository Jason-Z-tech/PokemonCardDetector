package de.pokemoncarddetector.recognition

/** Eine von der Texterkennung gelieferte Zeile inkl. Position im Bild (Pixel). */
data class OcrLine(
    val text: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** Sammlernummer wie "025/185", "TG05/TG30", "SV050/SV122" oder Promo "SWSH050". */
data class CollectorNumber(
    /** Nummer wie gedruckt, z.B. "025", "TG05", "SWSH050". */
    val number: String,
    /** Zahl hinter dem Schraegstrich (z.B. 185) oder null bei Promos ohne Gesamtzahl. */
    val total: Int?,
    /** Original-Ausschnitt aus dem OCR-Text (zur Anzeige/Fehlersuche). */
    val raw: String,
    /** true = nur geraten (Schraegstrich fehlt / als 1 gelesen) – zaehlt nur zusammen mit passendem Namen */
    val weak: Boolean = false,
) {
    val key: String get() = CardTextParser.normalizeLocalId(number)
}

data class ParsedCardText(
    val numbers: List<CollectorNumber>,
    /** Kandidaten fuer Set-Kuerzel wie "PAL", "SVI", "MEG" (noch nicht gegen Katalog geprueft). */
    val setCodes: Set<String>,
    /** Copyright-Jahre (z.B. 2020 aus "©2020 Pokémon"). */
    val years: List<Int>,
    /** Wahrscheinliche Namenszeilen, normalisiert, wichtigste zuerst. */
    val nameLines: List<String>,
    /** Kraftpunkte, z.B. 170 aus "KP 170" / "170 HP" (null = nicht gelesen) */
    val hp: Int? = null,
    /** Alle Zeilen (Originaltext). */
    val allLines: List<String>,
) {
    val isEmpty: Boolean get() = allLines.isEmpty()
    val maxYear: Int? get() = years.maxOrNull()
}

object CardTextParser {

    private const val CONFUSABLE_DIGITS = "0-9OQDILSZ|"
    /** In der Gesamtzahl gibt es keine Buchstaben-Suffixe, dort ist auch B (=8) ein Verwechsler. */
    private const val CONFUSABLE_TOTAL = "0-9OQDILSZB|"

    /**
     * Nummer/Gesamtzahl, optional mit Buchstaben-Praefix (TG, GG, SV, RC, H ...).
     * Erlaubt Leerzeichen um den Schraegstrich und typische OCR-Verwechsler (O statt 0, I/l statt 1).
     */
    private val SLASH_NUMBER = Regex(
        "(?<![A-Z0-9])([A-Z]{1,4})?\\s?([$CONFUSABLE_DIGITS]{1,3}[A-Z]?)\\s?[/⁄∕]\\s?([A-Z]{1,4})?\\s?([$CONFUSABLE_TOTAL]{1,4})(?![0-9])"
    )

    /** Promos ohne Gesamtzahl: SWSH050, SM210, XY123, BW95, DP45, HGSS18 */
    private val PROMO_NUMBER = Regex("(?<![A-Z0-9/])(SWSH|HGSS|SM|XY|BW|DP)\\s?([0-9]{1,3})(?![0-9/])")

    /** Set-Kuerzel vor Sprachcode, z.B. "PAL DE 123/193", "SVI EN", "MEG DE". */
    private val CODE_BEFORE_LANG = Regex("(?<![\\p{L}0-9])([A-Z][A-Z0-9]{1,3})\\s?[•·.]?\\s?(DE|EN|FR|IT|ES|PT|NL)(?=\\s?[0-9OQ]?[0-9])")

    /** Set-Kuerzel direkt vor der Nummer, z.B. "PAF 045/091". */
    private val CODE_BEFORE_NUMBER = Regex("(?<![\\p{L}0-9])([A-Z][A-Z0-9]{1,3})\\s+(?:[A-Z]{2}\\s+)?[0-9]{1,3}\\s?/")

    /** Notfall-Muster ohne Praefix-Logik, falls OCR Leerzeichen verschluckt ("SVIDE025/198"). */
    private val PLAIN_SLASH_NUMBER = Regex("([0-9]{2,3})\\s?/\\s?([0-9]{2,3})(?![0-9])")

    /** Schraegstrich nicht erkannt: "025I185", "025|185", "025 185" (nur 3+3 Ziffern, sonst zu unsicher). */
    private val MISSING_SLASH = Regex("(?<![0-9])([0-9]{3})(?:\\s?[IL|\\\\]\\s?|\\s)([0-9]{3})(?![0-9])")

    /** Ziffernfolge, in der der Schraegstrich fehlt oder als 1/I/l gelesen wurde ("020078", "941149", "691110"). */
    private val NO_SLASH_RUN = Regex("(?<![0-9A-Z])[0-9][0-9LI|]{3,6}(?![0-9A-Z])")

    /** Regelzeichen (D-J) direkt vor dem Kuerzel: "GSVP", "HPAL" */
    private fun withoutRegulationMark(code: String): String? =
        if (code.length == 4 && code[0] in 'D'..'J' && code.drop(1).all { it.isLetter() }) code.drop(1) else null

    /** Tokens, die wie Set-Kuerzel aussehen, aber auf jeder Karte stehen (Kraftpunkte). */
    private val NOT_A_SET_CODE = setOf("HP", "KP", "PV", "PS", "LV", "DE", "EN")

    /** Promo-Nummer neben Set-Kuerzel (SVP/MEP haben keine Gesamtzahl), z.B. "SVP DE 049". */
    private val CODE_PROMO_NUMBER = Regex("(?<![\\p{L}0-9])[D-J]?(S[VY]P|MEP)\\s?[•·.]?\\s?(?:(?:DE|EN|FR|IT|ES|PT|NL)\\s?)?([0-9]{1,3})(?![0-9/])")

    /** "KP 170", "KP170", "170 KP", "HP 170", "PV 170" */
    private val HP = Regex("(?<![A-Z0-9])(?:KP|HP|PV|PS)\\s?([0-9]{2,3})(?![0-9])|(?<![0-9])([0-9]{2,3})\\s?(?:KP|HP|PV|PS)(?![A-Z])")

    private val YEAR = Regex("(?<![0-9])(199[5-9]|20[0-4][0-9])(?![0-9])")
    private val COPYRIGHT_HINT = Regex("©|\\(C\\)|NINTENDO|POK[EÉ]MON|CREATURES|GAME ?FREAK|WIZARDS", RegexOption.IGNORE_CASE)

    private val LETTER = Regex("\\p{L}")
    private val EVOLVES_FROM = Regex("^\\s*(entwickelt sich aus|entsteht aus|evolves from|put .* on the basic)\\b", RegexOption.IGNORE_CASE)
    private val DIGIT = Regex("[0-9]")

    /** Woerter, die oft gross auf Karten stehen, aber keine Namen sind. */
    private val NON_NAME_WORDS = setOf(
        "basis", "basic", "phase1", "phase2", "stage1", "stage2", "trainer", "item", "unterstutzer",
        "supporter", "stadion", "stadium", "energie", "energy", "kp", "hp", "schwache", "weakness",
        "resistenz", "resistance", "ruckzug", "retreat", "pokemon", "fahigkeit", "ability",
        "werkzeug", "tool", "illus", "nintendo", "creatures", "gamefreak", "regel", "rule",
    )

    /** "025" -> "25", "TG05" -> "TG5", "SV001" -> "SV1", "12a" -> "12A". */
    fun normalizeLocalId(id: String): String {
        val up = id.uppercase().trim()
        val m = Regex("^([A-Z]*)([0-9]+)([A-Z]*)$").matchEntire(up) ?: return up
        val digits = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        return m.groupValues[1] + digits + m.groupValues[3]
    }

    private fun toDigits(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                'O', 'Q', 'D' -> '0'
                'I', 'L', '|' -> '1'
                'S' -> '5'
                'Z' -> '2'
                'B' -> '8'
                else -> c
            }
        )
    }

    /** Praefix, das nur aus Verwechslern besteht (z.B. "O" in "O25/185"), gehoert zur Zahl. */
    private fun isConfusablePrefix(p: String) = p.isNotEmpty() && p.all { it in "OQDIL" }

    fun parse(lines: List<OcrLine>): ParsedCardText {
        val texts = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        val numbers = LinkedHashMap<String, CollectorNumber>()
        val codes = LinkedHashSet<String>()
        val years = ArrayList<Int>()

        // Zeilen einzeln und zusaetzlich paarweise verbunden pruefen (OCR trennt "025/" und "185" manchmal).
        val candidatesText = ArrayList<String>(texts.size * 2)
        candidatesText += texts
        for (i in 0 until texts.size - 1) candidatesText += texts[i] + " " + texts[i + 1]
        // Kaestchen auf gleicher Hoehe links->rechts verbinden ("GSVP DE" + "021", "Energy" + "Switch")
        val rowLines = rows(lines)
        candidatesText += rowLines.map { it.text }

        for (raw in candidatesText) {
            val up = raw.uppercase()
            for (m in SLASH_NUMBER.findAll(up)) {
                var prefix = m.groupValues[1]
                var num = m.groupValues[2]
                var totalPrefix = m.groupValues[3]
                var total = m.groupValues[4]
                if (isConfusablePrefix(prefix) && prefix.length + num.length <= 3) {
                    num = prefix + num; prefix = ""
                }
                if (totalPrefix.isNotEmpty() && prefix.length > totalPrefix.length && prefix.startsWith(totalPrefix) &&
                    isConfusablePrefix(prefix.substring(totalPrefix.length))
                ) {
                    num = prefix.substring(totalPrefix.length) + num; prefix = totalPrefix
                }
                if (isConfusablePrefix(totalPrefix) && totalPrefix.length + total.length <= 3) {
                    total = totalPrefix + total; totalPrefix = ""
                }
                // Mindestens eine echte Ziffer auf beiden Seiten, sonst ist es vermutlich Text.
                if (!DIGIT.containsMatchIn(num) || !DIGIT.containsMatchIn(total)) continue
                val suffix = num.takeLastWhile { it.isLetter() && it !in "OQDILSZ" }
                val numDigits = toDigits(num.dropLast(suffix.length))
                val totalDigits = toDigits(total)
                if (!numDigits.all { it.isDigit() } || !totalDigits.all { it.isDigit() }) continue
                // Seltenheitssymbol hinter der Gesamtzahl als Ziffer gelesen: "36/1140" -> 36/114, "12/640" -> 12/64
                var totalStr = totalDigits
                if (totalStr.length == 4) totalStr = totalStr.take(3)
                if ((totalStr.toIntOrNull() ?: 0) > 500 && totalStr.length == 3) totalStr = totalStr.take(2)
                val totalInt = totalStr.toIntOrNull() ?: continue
                if (totalInt <= 0 || totalInt > 500) continue
                // Praefix nur behalten, wenn es auch vor der Gesamtzahl steht (TG05/TG30) oder bekannt ist.
                val keepPrefix = when {
                    prefix.isEmpty() -> ""
                    prefix == totalPrefix -> prefix
                    prefix in KNOWN_NUMBER_PREFIXES -> prefix
                    else -> ""
                }
                val number = keepPrefix + numDigits + suffix
                val key = "$number/$totalInt"
                if (key !in numbers) numbers[key] = CollectorNumber(number, totalInt, m.value.trim())
                // Bekanntes Praefix ohne Wiederholung vor der Gesamtzahl kann Rest eines Kuerzels sein ("PAR 063" -> "AR063")
                if (keepPrefix.isNotEmpty() && keepPrefix != totalPrefix) {
                    val plainKey = "${numDigits + suffix}/$totalInt"
                    if (plainKey !in numbers) numbers[plainKey] = CollectorNumber(numDigits + suffix, totalInt, m.value.trim())
                }
            }
            for (m in PROMO_NUMBER.findAll(up)) {
                val number = m.groupValues[1] + m.groupValues[2]
                if (numbers.keys.none { it.startsWith("$number/") } && number !in numbers) {
                    numbers[number] = CollectorNumber(number, null, m.value.trim())
                }
            }
            for (m in CODE_PROMO_NUMBER.findAll(up)) {
                val code = m.groupValues[1].replace('Y', 'V') // "SYP" = OCR-Fehler fuer "SVP"
                codes += code
                val number = m.groupValues[2]
                val key = "$code:$number"
                if (key !in numbers) numbers[key] = CollectorNumber(number, null, m.value.trim())
            }
            for (m in CODE_BEFORE_LANG.findAll(up)) {
                codes += m.groupValues[1]
                withoutRegulationMark(m.groupValues[1])?.let { codes += it }
            }
            for (m in CODE_BEFORE_NUMBER.findAll(up)) codes += m.groupValues[1]
        }
        codes.removeAll(NOT_A_SET_CODE)

        if (numbers.values.none { it.total != null }) {
            for (raw in texts) {
                for (m in PLAIN_SLASH_NUMBER.findAll(raw.uppercase())) {
                    val total = m.groupValues[2].toInt()
                    if (total <= 0 || total > 500) continue
                    val key = "${m.groupValues[1]}/$total"
                    if (key !in numbers) numbers[key] = CollectorNumber(m.groupValues[1], total, m.value.trim())
                }
            }
        }
        if (numbers.values.none { it.total != null }) {
            for (raw in texts) {
                for (m in MISSING_SLASH.findAll(raw.uppercase())) {
                    val total = m.groupValues[2].toInt()
                    if (total <= 0 || total > 500) continue
                    val key = "${m.groupValues[1]}/$total"
                    if (key !in numbers) numbers[key] = CollectorNumber(m.groupValues[1], total, m.value.trim(), weak = true)
                }
            }
        }
        if (numbers.values.none { it.total != null }) {
            for (raw in texts + rowLines.map { it.text }) {
                if (COPYRIGHT_HINT.containsMatchIn(raw)) continue // "©2024" -> "02024"
                for (m in NO_SLASH_RUN.findAll(raw.uppercase())) {
                    val run = m.value
                    val splits = ArrayList<Pair<String, String>>()
                    for (i in 1..3) if (i < run.length && run[i] in "1LI|") splits += run.take(i) to run.substring(i + 1)
                    if (run.length in 5..6) splits += run.take(3) to run.substring(3)
                    for ((n, t) in splits) {
                        if (n.isEmpty() || t.length !in 2..3 || !n.all { it.isDigit() } || !t.all { it.isDigit() }) continue
                        val total = t.toInt()
                        val num = n.toInt()
                        if (total < 10 || total > 500 || num == 0 || num > total + 160) continue
                        val key = "$n/$total"
                        if (key !in numbers) numbers[key] = CollectorNumber(n, total, run, weak = true)
                    }
                }
            }
        }

        for (t in texts) {
            if (COPYRIGHT_HINT.containsMatchIn(t)) {
                YEAR.findAll(t).forEach { years += it.value.toInt() }
            }
        }

        val hp = texts.asSequence()
            .flatMap { HP.findAll(it.uppercase()) }
            .mapNotNull { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
            .firstOrNull { it in 30..400 && it % 10 == 0 }

        return ParsedCardText(
            numbers = numbers.values.toList(),
            setCodes = codes,
            years = years,
            nameLines = nameLines(lines + rows(lines)),
            allLines = texts,
            hp = hp,
        )
    }

    /** Kaestchen mit vertikaler Ueberlappung (gleiche Zeile auf der Karte), von links nach rechts verbunden. */
    private fun rows(lines: List<OcrLine>): List<OcrLine> {
        val boxed = lines.filter { it.bottom > it.top && it.text.isNotBlank() }.sortedBy { it.top + it.bottom }
        if (boxed.size < 2) return emptyList()
        val groups = ArrayList<MutableList<OcrLine>>()
        for (l in boxed) {
            val g = groups.lastOrNull()
            val joins = g != null && g.any { o -> minOf(o.bottom, l.bottom) - maxOf(o.top, l.top) > 0.5 * minOf(o.height, l.height) }
            if (joins) g!!.add(l) else groups += mutableListOf(l)
        }
        return groups.filter { it.size > 1 }.map { g ->
            val s = g.sortedBy { it.left }
            OcrLine(s.joinToString(" ") { it.text.trim() }, s.minOf { it.left }, s.minOf { it.top }, s.maxOf { it.right }, s.maxOf { it.bottom })
        }
    }

    /**
     * Sortiert Zeilen nach Wahrscheinlichkeit, der Kartenname zu sein:
     * grosse Schrift, weit oben, ueberwiegend Buchstaben.
     */
    private fun nameLines(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val hasBoxes = lines.any { it.bottom > it.top }
        val minTop = lines.minOf { it.top }
        val maxBottom = lines.maxOf { it.bottom }
        val span = (maxBottom - minTop).coerceAtLeast(1)
        val maxHeight = lines.maxOf { it.height }.coerceAtLeast(1)

        data class Scored(val norm: String, val score: Double)

        val scored = lines.mapIndexedNotNull { index, line ->
            val text = line.text.trim()
            val letters = LETTER.findAll(text).count()
            if (letters < 3) return@mapIndexedNotNull null
            val digits = DIGIT.findAll(text).count()
            val norm = TextNorm.normalize(text)
            if (norm.length < 3 || norm in NON_NAME_WORDS) return@mapIndexedNotNull null
            // "Entwickelt sich aus Glutexo" / "Evolves from Charmeleon" nennt die VORSTUFE, nicht diese Karte
            if (EVOLVES_FROM.containsMatchIn(text)) return@mapIndexedNotNull null
            if (letters.toDouble() / (letters + digits) < 0.5) return@mapIndexedNotNull null
            val score = if (hasBoxes) {
                val relTop = (line.top - minTop).toDouble() / span
                val sizeScore = line.height.toDouble() / maxHeight
                val posScore = when {
                    relTop < 0.15 -> 1.0
                    relTop < 0.35 -> 0.6
                    relTop < 0.7 -> 0.25
                    else -> 0.1
                }
                sizeScore * 0.6 + posScore * 0.4 + if (text.length <= 28) 0.05 else 0.0
            } else {
                1.0 / (1 + index) // Ohne Positionen: Reihenfolge der OCR (oben zuerst)
            }
            Scored(norm, score)
        }
        return scored.sortedByDescending { it.score }.map { it.norm }.distinct().take(12)
    }

    /** Buchstaben-Praefixe, die auf Karten vor der Nummer stehen koennen. */
    val KNOWN_NUMBER_PREFIXES = setOf("TG", "GG", "SV", "RC", "H", "SH", "SL", "AR", "RT", "CC", "SWSH", "SM", "XY", "BW", "DP", "HGSS")
}
