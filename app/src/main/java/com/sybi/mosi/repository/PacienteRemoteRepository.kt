package com.sybi.mosi.repository

import android.content.Context
import android.util.Log
import com.sybi.mosi.DeviceInfoActivity
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.network.MedicionesRequest
import com.sybi.mosi.network.RetrofitClient
import com.sybi.mosi.network.UsuarioWeb

class PacienteRemoteRepository(private val context: Context) {

    companion object {
        private const val TAG = "PacienteRemoteRepo"
    }

    data class ResultadoBusqueda(
        val idPaciente: Int,
        val usuarioWeb: UsuarioWeb
    )

    data class ResultadoMultiple(
        val usuarios: List<UsuarioWeb>,
        val primerUsuario: UsuarioWeb? = usuarios.firstOrNull(),
        val idPaciente: Int? = primerUsuario?.idUsuarioWeb?.toIntOrNull()
    )

    /**
     * ✅ Obtiene el id_cliente configurado en el dispositivo.
     */
    private fun getIdCliente(): String? {
        val id = DeviceInfoActivity.getCliente(context)
        return if (id.isBlank()) null else id
    }

    // ==========================================
    // BÚSQUEDA PRINCIPAL (flujo original)
    // ==========================================
    suspend fun buscarPacienteEnAPI(paciente: Paciente): ResultadoBusqueda? {
        return try {
            if (paciente.telefono.isNotEmpty()) {
                Log.d(TAG, "🔍 Buscando por celular: ${paciente.telefono}")
                val r = buscarPorCelular(paciente.telefono)
                if (r != null) return r
            }

            if (paciente.curp.isNotEmpty()) {
                Log.d(TAG, "🔍 Buscando por CURP: ${paciente.curp}")
                val r = buscarPorCurp(paciente.curp)
                if (r != null) return r
            }

            if (paciente.folio.isNotEmpty()) {
                Log.d(TAG, "🔍 Buscando por folio: ${paciente.folio}")
                val r = buscarPorFolio(
                    folio = paciente.folio,
                    nombre = paciente.nombre,
                    apellidoPaterno = paciente.apellido_paterno,
                    apellidoMaterno = paciente.apellido_materno
                )
                if (r != null) return r
            }

            Log.w(TAG, "❌ Paciente no encontrado en la API")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}", e)
            null
        }
    }

    // ==========================================
    // BÚSQUEDA POR CELULAR
    // ==========================================
    suspend fun buscarPorCelular(telefono: String): ResultadoBusqueda? {
        Log.d(TAG, "🌐 Iniciando búsqueda por celular: $telefono")
        return try {
            val telLimpio = telefono.replace(Regex("[^0-9]"), "")
            val idCliente = getIdCliente()
            Log.d(TAG, "   id_cliente: $idCliente")

            val response = RetrofitClient.apiService.buscarPaciente(
                celular = telLimpio,
                idCliente = idCliente
            )
            Log.d(TAG, "📡 Respuesta búsqueda por celular: HTTP ${response.code()}")

            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "📦 Body búsqueda por celular: $body")
                val usuario = body?.primerUsuario
                val id = usuario?.idUsuarioWeb?.toIntOrNull()

                if (id != null && usuario != null) {
                    Log.d(TAG, "✅ Paciente encontrado: ${usuario.nombre}")
                    ResultadoBusqueda(id, usuario)
                } else {
                    Log.w(TAG, "⚠️ No se encontró id o usuario en el body")
                    null
                }
            } else {
                Log.e(TAG, "❌ Error HTTP búsqueda por celular: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "🔥 Excepción buscando por celular: ${e.message}", e)
            null
        }
    }

    // ==========================================
    // BÚSQUEDA POR CURP
    // ==========================================
    suspend fun buscarPorCurp(curp: String): ResultadoBusqueda? {
        Log.d(TAG, "🌐 Iniciando búsqueda por CURP: $curp")
        return try {
            val curpLimpia = curp.trim().uppercase()
            val idCliente = getIdCliente()
            Log.d(TAG, "   id_cliente: $idCliente")

            val response = RetrofitClient.apiService.buscarPaciente(
                curp = curpLimpia,
                idCliente = idCliente
            )
            Log.d(TAG, "📡 Respuesta búsqueda por CURP: HTTP ${response.code()}")

            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "📦 Body búsqueda por CURP: $body")
                val usuario = body?.primerUsuario
                val id = usuario?.idUsuarioWeb?.toIntOrNull()

                if (id != null && usuario != null) {
                    Log.d(TAG, "✅ Paciente encontrado: ${usuario.nombre}")
                    ResultadoBusqueda(id, usuario)
                } else {
                    Log.w(TAG, "⚠️ No se encontró id o usuario en el body")
                    null
                }
            } else {
                Log.e(TAG, "❌ Error HTTP búsqueda por CURP: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "🔥 Excepción buscando por CURP: ${e.message}", e)
            null
        }
    }

    // ==========================================
    // BÚSQUEDA POR FOLIO (devuelve MÚLTIPLES usuarios)
    // ==========================================
    suspend fun buscarPorFolio(folio: String): ResultadoMultiple? {
        Log.d(TAG, "🌐 Iniciando búsqueda por folio: $folio")
        return try {
            val folioLimpio = folio.trim()
            val idCliente = getIdCliente()
            Log.d(TAG, "   id_cliente: $idCliente")

            val response = RetrofitClient.apiService.buscarPaciente(
                folio = folioLimpio,
                idCliente = idCliente
            )
            Log.d(TAG, "📡 Respuesta búsqueda por folio: HTTP ${response.code()}")

            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "📦 Body búsqueda por folio: $body")
                val usuarios = body?.usuarios

                if (usuarios.isNullOrEmpty()) {
                    Log.w(TAG, "⚠️ No se encontraron usuarios para el folio $folioLimpio")
                    null
                } else {
                    Log.d(TAG, "✅ ${usuarios.size} usuario(s) encontrado(s) para folio $folioLimpio")
                    ResultadoMultiple(usuarios)
                }
            } else {
                Log.e(TAG, "❌ Error HTTP búsqueda por folio: ${response.errorBody()?.string()}")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "🔥 Excepción buscando por folio: ${e.message}", e)
            null
        }
    }

    // ==========================================
    // BÚSQUEDA POR FOLIO (flujo interno, filtra por nombre)
    // ==========================================
    private suspend fun buscarPorFolio(
        folio: String,
        nombre: String,
        apellidoPaterno: String,
        apellidoMaterno: String
    ): ResultadoBusqueda? {
        val resultado = buscarPorFolio(folio) ?: return null
        val total = resultado.usuarios.size

        val usuario = if (total == 1) {
            resultado.primerUsuario
        } else {
            Log.d(TAG, "⚠️ Múltiples resultados ($total), filtrando por nombre...")
            resultado.usuarios.firstOrNull { u ->
                val coincideNombre = nombre.isBlank() ||
                        u.nombre?.equals(nombre, ignoreCase = true) == true
                val coincideApPat = apellidoPaterno.isBlank() ||
                        u.apellidoPaterno?.equals(apellidoPaterno, ignoreCase = true) == true
                val coincideApMat = apellidoMaterno.isBlank() ||
                        u.apellidoMaterno?.equals(apellidoMaterno, ignoreCase = true) == true
                coincideNombre && coincideApPat && coincideApMat
            }
        }

        val id = usuario?.idUsuarioWeb?.toIntOrNull()
        return if (id != null && usuario != null) ResultadoBusqueda(id, usuario) else null
    }

    // ==========================================
    // REGISTRAR MEDICIÓN
    // ==========================================
    suspend fun enviarMediciones(mediciones: MedicionesRequest): Boolean {
        return try {
            Log.d(TAG, "📤 Enviando medición al servidor:")
            Log.d(TAG, "   id_UsuarioWeb: ${mediciones.idUsuarioWeb}")
            Log.d(TAG, "   id_VendingMachine: ${mediciones.idVendingMachine}")
            Log.d(TAG, "   recordID: ${mediciones.recordID}")
            Log.d(TAG, "   device_id: ${mediciones.deviceId}")
            Log.d(TAG, "   ECG Base64 length: ${mediciones.ecgImg?.length ?: 0} chars (~${(mediciones.ecgImg?.length ?: 0) / 1024} KB)")

            val jsonSize = com.google.gson.Gson().toJson(mediciones).length
            Log.d(TAG, "   JSON total: ~${jsonSize / 1024} KB")

            if (jsonSize > 5 * 1024 * 1024) {
                Log.e(TAG, "❌ JSON demasiado grande (>5 MB), abortando envío")
                return false
            }

            val response = RetrofitClient.apiService.registrarMedicion(mediciones)

            if (response.isSuccessful) {
                Log.d(TAG, "✅ Medición registrada: id=${response.body()?.id}")
                true
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "❌ HTTP ${response.code()} → $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Excepción enviando medición: ${e.message}", e)
            false
        }
    }
}