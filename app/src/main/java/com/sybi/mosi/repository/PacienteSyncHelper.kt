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
        val curpFinal = usuario.curp?.ifBlank { null }?.uppercase() ?: local.curp
        val generoInferido = obtenerGeneroDesdeCurp(curpFinal)
        val generoFinal = if (!generoInferido.isNullOrBlank() && (local.genero.isBlank() || local.genero == "O")) {
            generoInferido
        } else {
            local.genero
        }

        return local.copy(
            id_usuario_web = idUsuarioWeb,
            nombre = usuario.nombre?.ifBlank { null }?.uppercase() ?: local.nombre,
            apellido_paterno = usuario.apellidoPaterno?.ifBlank { null }?.uppercase() ?: local.apellido_paterno,
            apellido_materno = usuario.apellidoMaterno?.ifBlank { null }?.uppercase() ?: local.apellido_materno,
            fecha_nacimiento = parsearFechaNacimiento(usuario.fechaNacimiento) ?: local.fecha_nacimiento,
            genero = generoFinal,
            curp = curpFinal,
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
        repo: PacienteRemoteRepository,
        forzarSobrescribirAPI: Boolean = false
    ): Boolean {
        return try {
            val resultado = repo.buscarPacienteEnAPI(paciente)
            if (resultado == null) {
                Log.d(TAG, "⚠️ Paciente ${paciente.id_local} no encontrado en la API (tel=${paciente.telefono}, curp=${paciente.curp}, folio=${paciente.folio})")
                return false
            }

            // 1. Actualizar el paciente (respetando cambios locales si no se fuerza la sobrescritura)
            val actualizado = if (!forzarSobrescribirAPI && paciente.id_usuario_web != null && paciente.id_usuario_web > 0) {
                paciente.copy(
                    id_usuario_web = resultado.idPaciente,
                    sincronizado = true
                )
            } else {
                aplicarDatosAPI(
                    local = paciente,
                    idUsuarioWeb = resultado.idPaciente,
                    usuario = resultado.usuarioWeb
                )
            }

            // Log de diagnóstico: qué campos realmente cambian con esta sincronización.
            if (actualizado == paciente) {
                Log.w(
                    TAG,
                    "⚠️ Paciente ${paciente.id_local}: la API respondió pero NINGÚN campo cambió " +
                            "(posible deserialización vacía de UsuarioWeb; revisar que los nombres de " +
                            "campo del JSON coincidan con @SerializedName en UsuarioWeb.kt). " +
                            "usuarioWeb=${resultado.usuarioWeb}"
                )
            } else {
                Log.d(
                    TAG,
                    "🔄 Paciente ${paciente.id_local}: nombre '${paciente.nombre}'->'${actualizado.nombre}', " +
                            "telefono '${paciente.telefono}'->'${actualizado.telefono}', " +
                            "correo '${paciente.correo}'->'${actualizado.correo}', " +
                            "curp '${paciente.curp}'->'${actualizado.curp}'"
                )
            }

            pacienteDao.actualizarPaciente(actualizado)

            // Verificación: releer de la BD para confirmar que el UPDATE realmente se aplicó.
            val relectura = pacienteDao.obtenerPacientePorIdLocal(paciente.id_local)
            if (relectura != actualizado) {
                Log.e(
                    TAG,
                    "❌ Paciente ${paciente.id_local}: actualizarPaciente() no dejó la fila como se esperaba. " +
                            "Esperado=$actualizado, leído=$relectura"
                )
            }

            // 2. Propagar el id_usuario_web a todos sus resultados sin id
            resultadoDao.actualizarIdUsuarioWebPorPaciente(
                idLocal = paciente.id_local,
                idUsuarioWeb = resultado.idPaciente
            )

            Log.d(TAG, "✅ Paciente ${paciente.id_local} sincronizado con id ${resultado.idPaciente}, resultados propagados")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sincronizando paciente ${paciente.id_local}: ${e.message}", e)
            false
        }
    }

    // ── Helpers privados ─────────────────────────────────

    private fun obtenerGeneroDesdeCurp(curp: String?): String? {
        if (curp.isNullOrBlank()) return null
        val curpLimpia = curp.trim().uppercase()
        if (curpLimpia.length >= 11) {
            return when (curpLimpia[10]) {
                'H' -> "M" // Hombre -> Masculino
                'M' -> "F" // Mujer -> Femenino
                else -> null
            }
        }
        return null
    }

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
        val formatos = listOf(
            SimpleDateFormat("MMM d yyyy hh:mma", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        )
        val formatoSalida = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        for (f in formatos) {
            try {
                f.isLenient = true
                val fecha = f.parse(fechaAPI)
                if (fecha != null) {
                    return formatoSalida.format(fecha)
                }
            } catch (_: Exception) {
                // continuar
            }
        }
        return fechaAPI.ifBlank { "" }
    }
}