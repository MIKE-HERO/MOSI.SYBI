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

    // ORDER BY id_local DESC: si por un bug anterior quedaron filas duplicadas
    // para la misma persona (antes de sobrescribir en vez de insertar), siempre
    // se prefiere la más reciente en vez de una fila vieja al azar (SQLite no
    // garantiza cuál devuelve LIMIT 1 sin ORDER BY cuando hay varias filas).
    @Query("SELECT * FROM pacientes WHERE telefono = :telefono ORDER BY id_local DESC LIMIT 1")
    suspend fun obtenerPacientePorTelefono(telefono: String): Paciente?

    @Query("SELECT * FROM pacientes WHERE tarjetaIc = :tarjetaIc ORDER BY id_local DESC LIMIT 1")
    suspend fun obtenerPacientePorTarjetaIc(tarjetaIc: String): Paciente?

    @Query("SELECT * FROM pacientes WHERE curp = :curp ORDER BY id_local DESC LIMIT 1")
    suspend fun obtenerPacientePorCurp(curp: String): Paciente?

    // Todas las filas que comparten teléfono, tarjetaIc o CURP con una ya conocida
    // (excluyéndola a ella misma), para detectar/limpiar duplicados de la misma
    // persona. Los campos vacíos no cuentan como coincidencia.
    @Query(
        """
        SELECT * FROM pacientes
        WHERE id_local != :idLocalExcluir
        AND (
            (:telefono != '' AND telefono = :telefono) OR
            (:tarjetaIc != '' AND tarjetaIc = :tarjetaIc) OR
            (:curp != '' AND curp = :curp)
        )
        """
    )
    suspend fun obtenerDuplicadosDe(
        idLocalExcluir: Long,
        telefono: String,
        tarjetaIc: String,
        curp: String
    ): List<Paciente>

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