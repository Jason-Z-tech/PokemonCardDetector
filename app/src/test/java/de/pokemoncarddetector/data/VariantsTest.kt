package de.pokemoncarddetector.data

import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.CardItem
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantsTest {

    @Test
    fun deutscheBezeichnungen() {
        assertEquals("Normal", Variants.label("normal"))
        assertEquals("Holo", Variants.label("holo"))
        assertEquals("Reverse Holo", Variants.label("reverse"))
        assertEquals("Reverse Holo · Pokéball-Muster", Variants.label("reverse|foil=pokeball"))
        assertEquals("Reverse · Meisterball", Variants.shortLabel("reverse|foil=masterball"))
        assertEquals("Holo · 1. Edition · Schattenlos", Variants.label("holo|sub=shadowless|stamp=1st-edition"))
        assertEquals("Normal · Set-Logo-Stempel", Variants.label("normal|stamp=set-logo"))
        assertEquals("Holo · Jumbo", Variants.label("holo|size=jumbo"))
        assertEquals("Variante unbekannt", Variants.label(""))
        assertEquals("Normal · Stempel: Jason Klaczynski", Variants.label("normal|stamp=jason-klaczynski"))
    }

    @Test
    fun anzahlenJeVariante() {
        val item = CardItem(
            card = CardEntity("sv08.5-001", "sv08.5", "001", "Owei", "Exeggcute", 7, 0, "owei", "1",
                variants = "normal;reverse;reverse|foil=pokeball;reverse|foil=masterball", hp = 60, regulationMark = "H"),
            quantity = 4, setName = "Prismatische Entwicklungen", setNameEn = "Prismatic Evolutions", serieId = "sv",
            setOfficial = 131, setRelease = "2025-01-17", imageSetId = "sv08.5",
            variantQuantities = "normal=1;reverse|foil=pokeball=2;=1",
        )
        assertEquals(mapOf("normal" to 1, "reverse|foil=pokeball" to 2, "" to 1), item.quantities)
        assertEquals("normal", item.defaultVariant)
        assertEquals(4, item.variantList.size)
    }
}
