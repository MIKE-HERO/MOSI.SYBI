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

        // Reintentos del envío de mediciones. El log del quiosco registró al
        // menos una caída puntual del servidor ("Sin respuesta") con el DNS
        // respondiendo bien; retryOnConnectionFailure de OkHttp no cubre un
        // POST cuyo cuerpo ya se empezó a enviar, así que el reintento se hace
        // aquí. Ver esSeguroReintentar() para qué fallos se reintentan y por
        // qué los timeouts de lectura quedan fuera.
        private const val MAX_INTENTOS_ENVIO = 3
        private const val BACKOFF_BASE_MS = 800L
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

        Log.d(TAG, "📤 [API REST] Iniciando envío de resultados de medición al servidor...")
        Log.d(TAG, "   id_UsuarioWeb: ${mediciones.idUsuarioWeb}")
        Log.d(TAG, "   id_VendingMachine: ${mediciones.idVendingMachine}")
        Log.d(TAG, "   recordID: ${mediciones.recordID}")
        Log.d(TAG, "   device_id: ${mediciones.deviceId}")
        Log.d(TAG, "   device_model: ${mediciones.deviceModel}")
        Log.d(TAG, "   Fecha Registro: ${mediciones.fechaRegistro}")

        // ════════════════════════════════════════════════════════════════════
        // TAMAÑO DEL PAYLOAD, SIN SERIALIZARLO COMPLETO
        // ════════════════════════════════════════════════════════════════════
        // Antes se hacía Gson().toJson(mediciones) únicamente para conocer el
        // tamaño, y acto seguido una SEGUNDA serialización con
        // setPrettyPrinting() para volcarla a logcat. Son dos pasadas completas
        // de Gson sobre un payload de megabytes (el ECG viaja en PNG + Base64),
        // en un RK3288 de 2014, antes de que salga el primer byte, más dos
        // copias del String en memoria y la presión de GC que eso implica.
        //
        // Ahora se serializa UNA sola vez la copia con las imágenes redactadas
        // (unos pocos KB) y el tamaño total se obtiene sumando la longitud real
        // de los Base64. El margen de error son las comillas y los escapes de
        // dos campos: irrelevante para un diagnóstico de tamaño.
        val prepStart = System.currentTimeMillis()
        val ecgChars = mediciones.ecgImg?.length ?: 0
        val fotoChars = mediciones.foto?.length ?: 0
        val jsonCampos: String
        val payloadBytes: Long
        try {
            val medicionesParaLog = mediciones.copy(
                ecgImg = if (mediciones.ecgImg != null) "[REDACTADO_${ecgChars}_CHARS]" else null,
                foto = if (mediciones.foto != null) "[REDACTADO_FOTO]" else null
            )
            jsonCampos = com.google.gson.Gson().toJson(medicionesParaLog)
            payloadBytes = jsonCampos.length.toLong() + ecgChars + fotoChars
        } catch (e: Exception) {
            Log.e(TAG, "🔥 [API REST] Error preparando el payload: ${e.javaClass.simpleName}: ${e.message}", e)
            return EnvioMedicionResult(success = false, error = e.message ?: e.javaClass.simpleName)
        }
        val prepDuration = System.currentTimeMillis() - prepStart
        val jsonSizeKb = (payloadBytes / 1024).toInt()
        val jsonSizeMb = payloadBytes / 1024.0 / 1024.0

        Log.d(TAG, "📦 [DIAGNÓSTICO PAYLOAD] Tamaño Total JSON: ~%.2f MB (%d KB) [ECG: %d KB, Foto: %d KB]".format(jsonSizeMb, jsonSizeKb, ecgChars / 1024, fotoChars / 1024))
        Log.d(TAG, "⏱️ [DIAGNÓSTICO PREPARACIÓN] Serialización del payload: ${prepDuration} ms")

        // ✅ Log de diagnóstico del JSON final (sin base64 pesados) para validar todos los campos de antropometría, composición, etc.
        Log.d(TAG, "📋 [DIAGNÓSTICO PAYLOAD JSON] Campos enviados a la API:\n$jsonCampos")

        if (jsonSizeKb > 5 * 1024) {
            Log.e(TAG, "❌ [DIAGNÓSTICO APP] JSON demasiado grande (>5 MB), abortando envío. Posible exceso de datos o imágenes no optimizadas.")
            return EnvioMedicionResult(success = false, error = "JSON demasiado grande (>5 MB)")
        }

        val connStatus = ConnectivityStatusProvider.get(context)

        // Abrir la conexión TCP+TLS antes del POST. Si el pool ya la tiene viva
        // esto no cuesta nada; si caducó (entre un paciente y el siguiente pasan
        // más de los 5 min del keepalive por defecto de OkHttp) el handshake se
        // paga aquí, medido y aparte, en vez de dentro del envío del payload.
        val msPrecalentado = RetrofitClient.precalentarConexion()

        var intento = 1
        while (true) {
            try {
                val callStartTime = System.currentTimeMillis()
                val response = RetrofitClient.apiService.registrarMedicion(mediciones)
                val callDuration = System.currentTimeMillis() - callStartTime

                // ✅ Cálculo real de velocidad de carga (throughput) de internet durante la transmisión del payload
                val uploadSpeedMbps = if (callDuration > 0) {
                    (payloadBytes * 8.0) / (callDuration / 1000.0) / 1_000_000.0
                } else 0.0
                val uploadSpeedKbps = uploadSpeedMbps * 1000.0

                val latenciaServer = NetworkSpeedHelper.medirLatenciaSocket("www.sybiml.com", 443, 2000)

                Log.d(TAG, "📊 [DIAGNÓSTICO RED POST-ENVÍO API]")
                Log.d(TAG, "   • Datos transmitidos: ~%.2f MB (%d bytes)".format(payloadBytes / 1024.0 / 1024.0, payloadBytes))
                Log.d(TAG, "   • Tiempo de respuesta HTTP POST: %d ms (%.2f s)".format(callDuration, callDuration / 1000.0))
                Log.d(TAG, "   • Velocidad real de carga (Upload Speed): %.2f Mbps (%.1f kbps)".format(uploadSpeedMbps, uploadSpeedKbps))
                Log.d(TAG, "   • Handshake previo (precalentamiento): ${if (msPrecalentado >= 0) "$msPrecalentado ms" else "no se pudo establecer"}")
                Log.d(TAG, "   • Preparación del payload (CPU, antes de la red): ${prepDuration} ms")
                Log.d(TAG, "   • Intento: $intento de $MAX_INTENTOS_ENVIO")
                Log.d(TAG, "   • Latencia Socket Servidor (www.sybiml.com:443): ${if (latenciaServer >= 0) "$latenciaServer ms" else "Sin respuesta"}")

                when {
                    !connStatus.internetAvailable || latenciaServer < 0 -> Log.e(TAG, "❌ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ CONEXIÓN PERDIDA O SERVIDOR INALCANZABLE.")
                    uploadSpeedMbps < 0.5 -> Log.e(TAG, "❌ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ VELOCIDAD DE CARGA MUY LENTA (< 0.5 Mbps). La lentitud es por la red del quiosco.")
                    uploadSpeedMbps < 2.0 -> Log.w(TAG, "⚠️ [DIAGNÓSTICO RED POST-ENVÍO] ⚠️ VELOCIDAD DE CARGA LENTA (0.5 - 2.0 Mbps).")
                    else -> Log.d(TAG, "✅ [DIAGNÓSTICO RED POST-ENVÍO] 🚀 Velocidad real de carga adecuada (> 2.0 Mbps).")
                }

                Log.d(TAG, "⏱️ [DIAGNÓSTICO API] Tiempo de respuesta HTTP (latencia/duración): ${callDuration} ms")
                Log.d(TAG, "⏱️ [DIAGNÓSTICO TOTAL] De inicio a respuesta: ${System.currentTimeMillis() - startTime} ms = preparación ${prepDuration} ms + handshake ${if (msPrecalentado >= 0) msPrecalentado else 0} ms + POST ${callDuration} ms")

                return if (response.isSuccessful) {
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
                if (intento < MAX_INTENTOS_ENVIO && esSeguroReintentar(e)) {
                    val espera = BACKOFF_BASE_MS * (1L shl (intento - 1))
                    Log.w(TAG, "♻️ [API REST REINTENTO] El intento $intento/$MAX_INTENTOS_ENVIO falló sin llegar al servidor (${e.javaClass.simpleName}: ${e.message}). Reintentando en ${espera} ms...")
                    kotlinx.coroutines.delay(espera)
                    intento++
                    continue
                }

                val totalDuration = System.currentTimeMillis() - startTime
                Log.e(TAG, "🔥 [API REST EXCEPCIÓN] Error enviando medición tras ${totalDuration}ms (intento $intento/$MAX_INTENTOS_ENVIO): ${e.javaClass.simpleName}: ${e.message}", e)
                when (e) {
                    is java.net.SocketTimeoutException -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED] Timeout de red (Conexión/Lectura/Escritura superada). Internet muy lento o API no respondió a tiempo.")
                    is java.net.UnknownHostException -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED] Error de DNS / Sin internet. Imposible resolver dominio del servidor.")
                    else -> Log.e(TAG, "🔍 [DIAGNÓSTICO RED/APP] Excepción de red o procesamiento durante el envío.")
                }
                if (!esSeguroReintentar(e)) {
                    Log.w(TAG, "🔍 [DIAGNÓSTICO REINTENTO] No se reintenta: no se puede descartar que el servidor haya recibido y registrado la medición.")
                }
                return EnvioMedicionResult(success = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Decide si un fallo se puede reintentar sin riesgo de duplicar el registro.
     *
     * Solo se reintenta cuando es seguro afirmar que la petición NUNCA llegó al
     * servidor: no hubo DNS, no se pudo abrir el socket, no hay ruta al host o
     * falló el handshake TLS. En todos esos casos el cuerpo no se envió.
     *
     * Deliberadamente NO se reintenta un SocketTimeoutException: un timeout de
     * lectura después de haber subido el payload puede significar que el backend
     * sí procesó y guardó la medición, y MedicionesRequest no lleva clave de
     * idempotencia (recordID va fijo en "0"), así que el reintento crearía un
     * registro duplicado del paciente. Para cubrir también ese caso haría falta
     * que la API aceptara una clave idempotente.
     */
    private fun esSeguroReintentar(e: Exception): Boolean = when (e) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is javax.net.ssl.SSLHandshakeException -> true
        else -> false
    }
}
