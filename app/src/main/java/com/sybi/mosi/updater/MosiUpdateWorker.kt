package com.sybi.mosi.updater

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sybi.mosi.integration.MosiApiException
import com.sybi.mosi.integration.MosiSecureStore
import java.util.concurrent.TimeUnit

/** Consulta periodicamente el canal MOSI y abre el flujo protegido de actualizacion. */
class MosiUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MosiSecureStore.estaActivado(applicationContext)) return Result.success()

        return try {
            val release = UpdateManager(applicationContext).fetchReleases().firstOrNull()
                ?: return Result.success()
            Log.i(TAG, "Actualizacion MOSI ${release.tagName} detectada por el monitor periodico")
            UpdateActivity.start(
                context = applicationContext,
                downloadUrl = release.downloadUrl,
                versionTag = release.tagName,
                sha256 = release.sha256,
                updateId = release.updateId,
                requestId = release.requestId
            )
            Result.success()
        } catch (e: MosiApiException) {
            Log.w(TAG, "No se pudo consultar el canal MOSI: ${e.message}")
            if (e.statusCode == 401 || e.statusCode == 403) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Fallo temporal consultando actualizaciones: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MosiUpdateWorker"
        private const val PERIODIC_WORK = "mosi-update-monitor"
        private const val IMMEDIATE_WORK = "mosi-update-check-now"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<MosiUpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun checkNow(context: Context, delaySeconds: Long = 4L) {
            if (!MosiSecureStore.estaActivado(context)) return
            val request = OneTimeWorkRequestBuilder<MosiUpdateWorker>()
                .setInitialDelay(delaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
