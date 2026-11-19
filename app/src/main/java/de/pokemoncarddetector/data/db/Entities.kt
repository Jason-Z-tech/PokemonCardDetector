package de.pokemoncarddetector.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "series")
data class SerieEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameEn: String,
)

@Entity(tableName = "sets")
data class SetEntity(
    @PrimaryKey val id: String,
    /** Deutscher Name (z.B. "Farbenschock"), sonst englischer. */
    val name: String,
    val nameEn: String,
    val serieId: String,
    /** yyyy-MM-dd */
    val releaseDate: String,
    /** Offizielles Kuerzel, z.B. "VIV", "PAL" (kann leer sein). */
    val abbreviation: String,
    /** Gedruckte Gesamtzahl ("/185"). */
    val officialCount: Int,
    /** Alle Karten inkl. Geheimkarten. */
    val totalCount: Int,
    val availableInGerman: Boolean,
    val logoUrl: String,
    /** Ordner der Kartenbilder (bei Unter-Sets wie swsh9tg das Haupt-Set swsh9). */
    @ColumnInfo(defaultValue = "''")
    val imageSetId: String,
    /** Anzahl Karten, die in der Datenbank zu diesem Set gespeichert sind. */
    val cardCount: Int,
)

@Entity(
    tableName = "cards",
    indices = [Index("setId"), Index("searchKey"), Index("numberKey")],
)
data class CardEntity(
    /** TCGdex-ID, z.B. "swsh4-25" */
    @PrimaryKey val id: String,
    val setId: String,
    /** Nummer im Set, z.B. "25", "TG05", "SV001" */
    val localId: String,
    val name: String,
    val nameEn: String,
    /** Bit 1 = deutsches Bild, Bit 2 = englisches Bild, Bit 4 = Karte existiert auf Deutsch */
    val imageFlags: Int,
    val sortIndex: Int,
    /** normalisierter Name (de + en) fuer die Suche */
    val searchKey: String,
    /** normalisierte Nummer fuer die Suche: "025" -> "25", "TG05" -> "TG5" */
    @ColumnInfo(defaultValue = "''")
    val numberKey: String,
    /** Varianten laut Katalog, ";"-getrennt (siehe [de.pokemoncarddetector.data.Variants]) */
    @ColumnInfo(defaultValue = "''")
    val variants: String = "",
    /** Kraftpunkte (0 = keine/unbekannt) – hilft der Erkennung */
    @ColumnInfo(defaultValue = "0")
    val hp: Int = 0,
    /** Regelzeichen (D, E, F, G, H, ...), leer wenn keins */
    @ColumnInfo(defaultValue = "''")
    val regulationMark: String = "",
    /** Seltenheit laut TCGdex (englisch, z.B. "Rare Holo"), leer wenn unbekannt */
    @ColumnInfo(defaultValue = "''")
    val rarity: String = "",
)

/** Eine Variante einer Karte in der Sammlung (z.B. swsh4-25 als Reverse Holo, 2x). */
@Entity(tableName = "collection", primaryKeys = ["cardId", "variant"])
data class CollectionEntity(
    val cardId: String,
    /** Varianten-Schluessel, "" = unbekannt */
    @ColumnInfo(defaultValue = "''")
    val variant: String,
    val quantity: Int,
    val updatedAt: Long,
)

/** Set mit Sammelfortschritt. */
data class SetWithProgress(
    @Embedded val set: SetEntity,
    /** verschiedene Karten im Besitz */
    @ColumnInfo(name = "owned") val owned: Int,
    /** Karten insgesamt (inkl. Doppelte) */
    @ColumnInfo(name = "copies") val copies: Int,
)

/** Karte + Set-Infos + Anzahl im Besitz. */
data class CardItem(
    @Embedded val card: CardEntity,
    @ColumnInfo(name = "quantity") val quantity: Int,
    @ColumnInfo(name = "setName") val setName: String,
    @ColumnInfo(name = "setNameEn") val setNameEn: String,
    @ColumnInfo(name = "serieId") val serieId: String,
    @ColumnInfo(name = "setOfficial") val setOfficial: Int,
    @ColumnInfo(name = "setRelease") val setRelease: String,
    @ColumnInfo(name = "imageSetId") val imageSetId: String,
    /** "variante=anzahl;..." aus der Sammlung (null = nichts gesammelt) */
    @ColumnInfo(name = "variantQuantities") val variantQuantities: String? = null,
    /** auf der Wunschliste */
    @ColumnInfo(name = "wished") val wished: Boolean = false,
) {
    /** Japanische Karte (eigener Katalog, IDs mit "ja:") */
    val isJapanese: Boolean get() = card.setId.startsWith("ja:")

    /** Japanischer Name + deutscher Hinweis, sonst der normale Name; ohne Namen (Platzhalter) die Nummer */
    val displayName: String
        get() = when {
            isJapanese && card.name.isNotBlank() && card.nameEn.isNotBlank() -> "${card.name} (${card.nameEn})"
            card.name.isNotBlank() -> card.name
            card.nameEn.isNotBlank() -> card.nameEn
            else -> "Karte Nr. $displayLocalId"
        }

    val owned: Boolean get() = quantity > 0

    /** Varianten, die es von dieser Karte gibt (Katalog-Reihenfolge). */
    val variantList: List<String> get() = de.pokemoncarddetector.data.Variants.list(card.variants)

    /** Anzahl je Variante im Besitz. */
    val quantities: Map<String, Int>
        get() = variantQuantities.orEmpty().split(';').mapNotNull { part ->
            val i = part.lastIndexOf('=')
            if (i < 0) null else part.substring(0, i) to (part.substring(i + 1).toIntOrNull() ?: 0)
        }.filter { it.second > 0 }.toMap()

    /** Standard-Variante zum schnellen Hinzufuegen. */
    val defaultVariant: String get() = de.pokemoncarddetector.data.Variants.default(variantList)

    /** Nummer zum Anzeigen ("%3F" -> "?"). */
    val displayLocalId: String
        get() = runCatching { java.net.URLDecoder.decode(card.localId, "UTF-8") }.getOrDefault(card.localId)

    /** Nummer wie auf der Karte gedruckt, z.B. "025/185" (ab Schwert & Schild dreistellig) oder "4/102". */
    val numberLabel: String
        get() {
            val id = displayLocalId
            val m = NUMBER.matchEntire(id)
            if (setOfficial <= 0 || m == null) return id
            val pad = if (serieId in PADDED_SERIES || isPaddedJapanese(serieId)) 3 else 0
            val digits = m.groupValues[1].trimStart('0').ifEmpty { "0" }.padStart(pad, '0')
            return "$digits${m.groupValues[2]}/${setOfficial.toString().padStart(pad, '0')}"
        }

    private companion object {
        val NUMBER = Regex("^([0-9]+)([a-zA-Z]?)$")
        /** Serien, die Nummern dreistellig drucken (025/185) */
        val PADDED_SERIES = setOf("swsh", "sv", "me")

        /** Japanische Karten drucken dreistellig (006/165), ausser den ersten Serien (PMCG, neo: ohne Nummer). */
        fun isPaddedJapanese(serieId: String): Boolean =
            serieId.startsWith("ja:") && !serieId.startsWith("ja:PMCG") && !serieId.startsWith("ja:neo")
    }
}

data class CollectionTotals(
    @ColumnInfo(name = "uniqueCards") val uniqueCards: Int,
    @ColumnInfo(name = "copies") val copies: Int,
)

data class IndexCardRow(
    val id: String,
    val setId: String,
    val localId: String,
    val name: String,
    val nameEn: String,
    val hp: Int,
)
