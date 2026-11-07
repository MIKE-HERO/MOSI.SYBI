package com.sybi.mosi.integration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.UUID

/** Almacén cifrado para la identidad y el token individual de la cabina. */
object MosiSecureStore {
    private const val TAG = "MosiSecureStore"
    private const val FILE_NAME = "MosiIntegrationSecure"
    private const val KEY_TOKEN = "cabina_token"
    private const val KEY_SERIAL = "activated_serial"
    private const val KEY_DEVICE_ID = "stable_device_id"
    private const val KEY_SYNC_INTERVAL = "sync_interval_seconds"
    private const val KEY_LAST_SYNC = "last_sync_iso"
    private const val KEY_ROUTE_TELEMETRY = "route_telemetry"
    private const val KEY_ROUTE_UPDATE_CHECK = "route_update_check"
    private const val KEY_ROUTE_UPDATE_EVENT = "route_update_event"
    private const val KEY_UPDATE_ID = "pending_update_id"
    private const val KEY_UPDATE_VERSION = "pending_update_version"
    private const val KEY_UPDATE_REQUEST_ID = "pending_update_request_id"
    private const val KEY_VIDEOLOOP_KEY = "videoloop_key"

    private fun prefs(context: Context): SharedPreferences {
        val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE_NAME,
            alias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Evita cualquier respaldo en SharedPreferences sin cifrar si el Keystore falla.
    private fun <T> withPrefs(context: Context, fallback: T, block: (SharedPreferences) -> T): T = try {
        block(prefs(context))
    } catch (e: Exception) {
        Log.e(TAG, "No se pudo usar el almacén cifrado: ${e.javaClass.simpleName}")
        fallback
    }

    fun token(context: Context): String = withPrefs(context, "") { it.getString(KEY_TOKEN, "").orEmpty() }
    fun serialActivado(context: Context): String = withPrefs(context, "") { it.getString(KEY_SERIAL, "").orEmpty() }
    fun estaActivado(context: Context): Boolean = token(context).startsWith("MLMOSI_")

    fun guardarActivacion(
        context: Context,
        token: String,
        serial: String,
        intervaloSegundos: Long,
        rutaTelemetria: String = "/api/mosi_telemetria.php",
        rutaConsultaActualizacion: String = "/api/mosi_actualizacion_consultar.php",
        rutaEventoActualizacion: String = "/api/mosi_actualizacion_evento.php"
    ) {
        require(token.startsWith("MLMOSI_")) { "El servidor devolvió un token MOSI inválido" }
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_SERIAL, serial.trim())
            .putLong(KEY_SYNC_INTERVAL, intervaloSegundos.coerceAtLeast(300L))
            .putString(KEY_ROUTE_TELEMETRY, rutaTelemetria)
            .putString(KEY_ROUTE_UPDATE_CHECK, rutaConsultaActualizacion)
            .putString(KEY_ROUTE_UPDATE_EVENT, rutaEventoActualizacion)
            .apply()
    }

    fun borrarActivacion(context: Context) {
        withPrefs(context, Unit) { it.edit().remove(KEY_TOKEN).remove(KEY_SERIAL).apply() }
    }

    fun guardarClaveVideoLoop(context: Context, clave: String) {
        prefs(context).edit().putString(KEY_VIDEOLOOP_KEY, clave.trim()).apply()
    }

    fun claveVideoLoop(context: Context): String =
        withPrefs(context, "") { it.getString(KEY_VIDEOLOOP_KEY, "").orEmpty() }

    fun deviceId(context: Context): String {
        val existing = withPrefs(context, "") { it.getString(KEY_DEVICE_ID, "").orEmpty() }
        if (existing.isNotBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs(context).edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun intervaloSegundos(context: Context): Long =
        withPrefs(context, 300L) { it.getLong(KEY_SYNC_INTERVAL, 300L).coerceAtLeast(300L) }

    fun rutaTelemetria(context: Context): String = withPrefs(context, "/api/mosi_telemetria.php") {
        it.getString(KEY_ROUTE_TELEMETRY, "/api/mosi_telemetria.php").orEmpty()
    }
    fun rutaConsultaActualizacion(context: Context): String = withPrefs(context, "/api/mosi_actualizacion_consultar.php") {
        it.getString(KEY_ROUTE_UPDATE_CHECK, "/api/mosi_actualizacion_consultar.php").orEmpty()
    }
    fun rutaEventoActualizacion(context: Context): String = withPrefs(context, "/api/mosi_actualizacion_evento.php") {
        it.getString(KEY_ROUTE_UPDATE_EVENT, "/api/mosi_actualizacion_evento.php").orEmpty()
    }

    fun guardarUltimaSincronizacion(context: Context, iso: String) =
        withPrefs(context, Unit) { it.edit().putString(KEY_LAST_SYNC, iso).apply() }

    fun ultimaSincronizacion(context: Context): String? =
        withPrefs(context, null) { it.getString(KEY_LAST_SYNC, null) }

    fun guardarActualizacionPendiente(context: Context, id: String, version: String, requestId: String) {
        prefs(context).edit()
            .putString(KEY_UPDATE_ID, id)
            .putString(KEY_UPDATE_VERSION, version)
            .putString(KEY_UPDATE_REQUEST_ID, requestId)
            .apply()
    }

    data class ActualizacionPendiente(val id: String, val version: String, val requestId: String)

    fun actualizacionPendiente(context: Context): ActualizacionPendiente? = withPrefs(context, null) { p ->
        val id = p.getString(KEY_UPDATE_ID, "").orEmpty()
        if (id.isBlank()) null else ActualizacionPendiente(
            id,
            p.getString(KEY_UPDATE_VERSION, "").orEmpty(),
            p.getString(KEY_UPDATE_REQUEST_ID, "").orEmpty()
        )
    }

    fun limpiarActualizacionPendiente(context: Context) = withPrefs(context, Unit) {
        it.edit().remove(KEY_UPDATE_ID).remove(KEY_UPDATE_VERSION).remove(KEY_UPDATE_REQUEST_ID).apply()
    }
}
