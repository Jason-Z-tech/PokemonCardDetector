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
    /** Anzahl Karten, die in der Datenbank zu diesem Set gespeichert sind. */
    val cardCount: Int,
)

@Entity(
    tableName = "cards",
    indices = [Index("setId"), Index("searchKey")],
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
)

@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val cardId: String,
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
) {
    val owned: Boolean get() = quantity > 0

    /** "25/185" bzw. "TG05" wenn keine Gesamtzahl bekannt ist. */
    val numberLabel: String
        get() = if (setOfficial > 0 && card.localId.firstOrNull()?.isDigit() == true) {
            "${card.localId}/${setOfficial.toString().padStart(minOf(3, card.localId.length), '0')}"
        } else card.localId
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
)
