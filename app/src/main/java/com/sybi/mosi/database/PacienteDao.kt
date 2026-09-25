package com.sybi.mosi.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PacienteDao {

    @Insert
    suspend fun insertarPaciente(paciente: Paciente): Long

    @Update
    suspend fun actualizarPaciente(paciente: Paciente)

    @Query("SELECT * FROM pacientes WHERE telefono = :telefono LIMIT 1")
    suspend fun obtenerPacientePorTelefono(telefono: String): Paciente?

    @Query("SELECT * FROM pacientes WHERE id_usuario_web = :idUsuarioWeb LIMIT 1")
    suspend fun obtenerPacientePorIdUsuarioWeb(idUsuarioWeb: Int): Paciente?

    @Query("SELECT * FROM pacientes WHERE id_local = :idLocal LIMIT 1")
    suspend fun obtenerPacientePorIdLocal(idLocal: Long): Paciente?

    @Query("SELECT * FROM pacientes ORDER BY id_local ASC")
    suspend fun obtenerTodosLosPacientes(): List<Paciente>

    @Query("DELETE FROM pacientes WHERE id_local = :idLocal")
    suspend fun eliminarPaciente(idLocal: Long)

    @Query("DELETE FROM pacientes")
    suspend fun eliminarTodosLosPacientes()
}