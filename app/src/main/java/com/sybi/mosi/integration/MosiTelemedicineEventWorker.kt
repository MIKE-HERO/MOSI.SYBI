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
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Entrega durable de eventos de llamada con request_id idempotente y reintentos de red. */
class MosiTelemedicineEventWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MosiSecureStore.estaActivado(applicationContext)) return Result.success()
        val event = inputData.getString(KEY_EVENT).orEmpty()
        val sessionId = inputData.getString(KEY_SESSION_ID).orEmpty()
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        if (event.isBlank() || sessionId.isBlank() || requestId.isBlank()) return Result.failure()

        return try {
            MosiApiClient(applicationContext).reportarEventoTelemedicina(
                event = event,
                sessionId = sessionId,
                requestId = requestId,
                detail = inputData.getString(KEY_DETAIL)?.takeIf { it.isNotBlank() }
            )
            Result.success()
        } catch (e: MosiApiException) {
            if (e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 422) Result.failure()
            else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_EVENT = "event"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_REQUEST_ID = "request_id"
        private const val KEY_DETAIL = "detail"

        fun enqueue(context: Context, event: String, sessionId: String, detail: String? = null) {
            if (!MosiSecureStore.estaActivado(context) || sessionId.isBlank()) return
            val requestId = UUID.randomUUID().toString()
            val work = OneTimeWorkRequestBuilder<MosiTelemedicineEventWorker>()
                .setInputData(workDataOf(
                    KEY_EVENT to event,
                    KEY_SESSION_ID to sessionId,
                    KEY_REQUEST_ID to requestId,
                    KEY_DETAIL to detail.orEmpty().take(300)
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "mosi-telemedicine-session-$sessionId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work
            )
        }
    }
}
