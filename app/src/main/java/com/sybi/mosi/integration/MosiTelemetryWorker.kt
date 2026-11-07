package com.sybi.mosi.integration

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sybi.mosi.database.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** NO SE ... */

class MosiTelemetryWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MosiSecureStore.estaActivado(applicationContext)) return Result.success()
        return try {
            val api = MosiApiClient(applicationContext)
            val database = AppDatabase.getInstance(applicationContext)
            val pending = database.pacienteDao().contarPendientesSincronizacion() +
                database.resultadoDao().contarPendientesSincronizacion()
            api.enviarTelemetria(
                requestId = inputData.getString(KEY_REQUEST_ID)
                    ?: throw IllegalStateException("Trabajo de telemetría sin request_id"),
                telemetry = api.technicalTelemetry(pending, 0, sensorStatus())
            )
            MosiSecureStore.guardarUltimaSincronizacion(applicationContext, api.nowIso())
            schedule(applicationContext, MosiSecureStore.intervaloSegundos(applicationContext))
            Result.success()
        } catch (e: MosiApiException) {
            if (e.statusCode == 401 || e.statusCode == 403) Result.failure() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun sensorStatus(): JSONArray {
        val prefs = applicationContext.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val definitions = listOf(
            Triple("bascula", "device_altura_peso", "device_altura_peso_port"),
            Triple("presion", "device_presion", "device_presion_port"),
            Triple("temperatura", "device_temperatura", "device_temperatura_port"),
            Triple("oximetro", "device_oxigeno", "device_oxigeno_port"),
            Triple("tarjeta_ic", "device_ic_card", "device_ic_card_port")
        )
        return JSONArray().apply {
            definitions.forEach { (name, enabledKey, portKey) ->
                val enabled = prefs.getBoolean(enabledKey, true)
                val configured = !prefs.getString(portKey, "").isNullOrBlank()
                put(JSONObject()
                    .put("nombre", name)
                    .put("conectado", enabled && configured)
                    .put("estado", when {
                        !enabled -> "deshabilitado"
                        configured -> "configurado"
                        else -> "sin_puerto"
                    }))
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK = "mosi-telemetry-next"
        private const val KEY_REQUEST_ID = "request_id"

        fun schedule(context: Context, delaySeconds: Long = 0L) {
            if (!MosiSecureStore.estaActivado(context)) return
            val request = OneTimeWorkRequestBuilder<MosiTelemetryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to UUID.randomUUID().toString()))
                .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
