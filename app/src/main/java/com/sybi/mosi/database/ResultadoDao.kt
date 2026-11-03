package com.sybi.mosi.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ResultadoDao {

    @Insert
    suspend fun insertarResultado(resultado: Resultado): Long

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

    @Query("SELECT * FROM resultados WHERE sincronizado_remoto = 0 AND id_usuario_web > 0 ORDER BY id_resultado ASC LIMIT :limit")
    suspend fun obtenerPendientesSincronizacion(limit: Int = 20): List<Resultado>

    @Query("SELECT COUNT(*) FROM resultados WHERE sincronizado_remoto = 0")
    suspend fun contarPendientesSincronizacion(): Int

    @Query("UPDATE resultados SET sincronizado_remoto = 1, id_medicion_remota = :idRemoto, ultimo_error_sincronizacion = '', fecha_ultimo_intento = :fecha WHERE id_resultado = :idResultado")
    suspend fun marcarSincronizado(idResultado: Long, idRemoto: Int?, fecha: String)

    @Query("UPDATE resultados SET intentos_sincronizacion = intentos_sincronizacion + 1, ultimo_error_sincronizacion = :error, fecha_ultimo_intento = :fecha WHERE id_resultado = :idResultado")
    suspend fun marcarErrorSincronizacion(idResultado: Long, error: String, fecha: String)
}
