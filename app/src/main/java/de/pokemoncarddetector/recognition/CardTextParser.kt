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
    /** Alle Zeilen (Originaltext). */
    val allLines: List<String>,
) {
    val isEmpty: Boolean get() = allLines.isEmpty()
    val maxYear: Int? get() = years.maxOrNull()
}

object CardTextParser {

    private const val CONFUSABLE_DIGITS = "0-9OQDIL|"

    /**
     * Nummer/Gesamtzahl, optional mit Buchstaben-Praefix (TG, GG, SV, RC, H ...).
     * Erlaubt Leerzeichen um den Schraegstrich und typische OCR-Verwechsler (O statt 0, I/l statt 1).
     */
    private val SLASH_NUMBER = Regex(
        "(?<![A-Z0-9])([A-Z]{1,4})?\\s?([$CONFUSABLE_DIGITS]{1,3}[A-Z]?)\\s?[/⁄∕]\\s?([A-Z]{1,4})?\\s?([$CONFUSABLE_DIGITS]{1,3})(?![0-9])"
    )

    /** Promos ohne Gesamtzahl: SWSH050, SM210, XY123, BW95, DP45, HGSS18 */
    private val PROMO_NUMBER = Regex("(?<![A-Z0-9/])(SWSH|HGSS|SM|XY|BW|DP)\\s?([0-9]{1,3})(?![0-9/])")

    /** Set-Kuerzel vor Sprachcode, z.B. "PAL DE 123/193", "SVI EN", "MEG DE". */
    private val CODE_BEFORE_LANG = Regex("(?<![A-Z0-9])([A-Z][A-Z0-9]{1,3})\\s?[•·.]?\\s?(DE|EN|FR|IT|ES|PT|NL)(?![A-Z])")

    /** Set-Kuerzel direkt vor der Nummer, z.B. "PAF 045/091". */
    private val CODE_BEFORE_NUMBER = Regex("(?<![A-Z0-9])([A-Z][A-Z0-9]{1,3})\\s+(?:[A-Z]{2}\\s+)?[0-9]{1,3}\\s?/")

    /** Notfall-Muster ohne Praefix-Logik, falls OCR Leerzeichen verschluckt ("SVIDE025/198"). */
    private val PLAIN_SLASH_NUMBER = Regex("([0-9]{2,3})\\s?/\\s?([0-9]{2,3})(?![0-9])")

    /** Tokens, die wie Set-Kuerzel aussehen, aber auf jeder Karte stehen (Kraftpunkte). */
    private val NOT_A_SET_CODE = setOf("HP", "KP", "PV", "PS", "LV", "DE", "EN")

    /** Promo-Nummer neben Set-Kuerzel (SVP/MEP haben keine Gesamtzahl), z.B. "SVP DE 049". */
    private val CODE_PROMO_NUMBER = Regex("(?<![A-Z0-9])(SVP|MEP)\\s?[•·.]?\\s?(?:(?:DE|EN|FR|IT|ES|PT|NL)\\s?)?([0-9]{1,3})(?![0-9/])")

    private val YEAR = Regex("(?<![0-9])(199[5-9]|20[0-4][0-9])(?![0-9])")
    private val COPYRIGHT_HINT = Regex("©|\\(C\\)|NINTENDO|POK[EÉ]MON|CREATURES|GAME ?FREAK|WIZARDS", RegexOption.IGNORE_CASE)

    private val LETTER = Regex("\\p{L}")
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
                if (isConfusablePrefix(totalPrefix) && totalPrefix.length + total.length <= 3) {
                    total = totalPrefix + total; totalPrefix = ""
                }
                // Mindestens eine echte Ziffer auf beiden Seiten, sonst ist es vermutlich Text.
                if (!DIGIT.containsMatchIn(num) || !DIGIT.containsMatchIn(total)) continue
                val suffix = num.takeLastWhile { it.isLetter() && it !in "OQDIL" }
                val numDigits = toDigits(num.dropLast(suffix.length))
                val totalDigits = toDigits(total)
                if (!numDigits.all { it.isDigit() } || !totalDigits.all { it.isDigit() }) continue
                val totalInt = totalDigits.toIntOrNull() ?: continue
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
            }
            for (m in PROMO_NUMBER.findAll(up)) {
                val number = m.groupValues[1] + m.groupValues[2]
                if (numbers.keys.none { it.startsWith("$number/") } && number !in numbers) {
                    numbers[number] = CollectorNumber(number, null, m.value.trim())
                }
            }
            for (m in CODE_PROMO_NUMBER.findAll(up)) {
                codes += m.groupValues[1]
                val number = m.groupValues[2]
                val key = "${m.groupValues[1]}:$number"
                if (key !in numbers) numbers[key] = CollectorNumber(number, null, m.value.trim())
            }
            for (m in CODE_BEFORE_LANG.findAll(up)) codes += m.groupValues[1]
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

        for (t in texts) {
            if (COPYRIGHT_HINT.containsMatchIn(t)) {
                YEAR.findAll(t).forEach { years += it.value.toInt() }
            }
        }

        return ParsedCardText(
            numbers = numbers.values.toList(),
            setCodes = codes,
            years = years,
            nameLines = nameLines(lines),
            allLines = texts,
        )
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
