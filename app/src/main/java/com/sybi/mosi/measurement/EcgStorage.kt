package com.sybi.mosi.measurement

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Copia la imagen del ECG desde la carpeta de la app externa
 * (ECGDATA/RETURN/) a la carpeta interna de nuestra app (filesDir/ecg/).
 * Así no dependemos de que la app externa conserve los archivos.
 */
object EcgStorage {

    private const val TAG = "EcgStorage"
    private const val ECG_DIR = "ecg"

    /**
     * Copia el JPG origen a la carpeta interna de la app.
     * Devuelve la ruta absoluta del archivo copiado, o null si falla.
     */
    fun copyToInternal(context: Context, sourcePath: String): String? {
        return try {
            val source = File(sourcePath)
            if (!source.exists()) {
                Log.w(TAG, "⚠️ No existe el archivo origen: $sourcePath")
                return null
            }

            val dir = File(context.filesDir, ECG_DIR).apply { if (!exists()) mkdirs() }

            val fileName = "ecg_${System.currentTimeMillis()}.jpg"
            val dest = File(dir, fileName)

            source.copyTo(dest, overwrite = true)

            Log.d(TAG, "✅ ECG copiado: ${dest.absolutePath} (${dest.length() / 1024} KB)")
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error copiando ECG: ${e.message}", e)
            null
        }
    }

    fun clearAll(context: Context) {
        try {
            File(context.filesDir, ECG_DIR).takeIf { it.exists() }?.deleteRecursively()
            Log.d(TAG, "🧹 Carpeta de ECGs limpiada")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error limpiando ECGs: ${e.message}", e)
        }
    }
}