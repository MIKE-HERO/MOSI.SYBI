package com.sybi.mosi.helpers

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class ECGHelper(private val callback: ECGCallback) {

    companion object {
        private const val TAG = "ECGHelper"
        private const val REQUEST_CODE_ECG = 1
        // ═══ FIX: subir a 40 reintentos (80s) porque la app externa tarda 40-60s ═══
        private const val MAX_RETRIES = 40
        private const val RETRY_DELAY_MS = 2000L
    }

    private var isRead = false
    private var isMeasure = false
    // ═══ FIX: bandera para no reiniciar reintentos en cada onResume() ═══
    private var isPolling = false
    private var currentFileName: String? = null
    private var currentPatientId: String? = null
    private var measurementStartTime: Long = 0

    var lastJpgPath: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            1 -> {
                val data = msg.data
                val heartRate = data.getInt("HeartRate", 0)
                val pAxis = data.getString("PAxis") ?: ""
                val qrsAxis = data.getString("QRSAxis") ?: ""
                val tAxis = data.getString("TAxis") ?: ""
                val prInterval = data.getString("PRInterval") ?: ""
                val qrsDuration = data.getString("QRSDuration") ?: ""
                val qtd = data.getString("QTD") ?: ""
                val qtc = data.getString("QTC") ?: ""
                val rv5 = data.getString("RV5") ?: ""
                val sv1 = data.getString("SV1") ?: ""
                val resCode = data.getString("ResCode") ?: ""
                val ecgImage = data.getParcelable<Bitmap>("ECGImg")

                if (heartRate > 0 && ecgImage != null) {
                    callback.onMeasureResult(
                        heartRate, pAxis, qrsAxis, tAxis, prInterval,
                        qrsDuration, qtd, qtc, rv5, sv1, resCode, ecgImage
                    )
                } else {
                    callback.onMeasureError()
                }
                true
            }
            2 -> {
                callback.onMeasureError()
                true
            }
            else -> false
        }
    }

    fun openECG6(
        activity: Activity,
        name: String, id: String, gender: String, age: Int,
        year: Int, month: Int, day: Int
    ): Boolean {
        currentPatientId = id
        measurementStartTime = System.currentTimeMillis()
        return openECG(activity, 1, name, id, gender, age, year, month, day)
    }

    fun openECG12(
        activity: Activity,
        name: String, id: String, gender: String, age: Int,
        year: Int, month: Int, day: Int
    ): Boolean {
        currentPatientId = id
        measurementStartTime = System.currentTimeMillis()
        return openECG(activity, 0, name, id, gender, age, year, month, day)
    }

    private fun openECG(
        activity: Activity, mode: Int,
        name: String, id: String, gender: String, age: Int,
        year: Int, month: Int, day: Int
    ): Boolean {
        val launchIntent = activity.packageManager.getLaunchIntentForPackage("com.ecgmac.ecgtab")
        if (launchIntent == null) {
            Log.e(TAG, "App de ECG no encontrada")
            return false
        }

        val bundle = Bundle().apply {
            putInt("ECG_MODE", mode)
            putString("Patient_Name", name)
            putString("Patient_ID", id)
            putString("Patient_Gender", gender)
            putInt("Patient_Age", age)
            putInt("Birth_Year", year)
            putInt("Birth_Month", month)
            putInt("Birth_Day", day)
            putBoolean("StartAD", false)
            putString("Export_Mode", "JPEG")
        }

        launchIntent.putExtras(bundle)
        activity.startActivityForResult(launchIntent, REQUEST_CODE_ECG)

        // Resetear archivo previo para permitir repeticiones rápidas
        currentFileName = null
        isRead = false
        isMeasure = true
        // ═══ FIX: iniciar polling inmediatamente en segundo plano mientras la app de ECG está al frente ═══
        isPolling = true
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "App de ECG iniciada para paciente=$id, startTime=$measurementStartTime. Polling iniciado.")
        handler.postDelayed({ readFileData(attempt = 0) }, 2000)
        return true
    }

    /**
     * Se llama desde Activity.onResume() cuando la app vuelve al frente.
     * ═══ FIX: no reiniciar el contador si ya hay una búsqueda en curso ═══
     */
    fun onResume() {
        if (isMeasure && !isPolling) {
            isRead = false
            isPolling = true
            Log.d(TAG, "onResume: iniciando búsqueda de archivos de ECG para paciente=$currentPatientId")
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ readFileData(attempt = 0) }, 1500)
        } else if (isMeasure) {
            Log.d(TAG, "onResume: búsqueda ya en curso, no se reinicia (attempt actual sigue contando)")
        } else {
            Log.d(TAG, "onResume: no hay medición pendiente")
        }
    }

    fun cancel() {
        isRead = false
        isMeasure = false
        isPolling = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Reanuda la búsqueda de resultados para una medición iniciada antes de
     * que esta instancia existiera (ver EcgManager.resumePendingMeasurement).
     */
    fun resumePolling(patientId: String, startTime: Long) {
        currentPatientId = patientId
        measurementStartTime = startTime
        currentFileName = null
        isRead = false
        isMeasure = true
        // ═══ FIX: marcar polling activo y limpiar handler antes de reenganchar ═══
        isPolling = true
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Reenganchando búsqueda de ECG para paciente=$patientId, startTime=$startTime")
        handler.postDelayed({ readFileData() }, 500)
    }

    @Deprecated("Usar onResume() en su lugar")
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.d(TAG, "handleActivityResult legacy: requestCode=$requestCode, resultCode=$resultCode")
        if (requestCode == REQUEST_CODE_ECG && isRead) {
            isRead = false
            handler.postDelayed({ readFileData() }, 2000)
        }
    }

    private fun readFileData(attempt: Int = 0) {
        Thread {
            try {
                val dir = getECGDirectory() ?: run { handleError(); return@Thread }
                val returnDir = File(dir, "RETURN")
                if (!returnDir.exists()) {
                    retryOrFail(attempt, "RETURN no existe")
                    return@Thread
                }

                val patientId = currentPatientId
                val xmlFiles = returnDir.listFiles { file ->
                    file.extension == "xml" &&
                            file.name.startsWith("SN004_") &&
                            (patientId == null || file.name.contains("_${patientId}_"))
                }

                if (xmlFiles.isNullOrEmpty()) {
                    retryOrFail(attempt, "No hay XMLs para paciente=$patientId")
                    return@Thread
                }

                // Filtrar solo archivos creados DESPUÉS de iniciar la medición
                val newFiles = xmlFiles.filter { file ->
                    val ts = extractTimestampFromName(file.nameWithoutExtension)
                    ts != null && ts >= measurementStartTime
                }

                if (newFiles.isEmpty()) {
                    Log.d(TAG, "⏳ Sin archivos nuevos (intento $attempt, startTime=$measurementStartTime)")
                    retryOrFail(attempt, "Sin archivos nuevos")
                    return@Thread
                }

                // De los nuevos, el de mayor timestamp
                val latestXml = newFiles.maxByOrNull {
                    extractTimestampFromName(it.nameWithoutExtension)!!
                } ?: run {
                    handleError()
                    return@Thread
                }

                val actualFileName = latestXml.nameWithoutExtension
                Log.d(TAG, "📄 Archivo seleccionado: $actualFileName")

                if (currentFileName == actualFileName) {
                    Log.d(TAG, "Archivo ya procesado, esperando nuevo...")
                    retryOrFail(attempt, "Archivo ya procesado")
                    return@Thread
                }

                // Verificar que existan ambos archivos (XML + JPG)
                val xmlFile = File(returnDir, "$actualFileName.xml")
                val jpgFile = File(returnDir, "$actualFileName.jpg")

                if (!xmlFile.exists()) {
                    Log.d(TAG, "⏳ XML aún no existe: $actualFileName, reintentando...")
                    retryOrFail(attempt, "XML aún no escrito")
                    return@Thread
                }

                if (!jpgFile.exists()) {
                    Log.d(TAG, "⏳ XML encontrado pero JPG aún no existe: $actualFileName, reintentando...")
                    retryOrFail(attempt, "JPG aún no escrito")
                    return@Thread
                }

                val xmlContent = readFile(xmlFile) ?: run {
                    Log.e(TAG, "XML vacío o no legible")
                    retryOrFail(attempt, "XML no legible")
                    return@Thread
                }

                val ecgImage = BitmapFactory.decodeFile(jpgFile.absolutePath)
                if (ecgImage == null) {
                    Log.e(TAG, "No se pudo decodificar el JPG: $actualFileName, reintentando...")
                    retryOrFail(attempt, "JPG no decodificable")
                    return@Thread
                }

                lastJpgPath = jpgFile.absolutePath
                Log.d(TAG, "✅ Imagen cargada: ${jpgFile.absolutePath}")

                val heartRate = extractInt(xmlContent, "HeartRate")
                val pAxis = extractString(xmlContent, "PAxis")
                val qrsAxis = extractString(xmlContent, "QRSAxis")
                val tAxis = extractString(xmlContent, "TAxis")
                val prInterval = extractString(xmlContent, "PRInterval")
                val qrsDuration = extractString(xmlContent, "QRSDuration")
                val qtd = extractString(xmlContent, "QTD")
                val qtc = extractString(xmlContent, "QTC")
                val rv5 = extractString(xmlContent, "RV5")
                val sv1 = extractString(xmlContent, "SV1")
                val resCode = extractResCode(xmlContent)

                currentFileName = actualFileName

                handleResult(
                    heartRate, pAxis, qrsAxis, tAxis, prInterval,
                    qrsDuration, qtd, qtc, rv5, sv1, resCode, ecgImage
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error leyendo datos de ECG", e)
                handleError()
            }
        }.start()
    }

    private fun retryOrFail(attempt: Int, reason: String) {
        if (attempt >= MAX_RETRIES) {
            Log.e(TAG, "❌ Se agotaron los reintentos ($MAX_RETRIES): $reason")
            // ═══ FIX: handleError() ahora SIEMPRE notifica ═══
            handleError()
            return
        }
        handler.postDelayed({ readFileData(attempt + 1) }, RETRY_DELAY_MS)
    }

    private fun extractTimestampFromName(nameWithoutExtension: String): Long? {
        return try {
            val parts = nameWithoutExtension.split("_")
            if (parts.size < 3) return null
            val timestampStr = parts.last()
            if (timestampStr.length != 14) return null

            val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            fmt.parse(timestampStr)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun handleResult(
        heartRate: Int, pAxis: String, qrsAxis: String, tAxis: String,
        prInterval: String, qrsDuration: String, qtd: String, qtc: String,
        rv5: String, sv1: String, resCode: String, ecgImage: Bitmap
    ) {
        val msg = handler.obtainMessage(1)
        msg.data = Bundle().apply {
            putInt("HeartRate", heartRate)
            putString("PAxis", pAxis)
            putString("QRSAxis", qrsAxis)
            putString("TAxis", tAxis)
            putString("PRInterval", prInterval)
            putString("QRSDuration", qrsDuration)
            putString("QTD", qtd)
            putString("QTC", qtc)
            putString("RV5", rv5)
            putString("SV1", sv1)
            putString("ResCode", resCode)
            putParcelable("ECGImg", ecgImage)
        }
        handler.sendMessage(msg)
        isMeasure = false
        isPolling = false
        currentPatientId = null
        measurementStartTime = 0
    }

    /**
     * ═══ FIX CRÍTICO ═══
     * Antes: `if (isMeasure) handler.sendMessage(...)` — si isMeasure ya era false,
     * la UI se quedaba atascada en "Procesando..." para siempre.
     * Ahora: SIEMPRE notifica onMeasureError(), sin condiciones.
     */
    private fun handleError() {
        Log.d(TAG, "handleError: notificando onMeasureError al callback")
        handler.removeCallbacksAndMessages(null)
        handler.sendMessage(handler.obtainMessage(2))
        isMeasure = false
        isPolling = false
        currentPatientId = null
        measurementStartTime = 0
    }

    private fun getECGDirectory(): File? {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            Environment.getExternalStorageDirectory()?.let { File(it, "ECGDATA") }
        } else null
    }

    private fun readFile(file: File): String? = try {
        if (file.exists() && file.isFile) file.readText() else null
    } catch (e: Exception) {
        Log.e(TAG, "Error leyendo archivo: ${file.absolutePath}", e)
        null
    }

    private fun extractInt(xml: String, key: String): Int =
        extractString(xml, key).toIntOrNull() ?: 0

    private fun extractString(xml: String, key: String): String {
        val pattern = "$key=\""
        val index = xml.indexOf(pattern)
        if (index >= 0) {
            val start = index + pattern.length
            val end = xml.indexOf("\"", start)
            if (end > start) return xml.substring(start, end)
        }
        return ""
    }

    private fun extractResCode(xml: String): String {
        val startTag = "<ResCode"
        val endTag = "</ResCode>"
        val startIndex = xml.indexOf(startTag)
        if (startIndex >= 0) {
            val start = xml.indexOf(">", startIndex) + 1
            val end = xml.indexOf(endTag, start)
            if (end > start) return xml.substring(start, end).trim()
        }
        return ""
    }
}