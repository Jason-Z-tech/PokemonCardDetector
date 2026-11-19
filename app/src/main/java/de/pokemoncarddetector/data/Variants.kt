package de.pokemoncarddetector.data

/**
 * Kartenvarianten (Normal, Holo, Reverse Holo, Pokéball-Reverse, 1. Edition, ...).
 *
 * Ein Schluessel kommt aus dem Katalog (TCGdex variants_detailed), z.B.
 * "reverse|foil=pokeball", "holo|sub=shadowless|stamp=1st-edition", "normal|stamp=set-logo".
 * Der leere Schluessel "" steht fuer "Variante unbekannt" (z.B. aus aelteren Versionen der App).
 */
object Variants {

    const val UNKNOWN = ""

    data class Parsed(
        val type: String,
        val subtype: String?,
        val foil: String?,
        val stamps: List<String>,
        val size: String?,
    )

    fun parse(key: String): Parsed {
        if (key.isBlank()) return Parsed("", null, null, emptyList(), null)
        val parts = key.split('|')
        var sub: String? = null
        var foil: String? = null
        var stamps = emptyList<String>()
        var size: String? = null
        for (p in parts.drop(1)) {
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (k) {
                "sub" -> sub = v
                "foil" -> foil = v
                "stamp" -> stamps = v.split(',').filter { it.isNotBlank() }
                "size" -> size = v
            }
        }
        return Parsed(parts[0], sub, foil, stamps, size)
    }

    /** Vollstaendige deutsche Bezeichnung, z.B. "Reverse Holo · Pokéball-Muster". */
    fun label(key: String): String {
        if (key.isBlank()) return "Variante unbekannt"
        val p = parse(key)
        return (listOf(typeLabel(p.type)) + details(p)).joinToString(" · ")
    }

    /** Kurze Bezeichnung fuer Chips, z.B. "Reverse · Pokéball". */
    fun shortLabel(key: String): String {
        if (key.isBlank()) return "Unbekannt"
        val p = parse(key)
        val type = when (p.type) {
            "reverse" -> "Reverse"
            else -> typeLabel(p.type)
        }
        return (listOf(type) + details(p).map { it.removeSuffix("-Muster").removeSuffix("-Stempel") }).joinToString(" · ")
    }

    private fun details(p: Parsed): List<String> = buildList {
        p.foil?.let { add(FOILS[it] ?: pretty(it)) }
        p.stamps.forEach { add(STAMPS[it] ?: "Stempel: ${pretty(it)}") }
        p.subtype?.let { add(SUBTYPES[it] ?: pretty(it)) }
        if (p.size == "jumbo") add("Jumbo")
    }

    private fun typeLabel(type: String) = when (type) {
        "normal" -> "Normal"
        "holo" -> "Holo"
        "reverse" -> "Reverse Holo"
        else -> pretty(type)
    }

    /** Liste aus dem Katalog-Feld ("a;b;c"). */
    fun list(joined: String): List<String> = if (joined.isBlank()) emptyList() else joined.split(';')

    /**
     * Standard-Variante beim Hinzufuegen: die erste von Normal/Holo in Katalog-Reihenfolge, sonst die erste
     * einfache Variante ausser Reverse, sonst die erste ohne Stempel/Muster, sonst die erste laut Katalog.
     *
     * Reverse wird uebersprungen, weil TCGdex es bei manchen Sets vor "Normal" listet (sv01, lc, ...).
     * Zwischen Normal und Holo zaehlt die Reihenfolge von TCGdex: bei Holo-Raren steht der Booster-Druck
     * (Holo) vorne, ein spaeter gelistetes "Normal" ist ein eigenes Cardmarket-Produkt (Deck/Promo).
     */
    fun default(variants: List<String>): String =
        variants.firstOrNull { it == "normal" || it == "holo" }
            ?: variants.firstOrNull { !it.contains('|') && it != "reverse" }
            ?: variants.firstOrNull { !it.contains('|') }
            ?: variants.firstOrNull()
            ?: UNKNOWN

    private fun pretty(s: String) = s.split('-', '_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private val FOILS = mapOf(
        "pokeball" to "Pokéball-Muster",
        "masterball" to "Meisterball-Muster",
        "duskball" to "Finsterball-Muster",
        "loveball" to "Sympaball-Muster",
        "friendball" to "Freundesball-Muster",
        "quickball" to "Flottball-Muster",
        "energy" to "Energie-Muster",
        "cosmos" to "Kosmos",
        "rainbow" to "Regenbogen",
        "gold" to "Gold",
        "league" to "Liga",
        "cracked-ice" to "Cracked Ice",
    )

    private val STAMPS = mapOf(
        "1st-edition" to "1. Edition",
        "set-logo" to "Set-Logo-Stempel",
        "30th-anniversary" to "30-Jahre-Stempel",
        "25th-celebration" to "25-Jahre-Stempel",
        "staff" to "Staff",
        "player-rewards-program" to "Player Rewards",
        "pre-release" to "Prerelease",
        "pokemon-center" to "Pokémon Center",
        "mcdonalds" to "McDonald's",
        "snowflake" to "Schneeflocke",
        "countdown-calendar" to "Adventskalender",
        "professor-program" to "Professor-Programm",
        "regional-championships" to "Regionalmeisterschaft",
        "national-championships" to "Nationale Meisterschaft",
        "world-championships" to "Weltmeisterschaft",
    )

    private val SUBTYPES = mapOf(
        "unlimited" to "Unlimitiert",
        "shadowless" to "Schattenlos",
        "shadowless-red-cheek" to "Schattenlos, rote Wangen",
        "1999-2000-copyright" to "© 1999–2000",
        "no-e-reader" to "ohne e-Reader",
        "missing-expansion-symbol" to "ohne Set-Symbol",
    )
}
