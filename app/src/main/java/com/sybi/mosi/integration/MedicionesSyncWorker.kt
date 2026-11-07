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
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.repository.MedicionesSender
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Reintenta únicamente mediciones clínicas ya guardadas localmente... */
class MedicionesSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MosiSecureStore.estaActivado(applicationContext)) return Result.success()
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.resultadoDao()
        val pending = dao.obtenerPendientesSincronizacion(20)
        if (pending.isEmpty()) return Result.success()

        val sender = MedicionesSender(applicationContext)
        var failures = 0
        pending.forEach { result ->
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
            try {
                val response = sender.enviar(sender.buildFromResultado(result))
                if (response.success) {
                    dao.marcarSincronizado(result.id_resultado, response.remoteId, now)
                } else {
                    failures++
                    dao.marcarErrorSincronizacion(result.id_resultado, response.error.take(500), now)
                }
            } catch (e: Exception) {
                failures++
                dao.marcarErrorSincronizacion(
                    result.id_resultado,
                    (e.message ?: e.javaClass.simpleName).take(500),
                    now
                )
            }
        }

        return if (failures > 0) Result.retry() else {
            if (dao.contarPendientesSincronizacion() > 0) schedule(applicationContext, 30L)
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "mosi-clinical-sync"

        fun schedule(context: Context, delaySeconds: Long = 0L) {
            if (!MosiSecureStore.estaActivado(context)) return
            val request = OneTimeWorkRequestBuilder<MedicionesSyncWorker>()
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
