package com.sybi.mosi.integration

import android.content.Context

/** Mantiene la sesión activa entre la app, el navegador y el deep link de regreso. */
object MosiTelemedicineSession {
    private const val PREFS = "MosiTelemedicineSession"
    private const val KEY_ACTIVE_SESSION = "active_session"

    fun start(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_ACTIVE_SESSION, "").orEmpty()
        if (previous.isNotBlank() && previous != sessionId) {
            MosiTelemedicineEventWorker.enqueue(context, "cancelacion", previous, "Se inició una nueva consulta")
        }
        prefs.edit().putString(KEY_ACTIVE_SESSION, sessionId).commit()
        MosiTelemedicineEventWorker.enqueue(context, "inicio", sessionId)
    }

    fun finish(context: Context) = close(context, "fin", null)

    fun cancel(context: Context, detail: String? = null) = close(context, "cancelacion", detail)

    fun error(context: Context, sessionId: String, detail: String? = null) {
        MosiTelemedicineEventWorker.enqueue(context, "error", sessionId, detail)
    }

    private fun close(context: Context, event: String, detail: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = prefs.getString(KEY_ACTIVE_SESSION, "").orEmpty()
        if (sessionId.isBlank()) return
        // commit evita que onDestroy registre una cancelación después de un fin normal.
        if (prefs.edit().remove(KEY_ACTIVE_SESSION).commit()) {
            MosiTelemedicineEventWorker.enqueue(context, event, sessionId, detail)
        }
    }
}
