package com.sybi.mosi.measurement

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.sybi.mosi.helpers.ECGCallback
import com.sybi.mosi.helpers.ECGHelper
import com.sybi.mosi.helpers.HWFatHelper
import java.io.ByteArrayOutputStream

/**
 * Resultado de una medición de ECG.
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
    companion object {
        private const val TAG = "EcgManager"
        // ═══ FIX: watchdog de 90s (el ECGHelper agota a los 80s, así que éste
        //           solo dispara si el helper se quedó colgado) ═══
        private const val WATCHDOG_TIMEOUT_MS = 90_000L
    }

    private var helper: ECGHelper? = null
    private var savedInternalPath: String? = null

    // ═══ FIX: watchdog para garantizar que onError() SIEMPRE se dispare ═══
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    fun init() {
        helper = ECGHelper(object : ECGCallback {
            override fun onMeasureResult(
                heartRate: Int, pAxis: String, qrsAxis: String, tAxis: String,
                prInterval: String, qrsDuration: String, qtd: String, qtc: String,
                rv5: String, sv1: String, resCode: String, ecgImage: Bitmap
            ) {
                // ═══ FIX: cancelar watchdog al recibir resultado exitoso ═══
                cancelWatchdog()
                // ═══ FIX: Guardar inmediatamente el Bitmap en almacenamiento interno ═══
                savedInternalPath = EcgStorage.saveBitmapToInternal(activity, ecgImage)

                val results = EcgResults(
                    heartRate, pAxis, qrsAxis, tAxis, prInterval,
                    qrsDuration, qtd, qtc, rv5, sv1, resCode
                )
                activity.runOnUiThread { onResult(results, ecgImage) }
            }

            override fun onMeasureError() {
                // ═══ FIX: cancelar watchdog al recibir error del helper ═══
                cancelWatchdog()
                activity.runOnUiThread { onError() }
            }
        })
    }

    fun onResume() {
        helper?.onResume()
    }

    fun cancel() {
        cancelWatchdog()
        helper?.cancel()
    }

    fun resumePendingMeasurement(patientId: String, startTime: Long) {
        armarWatchdog(patientId, startTime)
        helper?.resumePolling(patientId, startTime)
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

        val ok = if (derivaciones == 12) {
            helper?.openECG12(activity, name, id, patientGender, age, year, month, day) ?: false
        } else {
            helper?.openECG6(activity, name, id, patientGender, age, year, month, day) ?: false
        }

        // ═══ FIX: armar watchdog si el helper arrancó correctamente ═══
        if (ok) {
            armarWatchdog(id, System.currentTimeMillis())
        }
        return ok
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
            val base64 = bitmapToBase64Original(it)
            editor.putString("ecg_image", base64)
            Log.d(TAG, "📷 ECG Base64 (original PNG): ${base64.length / 1024} KB")
        }
        editor.apply()
    }

    fun getLastJpgPath(): String? = savedInternalPath ?: helper?.lastJpgPath

    // ═══ FIX: watchdog propio del EcgManager ═══
    private fun armarWatchdog(patientId: String, startTime: Long) {
        cancelWatchdog()
        val restante = (WATCHDOG_TIMEOUT_MS - (System.currentTimeMillis() - startTime))
            .coerceAtLeast(5_000L)
        Log.d(TAG, "⏰ Watchdog ECG armado para paciente=$patientId en ${restante}ms")
        watchdogRunnable = Runnable {
            Log.w(TAG, "⏰ Watchdog ECG DISPARADO: sin resultado en ${WATCHDOG_TIMEOUT_MS}ms, forzando onError()")
            helper?.cancel()
            activity.runOnUiThread { onError() }
        }
        watchdogHandler.postDelayed(watchdogRunnable!!, restante)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let {
            watchdogHandler.removeCallbacks(it)
            Log.d(TAG, "⏰ Watchdog ECG cancelado")
        }
        watchdogRunnable = null
    }

    private fun bitmapToBase64Original(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val bytes = baos.toByteArray()
        Log.d(TAG, "📷 ECG original (PNG): ${bytes.size / 1024} KB (${bitmap.width}x${bitmap.height})")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}