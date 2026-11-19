package de.pokemoncarddetector.data

import de.pokemoncarddetector.data.db.CardItem

/** Baut Bild-URLs fuer die TCGdex-Bilderserver. */
object ImageUrls {
    private const val ASSETS = "https://assets.tcgdex.net"

    enum class Quality(val path: String) { LOW("low"), HIGH("high") }

    fun card(
        serieId: String,
        setId: String,
        localId: String,
        imageFlags: Int,
        quality: Quality,
        language: ImageLanguage,
    ): String? {
        val hasDe = imageFlags and 1 != 0
        val hasEn = imageFlags and 2 != 0
        val lang = when {
            language == ImageLanguage.GERMAN && hasDe -> "de"
            hasEn -> "en"
            hasDe -> "de"
            else -> return null
        }
        return "$ASSETS/$lang/$serieId/$setId/$localId/${quality.path}.webp"
    }

    fun card(item: CardItem, quality: Quality, language: ImageLanguage): String? =
        card(item.serieId, item.card.setId, item.card.localId, item.card.imageFlags, quality, language)

    /** Set-Logo (z.B. Schriftzug "Farbenschock"). */
    fun logo(logoUrl: String): String? = logoUrl.takeIf { it.isNotBlank() }?.let { "$it.webp" }
}
