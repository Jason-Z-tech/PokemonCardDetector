package de.pokemoncarddetector.data.catalog

import de.pokemoncarddetector.data.db.CardEntity
import de.pokemoncarddetector.data.db.SerieEntity
import de.pokemoncarddetector.data.db.SetEntity
import de.pokemoncarddetector.recognition.CardTextParser
import de.pokemoncarddetector.recognition.TextNorm
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Format von assets/catalog.json (erzeugt von tools/build_catalog.py). */
@Serializable
data class CatalogFile(
    val version: String,
    val series: List<CatalogSerie>,
    val sets: List<CatalogSet>,
)

@Serializable
data class CatalogSerie(val id: String, val name: String, val nameEn: String)

@Serializable
data class CatalogSet(
    val id: String,
    val name: String,
    val nameEn: String,
    val serie: String,
    val release: String = "",
    val abbr: String = "",
    val official: Int = 0,
    val total: Int = 0,
    val de: Boolean = false,
    val logo: String = "",
    val symbol: String = "",
    /** Ordner der Kartenbilder (Unter-Sets: Haupt-Set), leer = eigene id */
    val img: String = "",
    /** [localId, nameDe, nameEn, flags] */
    val cards: List<JsonArray> = emptyList(),
)

/** Set-Metadaten + Karten, bereit zum Speichern. */
data class SetPayload(val meta: CatalogSet, val cards: List<CatalogCard>)

/** Karte, wie sie Import und Online-Abgleich liefern. */
data class CatalogCard(val localId: String, val name: String, val nameEn: String, val flags: Int)

fun CatalogSet.parsedCards(): List<CatalogCard> = cards.mapNotNull { row ->
    if (row.size < 4) return@mapNotNull null
    CatalogCard(
        localId = row[0].jsonPrimitive.content,
        name = row[1].jsonPrimitive.content,
        nameEn = row[2].jsonPrimitive.content,
        flags = row[3].jsonPrimitive.intOrNull ?: row[3].jsonPrimitive.int,
    )
}

fun CatalogSerie.toEntity() = SerieEntity(id = id, name = name, nameEn = nameEn)

fun CatalogSet.toEntity(cardCount: Int) = SetEntity(
    id = id,
    name = name,
    nameEn = nameEn,
    serieId = serie,
    releaseDate = release,
    abbreviation = abbr,
    officialCount = official,
    totalCount = total,
    availableInGerman = de,
    logoUrl = logo,
    imageSetId = img.ifBlank { id },
    cardCount = cardCount,
)

fun CatalogCard.toEntity(setId: String, sortIndex: Int) = CardEntity(
    id = "$setId-$localId",
    setId = setId,
    localId = localId,
    name = name,
    nameEn = nameEn,
    imageFlags = flags,
    sortIndex = sortIndex,
    searchKey = buildSearchKey(name, nameEn),
    numberKey = CardTextParser.normalizeLocalId(localId),
)

/** Unter-Sets (Trainer-Galerie, Galar-Galerie, Glitzer-Tresor) haben ihre Bilder im Ordner des Haupt-Sets. */
private val SUBSET = Regex("^(swsh\\d+(?:\\.5)?)(tg|gg|sv)$")

fun imageSetIdFor(setId: String): String = SUBSET.matchEntire(setId)?.groupValues?.get(1) ?: setId

/** Sortierung wie auf den Karten: SV2 vor SV10, Zahlen vor Praefix-Nummern. */
fun naturalCardOrder(): Comparator<String> = compareBy<String>(
    { Regex("^([A-Za-z]*)([0-9]+)(.*)$").matchEntire(it)?.groupValues?.get(1)?.uppercase() ?: "~" },
    { Regex("^([A-Za-z]*)([0-9]+)(.*)$").matchEntire(it)?.groupValues?.get(2)?.toIntOrNull() ?: Int.MAX_VALUE },
    { Regex("^([A-Za-z]*)([0-9]+)(.*)$").matchEntire(it)?.groupValues?.get(3) ?: it },
)

fun buildSearchKey(name: String, nameEn: String): String {
    val a = TextNorm.normalize(name)
    val b = TextNorm.normalize(nameEn)
    return if (a == b) a else "$a|$b"
}
