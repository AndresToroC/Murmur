package com.nochesonora.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, EventEntity::class, EpisodeEntity::class, PauseEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nightDao(): NightDao

    companion object {
        /** 1 → 2: transcription of speech clips (0.5.0). Existing nights are kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN transcript TEXT")
            }
        }

        /** 2 → 3: repeats of ambient noises (0.6.0). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN repeats INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE events ADD COLUMN lastRepeatAt INTEGER")
            }
        }

        /**
         * Stored values that were Spanish before 4, for each table and column. The schema does
         * not change; only these values are rewritten in English.
         */
        internal val ENGLISH_VALUES: List<Triple<String, String, Map<String, String>>> = run {
            val categories = mapOf(
                "RONQUIDO" to "SNORING", "HABLA" to "SPEECH", "TOS" to "COUGH", "OTRO" to "OTHER", "FONDO" to "BACKGROUND",
            )
            listOf(
                Triple("events", "category", categories),
                Triple("events", "userCategory", categories),
                Triple("episodes", "category", categories),
                Triple(
                    "sessions", "status",
                    mapOf("EN_CURSO" to "IN_PROGRESS", "COMPLETADA" to "COMPLETED", "INTERRUMPIDA" to "INTERRUPTED"),
                ),
                Triple(
                    "pauses", "reason",
                    mapOf("MICROFONO_OCUPADO" to "MIC_BUSY", "LLAMADA" to "CALL", "ERROR_AUDIO" to "AUDIO_ERROR", "USUARIO" to "USER"),
                ),
                Triple("events", "otherLabel", mapOf("Desconocido" to "Unknown", "Sin clasificar" to "Unclassified")),
            )
        }

        /** 3 → 4: stored values in English (0.8.0). Existing nights are kept. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for ((table, column, values) in ENGLISH_VALUES) {
                    for ((spanish, english) in values) {
                        db.execSQL("UPDATE $table SET $column = ? WHERE $column = ?", arrayOf<Any>(english, spanish))
                    }
                }
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nochesonora.db")
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
