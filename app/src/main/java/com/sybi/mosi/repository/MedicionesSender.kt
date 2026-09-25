package com.sybi.mosi.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Resultado
import com.sybi.mosi.network.MedicionesRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centraliza la construcción y envío de MedicionesRequest.
 * Usado por ResultsActivity (envío en vivo) y ResultTableActivity (reenvío).
 */
class MedicionesSender(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    companion object {
        private const val TAG = "MedicionesSender"
    }

    /**
     * Construye un MedicionesRequest desde ResultsPrefs (envío en vivo).
     */
    suspend fun buildFromPrefs(idUsuarioWeb: Int, idLocal: Long): MedicionesRequest {
        val prefs = context.getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)
        val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        val modelo = devicePrefs.getString("device_model_custom", "") ?: ""
        val serie = devicePrefs.getString("device_serial_custom", "") ?: ""
        val cabina = devicePrefs.getString("cabina_number", "1")?.toIntOrNull() ?: 1

        val ecgFromPrefs = prefs.getString("ecg_image", null)
        Log.d(TAG, "📷 ECG desde prefs: ${ecgFromPrefs?.length ?: 0} chars (~${(ecgFromPrefs?.length ?: 0) / 1024} KB)")

        // ✅ Obtener y comprimir la foto del paciente
        val fotoComprimida = if (idLocal != 0L) {
            val p = db.pacienteDao().obtenerPacientePorIdLocal(idLocal)
            p?.foto?.let { comprimirBase64(it) }
        } else null

        return MedicionesRequest(
            idUsuarioWeb = idUsuarioWeb,
            idVendingMachine = cabina,
            recordID = "0",
            memberID = "0",
            measureData = "0",
            deviceId = serie,
            deviceModel = modelo,
            height = prefs.getFloat("height", 0f).toString(),
            weight = prefs.getFloat("weight", 0f).toString(),
            bmi = prefs.getFloat("imc", 0f).toString(),
            pressureDiastolic = prefs.getInt("diastolic", 0).toString(),
            pressureSystolic = prefs.getInt("systolic", 0).toString(),
            pressurePulseRate = prefs.getInt("pulse", 0).toString(),
            spo2 = prefs.getInt("spo2", 0).toString(),
            pulseRate = prefs.getInt("pulse_rate", 0).toString(),
            temper = prefs.getFloat("temperature", 0f).toString(),
            fatType = prefs.getInt("fat_type", 0).toString(),
            bodyAge = "0",
            bodyScore = "0",
            metabolism = prefs.getInt("metabolism", 0).toString(),
            whr = "0",
            fat = prefs.getFloat("fat", 0f).toString(),
            notFat = prefs.getFloat("not_fat", 0f).toString(),
            fatRate = prefs.getFloat("fat_rate", 0f).toString(),
            water = prefs.getFloat("water", 0f).toString(),
            waterRate = prefs.getFloat("water_rate", 0f).toString(),
            visceralFat = prefs.getFloat("visceral_fat", 0f).toString(),
            idealWeight = prefs.getFloat("ideal_weight", 0f).toString(),
            muscle = prefs.getFloat("muscle", 0f).toString(),
            mineral = prefs.getFloat("mineral", 0f).toString(),
            boneMuscle = "0",
            controlFat = "0",
            controlMuscle = "0",
            controlWeight = "0",
            protein = prefs.getFloat("protein", 0f).toString(),
            proteinRate = "0",
            cellFluidIn = "0",
            cellFluidEx = "0",
            fatRateTrunk = "0",
            fatRateLeftHand = "0",
            fatRateLeftFoot = "0",
            fatRateRightHand = "0",
            fatRateRightFoot = "0",
            muscleTrunk = "0",
            muscleLeftHand = "0",
            muscleLeftFoot = "0",
            muscleRightHand = "0",
            muscleRightFoot = "0",
            impedanceTrunk = "0",
            impedanceLeftHand = "0",
            impedanceLeftFoot = "0",
            impedanceRightHand = "0",
            impedanceRightFoot = "0",
            impedanceLowTrunk = "0",
            impedanceLowLeftHand = "0",
            impedanceLowLeftFoot = "0",
            impedanceLowRightHand = "0",
            impedanceLowRightFoot = "0",
            sugar = "0",
            sugarType = "0",
            ua = "0",
            ua2 = "0",
            chol = "0",
            waist = "0",
            hips = "0",
            waistHip = "0",
            heartRate = prefs.getInt("ecg_heart_rate", 0).toString(),
            pAxis = prefs.getString("ecg_p_axis", "0") ?: "0",
            qrsAxis = prefs.getString("ecg_qrs_axis", "0") ?: "0",
            tAxis = prefs.getString("ecg_t_axis", "0") ?: "0",
            prInterval = prefs.getString("ecg_pr_interval", "0") ?: "0",
            qrsDuration = prefs.getString("ecg_qrs_duration", "0") ?: "0",
            qtd = prefs.getString("ecg_qtd", "0") ?: "0",
            qtc = prefs.getString("ecg_qtc", "0") ?: "0",
            rv5 = prefs.getString("ecg_rv5", "0") ?: "0",
            sv1 = prefs.getString("ecg_sv1", "0") ?: "0",
            resCode = prefs.getString("ecg_res_code", "Sinus rhythm") ?: "Sinus rhythm",
            ecgImg = ecgFromPrefs,
            foto = fotoComprimida,
            lipidsChol = "0",
            lipidsTg = "0",
            lipidsHdl = "0",
            lipidsLdl = "0",
            rightVision = "0",
            leftVision = "0",
            fechaRegistro = SimpleDateFormat("yyyy-MM-dd HH:mm.SSS a", Locale.US).format(Date())
        )
    }

    /**
     * Construye un MedicionesRequest desde un Resultado de Room (reenvío).
     * Lee el JPG original y lo pasa a Base64 SIN recomprimir.
     */
    suspend fun buildFromResultado(r: Resultado): MedicionesRequest {
        val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val modelo = devicePrefs.getString("device_model_custom", "") ?: ""
        val serie = devicePrefs.getString("device_serial_custom", "") ?: ""
        val cabina = devicePrefs.getString("cabina_number", "1")?.toIntOrNull() ?: 1

        // ✅ Leer el JPG original tal cual y pasarlo a Base64 sin recomprimir
        val ecgBase64 = if (r.ruta_ecg.isNotEmpty() && File(r.ruta_ecg).exists()) {
            archivoABase64SinComprimir(r.ruta_ecg)
        } else null

        // ✅ Obtener y comprimir la foto del paciente
        val fotoComprimida = if (r.id_local != 0L) {
            val p = db.pacienteDao().obtenerPacientePorIdLocal(r.id_local)
            p?.foto?.let { comprimirBase64(it) }
        } else null

        return MedicionesRequest(
            idUsuarioWeb = r.id_usuario_web,
            idVendingMachine = cabina,
            recordID = "0",
            memberID = "0",
            measureData = "0",
            deviceId = serie,
            deviceModel = modelo,
            height = r.altura,
            weight = r.peso,
            bmi = r.imc,
            pressureDiastolic = r.diastolica,
            pressureSystolic = r.sistolica,
            pressurePulseRate = r.pulso,
            spo2 = r.spo2,
            pulseRate = r.frecuencia_pulso,
            temper = r.temperatura,
            fatType = r.tipo_grasa,
            bodyAge = "0",
            bodyScore = "0",
            metabolism = r.metabolismo_basal,
            whr = "0",
            fat = r.grasa_corporal_kg,
            notFat = r.masa_libre_grasa,
            fatRate = r.grasa_corporal,
            water = r.agua_corporal_kg,
            waterRate = r.agua_corporal,
            visceralFat = r.grasa_visceral,
            idealWeight = r.peso_ideal,
            muscle = r.masa_muscular,
            mineral = r.minerales,
            boneMuscle = "0",
            controlFat = "0",
            controlMuscle = "0",
            controlWeight = "0",
            protein = r.proteina,
            proteinRate = "0",
            cellFluidIn = "0",
            cellFluidEx = "0",
            fatRateTrunk = "0",
            fatRateLeftHand = "0",
            fatRateLeftFoot = "0",
            fatRateRightHand = "0",
            fatRateRightFoot = "0",
            muscleTrunk = "0",
            muscleLeftHand = "0",
            muscleLeftFoot = "0",
            muscleRightHand = "0",
            muscleRightFoot = "0",
            impedanceTrunk = "0",
            impedanceLeftHand = "0",
            impedanceLeftFoot = "0",
            impedanceRightHand = "0",
            impedanceRightFoot = "0",
            impedanceLowTrunk = "0",
            impedanceLowLeftHand = "0",
            impedanceLowLeftFoot = "0",
            impedanceLowRightHand = "0",
            impedanceLowRightFoot = "0",
            sugar = "0",
            sugarType = "0",
            ua = "0",
            ua2 = "0",
            chol = "0",
            waist = "0",
            hips = "0",
            waistHip = "0",
            heartRate = r.frecuencia_cardiaca,
            pAxis = r.eje_p,
            qrsAxis = r.eje_qrs,
            tAxis = r.eje_t,
            prInterval = r.intervalo_pr,
            qrsDuration = r.duracion_qrs,
            qtd = r.intervalo_qt,
            qtc = r.qt_corregido,
            rv5 = r.onda_rv5,
            sv1 = r.onda_sv1,
            resCode = r.resultado_ecg.ifEmpty { "Sinus rhythm" },
            ecgImg = ecgBase64,
            foto = fotoComprimida,
            lipidsChol = "0",
            lipidsTg = "0",
            lipidsHdl = "0",
            lipidsLdl = "0",
            rightVision = "0",
            leftVision = "0",
            fechaRegistro = reformatearFecha(r.fecha_medicion)
        )
    }

    suspend fun enviar(request: MedicionesRequest): Boolean {
        val repo = PacienteRemoteRepository(context)
        return repo.enviarMediciones(request)
    }

    /**
     * Lee un archivo JPG/PNG y lo convierte a Base64 SIN recomprimir ni redimensionar.
     * La imagen se envía tal cual está en disco.
     */
    private fun archivoABase64SinComprimir(ruta: String): String? {
        return try {
            val file = File(ruta)
            if (!file.exists()) {
                Log.w(TAG, "⚠️ No existe el archivo ECG: $ruta")
                return null
            }

            val bytes = file.readBytes()
            Log.d(TAG, "📷 ECG desde archivo (sin comprimir): ${bytes.size / 1024} KB")

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error leyendo ECG: ${e.message}", e)
            null
        }
    }

    /**
     * ✅ Decodifica un Base64, lo redimensiona y lo comprime como JPG
     * para que el envío sea rápido.
     */
    private fun comprimirBase64(base64Str: String): String? {
        return try {
            val bytes = Base64.decode(base64Str, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // Redimensionar a un tamaño pequeño (ej: 120x160 o proporcional)
            val maxW = 120
            val maxH = 160
            val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
            
            val finalW = (bitmap.width * scale).toInt()
            val finalH = (bitmap.height * scale).toInt()
            
            val resized = Bitmap.createScaledBitmap(bitmap, finalW, finalH, true)

            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 70, out) // 70% calidad
            
            val resultBytes = out.toByteArray()
            Log.d(TAG, "🖼️ Foto paciente comprimida: ${resultBytes.size / 1024} KB")
            
            Base64.encodeToString(resultBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error comprimiendo foto: ${e.message}")
            null
        }
    }

    private fun reformatearFecha(fechaLocal: String): String {
        return try {
            val input = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val output = SimpleDateFormat("yyyy-MM-dd HH:mm.SSS a", Locale.US)
            output.format(input.parse(fechaLocal) ?: Date())
        } catch (e: Exception) {
            SimpleDateFormat("yyyy-MM-dd HH:mm.SSS a", Locale.US).format(Date())
        }
    }
}