package de.pokemoncarddetector.recognition

import java.text.Normalizer

/** Hilfsfunktionen zum unscharfen Vergleichen von OCR-Text mit Kartennamen. */
object TextNorm {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]")

    /** Kleinbuchstaben, ohne Akzente/Umlaut-Punkte, nur a-z und 0-9. "Pokémon-ex" -> "pokemonex" */
    fun normalize(s: String): String {
        val lower = s.lowercase()
            .replace("ß", "ss")
            .replace("♀", "w")
            .replace("♂", "m")
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "").let { NON_ALNUM.replace(it, "") }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 1.0 = identisch, 0.0 = voellig verschieden. */
    fun ratio(a: String, b: String): Double {
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max
    }

    /**
     * Bester Vergleich von [needle] mit einem gleich langen Ausschnitt aus [hay].
     * Damit wird "Glurak" auch in "PHASE2Glurak" oder "GlurakKP170" gefunden.
     */
    fun partialRatio(needle: String, hay: String): Double {
        if (needle.isEmpty() || hay.isEmpty()) return 0.0
        if (hay.length <= needle.length) return ratio(needle, hay)
        var best = 0.0
        val n = needle.length
        for (len in maxOf(1, n - 1)..minOf(hay.length, n + 1)) {
            for (start in 0..hay.length - len) {
                val r = ratio(needle, hay.substring(start, start + len))
                if (r > best) {
                    best = r
                    if (best == 1.0) return best
                }
            }
        }
        return best
    }

    /**
     * Aehnlichkeit einer (normalisierten) OCR-Zeile mit einem (normalisierten) Kartennamen.
     * Beruecksichtigt, dass die Zeile zusaetzlichen Text enthalten kann (Phase, KP ...).
     */
    fun nameSimilarity(lineNorm: String, nameNorm: String): Double {
        if (lineNorm.isEmpty() || nameNorm.isEmpty()) return 0.0
        if (lineNorm == nameNorm) return 1.0
        if (nameNorm.length < 3) return 0.0
        if (lineNorm.contains(nameNorm)) {
            return 0.85 + 0.15 * nameNorm.length / lineNorm.length
        }
        // Sehr kurze Zeilen nicht gegen lange Namen "hochrechnen"
        if (lineNorm.length < 3) return 0.0
        val full = ratio(lineNorm, nameNorm)
        val partial = if (lineNorm.length > nameNorm.length) partialRatio(nameNorm, lineNorm) * 0.95 else 0.0
        return maxOf(full, partial)
    }
}
