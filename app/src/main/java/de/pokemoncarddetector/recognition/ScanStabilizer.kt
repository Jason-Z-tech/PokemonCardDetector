package de.pokemoncarddetector.recognition

/**
 * Glaettet Ergebnisse ueber mehrere Kamerabilder: Eine Karte gilt erst als erkannt,
 * wenn sie in mehreren der letzten Bilder als bester Treffer auftaucht.
 * Verhindert flackernde Anzeigen und Fehlerkennungen durch Bewegungsunschaerfe.
 */
class ScanStabilizer(
    private val window: Int = 4,
    private val required: Int = 2,
) {
    private val history = ArrayDeque<String?>()
    private val lastResults = HashMap<String, MatchResult>()

    /** Liefert ein stabiles Ergebnis oder null, solange noch nicht sicher. */
    fun offer(result: MatchResult): MatchResult? {
        val id = result.best?.card?.id?.takeIf { result.confident }
        history.addLast(id)
        while (history.size > window) history.removeFirst()
        if (id != null) lastResults[id] = result
        if (lastResults.size > 20) lastResults.keys.retainAll(history.filterNotNull().toSet())

        val counts = history.filterNotNull().groupingBy { it }.eachCount()
        val (bestId, count) = counts.maxByOrNull { it.value } ?: return null
        return if (count >= required) lastResults[bestId] else null
    }

    /** true, wenn in den letzten Bildern gar kein Text/keine Karte mehr zu sehen war. */
    fun isIdle(): Boolean = history.size >= window && history.all { it == null }

    fun reset() {
        history.clear()
        lastResults.clear()
    }
}
