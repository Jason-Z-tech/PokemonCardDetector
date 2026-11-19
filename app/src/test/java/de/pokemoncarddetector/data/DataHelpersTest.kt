package de.pokemoncarddetector.data

import de.pokemoncarddetector.data.catalog.imageSetIdFor
import de.pokemoncarddetector.data.catalog.naturalCardOrder
import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.CardItem
import de.pokemoncarddetector.recognition.FrameFilter
import de.pokemoncarddetector.recognition.FrameGeometry
import de.pokemoncarddetector.recognition.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Test

class DataHelpersTest {

    private fun item(localId: String, serie: String, official: Int, flags: Int = 3, setId: String = "x", imageSet: String = setId) = CardItem(
        card = CardEntity("$setId-$localId", setId, localId, "Name", "Name", flags, 0, "name", localId),
        quantity = 0, setName = "Set", setNameEn = "Set", serieId = serie, setOfficial = official, setRelease = "2020-01-01",
        imageSetId = imageSet,
    )

    @Test
    fun nummernWieGedruckt() {
        assertEquals("025/185", item("25", "swsh", 185).numberLabel)       // swsh1-8: TCGdex ungepolstert
        assertEquals("025/198", item("025", "sv", 198).numberLabel)
        assertEquals("045/091", item("045", "sv", 91).numberLabel)
        assertEquals("4/102", item("4", "base", 102).numberLabel)
        assertEquals("24a/6", item("24a", "xy", 6).numberLabel)
        assertEquals("TG05", item("TG05", "swsh", 30).numberLabel)
        assertEquals("?", item("%3F", "ex", 28).numberLabel)
        assertEquals("SWSH050", item("SWSH050", "swsh", 307).numberLabel)
    }

    @Test
    fun bildordnerFuerUnterSets() {
        assertEquals("swsh9", imageSetIdFor("swsh9tg"))
        assertEquals("swsh12.5", imageSetIdFor("swsh12.5gg"))
        assertEquals("swsh4.5", imageSetIdFor("swsh4.5sv"))
        assertEquals("sv01", imageSetIdFor("sv01"))
        assertEquals("swsh4", imageSetIdFor("swsh4"))
        val url = ImageUrls.card(item("TG05", "swsh", 30, setId = "swsh9tg", imageSet = "swsh9"), ImageUrls.Quality.LOW, ImageLanguage.GERMAN)
        assertEquals("https://assets.tcgdex.net/de/swsh/swsh9/TG05/low.webp", url)
    }

    @Test
    fun bildspracheMitFallback() {
        val onlyEn = item("4", "base", 102, flags = 2, setId = "base1")
        assertEquals("https://assets.tcgdex.net/en/base/base1/4/high.webp", ImageUrls.card(onlyEn, ImageUrls.Quality.HIGH, ImageLanguage.GERMAN))
        assertEquals(null, ImageUrls.card(item("4", "base", 102, flags = 4), ImageUrls.Quality.LOW, ImageLanguage.GERMAN))
    }

    @Test
    fun natuerlicheReihenfolge() {
        val sorted = listOf("XY11", "XY100", "XY2", "RC1", "10", "2", "1a", "Museum").sortedWith(naturalCardOrder())
        assertEquals(listOf("2", "1a", "10", "RC1", "XY2", "XY11", "XY100", "Museum").sortedWith(naturalCardOrder()), sorted)
        assertEquals("2", sorted.first { it.all(Char::isDigit) })
        assertEquals(listOf("XY2", "XY11", "XY100"), sorted.filter { it.startsWith("XY") })
        assertEquals("Museum", sorted.last())
    }

    @Test
    fun rahmenfilterRechnetVorschauUm() {
        // Bild 1080x1920 (hochkant), View 1080x2000 -> skaliert auf Hoehe, Seiten beschnitten
        val frame = FrameGeometry(1080, 2000, 200f, 500f, 880f, 1450f)
        val inside = OcrLine("025/185", 300, 1200, 420, 1230)
        val outside = OcrLine("999/999", 10, 100, 100, 120)
        val r = FrameFilter.filter(listOf(inside, outside), 1080, 1920, frame)
        assertEquals(listOf(inside), r)
        // Live: auch wenn nichts im Rahmen liegt, keine Zeilen von ausserhalb verwenden
        assertEquals(emptyList<OcrLine>(), FrameFilter.filter(listOf(outside), 1080, 1920, frame))
        // Einzelfoto: Rueckfall auf alle Zeilen
        assertEquals(listOf(outside), FrameFilter.filter(listOf(outside), 1080, 1920, frame, fallbackToAll = true))
    }
}
