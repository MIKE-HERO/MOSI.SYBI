package com.sybi.mosi.measurement

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.sybi.mosi.helpers.ECGCallback
import com.sybi.mosi.helpers.ECGHelper
import com.sybi.mosi.helpers.HWFatHelper
import java.io.ByteArrayOutputStream

/**
 * Resultado de una medición de ECG.
 * Vive aquí porque solo lo produce EcgManager.
 */
data class EcgResults(
    val heartRate: Int,
    val pAxis: String,
    val qrsAxis: String,
    val tAxis: String,
    val prInterval: String,
    val qrsDuration: String,
    val qtd: String,
    val qtc: String,
    val rv5: String,
    val sv1: String,
    val resCode: String
)

class EcgManager(
    private val activity: Activity,
    private val onResult: (EcgResults, Bitmap) -> Unit,
    private val onError: () -> Unit
) {
    companion object { private const val TAG = "EcgManager" }

    private var helper: ECGHelper? = null

    fun init() {
        helper = ECGHelper(object : ECGCallback {
            override fun onMeasureResult(
                heartRate: Int, pAxis: String, qrsAxis: String, tAxis: String,
                prInterval: String, qrsDuration: String, qtd: String, qtc: String,
                rv5: String, sv1: String, resCode: String, ecgImage: Bitmap
            ) {
                val results = EcgResults(
                    heartRate, pAxis, qrsAxis, tAxis, prInterval,
                    qrsDuration, qtd, qtc, rv5, sv1, resCode
                )
                activity.runOnUiThread { onResult(results, ecgImage) }
            }

            override fun onMeasureError() {
                activity.runOnUiThread { onError() }
            }
        })
    }

    /**
     * ✅ Se llama desde Activity.onResume() cuando la app vuelve al frente.
     *    Delega al ECGHelper para que busque los archivos de la medición.
     */
    fun onResume() {
        helper?.onResume()
    }

    /**
     * ✅ Cancela reintentos de búsqueda de ECG.
     */
    fun cancel() {
        helper?.cancel()
    }

    /**
     * Reengancha la búsqueda del resultado de una medición que ya se había
     * iniciado antes de que el proceso se recreara (Android mató la app en
     * segundo plano mientras la app externa de ECG estaba al frente — típico
     * en equipos de poca RAM). [helper] es una instancia nueva recién creada
     * por [init], así que no sabe que hay una medición pendiente hasta que se
     * lo decimos aquí.
     */
    fun resumePendingMeasurement(patientId: String, startTime: Long) {
        helper?.resumePolling(patientId, startTime)
    }

    /**
     * Método legacy. Ya no se usa.
     */
    @Deprecated("Usar onResume() en su lugar")
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        if (requestCode == 1) helper?.handleActivityResult(requestCode, resultCode, data)
    }

    fun startMeasurement(
        patientName: String,
        patientId: String,
        patientGender: String,
        patientBirthDate: String,
        derivaciones: Int = 6
    ): Boolean {
        val age = HWFatHelper(activity).calcularEdadDesdeFecha(patientBirthDate)
        val parts = patientBirthDate.split("/")
        val year = parts.getOrNull(2)?.toIntOrNull() ?: 1990
        val month = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val day = parts.getOrNull(0)?.toIntOrNull() ?: 1

        Log.d(TAG, "📤 Enviando datos al ECG: $patientName / $patientId / $patientGender / $age / ${derivaciones}d")

        val name = patientName.ifEmpty { "Paciente" }
        val id = patientId.ifEmpty { "0000" }

        return if (derivaciones == 12) {
            helper?.openECG12(activity, name, id, patientGender, age, year, month, day) ?: false
        } else {
            helper?.openECG6(activity, name, id, patientGender, age, year, month, day) ?: false
        }
    }

    fun saveToPrefs(context: Context, results: EcgResults, image: Bitmap?) {
        val editor = context.getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE).edit()
        editor.putInt("ecg_heart_rate", results.heartRate)
        editor.putString("ecg_p_axis", results.pAxis)
        editor.putString("ecg_qrs_axis", results.qrsAxis)
        editor.putString("ecg_t_axis", results.tAxis)
        editor.putString("ecg_pr_interval", results.prInterval)
        editor.putString("ecg_qrs_duration", results.qrsDuration)
        editor.putString("ecg_qtd", results.qtd)
        editor.putString("ecg_qtc", results.qtc)
        editor.putString("ecg_rv5", results.rv5)
        editor.putString("ecg_sv1", results.sv1)
        editor.putString("ecg_res_code", results.resCode)

        image?.let {
            // ✅ Comprimir y optimizar la imagen del ECG (JPEG 85%, máx. 1600px)
            //    Mantiene máxima nitidez visual pero reduce el tamaño de ~4.5 MB a ~200 KB
            //    impidiendo timeouts en conexiones lentas.
            val base64 = bitmapToBase64Optimizado(it)
            editor.putString("ecg_image", base64)
            Log.d(TAG, "📷 ECG Base64 (optimizado JPEG): ${base64.length / 1024} KB")
        }
        editor.apply()
    }

    fun getLastJpgPath(): String? = helper?.lastJpgPath

    /**
     * Convierte el Bitmap a Base64 optimizado para un envío rápido por internet.
     * Escala a máximo 1600px y comprime a JPEG 85% preservando excelente nitidez.
     */
    private fun bitmapToBase64Optimizado(bitmap: Bitmap): String {
        val maxDim = 1600
        val width = bitmap.width
        val height = bitmap.height
        val scaledBitmap = if (width > maxDim || height > maxDim) {
            val scale = minOf(maxDim.toFloat() / width, maxDim.toFloat() / height)
            val newW = (width * scale).toInt()
            val newH = (height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val bytes = baos.toByteArray()
        Log.d(TAG, "📷 ECG optimizado (JPEG 85%): ${bytes.size / 1024} KB (${scaledBitmap.width}x${scaledBitmap.height})")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}