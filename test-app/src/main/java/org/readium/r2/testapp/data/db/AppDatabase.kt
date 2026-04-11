package org.readium.r2.testapp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.readium.r2.testapp.data.model.*

@Database(
    entities = [
        Book::class,
        Bookmark::class,
        Highlight::class,
        Catalog::class,
        ReadingStat::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(HighlightConverters::class, Converters::class) // Добавляем Converters
abstract class AppDatabase : RoomDatabase() {

    abstract fun booksDao(): BooksDao
    abstract fun catalogDao(): CatalogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Миграция с версии 1 на 2
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE books ADD COLUMN reading_time INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE books ADD COLUMN pages_read INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE books ADD COLUMN last_read_date INTEGER")
            }
        }

        // Миграция с версии 2 на 3
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Создаем таблицу reading_stats
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS reading_stats (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        book_id INTEGER NOT NULL,
                        date TEXT NOT NULL,
                        pages_read INTEGER NOT NULL DEFAULT 0,
                        hours_read REAL NOT NULL DEFAULT 0,
                        FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE,
                        UNIQUE(book_id, date)
                    )
                """
                )

                // Создаем индексы
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_reading_stats_book_id ON reading_stats(book_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_reading_stats_date ON reading_stats(date)")
            }
        }
    }
}
