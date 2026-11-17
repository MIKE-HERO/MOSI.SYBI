package com.sybi.mosi.measurement

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Gestor de almacenamiento y limpieza de imágenes de ECG.
 * Se evita duplicar imágenes si ya existen en ECGDATA/RETURN/ para ahorrar espacio.
 */
object EcgStorage {

    private const val TAG = "EcgStorage"
    private const val ECG_DIR = "ecg"

    /**
     * Limpia archivos de ECG en la carpeta externa ECGDATA/RETURN con más de 3 meses de antigüedad.
     * También limpia cualquier archivo residual en la carpeta interna de la app.
     */
    fun limpiarArchivosAntiguos(context: Context) {
        try {
            val root = Environment.getExternalStorageDirectory() ?: return
            val returnDir = File(root, "ECGDATA/RETURN")

            if (returnDir.exists() && returnDir.isDirectory) {
                // 3 meses = 90 días en milisegundos
                val limiteTresMesesMs = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000L)
                var eliminados = 0

                returnDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < limiteTresMesesMs) {
                        if (file.delete()) {
                            eliminados++
                            Log.d(TAG, "🧹 Archivo de ECG antiguo eliminado (>3 meses): ${file.name}")
                        }
                    }
                }
                if (eliminados > 0) {
                    Log.d(TAG, "🧹 Se eliminaron $eliminados archivo(s) de ECG con más de 3 meses de antigüedad en ECGDATA/RETURN")
                }
            }

            // Limpiar copias residuales en la carpeta interna filesDir/ecg
            clearAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al limpiar archivos antiguos de ECG: ${e.message}", e)
        }
    }

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
            Log.d(TAG, "🧹 Carpeta interna de ECGs limpiada")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error limpiando ECGs internos: ${e.message}", e)
        }
    }

    /**
     * Guarda un Bitmap como archivo JPG en la carpeta interna de la app.
     * Devuelve la ruta absoluta del archivo guardado, o null si falla.
     */
    fun saveBitmapToInternal(context: Context, bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, ECG_DIR).apply { if (!exists()) mkdirs() }
            val fileName = "ecg_${System.currentTimeMillis()}.jpg"
            val dest = File(dir, fileName)

            val out = java.io.FileOutputStream(dest)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()

            Log.d(TAG, "✅ Bitmap ECG guardado: ${dest.absolutePath} (${dest.length() / 1024} KB)")
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando Bitmap ECG: ${e.message}", e)
            null
        }
    }
}