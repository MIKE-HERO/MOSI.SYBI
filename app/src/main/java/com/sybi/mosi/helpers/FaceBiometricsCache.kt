package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import kotlinx.coroutines.runBlocking

object FaceBiometricsCache {

    private const val TAG = "FaceBiometricsCache"

    data class PacienteBiometria(
        val paciente: Paciente,
        val biometria: FaceBiometricsHelper.BiometricFaceData
    )

    @Volatile private var cachedPatientsBiometrics: List<PacienteBiometria> = emptyList()
    @Volatile private var isPreloading = false
    @Volatile private var isLoaded = false

    fun isLoaded(): Boolean = isLoaded
    fun isPreloading(): Boolean = isPreloading

    fun getCachedCandidates(): List<PacienteBiometria> = cachedPatientsBiometrics

    /**
     * Inicia la precarga de datos biométricos de pacientes en segundo plano.
     * Si ya se cargaron los datos, ignora llamadas repetidas salvo que se fuerce con refresh().
     */
    fun preload(context: Context, onComplete: (() -> Unit)? = null) {
        if (isLoaded) {
            onComplete?.invoke()
            return
        }
        if (isPreloading) return

        isPreloading = true
        val appContext = context.applicationContext

        Thread {
            try {
                FaceBiometricsHelper.init(appContext)
                val database = AppDatabase.getInstance(appContext)
                val pacientes = runBlocking { database.pacienteDao().obtenerTodosLosPacientes() }

                val list = mutableListOf<PacienteBiometria>()
                for (paciente in pacientes) {
                    val fotoGuardada = paciente.foto
                    if (!fotoGuardada.isNullOrEmpty()) {
                        val biometria = extraerBiometriaDeFoto(fotoGuardada)
                        if (biometria != null) {
                            list.add(PacienteBiometria(paciente, biometria))
                        }
                    }
                }
                cachedPatientsBiometrics = list
                isLoaded = true
                Log.d(TAG, "Precarga completa de biometría: ${list.size} pacientes guardados en memoria.")
            } catch (e: Throwable) {
                Log.e(TAG, "Error precargando biometría de pacientes: ${e.message}", e)
            } finally {
                isPreloading = false
                onComplete?.invoke()
            }
        }.start()
    }

    /**
     * Fuerza la recarga del caché (útil al registrar un nuevo paciente o actualizar foto).
     */
    fun refresh(context: Context, onComplete: (() -> Unit)? = null) {
        isLoaded = false
        preload(context, onComplete)
    }

    private fun extraerBiometriaDeFoto(base64Foto: String): FaceBiometricsHelper.BiometricFaceData? {
        return try {
            val bytes = Base64.decode(base64Foto, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val mpResult = MediaPipeFaceHelper.detect(bmp) ?: return null
            if (mpResult.faceLandmarks().isEmpty()) return null
            FaceBiometricsHelper.processFace(bmp, mpResult)
        } catch (e: Throwable) {
            Log.e(TAG, "Error extrayendo biometría guardada: ${e.message}", e)
            null
        }
    }
}
