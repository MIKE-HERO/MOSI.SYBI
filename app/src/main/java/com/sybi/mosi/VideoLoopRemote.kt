package com.sybi.mosi

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Control remoto de VideoLoop (pantalla secundaria) por la red local.
 * Todas las órdenes llevan la clave que muestra VideoLoop en su pantalla (cabecera X-VideoLoop-Key).
 *
 * Emparejamiento automático: si VideoLoop no responde en la IP guardada (reinicio del módem, cambio de IP…),
 * se busca en la red el VideoLoop que tiene la misma clave, se guarda su nueva IP y se repite la orden.
 */
object VideoLoopRemote {

    private const val TAG = "VideoLoopRemote"

    // Claves de SharedPreferences
    const val PREF_VIDEOLOOP_IP = "videoloop_ip"       // compatibilidad con versión anterior
    const val PREF_VIDEOLOOP_HOST = "videoloop_host"   // nueva clave: solo la IP
    const val PREF_VIDEOLOOP_PORT = "videoloop_port"   // nueva clave: solo el puerto
    const val PREF_VIDEOLOOP_KEY = "videoloop_key"     // clave que muestra VideoLoop en su pantalla
    const val PREF_VIDEOLOOP_MEASUREMENT_MUTE = "videoloop_measurement_mute" // silenciar durante la medición

    private const val DEFAULT_HOST = "192.168.101.67"
    private const val DEFAULT_PORT = 8080
    /** Puerto UDP fijo en el que VideoLoop responde a las búsquedas. */
    private const val DISCOVERY_PORT = 8080
    private const val DISCOVERY_TIMEOUT_MS = 2000L
    private const val RETRY_DELAY_MS = 500L
    private const val TIMEOUT_MS = 3000
    private const val UPLOAD_READ_TIMEOUT_MS = 60_000

    data class VideoInfo(val name: String, val size: Long)
    data class Status(val muted: Boolean, val volume: Int, val videos: List<VideoInfo>)

    /**
     * Resultado de una orden: [status] con el estado de VideoLoop si fue bien, o [error] con el motivo.
     * [networkError] = no hubo respuesta (VideoLoop apagado, IP cambiada, sin red…).
     */
    data class Result(
        val ok: Boolean,
        val status: Status? = null,
        val error: String? = null,
        val networkError: Boolean = false,
    )

    // ==========================================
    // CONFIGURACIÓN GUARDADA
    // ==========================================

    /**
     * Devuelve el host (IP) guardado. Si solo existe la clave antigua "videoloop_ip"
     * con formato "ip:port", la migra automáticamente.
     */
    fun getSavedHost(context: Context): String {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val host = prefs.getString(PREF_VIDEOLOOP_HOST, null)
        if (!host.isNullOrBlank()) return host

        // Migración desde la clave antigua
        val legacy = prefs.getString(PREF_VIDEOLOOP_IP, null)
        if (!legacy.isNullOrBlank()) {
            val parts = legacy.split(":")
            val legacyHost = parts.getOrNull(0) ?: DEFAULT_HOST
            val legacyPort = parts.getOrNull(1)?.toIntOrNull() ?: DEFAULT_PORT
            prefs.edit()
                .putString(PREF_VIDEOLOOP_HOST, legacyHost)
                .putInt(PREF_VIDEOLOOP_PORT, legacyPort)
                .apply()
            return legacyHost
        }
        return DEFAULT_HOST
    }

    fun getSavedPort(context: Context): Int {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        return prefs.getInt(PREF_VIDEOLOOP_PORT, DEFAULT_PORT)
    }

    /** Devuelve "host:port" formateado para mostrar en el campo de texto. */
    fun getSavedHostPort(context: Context): String {
        return "${getSavedHost(context)}:${getSavedPort(context)}"
    }

    /**
     * Guarda host y puerto desde un string "ip:puerto" o "ip".
     * Si no se especifica puerto, usa el default.
     */
    fun saveHostPort(context: Context, value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        val parts = trimmed.split(":")
        val host = parts.getOrNull(0)?.trim().orEmpty()
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        if (host.isBlank()) return
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_VIDEOLOOP_HOST, host)
            .putInt(PREF_VIDEOLOOP_PORT, port)
            .apply()
        Log.d(TAG, "💾 Guardado VideoLoop: $host:$port")
    }

    fun getSavedKey(context: Context): String {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_VIDEOLOOP_KEY, "") ?: ""
    }

    /** Si está desactivado, la pantalla de medición no envía órdenes a VideoLoop. */
    fun isMeasurementMuteEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_VIDEOLOOP_MEASUREMENT_MUTE, true)
    }

    fun setMeasurementMuteEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_VIDEOLOOP_MEASUREMENT_MUTE, enabled)
            .apply()
    }

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_VIDEOLOOP_KEY, key.trim())
            .apply()
    }

    // ==========================================
    // ÓRDENES
    // ==========================================

    /**
     * Silencia / reactiva en una IP y puerto concretos, sin buscar en la red (para probar lo que se escribió).
     * Si falla, reintenta una vez.
     */
    suspend fun setMuted(
        host: String,
        muted: Boolean,
        port: Int,
        key: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = "{\"muted\":$muted}"
        if (send("POST", host, port, "/audio", body, key).ok) return@withContext true
        delay(RETRY_DELAY_MS)
        send("POST", host, port, "/audio", body, key).ok
    }

    /** Sobrecarga de conveniencia usando la configuración guardada (con emparejamiento automático). */
    suspend fun setMuted(context: Context, muted: Boolean): Boolean = sendMuted(context, muted).ok

    /** Silencia (true) o reactiva (false) el audio de VideoLoop, devolviendo el estado o el error. */
    suspend fun sendMuted(context: Context, muted: Boolean): Result = withAutoDiscovery(context) { host, port ->
        send("POST", host, port, "/audio", "{\"muted\":$muted}", getSavedKey(context))
    }

    /** Volumen del reproductor de VideoLoop, 0–100 (independiente del silencio). */
    suspend fun setVolume(context: Context, volume: Int): Result = withAutoDiscovery(context) { host, port ->
        send("POST", host, port, "/volume", "{\"volume\":${volume.coerceIn(0, 100)}}", getSavedKey(context))
    }

    /** Estado actual de VideoLoop: silencio, volumen y lista de videos. */
    suspend fun getStatus(context: Context): Result = withAutoDiscovery(context) { host, port ->
        send("GET", host, port, "/status", null, getSavedKey(context))
    }

    /**
     * Envía un video a VideoLoop.
     * @param replace true = queda solo este video; false = se agrega a la lista de reproducción.
     * @param onProgress porcentaje enviado (0–100), llamado en el hilo principal.
     */
    suspend fun uploadVideo(
        context: Context,
        uri: Uri,
        replace: Boolean,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        var tempCopy: File? = null
        try {
            var (name, size) = describe(context, uri)
            if (size <= 0) {
                // El proveedor no informa el tamaño: copiar a caché para saberlo antes de enviar
                tempCopy = File.createTempFile("videoloop_", ".tmp", context.cacheDir)
                context.contentResolver.openInputStream(uri)!!.use { input -> tempCopy.outputStream().use { input.copyTo(it) } }
                size = tempCopy.length()
            }
            val mode = if (replace) "replace" else "add"
            val open: () -> InputStream = { tempCopy?.inputStream() ?: context.contentResolver.openInputStream(uri)!! }
            withAutoDiscovery(context) { host, port ->
                val url = "http://$host:$port/videos?name=${URLEncoder.encode(name, "UTF-8")}&mode=$mode"
                Log.d(TAG, "📤 Enviando video $name ($size bytes, $mode) a $url")
                upload(url, size, open, getSavedKey(context), onProgress)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al preparar el video -> ${e.message}")
            Result(false, error = e.message ?: e.javaClass.simpleName)
        } finally {
            tempCopy?.delete()
        }
    }

    // ==========================================
    // EMPAREJAMIENTO AUTOMÁTICO
    // ==========================================

    /**
     * Busca en la red local el VideoLoop que tiene la clave [key] y devuelve "ip:puerto", o null si no responde.
     * No guarda nada; ver [withAutoDiscovery].
     */
    suspend fun discover(context: Context, key: String = getSavedKey(context)): String? = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext null
        val request = "VIDEOLOOP_DISCOVER ${fingerprint(key)}".toByteArray()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val targets = broadcastAddresses()
                val buffer = ByteArray(128)
                val deadline = SystemClock.elapsedRealtime() + DISCOVERY_TIMEOUT_MS
                while (SystemClock.elapsedRealtime() < deadline) {
                    for (target in targets) {
                        try {
                            socket.send(DatagramPacket(request, request.size, target, DISCOVERY_PORT))
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo buscar en ${target.hostAddress}: ${e.message}")
                        }
                    }
                    try {
                        val reply = DatagramPacket(buffer, buffer.size)
                        socket.receive(reply)
                        val text = String(reply.data, 0, reply.length, Charsets.UTF_8).trim()
                        if (text.startsWith("VIDEOLOOP_HERE")) {
                            val port = text.substringAfter(' ', "").trim().toIntOrNull() ?: DEFAULT_PORT
                            val found = "${reply.address.hostAddress}:$port"
                            Log.d(TAG, "🔎 VideoLoop encontrado en $found")
                            return@withContext found
                        }
                    } catch (_: SocketTimeoutException) {
                        // sin respuesta todavía: volver a preguntar
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error buscando VideoLoop -> ${e.message}")
        }
        Log.w(TAG, "🔎 No se encontró ningún VideoLoop con esa clave en la red")
        null
    }

    /**
     * Ejecuta [action] con la IP y puerto guardados. Si VideoLoop no responde, lo busca en la red:
     * si aparece en otra dirección la guarda y repite la orden allí; si no, reintenta una vez en la misma.
     */
    private suspend fun withAutoDiscovery(
        context: Context,
        action: suspend (host: String, port: Int) -> Result,
    ): Result = withContext(Dispatchers.IO) {
        val savedHost = getSavedHost(context)
        val savedPort = getSavedPort(context)
        val first = action(savedHost, savedPort)
        if (first.ok || !first.networkError) return@withContext first

        val found = discover(context)
        if (found != null && found != "$savedHost:$savedPort") {
            Log.i(TAG, "🔁 VideoLoop cambió de dirección: $savedHost:$savedPort -> $found (guardada)")
            saveHostPort(context, found)
            action(getSavedHost(context), getSavedPort(context))
        } else {
            delay(RETRY_DELAY_MS)
            action(savedHost, savedPort)
        }
    }

    // ==========================================
    // HTTP
    // ==========================================

    private fun send(method: String, host: String, port: Int, path: String, body: String?, key: String): Result {
        val urlString = "http://$host:$port$path"
        Log.d(TAG, "📡 $method $urlString ${body ?: ""}")
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestProperty("X-VideoLoop-Key", key)
            conn.setRequestProperty("Connection", "close") // no reutilizar conexiones viejas
            if (body != null) {
                val bytes = body.toByteArray()
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size) // Content-Length exacto, sin "chunked"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(bytes) }
            }
            readResult(conn).also { Log.d(TAG, "✅ Respuesta de VideoLoop ($host:$port): $it") }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en $method $urlString -> ${e.message}")
            Result(false, error = e.message ?: e.javaClass.simpleName, networkError = true)
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun upload(
        url: String,
        size: Long,
        open: () -> InputStream,
        key: String,
        onProgress: (Int) -> Unit,
    ): Result {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "PUT"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = UPLOAD_READ_TIMEOUT_MS
            conn.useCaches = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(size)
            conn.setRequestProperty("X-VideoLoop-Key", key)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Connection", "close")
            var lastPercent = -1
            open().use { input ->
                conn.outputStream.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        sent += n
                        val percent = (sent * 100 / size).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                }
            }
            readResult(conn).also { Log.d(TAG, "✅ Video enviado: $it") }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al enviar video a $url -> ${e.message}")
            Result(false, error = e.message ?: e.javaClass.simpleName, networkError = true)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResult(conn: HttpURLConnection): Result {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        if (code == 200) return Result(true, status = parseStatus(text))
        val detail = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
        val message = when (code) {
            401 -> "Clave incorrecta (revise la clave de VideoLoop en la configuración)"
            else -> "Error $code${if (detail.isNotEmpty()) ": $detail" else ""}"
        }
        return Result(false, error = message)
    }

    private fun parseStatus(json: String): Status? = runCatching {
        val o = JSONObject(json)
        val arr = o.optJSONArray("videos")
        val videos = (0 until (arr?.length() ?: 0)).map {
            val v = arr!!.getJSONObject(it)
            VideoInfo(v.getString("name"), v.optLong("size"))
        }
        Status(o.optBoolean("muted"), o.optInt("volume", 100), videos)
    }.getOrNull()

    /** Huella de la clave usada en la búsqueda (la clave no viaja en claro). Debe coincidir con VideoLoop. */
    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)

    /** 255.255.255.255 y la dirección de difusión de cada red (WiFi / Ethernet) a la que está conectado el equipo. */
    private fun broadcastAddresses(): List<InetAddress> {
        val perInterface = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
        } catch (e: Exception) {
            emptyList()
        }
        return (perInterface + InetAddress.getByName("255.255.255.255")).distinct()
    }

    /** Nombre (con extensión de video) y tamaño en bytes (-1 si el proveedor no lo sabe). */
    private fun describe(context: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                    size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: -1L
                }
            }
        if (uri.scheme == "file") {
            val f = File(uri.path!!)
            name = name ?: f.name
            if (size <= 0) size = f.length()
        }
        var finalName = (name ?: "video_${System.currentTimeMillis()}").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!finalName.contains('.')) {
            val ext = context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "mp4"
            finalName += ".$ext"
        }
        return finalName to size
    }
}
