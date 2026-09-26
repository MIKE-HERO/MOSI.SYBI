package com.sybi.mosi.telemedicina

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TelemedicinaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Cliente de los servicios PHP de telemedicina de sybiml.com y de Firestore.
 *
 * Replica las llamadas que hace la página web (telemedicina/js/videollamadas/services.js)
 * para que el médico reciba exactamente los mismos datos que con el flujo web.
 * Los ids se devuelven como JsonElement para reenviarlos con el mismo tipo (número o texto)
 * que entrega el servidor.
 *
 * @param telemedicinaUrl URL de la página de telemedicina (Ajustes → Telemedicina),
 *        p. ej. https://www.sybiml.com/telemedicina/. Las APIs cuelgan de su origen.
 */
class TelemedicinaApi(telemedicinaUrl: String) {

    companion object {
        private const val TAG = "TelemedicinaApi"
        private const val DEFAULT_URL = "https://www.sybiml.com/telemedicina/"

        // jQuery envía JSON.stringify(...) con este content-type cuando no se indica otro
        private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
        private val JSON = "application/json".toMediaType()
    }

    sealed class ApiKeyResultado {
        data class ServidorLleno(val mensaje: String) : ApiKeyResultado()
        data class Asignada(val idApikeyMedico: JsonElement, val apikey: String) : ApiKeyResultado()
    }

    data class Medico(val datos: JsonObject) {
        val id: JsonElement get() = datos.get("id") ?: JsonNull.INSTANCE
        val idSucursal: JsonElement get() = datos.get("id_Sucursal") ?: JsonNull.INSTANCE
        val nombre: String get() = datos.get("nombre").textoONulo().orEmpty()
    }

    data class Notificacion(val idNotificacion: JsonElement, val stSucursal: JsonElement)

    private val paginaUrl: HttpUrl = normalizarUrl(telemedicinaUrl)
    private val origen: HttpUrl = paginaUrl.newBuilder().encodedPath("/").query(null).build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // ==========================================
    // API KEY DE apiRTC
    // ==========================================
    suspend fun obtenerApikey(idUsuarioWeb: Int, idCabina: String): ApiKeyResultado {
        val url = origen.newBuilder()
            .addPathSegments("system/ML/obtenerApikeyMedico/API/index.php")
            .addQueryParameter("usuario", idUsuarioWeb.toString())
            // La web manda su query string completa (location.search)
            .addQueryParameter("queryString", "?id_usuario=$idUsuarioWeb&idMachine=$idCabina")
            .build()
        val resp = get(url)

        val mensaje = resp.get("mensaje").textoONulo()
        if (!mensaje.isNullOrBlank()) return ApiKeyResultado.ServidorLleno(mensaje)

        val apikey = resp.get("apikey").textoONulo()
            ?: throw TelemedicinaException("El servidor no asignó una clave de videollamada")
        return ApiKeyResultado.Asignada(resp.get("id_apikeyMedico") ?: JsonNull.INSTANCE, apikey)
    }

    /** Libera la clave de apiRTC y registra los minutos consumidos. */
    suspend fun liberarApikey(idApikeyMedico: JsonElement, minutosOcupados: Int) {
        val body = JsonObject().apply {
            add("id_apikeyMedico", idApikeyMedico)
            addProperty("i_minutosOcupados", minutosOcupados)
        }
        post(origen.resolve("system/ML/obtenerApikeyMedico/API/index.php")!!, body, JSON)
    }

    // ==========================================
    // MÉDICOS
    // ==========================================
    /** Devuelve el médico disponible para la cabina o null si no hay ninguno. */
    suspend fun obtenerMedicoDisponible(idCabina: String): Medico? {
        val url = origen.newBuilder()
            .addPathSegments("system/ML/Notificaciones/API/medicos.php")
            .addQueryParameter("cabinamedica", "true")
            .addQueryParameter("id_cabinaMedica", idCabina)
            .build()
        val resp = get(url)
        if (resp.get("status").textoONulo() != "200") return null
        val primero = (resp.get("data") as? JsonArray)?.firstOrNull() as? JsonObject
        return primero?.let { Medico(it) }
    }

    /** Nombre del médico que tomó la llamada identificada por [codigo]. */
    suspend fun obtenerNombreMedicoActual(idUsuarioWeb: Int, codigo: String): String? {
        val url = origen.newBuilder()
            .addPathSegments("system/ml/Notificaciones/API/medicoActual.php")
            .addQueryParameter("id_usuarioWeb", idUsuarioWeb.toString())
            .addQueryParameter("st_mensaje", codigo)
            .build()
        val primero = (get(url).get("data") as? JsonArray)?.firstOrNull() as? JsonObject
        return primero?.get("nombre").textoONulo()
    }

    // ==========================================
    // LISTA DE ESPERA
    // ==========================================
    /** Registra al paciente en la lista de espera. Devuelve el id de la cola. */
    suspend fun entrarListaEspera(idUsuarioWeb: Int, idCabina: String, idApikeyMedico: JsonElement): JsonElement {
        val body = JsonObject().apply {
            addProperty("id_usuarioWeb", idUsuarioWeb.toString())
            addProperty("id_cabinaMedica", idCabina)
            add("id_apikeyMedico", idApikeyMedico)
        }
        return post(logTelemedicinaUrl(), body, FORM).get("id") ?: JsonNull.INSTANCE
    }

    suspend fun salirListaEspera(idCola: JsonElement) {
        val body = JsonObject().apply { add("id_cola", idCola) }
        post(logTelemedicinaUrl(), body, FORM)
    }

    private fun logTelemedicinaUrl() = origen.resolve("system/ML/Notificaciones/API/logTelemedicina.php")!!

    // ==========================================
    // NOTIFICACIÓN AL MÉDICO
    // ==========================================
    suspend fun registrarNotificacion(campos: JsonObject): Notificacion {
        val resp = post(origen.resolve("system/ML/Notificaciones/API/notificacion.php")!!, campos, FORM)
        val idNotificacion = resp.get("id_notificacion")
            ?: throw TelemedicinaException("El servidor no devolvió el id de la notificación")
        return Notificacion(idNotificacion, resp.get("st_sucursal") ?: JsonNull.INSTANCE)
    }

    suspend fun cancelarNotificacion(idNotificacion: JsonElement) {
        val body = JsonObject().apply { add("id_notificacion", idNotificacion) }
        post(origen.resolve("system/ML/Notificaciones/API/cancelarNotificacion.php")!!, body, FORM)
    }

    /**
     * Agrega el documento en la colección `notificacionesMedicos` de Firestore, que es lo que
     * dispara el aviso en el portal del médico. Usa la configuración web de Firebase que
     * publica el servidor (utils/util.php), igual que la página, vía la API REST de Firestore.
     */
    suspend fun notificarMedicoFirestore(campos: JsonObject) {
        val config = get(paginaUrl.resolve("utils/util.php")!!)
        val projectId = config.get("projectId").textoONulo()
            ?: throw TelemedicinaException("Configuración de Firebase sin projectId")
        val apiKey = config.get("apiKey").textoONulo()
            ?: throw TelemedicinaException("Configuración de Firebase sin apiKey")

        val raiz = "projects/$projectId/databases/(default)/documents"
        val documento = JsonObject().apply {
            addProperty("name", "$raiz/notificacionesMedicos/${generarIdDocumento()}")
            add("fields", JsonObject().apply {
                for ((clave, valor) in campos.entrySet()) add(clave, aValorFirestore(valor))
            })
        }
        val escritura = JsonObject().apply {
            add("update", documento)
            add("currentDocument", JsonObject().apply { addProperty("exists", false) })
            // Equivalente a firebase.firestore.FieldValue.serverTimestamp()
            add("updateTransforms", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("fieldPath", "timestamp")
                    addProperty("setToServerValue", "REQUEST_TIME")
                })
            })
        }
        val body = JsonObject().apply { add("writes", JsonArray().apply { add(escritura) }) }

        val url = "https://firestore.googleapis.com/v1/$raiz:commit".toHttpUrl()
            .newBuilder().addQueryParameter("key", apiKey).build()
        post(url, body, JSON)
    }

    // ==========================================
    // HTTP
    // ==========================================
    private suspend fun get(url: HttpUrl): JsonObject =
        ejecutar(Request.Builder().url(url).get().build())

    private suspend fun post(url: HttpUrl, body: JsonObject, tipo: okhttp3.MediaType): JsonObject =
        ejecutar(Request.Builder().url(url).post(body.toString().toRequestBody(tipo)).build())

    private suspend fun ejecutar(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val ruta = request.url.encodedPath
        try {
            client.newCall(request).execute().use { resp ->
                val texto = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e(TAG, "❌ HTTP ${resp.code} en $ruta: ${texto.take(300)}")
                    throw TelemedicinaException("El servidor respondió ${resp.code}")
                }
                // Gson 2.8.5 (vía converter-gson) aún no tiene JsonParser.parseString
                @Suppress("DEPRECATION")
                val json = runCatching { JsonParser().parse(texto) }.getOrNull()
                json as? JsonObject ?: JsonObject().also {
                    if (texto.isNotBlank()) Log.w(TAG, "⚠️ Respuesta no JSON en $ruta: ${texto.take(300)}")
                }
            }
        } catch (e: TelemedicinaException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "🔥 Error de red en $ruta: ${e.message}", e)
            throw TelemedicinaException("No hay conexión con el servidor de telemedicina", e)
        }
    }

    private fun normalizarUrl(url: String): HttpUrl {
        val limpia = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        return limpia.toHttpUrlOrNull() ?: run {
            Log.w(TAG, "⚠️ URL de telemedicina inválida '$url', usando $DEFAULT_URL")
            DEFAULT_URL.toHttpUrl()
        }
    }

    private fun generarIdDocumento(): String {
        val caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..20).map { caracteres.random() }.joinToString("")
    }

    /** Convierte un valor JSON al formato tipado de la API REST de Firestore. */
    private fun aValorFirestore(valor: JsonElement): JsonObject = JsonObject().apply {
        val primitivo = valor as? JsonPrimitive
        when {
            primitivo == null -> add("nullValue", JsonNull.INSTANCE)
            primitivo.isBoolean -> addProperty("booleanValue", primitivo.asBoolean)
            primitivo.isNumber -> {
                val texto = primitivo.asNumber.toString()
                if (texto.any { it == '.' || it == 'e' || it == 'E' }) addProperty("doubleValue", primitivo.asDouble)
                else addProperty("integerValue", texto)
            }
            else -> addProperty("stringValue", primitivo.asString)
        }
    }
}

private fun JsonElement?.textoONulo(): String? =
    (this as? JsonPrimitive)?.asString
