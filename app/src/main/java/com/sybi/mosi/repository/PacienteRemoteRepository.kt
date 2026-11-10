package com.sybi.mosi.repository

import android.content.Context
import android.util.Log
import com.sybi.mosi.DeviceInfoActivity
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.network.ConnectivityStatusProvider
import com.sybi.mosi.network.MedicionesRequest
import com.sybi.mosi.network.NetworkSpeedHelper
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

    data class EnvioMedicionResult(
        val success: Boolean,
        val remoteId: Int = 0,
        val error: String = ""
    )

    /**
     * ✅ Obtiene el id_cliente configurado en el dispositivo.
     */
    private fun getIdCliente(): String? {
        val id = DeviceInfoActivity.getCliente(context)
        return if (id.isBlank()) null else id
    }

    /**
     * Cuando hay varios beneficiarios (mismo folio o mismo teléfono),
     * elige el que coincida con nombre y apellidos. Si no hay coincidencia,
     * devuelve null para no vincular al paciente equivocado.
     */
    private fun filtrarPorNombre(
        usuarios: List<UsuarioWeb>?,
        nombre: String,
        apellidoPaterno: String,
        apellidoMaterno: String
    ): UsuarioWeb? {
        if (usuarios.isNullOrEmpty()) return null
        if (usuarios.size == 1) return usuarios.first()

        Log.d(TAG, "⚠️ Múltiples resultados (${usuarios.size}), filtrando por nombre...")
        return usuarios.firstOrNull { u ->
            val coincideNombre = nombre.isBlank() ||
                    u.nombre?.equals(nombre, ignoreCase = true) == true
            val coincideApPat = apellidoPaterno.isBlank() ||
                    u.apellidoPaterno?.equals(apellidoPaterno, ignoreCase = true) == true
            val coincideApMat = apellidoMaterno.isBlank() ||
                    u.apellidoMaterno?.equals(apellidoMaterno, ignoreCase = true) == true
            coincideNombre && coincideApPat && coincideApMat
        }
    }

    // ==========================================
    // BÚSQUEDA PRINCIPAL (flujo interno de sincronización)
    // ==========================================
    suspend fun buscarPacienteEnAPI(paciente: Paciente): ResultadoBusqueda? {
        return try {
            if (paciente.telefono.isNotEmpty()) {
                Log.d(TAG, "🔍 Buscando por celular: ${paciente.telefono}")
                val r = buscarPorCelular(paciente.telefono)
                val usuario = filtrarPorNombre(
                    usuarios = r?.usuarios,
                    nombre = paciente.nombre,
                    apellidoPaterno = paciente.apellido_paterno,
                    apellidoMaterno = paciente.apellido_materno
                )
                val id = usuario?.idUsuarioWeb?.toIntOrNull()
                if (id != null && usuario != null) return ResultadoBusqueda(id, usuario)
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
    // BÚSQUEDA POR CELULAR (devuelve MÚLTIPLES beneficiarios)
    // ==========================================
    suspend fun buscarPorCelular(telefono: String): ResultadoMultiple? {
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
                val usuarios = body?.usuarios

                if (usuarios.isNullOrEmpty()) {
                    Log.w(TAG, "⚠️ No se encontraron usuarios para el celular $telLimpio")
                    null
                } else {
                    Log.d(TAG, "✅ ${usuarios.size} beneficiario(s) encontrado(s) para celular $telLimpio")
                    ResultadoMultiple(usuarios)
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
    // BÚSQUEDA POR CURP (un solo resultado)
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
        val usuario = filtrarPorNombre(
            usuarios = resultado.usuarios,
            nombre = nombre,
            apellidoPaterno = apellidoPaterno,
            apellidoMaterno = apellidoMaterno
        )
        val id = usuario?.idUsuarioWeb?.toIntOrNull()
        return if (id != null && usuario != null) ResultadoBusqueda(id, usuario) else null
    }

    // ==========================================
    // REGISTRAR MEDICIÓN
    // ==========================================
    suspend fun enviarMediciones(mediciones: MedicionesRequest): EnvioMedicionResult {
        val startTime = System.currentTimeMillis()
        return try {
            Log.d(TAG, "📤 [API REST] Iniciando envío de resultados de medición al servidor...")
            Log.d(TAG, "   id_UsuarioWeb: ${mediciones.idUsuarioWeb}")
            Log.d(TAG, "   id_VendingMachine: ${mediciones.idVendingMachine}")
            Log.d(TAG, "   recordID: ${mediciones.recordID}")
            Log.d(TAG, "   device_id: ${mediciones.deviceId}")
            Log.d(TAG, "   device_model: ${mediciones.deviceModel}")
            Log.d(TAG, "   Fecha Registro: ${mediciones.fechaRegistro}")

            // Desglose del payload
            val ecgSizeKb = (mediciones.ecgImg?.length ?: 0) / 1024
            val fotoSizeKb = (mediciones.foto?.length ?: 0) / 1024
            val jsonString = com.google.gson.Gson().toJson(mediciones)
            val jsonSizeKb = jsonString.length / 1024
            val jsonSizeMb = jsonSizeKb / 1024.0

            Log.d(TAG, "📦 [DIAGNÓSTICO PAYLOAD] Tamaño Total JSON: ~%.2f MB (%d KB) [ECG: %d KB, Foto: %d KB]".format(jsonSizeMb, jsonSizeKb, ecgSizeKb, fotoSizeKb))

            // ✅ Log de diagnóstico del JSON final (sin base64 pesados) para validar todos los campos de antropometría, composición, etc.
            val medicionesParaLog = mediciones.copy(
                ecgImg = if (mediciones.ecgImg != null) "[REDACTADO_${mediciones.ecgImg.length}_CHARS]" else null,
                foto = if (mediciones.foto != null) "[REDACTADO_FOTO]" else null
            )
            val jsonLog = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(medicionesParaLog)
            Log.d(TAG, "📋 [DIAGNÓSTICO PAYLOAD JSON] Campos enviados a la API:\n$jsonLog")

            if (jsonSizeKb > 5 * 1024) {
                Log.e(TAG, "❌ [DIAGNÓSTICO APP] JSON demasiado grande (>5 MB), abortando envío. Posible exceso de datos o imágenes no optimizadas.")
                return EnvioMedicionResult(success = false, error = "JSON demasiado grande (>5 MB)")
            }

            val connStatus = ConnectivityStatusProvider.get(context)
            val callStartTime = System.currentTimeMillis()
            val response = RetrofitClient.apiService.registrarMedicion(mediciones)
            val callDuration = System.currentTimeMillis() - callStartTime

            // ✅ Cálculo real de velocidad de carga (throughput) de internet durante la transmisión del payload
            val payloadBytes = jsonString.length.toLong()
            val uploadSpeedMbps = if (callDuration > 0) {
                (payloadBytes * 8.0) / (callDuration / 1000.0) / 1_000_000.0
            } else 0.0
            val uploadSpeedKbps = uploadSpeedMbps * 1000.0

            val latenciaServer = NetworkSpeedHelper.medirLatenciaSocket("www.sybiml.com", 443, 2000)

            Log.d(TAG, "📊 [DIAGNÓSTICO RED POST-ENVÍO API]")
            Log.d(TAG, "   • Datos transmitidos: ~%.2f MB (%d bytes)".format(payloadBytes / 1024.0 / 1024.0, payloadBytes))
            Log.d(TAG, "   • Tiempo de respuesta HTTP POST: %d ms (%.2f s)".format(callDuration, callDuration / 1000.0))
            Log.d(TAG, "   • Velocidad real de carga (Upload Speed): %.2f Mbps (%.1f kbps)".format(uploadSpeedMbps, uploadSpeedKbps))
            Log.d(TAG, "   • Latencia Socket Servidor (www.sybiml.com:443): ${if (latenciaServer >= 0) "$latenciaServer ms" else "Sin respuesta"}")

            when {
                !connStatus.internetAvailable || latenciaServer < 0 -> Log.e(TAG, "❌ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ CONEXIÓN PERDIDA O SERVIDOR INALCANZABLE.")
                uploadSpeedMbps < 0.5 -> Log.e(TAG, "❌ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ VELOCIDAD DE CARGA MUY LENTA (< 0.5 Mbps). La lentitud es por la red del quiosco.")
                uploadSpeedMbps < 2.0 -> Log.w(TAG, "⚠️ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ VELOCIDAD DE CARGA LENTA (0.5 - 2.0 Mbps).")
                else -> Log.d(TAG, "✅ [DIAGNÓSTICO RED POST-ENVÍO] 🚀 Velocidad real de carga adecuada (> 2.0 Mbps).")
            }

            Log.d(TAG, "⏱️ [DIAGNÓSTICO API] Tiempo de respuesta HTTP (latencia/duración): ${callDuration} ms")

            if (response.isSuccessful) {
                val body = response.body()
                Log.d(TAG, "✅ [API REST EXITO] Medición registrada correctamente. id=${body?.id}, Código HTTP: ${response.code()}, Duración: ${callDuration}ms")
                if (callDuration > 3000) {
                    Log.w(TAG, "⚠️ [DIAGNÓSTICO ADVERTENCIA] El envío tardó mucho (${callDuration} ms). Posible lentitud en la red del quiosco o congestión en la API.")
                }
                EnvioMedicionResult(success = true, remoteId = body?.id ?: 0)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "❌ [API REST ERROR] HTTP ${response.code()} en registrarMedicion. Duración: ${callDuration}ms. ErrorBody: $errorBody")
                Log.e(TAG, "🔍 [DIAGNÓSTICO] Error proveniente de la API (Servidor remoto) o datos incompletos/rechazados por el backend.")
                EnvioMedicionResult(success = false, error = errorBody ?: "HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            val totalDuration = System.currentTimeMillis() - startTime
            Log.e(TAG, "🔥 [API REST EXCEPCIÓN] Error enviando medición tras ${totalDuration}ms: ${e.javaClass.simpleName}: ${e.message}", e)
            when (e) {
                is java.net.SocketTimeoutException -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED] Timeout de red (Conexión/Lectura/Escritura superada). Internet muy lento o API no respondió a tiempo.")
                is java.net.UnknownHostException -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED] Error de DNS / Sin internet. Imposible resolver dominio del servidor.")
                else -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED/APP] Excepción de red o procesamiento durante el envío.")
            }
            EnvioMedicionResult(success = false, error = e.message ?: e.javaClass.simpleName)
        }
    }
}