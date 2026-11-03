package com.sybi.mosi.integration

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.sybi.mosi.DeviceInfoActivity
import com.sybi.mosi.TelemedicineSettingsActivity
import com.sybi.mosi.admin.PermisosAdmin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MosiAvailableUpdate(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean,
    val requestId: String
)

data class MosiSucursal(val id: Int, val nombre: String) {
    override fun toString(): String = nombre
}

data class MosiCliente(val id: String, val nombre: String)

class MosiApiException(message: String, val statusCode: Int? = null) : Exception(message)

/** Cliente del canal administrativo MOSI. Nunca registra tokens ni cuerpos clínicos. */
class MosiApiClient(private val context: Context) {
    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** Administrador MOSI; es distinto del portal clínico configurado para videollamadas. */
        private const val ADMIN_BASE_URL = "https://www.sybiml.com/php8/adminVending/public/"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun activar(codigo: String, numeroSerie: String): JSONObject {
        val body = JSONObject()
            .put("codigo_activacion", codigo.trim())
            .put("numero_serie", numeroSerie.trim())
            .put("device_id", MosiSecureStore.deviceId(context))
            .put("android_id", androidId())
            .put("package_name", context.packageName)
            .put("certificado_sha256", certificateSha256())

        val response = post("/api/mosi_activar.php", body, authenticated = false)
        val data = response.optJSONObject("data")
            ?: throw MosiApiException("La respuesta de activación no contiene data")
        val token = data.optString("token")
        if (!token.startsWith("MLMOSI_")) throw MosiApiException("El servidor no entregó un token MOSI válido")

        val routes = data.optJSONObject("rutas") ?: JSONObject()
        val interval = data.optLong("intervalo_sincronizacion", 300L).coerceAtLeast(300L)
        MosiSecureStore.guardarActivacion(
            context = context,
            token = token,
            serial = numeroSerie,
            intervaloSegundos = interval,
            rutaTelemetria = routes.optString("telemetria", "/api/mosi_telemetria.php"),
            rutaConsultaActualizacion = routes.optString("actualizacion_consultar", "/api/mosi_actualizacion_consultar.php"),
            rutaEventoActualizacion = routes.optString("actualizacion_evento", "/api/mosi_actualizacion_evento.php")
        )
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE).edit()
            .putString(DeviceInfoActivity.PREF_DEVICE_SERIAL, numeroSerie.trim())
            .apply()
        return data
    }

    fun iniciarSesionInstalador(email: String, password: String): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val response = post(
            "/api/mosi_instalador_login.php",
            JSONObject().put("email", email.trim()).put("password", password)
                .put("app_version", info.versionName.orEmpty()),
            authenticated = false
        )
        val token = response.optJSONObject("data")?.optString("token").orEmpty()
        if (!token.startsWith("TECH_")) throw MosiApiException("El servidor no entregó una sesión de instalador válida")
        return token
    }

    fun verificarServidorInstalacion(): String {
        val url = resolveRoute("/api/mosi_instalador_login.php")
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(JSONObject().toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            // El endpoint responde 422 a un POST vacío: eso confirma que existe,
            // procesa JSON y está listo para recibir las credenciales reales.
            val healthy = (response.isSuccessful && json?.optBoolean("ok") == true) ||
                (response.code == 422 && json?.has("error") == true)
            if (!healthy) {
                val detail = json?.optString("error")?.takeIf { it.isNotBlank() }
                throw MosiApiException(detail ?: "Administrador no disponible (HTTP ${response.code})", response.code)
            }
            return url
        }
    }

    fun listarClientes(tokenInstalador: String): List<MosiCliente> {
        val response = post(
            "/api/mosi_instalacion_clientes.php", JSONObject(), authenticated = false,
            extraHeaders = mapOf("X-Tecnico-Token" to tokenInstalador)
        )
        val items = response.optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id_cliente").trim()
            val nombre = item.optString("nombre").trim()
            if (id.isNotBlank() && nombre.isNotBlank()) MosiCliente(id, nombre) else null
        }
    }

    fun listarSucursales(tokenInstalador: String, clienteId: String): List<MosiSucursal> {
        val response = post(
            "/api/mosi_instalacion_sucursales.php",
            JSONObject().put("id_cliente", clienteId.trim()), authenticated = false,
            extraHeaders = mapOf("X-Tecnico-Token" to tokenInstalador)
        )
        val items = response.optJSONArray("data") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optInt("id_sucursal_clinica")
            val nombre = item.optString("nombre").trim()
            if (id > 0 && nombre.isNotBlank()) MosiSucursal(id, nombre) else null
        }
    }

    fun completarInstalacion(
        tokenInstalador: String,
        numeroSerie: String,
        clienteId: String,
        clienteNombre: String,
        sucursal: MosiSucursal,
        videoLoopHost: String,
        videoLoopPort: Int,
        videoLoopKey: String,
        videoLoopEmail: String,
        printerIp: String,
        printerPort: Int
    ): JSONObject {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val config = JSONObject()
            .put("id_cliente", clienteId.trim())
            .put("cliente_nombre", clienteNombre.trim())
            .put("videoloop_host", videoLoopHost.trim())
            .put("videoloop_port", videoLoopPort)
            .put("videoloop_clave", videoLoopKey)
            .put("videoloop_email", videoLoopEmail.trim())
            .put("impresora_ip", printerIp.trim())
            .put("impresora_puerto", printerPort)
        val body = JSONObject()
            .put("numero_serie", numeroSerie.trim())
            .put("id_sucursal_clinica", sucursal.id)
            .put("device_id", MosiSecureStore.deviceId(context))
            .put("android_id", androidId())
            .put("package_name", context.packageName)
            .put("certificado_sha256", certificateSha256())
            .put("app_version", info.versionName.orEmpty())
            .put("configuracion", config)
        val response = post(
            "/api/mosi_instalacion_completar.php", body, authenticated = false,
            extraHeaders = mapOf("X-Tecnico-Token" to tokenInstalador)
        )
        val data = response.optJSONObject("data") ?: throw MosiApiException("La instalación no devolvió datos")
        guardarActivacion(data, numeroSerie)
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE).edit()
            .putString(DeviceInfoActivity.PREF_DEVICE_SERIAL, numeroSerie.trim())
            .putString(TelemedicineSettingsActivity.PREF_CLIENTE, clienteId.trim())
            .putString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, clienteNombre.trim())
            .putString(TelemedicineSettingsActivity.PREF_SUCURSAL, sucursal.id.toString())
            .putString(TelemedicineSettingsActivity.PREF_SUCURSAL_NOMBRE, sucursal.nombre)
            .putBoolean(TelemedicineSettingsActivity.PREF_TELEMEDICINE_ENABLED, true)
            .putString("printer_ip", printerIp.trim())
            .apply()
        com.sybi.mosi.VideoLoopRemote.saveHostPort(context, "${videoLoopHost.trim()}:$videoLoopPort")
        com.sybi.mosi.VideoLoopRemote.saveKey(context, videoLoopKey)
        return data
    }

    private fun guardarActivacion(data: JSONObject, numeroSerie: String) {
        val token = data.optString("token")
        if (!token.startsWith("MLMOSI_")) throw MosiApiException("El servidor no entregó un token MOSI válido")
        val routes = data.optJSONObject("rutas") ?: JSONObject()
        MosiSecureStore.guardarActivacion(
            context, token, numeroSerie, data.optLong("intervalo_sincronizacion", 300L),
            routes.optString("telemetria", "/api/mosi_telemetria.php"),
            routes.optString("actualizacion_consultar", "/api/mosi_actualizacion_consultar.php"),
            routes.optString("actualizacion_evento", "/api/mosi_actualizacion_evento.php")
        )
    }

    fun enviarTelemetria(requestId: String, telemetry: JSONObject) {
        val body = JSONObject()
            .put("request_id", requestId)
            .put("numero_serie", requireSerial())
            .put("device_id", MosiSecureStore.deviceId(context))
            .put("telemetria", telemetry)
        post(MosiSecureStore.rutaTelemetria(context), body, authenticated = true)
    }

    fun consultarActualizacion(requestId: String = UUID.randomUUID().toString()): MosiAvailableUpdate? {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
        val body = JSONObject()
            .put("request_id", requestId)
            .put("numero_serie", requireSerial())
            .put("version_code_actual", versionCode)
            .put("version_name_actual", packageInfo.versionName.orEmpty())
        val response = post(MosiSecureStore.rutaConsultaActualizacion(context), body, authenticated = true)
        val update = response.optJSONObject("data")?.optJSONObject("actualizacion") ?: return null
        val url = update.optString("apk_url")
        val sha = update.optString("sha256").lowercase(Locale.US)
        if (url.isBlank()) return null
        if (!sha.matches(Regex("[0-9a-f]{64}"))) {
            throw MosiApiException("La actualización no incluye un SHA-256 válido")
        }
        return MosiAvailableUpdate(
            id = update.optString("id_actualizacion", update.optString("id")),
            versionCode = update.optLong("version_code", 0L),
            versionName = update.optString("version_name"),
            apkUrl = url,
            sha256 = sha,
            mandatory = update.optBoolean("obligatoria", false),
            requestId = requestId
        )
    }

    fun reportarEventoActualizacion(
        event: String,
        updateId: String,
        version: String,
        requestId: String,
        detail: String? = null
    ) {
        val body = JSONObject()
            .put("request_id", requestId.ifBlank { UUID.randomUUID().toString() })
            .put("numero_serie", requireSerial())
            .put("id_actualizacion", updateId)
            .put("evento", event)
            .put("version", version)
        if (!detail.isNullOrBlank()) body.put("detalle", detail.take(500))
        post(MosiSecureStore.rutaEventoActualizacion(context), body, authenticated = true)
    }

    fun reportarEventoTelemedicina(
        event: String,
        sessionId: String,
        requestId: String = UUID.randomUUID().toString(),
        detail: String? = null
    ) {
        val allowed = setOf("inicio", "fin", "cancelacion", "error")
        require(event in allowed) { "Evento de telemedicina no válido" }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        val body = JSONObject()
            .put("request_id", requestId.ifBlank { UUID.randomUUID().toString() })
            .put("numero_serie", requireSerial())
            .put("sesion_id", sessionId.take(120))
            .put("evento", event)
            .put("version_code", versionCode)
            .put("version_name", info.versionName.orEmpty())
        if (!detail.isNullOrBlank()) body.put("detalle", detail.take(300))
        post("/api/mosi_telemedicina_evento.php", body, authenticated = true)
    }

    fun technicalTelemetry(
        pendingSync: Int,
        recentErrors: Int,
        sensors: JSONArray
    ): JSONObject {
        val network = com.sybi.mosi.network.ConnectivityStatusProvider.get(context)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        return JSONObject()
            .put("version_code", code)
            .put("version_name", info.versionName.orEmpty())
            .put("tipo_conexion", network.transport)
            .put("internet_disponible", network.internetAvailable)
            .put("wifi_conectado", network.wifiConnected)
            .put("ethernet_conectado", network.ethernetConnected)
            .put("nombre_red", network.ssid ?: JSONObject.NULL)
            .put("pendientes_sincronizacion", pendingSync)
            .put("errores_recientes", recentErrors)
            .put("ultima_sincronizacion", MosiSecureStore.ultimaSincronizacion(context) ?: JSONObject.NULL)
            .put("device_owner", PermisosAdmin.esPropietario(context))
            .put("sensores", sensors)
    }

    private fun post(
        route: String,
        body: JSONObject,
        authenticated: Boolean,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject {
        val requestBuilder = Request.Builder()
            .url(resolveRoute(route))
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
        if (authenticated) {
            val token = MosiSecureStore.token(context)
            if (token.isBlank()) throw MosiApiException("Esta cabina no está activada")
            requestBuilder.header("Authorization", "Bearer $token")
        }
        extraHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (authenticated && (response.code == 401 || response.code == 403)) {
                    MosiSecureStore.borrarActivacion(context)
                }
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw MosiApiException(error?.takeIf { it.isNotBlank() } ?: "Servidor MOSI respondió HTTP ${response.code}", response.code)
            }
            val json = runCatching { JSONObject(text) }
                .getOrElse { throw MosiApiException("El servidor MOSI devolvió JSON inválido") }
            val success = when {
                json.has("ok") -> json.optBoolean("ok")
                json.has("success") -> json.optBoolean("success")
                json.has("exito") -> json.optBoolean("exito")
                else -> true
            }
            if (!success) throw MosiApiException(json.optString("error", json.optString("mensaje", "Operación rechazada por MOSI")))
            return json
        }
    }

    private fun resolveRoute(route: String): String {
        if (route.startsWith("https://")) return route
        val base = ADMIN_BASE_URL.toHttpUrl()
        return base.resolve(route.trimStart('/'))?.toString()
            ?: throw MosiApiException("Ruta administrativa MOSI inválida: $route")
    }

    private fun requireSerial(): String = MosiSecureStore.serialActivado(context).ifBlank {
        throw MosiApiException("La activación no tiene número de serie")
    }

    private fun androidId(): String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ).orEmpty()

    private fun certificateSha256(): String {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        val signature = signatures.firstOrNull() ?: throw MosiApiException("No se pudo leer el certificado de la app")
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date())
}
