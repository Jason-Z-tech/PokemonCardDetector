package de.pokemoncarddetector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SerieEntity::class, SetEntity::class, CardEntity::class, CollectionEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        /**
         * v2: Bildordner pro Set (Unter-Sets) und normalisierte Kartennummer fuer die Suche.
         * Die Sammlung bleibt erhalten; die neuen Spalten fuellt der naechste Katalog-Import.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sets ADD COLUMN imageSetId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN numberKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cards_numberKey ON cards (numberKey)")
            }
        }

        /**
         * v3: Varianten (Normal/Holo/Reverse/...). Die Sammlung zaehlt je Variante; vorhandene Eintraege
         * werden als "Variante unbekannt" uebernommen. Dazu KP und Regelzeichen fuer die Erkennung.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN variants TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN hp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN regulationMark TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE collection_new (cardId TEXT NOT NULL, variant TEXT NOT NULL DEFAULT '', " +
                        "quantity INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(cardId, variant))"
                )
                db.execSQL(
                    "INSERT INTO collection_new (cardId, variant, quantity, updatedAt) " +
                        "SELECT cardId, '', quantity, updatedAt FROM collection"
                )
                db.execSQL("DROP TABLE collection")
                db.execSQL("ALTER TABLE collection_new RENAME TO collection")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "karten.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
