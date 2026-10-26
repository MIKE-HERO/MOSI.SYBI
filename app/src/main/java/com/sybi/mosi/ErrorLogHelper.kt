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
    private const val MAX_LINEAS = 5000

    /** BLOQUEANTE (ejecuta logcat y lee archivos): llamar desde un hilo de fondo. */
    fun generarLogDeErrores(context: Context): File? {
        return try {
            val pid = Process.myPid()
            // Sin filtro de nivel "*:E" → incluye TODOS los niveles del proceso
            val proceso = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "*:V")
                .redirectErrorStream(true)
                .start()

            val lineas = proceso.inputStream.bufferedReader().readLines()
            proceso.waitFor()

            val recientes = if (lineas.size > MAX_LINEAS) lineas.takeLast(MAX_LINEAS) else lineas

            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val archivo = File(dir, "mosi_log_completo.log")

            val encabezado = buildString {
                appendLine("=== Log completo — Quiosco MOSI ===")
                appendLine("Generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Versión de la app: ${obtenerVersionApp(context)}")
                appendLine("=======================================")
                appendLine()
            }

            val cuerpo = if (recientes.isEmpty())
                "(Sin mensajes de log registrados en esta sesión de la app)"
            else
                recientes.joinToString("\n")

            archivo.writeText(encabezado + cuerpo)
            archivo
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo generar el log completo: ${e.message}", e)
            null
        }
    }

    private fun obtenerVersionApp(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
    } catch (e: Exception) {
        "desconocida"
    }
}