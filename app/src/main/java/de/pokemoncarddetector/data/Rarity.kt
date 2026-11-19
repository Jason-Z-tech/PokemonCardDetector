package de.pokemoncarddetector.data

/** Deutsche Bezeichnungen fuer die (englischen) TCGdex-Seltenheiten. */
object Rarity {
    private val LABELS = mapOf(
        "common" to "Häufig",
        "uncommon" to "Nicht so häufig",
        "rare" to "Selten",
        "rare holo" to "Holo-Selten",
        "holo rare" to "Holo-Selten",
        "ultra rare" to "Ultra-Selten",
        "secret rare" to "Geheimselten",
        "promo" to "Promo",
        "double rare" to "Doppelselten",
        "illustration rare" to "Illustration Rare",
        "special illustration rare" to "Special Illustration Rare",
        "hyper rare" to "Hyper Rare",
        "shiny rare" to "Schillernd selten",
        "shiny ultra rare" to "Schillernd ultraselten",
        "radiant rare" to "Strahlend selten",
        "amazing rare" to "Fantastisch selten",
        "ace spec rare" to "ACE SPEC",
        "classic collection" to "Klassische Sammlung",
        "rare prime" to "Selten PRIME",
        "legend" to "LEGENDE",
        "none" to "Ohne Angabe",
        "" to "Ohne Angabe",
    )

    fun label(rarity: String): String {
        val key = rarity.trim().lowercase()
        LABELS[key]?.let { return it }
        // "Holo Rare V", "Holo Rare VMAX", "Shiny rare V" ...
        LABELS.entries.firstOrNull { (k, _) -> k.isNotEmpty() && key.startsWith("$k ") }?.let { (k, v) ->
            return v + rarity.trim().substring(k.length)
        }
        return rarity.trim()
    }
}
