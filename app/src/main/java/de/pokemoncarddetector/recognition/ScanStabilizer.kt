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
    /** Je Bild: war ueberhaupt eine Karte/Text zu sehen? (unabhaengig davon, ob sicher erkannt) */
    private val seen = ArrayDeque<Boolean>()
    private val lastResults = HashMap<String, MatchResult>()

    /** Liefert ein stabiles Ergebnis oder null, solange noch nicht sicher. */
    fun offer(result: MatchResult): MatchResult? {
        val id = result.best?.card?.id?.takeIf { result.confident }
        history.addLast(id)
        while (history.size > window) history.removeFirst()
        seen.addLast(result.best != null)
        while (seen.size > window) seen.removeFirst()
        if (id != null) lastResults[id] = result
        if (lastResults.size > 20) lastResults.keys.retainAll(history.filterNotNull().toSet())

        val counts = history.filterNotNull().groupingBy { it }.eachCount()
        val (bestId, count) = counts.maxByOrNull { it.value } ?: return null
        return if (count >= required) lastResults[bestId] else null
    }

    /** true, wenn in den letzten Bildern gar kein Text/keine Karte mehr zu sehen war. */
    fun isIdle(): Boolean = seen.size >= window && seen.none { it }

    fun reset() {
        history.clear()
        seen.clear()
        lastResults.clear()
    }
}
