package de.pokemoncarddetector.recognition

import java.text.Normalizer

/** Hilfsfunktionen zum unscharfen Vergleichen von OCR-Text mit Kartennamen. */
object TextNorm {

    /**
     * Kleinbuchstaben, ohne Akzente, nur Buchstaben und Ziffern (auch Kana/Kanji fuer japanische Karten).
     * "Pokémon-ex" -> "pokemonex", "ＳＶ２ａ" -> "sv2a", "リザードンex" -> "リサードンex" (Dakuten entfallen auf beiden Seiten).
     */
    fun normalize(s: String): String {
        val compat = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val lower = compat.lowercase()
            .replace("ß", "ss")
            .replace("♀", "w")
            .replace("♂", "m")
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch)) sb.append(ch)
        }
        return sb.toString()
    }

    /** Kana (inkl. Laengenzeichen "ー") oder Kanji. */
    fun isCjk(c: Char): Boolean = c in '\u3040'..'\u30FF' || c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF'

    fun containsCjk(s: String): Boolean = s.any(::isCjk)

    /**
     * Lang genug fuer die Namenssuche: lateinisch ab 3 Zeichen, japanisch schon ab 2
     * (viele Namen haben nur zwei Kana: パモ, ピィ, ネモ, タロ).
     */
    fun isSearchableName(norm: String): Boolean = norm.length >= 3 || (norm.length == 2 && norm.all(::isCjk))

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
        // Sellers-Algorithmus: kleinste Editierdistanz von [needle] zu irgendeinem Teilstring von [hay], O(n*m)
        val n = needle.length
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        var best = n
        for (j in 1..hay.length) {
            cur[0] = 0
            val ch = hay[j - 1]
            for (i in 1..n) {
                val cost = if (needle[i - 1] == ch) 0 else 1
                cur[i] = minOf(prev[i] + 1, cur[i - 1] + 1, prev[i - 1] + cost)
            }
            if (cur[n] < best) { best = cur[n]; if (best == 0) return 1.0 }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - best.toDouble() / n
    }

    /**
     * Aehnlichkeit einer (normalisierten) OCR-Zeile mit einem (normalisierten) Kartennamen.
     * Beruecksichtigt, dass die Zeile zusaetzlichen Text enthalten kann (Phase, KP ...).
     */
    fun nameSimilarity(lineNorm: String, nameNorm: String): Double {
        if (lineNorm.isEmpty() || nameNorm.isEmpty()) return 0.0
        if (lineNorm == nameNorm) return 1.0
        if (nameNorm.length < 3) {
            // Zwei-Kana-Namen ("ハモ") nur, wenn der Rest der Zeile kein Kana/Kanji ist ("ハモhp70"):
            // sonst steckt "カイ" in "カイリュー" oder "タロ" in jeder Zeile mit diesen Zeichen.
            if (!isSearchableName(nameNorm)) return 0.0
            val i = lineNorm.indexOf(nameNorm)
            if (i < 0 || containsCjk(lineNorm.removeRange(i, i + nameNorm.length))) return 0.0
            return 0.85 + 0.15 * nameNorm.length / lineNorm.length
        }
        if (lineNorm.contains(nameNorm)) {
            return 0.85 + 0.15 * nameNorm.length / lineNorm.length
        }
        // Sehr kurze Zeilen nicht gegen lange Namen "hochrechnen"
        if (lineNorm.length < 3) return 0.0
        // Ab doppelter Laenge kann die Gesamt-Aehnlichkeit 0.5 nicht mehr erreichen -> teure Levenshtein sparen
        val full = if (lineNorm.length <= 2 * nameNorm.length) ratio(lineNorm, nameNorm) else 0.0
        val partial = if (lineNorm.length > nameNorm.length) partialRatio(nameNorm, lineNorm) * 0.95 else 0.0
        return maxOf(full, partial)
    }
}
