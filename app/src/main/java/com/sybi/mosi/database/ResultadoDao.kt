package com.sybi.mosi.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ResultadoDao {

    @Insert
    suspend fun insertarResultado(resultado: Resultado)

    @Query("SELECT * FROM resultados WHERE id_usuario_web = :idUsuarioWeb ORDER BY id_resultado DESC")
    suspend fun obtenerResultadosPorUsuarioWeb(idUsuarioWeb: Int): List<Resultado>

    @Query("SELECT * FROM resultados WHERE id_local = :idLocal ORDER BY id_resultado DESC")
    suspend fun obtenerResultadosPorIdLocal(idLocal: Long): List<Resultado>

    @Query("SELECT * FROM resultados WHERE id_resultado = :idResultado LIMIT 1")
    suspend fun obtenerResultadoPorId(idResultado: Long): Resultado?

    @Query("SELECT * FROM resultados ORDER BY id_resultado DESC")
    suspend fun obtenerTodosLosResultados(): List<Resultado>

    @Query("UPDATE resultados SET id_usuario_web = :idUsuarioWeb WHERE id_resultado = :idResultado")
    suspend fun actualizarIdUsuarioWeb(idResultado: Long, idUsuarioWeb: Int)

    @Query("DELETE FROM resultados WHERE id_resultado = :idResultado")
    suspend fun eliminarResultado(idResultado: Long)

    @Query("UPDATE resultados SET id_usuario_web = :idUsuarioWeb WHERE id_local = :idLocal AND id_usuario_web = 0")
    suspend fun actualizarIdUsuarioWebPorPaciente(idLocal: Long, idUsuarioWeb: Int)

    // Al eliminar un paciente hay que limpiar también sus mediciones: son su propio
    // almacenamiento (tabla "resultados"), sin relación de borrado en cascada con
    // Room, así que si no se borran aquí quedan huérfanas (id_local apuntando a un
    // paciente que ya no existe).
    @Query("DELETE FROM resultados WHERE id_local = :idLocal")
    suspend fun eliminarResultadosPorIdLocal(idLocal: Long)
}