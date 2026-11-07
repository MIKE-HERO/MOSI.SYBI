package com.sybi.mosi.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Paciente::class, Resultado::class],
    version = 24,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun pacienteDao(): PacienteDao
    abstract fun resultadoDao(): ResultadoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "quiosco.db"
                )
                    .addMigrations(MIGRATION_23_24)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE resultados ADD COLUMN sincronizado_remoto INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE resultados ADD COLUMN id_medicion_remota INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE resultados ADD COLUMN intentos_sincronizacion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE resultados ADD COLUMN ultimo_error_sincronizacion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE resultados ADD COLUMN fecha_ultimo_intento TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}