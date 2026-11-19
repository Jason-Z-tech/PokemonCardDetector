package de.pokemoncarddetector.recognition

/**
 * Karten, deren gedruckte Nummer nicht zur Katalog-Nummer passt.
 *
 * Die "Celebrations – Klassische Sammlung" (cel25cc) sind Nachdrucke mit der ORIGINAL-Nummer
 * (Glurak "4/102" wie im Grundset, aber ©2021). TCGdex fuehrt sie als CC001..CC025.
 * Damit sie erkannt werden, bekommen sie hier ihre gedruckte Nummer und den deutschen Namen.
 */
object PrintedAliases {

    data class Alias(val number: String, val total: Int?, val nameDe: String) {
        val key: String = CardTextParser.normalizeLocalId(number)
    }

    private val ALIASES: Map<String, Alias> = mapOf(
        "cel25cc-CC001" to Alias("2", 102, "Turtok"),
        "cel25cc-CC002" to Alias("4", 102, "Glurak"),
        "cel25cc-CC003" to Alias("15", 102, "Bisaflor"),
        "cel25cc-CC004" to Alias("73", 102, "Professor Eich als Hochstapler"),
        "cel25cc-CC005" to Alias("8", 82, "Dunkles Garados"),
        "cel25cc-CC006" to Alias("15", 82, "Hier kommt Team Rocket!"),
        "cel25cc-CC007" to Alias("15", 132, "Rockets Zapdos"),
        "cel25cc-CC008" to Alias("24", null, "_____s Pikachu"),
        "cel25cc-CC009" to Alias("20", 111, "Pii"),
        "cel25cc-CC010" to Alias("66", 64, "Schillerndes Karpador"),
        "cel25cc-CC011" to Alias("9", 95, "Team Magmas Groudon"),
        "cel25cc-CC012" to Alias("86", 109, "Rockets Admin."),
        "cel25cc-CC013" to Alias("88", 92, "Mew-ex"),
        "cel25cc-CC014" to Alias("93", 101, "Guardevoir-ex"),
        "cel25cc-CC015" to Alias("17", 17, "Nachtara"),
        "cel25cc-CC016" to Alias("15", 106, "Lepumentas"),
        "cel25cc-CC017" to Alias("109", 111, "Luxtra GL LV.X"),
        "cel25cc-CC018" to Alias("145", 147, "Knakrack C LV.X"),
        "cel25cc-CC019" to Alias("107", 123, "Donphan"),
        "cel25cc-CC020" to Alias("113", 114, "Reshiram"),
        "cel25cc-CC021" to Alias("114", 114, "Zekrom"),
        "cel25cc-CC022" to Alias("54", 99, "Mewtu-EX"),
        "cel25cc-CC023" to Alias("146", 146, "Xerneas-EX"),
        "cel25cc-CC024" to Alias("76", 108, "M-Rayquaza-EX"),
        "cel25cc-CC025" to Alias("60", 145, "Kapu-Toxel-GX"),
    )

    fun of(cardId: String): Alias? = ALIASES[cardId]
}
