package com.sybi.mosi.repository

import android.util.Log
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.PacienteDao
import com.sybi.mosi.database.ResultadoDao
import com.sybi.mosi.network.UsuarioWeb
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Helper para sincronizar pacientes con la API y propagar id_usuario_web
 * a todos sus resultados locales.
 */
object PacienteSyncHelper {

    private const val TAG = "PacienteSyncHelper"

    /**
     * Aplica los datos de un UsuarioWeb de la API sobre un Paciente local,
     * sobrescribiendo solo los campos que la API devuelve con valor.
     */
    fun aplicarDatosAPI(
        local: Paciente,
        idUsuarioWeb: Int,
        usuario: UsuarioWeb
    ): Paciente {
        return local.copy(
            id_usuario_web = idUsuarioWeb,
            nombre = usuario.nombre?.ifBlank { null }?.uppercase() ?: local.nombre,
            apellido_paterno = usuario.apellidoPaterno?.ifBlank { null }?.uppercase() ?: local.apellido_paterno,
            apellido_materno = usuario.apellidoMaterno?.ifBlank { null }?.uppercase() ?: local.apellido_materno,
            fecha_nacimiento = parsearFechaNacimiento(usuario.fechaNacimiento) ?: local.fecha_nacimiento,
            curp = usuario.curp?.ifBlank { null }?.uppercase() ?: local.curp,
            telefono = usuario.celular ?: usuario.telefonoCasa ?: local.telefono,
            correo = usuario.correo?.trim()?.ifBlank { null }?.lowercase() ?: local.correo,
            folio = usuario.folio?.ifBlank { null } ?: local.folio,
            direccion = usuario.direccion?.uppercase() ?: local.direccion,
            fecha_registro_global = parsearFechaRegistroAPI(usuario.fechaRegistro),
            sincronizado = true
        )
    }

    /**
     * Sincroniza un paciente con la API y propaga su id_usuario_web
     * a todos los resultados que aún no lo tenían.
     *
     * @return true si el paciente se vinculó correctamente
     */
    suspend fun sincronizarPaciente(
        paciente: Paciente,
        pacienteDao: PacienteDao,
        resultadoDao: ResultadoDao,
        repo: PacienteRemoteRepository
    ): Boolean {
        val resultado = repo.buscarPacienteEnAPI(paciente) ?: return false

        // 1. Actualizar el paciente
        val actualizado = aplicarDatosAPI(
            local = paciente,
            idUsuarioWeb = resultado.idPaciente,
            usuario = resultado.usuarioWeb
        )
        pacienteDao.actualizarPaciente(actualizado)

        // 2. Propagar el id_usuario_web a todos sus resultados sin id
        resultadoDao.actualizarIdUsuarioWebPorPaciente(
            idLocal = paciente.id_local,
            idUsuarioWeb = resultado.idPaciente
        )

        Log.d(TAG, "✅ Paciente ${paciente.id_local} sincronizado con id ${resultado.idPaciente}, resultados propagados")
        return true
    }

    // ── Helpers privados ─────────────────────────────────

    private fun parsearFechaNacimiento(fechaAPI: String?): String? {
        if (fechaAPI.isNullOrBlank()) return null
        return try {
            val formatoEntrada = SimpleDateFormat("MMM d yyyy hh:mma", Locale.US)
            val fecha = formatoEntrada.parse(fechaAPI)
            val formatoSalida = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            fecha?.let { formatoSalida.format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando fecha nac: $fechaAPI", e)
            null
        }
    }

    private fun parsearFechaRegistroAPI(fechaAPI: String?): String {
        if (fechaAPI.isNullOrBlank()) return ""
        return try {
            val formatoEntrada = SimpleDateFormat("MMM d yyyy hh:mma", Locale.US)
            val fecha = formatoEntrada.parse(fechaAPI)
            val formatoSalida = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            fecha?.let { formatoSalida.format(it) } ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando fecha registro: $fechaAPI", e)
            ""
        }
    }
}