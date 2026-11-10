package com.sybi.mosi

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
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

    /**
     * Genera un archivo con el log completo del propio proceso de la app
     * (todos los niveles: V, D, I, W, E, F), guardando una copia con fecha
     * en la carpeta dedicada 'Logs_Mosi' dentro del almacenamiento interno como respaldo.
     */
    fun generarLogDeErrores(context: Context, tipoLog: String = "manual"): File? {
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

            // Filtrar líneas de sistema ('chatty', 'expire') y ruido de memoria de ART
            val lineasUtiles = lineas.filter { linea ->
                !linea.contains("chatty", ignoreCase = true) &&
                        !linea.contains(" expire ", ignoreCase = true) &&
                        !linea.contains(" art     :") // 🚫 Excluye logs internos de memoria (GC, JitCodeCache)
            }

            val recientes = if (lineasUtiles.size > MAX_LINEAS) lineasUtiles.takeLast(MAX_LINEAS) else lineasUtiles

            val dir = File(context.filesDir, CARPETA_LOGS).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val archivo = File(dir, "mosi_log_${tipoLog}_$timestamp.log")

            val encabezado = buildString {
                appendLine("=== Log completo — Quiosco MOSI ===")
                appendLine("Generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Tipo: $tipoLog")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Versión de la app: ${obtenerVersionApp(context)}")
                appendLine("Ubicación: ${archivo.absolutePath}")
                appendLine("=======================================")
                appendLine()
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