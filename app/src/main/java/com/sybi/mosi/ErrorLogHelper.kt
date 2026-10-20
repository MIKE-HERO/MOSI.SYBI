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
 * Genera un archivo con exclusivamente los mensajes de nivel ERROR del propio
 * proceso de la app (los Log.e(...) ya presentes en todo el código), leyendo
 * el logcat del sistema filtrado por el PID actual — no requiere permisos
 * especiales porque un proceso siempre puede leer su propio log.
 *
 * Pensado para "Testeo y Calibración" → "Enviar log": el administrador puede
 * mandarlo por correo cuando algo falla en campo, sin acceso a un depurador.
 */
object ErrorLogHelper {
    private const val TAG = "ErrorLogHelper"
    private const val MAX_LINEAS = 3000

    /** BLOQUEANTE (ejecuta logcat y lee archivos): llamar desde un hilo de fondo. */
    fun generarLogDeErrores(context: Context): File? {
        return try {
            val pid = Process.myPid()
            val proceso = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid", "*:E")
                .redirectErrorStream(true)
                .start()

            val lineas = proceso.inputStream.bufferedReader().readLines()
            proceso.waitFor()

            val recientes = if (lineas.size > MAX_LINEAS) lineas.takeLast(MAX_LINEAS) else lineas

            val dir = File(context.cacheDir, "logs").apply { mkdirs() }
            val archivo = File(dir, "mosi_errores.log")

            val encabezado = buildString {
                appendLine("=== Log de errores — Quiosco MOSI ===")
                appendLine("Generado: ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
                appendLine("Versión de la app: ${obtenerVersionApp(context)}")
                appendLine("=======================================")
                appendLine()
            }

            val cuerpo = if (recientes.isEmpty())
                "(Sin mensajes de error registrados en esta sesión de la app)"
            else
                recientes.joinToString("\n")

            archivo.writeText(encabezado + cuerpo)
            archivo
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo generar el log de errores: ${e.message}", e)
            null
        }
    }

    private fun obtenerVersionApp(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "desconocida"
    } catch (e: Exception) {
        "desconocida"
    }
}
