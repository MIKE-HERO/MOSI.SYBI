package com.sybi.mosi

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.sybi.mosi.TelemedicineSettingsActivity.Companion.PREF_CLIENTE
import com.sybi.mosi.TelemedicineSettingsActivity.Companion.PREF_CLIENTE_NOMBRE
import com.sybi.mosi.TelemedicineSettingsActivity.Companion.PREF_SUCURSAL
import com.sybi.mosi.TelemedicineSettingsActivity.Companion.PREF_SUCURSAL_NOMBRE
import com.sybi.mosi.integration.MosiSecureStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Genera un archivo con el log completo del propio proceso de la app
 * (todos los niveles: V, D, I, W, E, F), leyendo el logcat del sistema
 * filtrado por el PID actual — no requiere permisos especiales porque un
 * proceso siempre puede leer su propio log.
 *
 * Pensado para "Testeo y Calibración" → "Enviar log": el administrador puede
 * mandarlo por correo cuando algo falla en campo, sin acceso a un depurador.
 */
object ErrorLogHelper {
    private const val TAG = "ErrorLogHelper"
    private const val MAX_LINEAS = 100000
    private const val RETENCION_DIAS_MS = 7 * 24 * 60 * 60 * 1000L // 7 días en milisegundos
    private const val CARPETA_LOGS = "Logs_Mosi"

    /** Preferencias donde la instalación deja cliente/sucursal (compartidas con TelemedicineSettingsActivity). */
    private const val PREFS_INSTALACION = "DevicePrefs"

    /**
     * Genera un archivo con el log completo del propio proceso de la app
     * (todos los niveles: V, D, I, W, E, F), guardando una copia con fecha
     * en la carpeta dedicada 'Logs_Mosi' dentro del almacenamiento interno como respaldo.
     *
     * Cliente, sucursal, cabina y sus IDs se leen automáticamente de las preferencias
     * que guarda la instalación (MosiApiClient.completarInstalacion), por lo
     * que no hace falta pasarlos en cada llamada. Si quieres sobreescribirlos,
     * usa los parámetros opcionales.
     *
     * @param cliente   Sobreescribe el cliente leído de prefs (opcional)
     * @param sucursal  Sobreescribe la sucursal leída de prefs (opcional)
     * @param cabina    Sobreescribe el número de cabina (opcional). Si es null
     *                  se usa el número de serie activado, que identifica la cabina.
     */
    fun generarLogDeErrores(
        context: Context,
        tipoLog: String = "manual",
        cliente: String? = null,
        sucursal: String? = null,
        cabina: String? = null
    ): File? {
        return try {
            // 1. Limpiar automáticamente logs de respaldo con más de 7 días
            limpiarLogsAntiguos(context)

            val pid = Process.myPid()
            // -t 100000 → Drena todo el buffer del logcat desde que inició el equipo/proceso
            val proceso = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "100000", "--pid=$pid", "*:V")
                .redirectErrorStream(true)
                .start()

            val lineas = proceso.inputStream.bufferedReader().readLines()
            proceso.waitFor()

            // Filtrar líneas de sistema, ruido gráfico, IA y errores de SELinux (audit)
            val lineasUtiles = lineas.filter { linea ->
                // 1. Filtros anteriores (Sistema, memoria y SELinux)
                !linea.contains("chatty", ignoreCase = true) &&
                        !linea.contains(" expire ", ignoreCase = true) &&
                        !linea.contains(" art     :") &&
                        !linea.contains(" audit", ignoreCase = true) &&
                        !linea.contains("avc: denied", ignoreCase = true) &&

                        // 2. Ruido gráfico y de renderizado (OpenGL, Mali, Skia)
                        !linea.contains("mali_winsys") &&
                        !linea.contains("OpenGLRenderer") &&
                        !linea.contains("skia") &&
                        !linea.contains("EGLint") &&

                        // 3. Librerías de Inteligencia Artificial (Cara, TensorFlow, MediaPipe)
                        !linea.contains("tflite") &&
                        !linea.contains("TensorFlowLite") &&
                        !linea.contains("FaceEmbeddingHelper") &&
                        !linea.contains("MediaPipeFaceHelper") &&
                        !linea.contains("interp.run()") &&

                        // 4. Librerías internas de Google y Android que no aportan
                        !linea.contains("TransportRuntime") &&
                        !linea.contains("WM-") &&  // Oculta WM-WorkerWrapper, WM-Schedulers, etc.
                        !linea.contains("mali_so") &&
                        !linea.contains("AppCompatDelegate") &&
                        !linea.contains("ProfileInstaller") &&
                        !linea.contains("NetworkSecurityConfig") &&
                        !linea.contains("EngineFactory") &&

                        // 5. Falsos positivos de USB
                        !linea.contains("Ignorando desconexión de USB de otro dispositivo")
            }

            val recientes = if (lineasUtiles.size > MAX_LINEAS) lineasUtiles.takeLast(MAX_LINEAS) else lineasUtiles

            val dir = File(context.filesDir, CARPETA_LOGS).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val archivo = File(dir, "mosi_log_${tipoLog}_$timestamp.log")

            val contexto = ContextoQuiosco.resolver(context, cliente, sucursal, cabina)

            val incluirResumen = tipoLog.equals("arranque", ignoreCase = true) || tipoLog.equals("manual", ignoreCase = true)

            val encabezado = buildString {
                appendLine("=== Log completo — Quiosco MOSI ===")
                appendLine("Generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Tipo: $tipoLog")
                appendLine("Cliente: ${contexto.clienteNombre} (ID: ${contexto.clienteId})")
                appendLine("Sucursal: ${contexto.sucursalNombre} (ID: ${contexto.sucursalId})")
                appendLine("Cabina: ${contexto.cabina}")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Versión de la app: ${obtenerVersionApp(context)}")
                appendLine("Ubicación: ${archivo.absolutePath}")
                appendLine("=======================================")
                appendLine()

                if (incluirResumen) {
                    val resumenRed = AutoLogHelper.obtenerResumenRedYVelocidad(context)
                    val resumenServicioWeb = AutoLogHelper.obtenerResumenServicioWeb()
                    val resumenPuertos = SerialService.obtenerEstadoPuertos(context)

                    val tituloResumen = if (tipoLog.equals("arranque", ignoreCase = true)) {
                        "--- RESUMEN DE DIAGNÓSTICO AL ARRANQUE ---"
                    } else {
                        "--- RESUMEN DE DIAGNÓSTICO ---"
                    }

                    appendLine(tituloResumen)
                    appendLine("[RED E INTERNET]")
                    appendLine(resumenRed)
                    appendLine()
                    appendLine("[SERVICIO WEB Y API REST]")
                    appendLine(resumenServicioWeb)
                    appendLine()
                    appendLine("[PUERTOS SERIALES Y DISPOSITIVOS]")
                    appendLine(resumenPuertos)
                    appendLine("=======================================")
                    appendLine()
                }
            }

            val cuerpo = if (recientes.isEmpty())
                "(Sin mensajes de log registrados en esta sesión de la app)"
            else
                recientes.joinToString("\n")

            archivo.writeText(encabezado + cuerpo)
            Log.d(TAG, "📄 Log de respaldo generado en $CARPETA_LOGS: ${archivo.name} (${archivo.length() / 1024} KB)")
            archivo
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo generar el log completo: ${e.message}", e)
            null
        }
    }

    /**
     * Datos de identificación del quiosco que se escriben en el encabezado.
     * Se resuelven una sola vez por generación de log.
     */
    private data class ContextoQuiosco(
        val clienteNombre: String,
        val clienteId: String,
        val sucursalNombre: String,
        val sucursalId: String,
        val cabina: String
    ) {
        companion object {
            private const val NO_DISPONIBLE = "No disponible"

            fun resolver(
                context: Context,
                clienteOverride: String?,
                sucursalOverride: String?,
                cabinaOverride: String?
            ): ContextoQuiosco {
                val prefs = context.getSharedPreferences(PREFS_INSTALACION, Context.MODE_PRIVATE)

                val clienteNombre = clienteOverride?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(PREF_CLIENTE_NOMBRE, null)?.takeIf { it.isNotBlank() }
                    ?: NO_DISPONIBLE

                val clienteId = prefs.getString(PREF_CLIENTE, null)?.takeIf { it.isNotBlank() }
                    ?: NO_DISPONIBLE

                val sucursalNombre = sucursalOverride?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(PREF_SUCURSAL_NOMBRE, null)?.takeIf { it.isNotBlank() }
                    ?: NO_DISPONIBLE

                val sucursalId = prefs.getString(PREF_SUCURSAL, null)?.takeIf { it.isNotBlank() }
                    ?: NO_DISPONIBLE

                // La cabina se identifica por el número de serie activado (único por equipo).
                // Si en el futuro guardas un número de cabina legible, pásalo por el override.
                val cabina = cabinaOverride?.takeIf { it.isNotBlank() }
                    ?: MosiSecureStore.serialActivado(context).takeIf { it.isNotBlank() }
                    ?: NO_DISPONIBLE

                return ContextoQuiosco(clienteNombre, clienteId, sucursalNombre, sucursalId, cabina)
            }
        }
    }

    /**
     * Elimina todos los archivos de log en 'Logs_Mosi' (y carpetas de logs antiguas en cache)
     * que hayan superado los 7 días de antigüedad.
     */
    fun limpiarLogsAntiguos(context: Context) {
        try {
            val carpetas = listOf(
                File(context.filesDir, CARPETA_LOGS),
                File(context.cacheDir, "logs")
            )
            val ahora = System.currentTimeMillis()
            var eliminados = 0

            carpetas.forEach { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { archivo ->
                        if (archivo.isFile) {
                            val antiguedadMs = ahora - archivo.lastModified()
                            if (antiguedadMs > RETENCION_DIAS_MS) {
                                if (archivo.delete()) {
                                    eliminados++
                                    Log.d(TAG, "🧹 Log de respaldo antiguo eliminado (>7 días): ${archivo.name}")
                                }
                            }
                        }
                    }
                }
            }
            if (eliminados > 0) {
                Log.d(TAG, "🧹 Se eliminaron $eliminados archivo(s) de log con antigüedad mayor a 7 días.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error al limpiar logs antiguos: ${e.message}")
        }
    }

    private fun obtenerVersionApp(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
    } catch (e: Exception) {
        "desconocida"
    }
}