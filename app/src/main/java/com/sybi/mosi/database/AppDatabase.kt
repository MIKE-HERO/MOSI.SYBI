package com.sybi.mosi.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Paciente::class, Resultado::class],
    version = 21, //21
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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}