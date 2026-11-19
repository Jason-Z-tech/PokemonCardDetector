package de.pokemoncarddetector.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SerieEntity::class, SetEntity::class, CardEntity::class, CollectionEntity::class],
    version = 2,
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

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "karten.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
