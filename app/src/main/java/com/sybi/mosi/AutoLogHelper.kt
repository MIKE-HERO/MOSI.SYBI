package com.sybi.mosi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Helper para gestionar el envío automático de logs por correo electrónico a soporte.
 *
 * Soporta dos disparadores principales:
 * 1. Log de arranque: Se envía automáticamente a los 5 minutos del inicio de la aplicación.
 * 2. Log de fin de consulta: Se envía al terminar el flujo de atención del paciente.
 */
object AutoLogHelper {

    private const val TAG = "AutoLogHelper"
    private const val PREF_NAME = "AppPrefs"
    private const val PREF_KEY_AUTO_LOG = "auto_log_enabled"

    /** Correo de soporte destino para el envío automático de logs */
    const val DESTINO_SOPORTE = "soporte.vending@medicallife.com.mx"

    private val executor = Executors.newSingleThreadExecutor()
    private val startupScheduled = AtomicBoolean(false)
    private val lastConsultationLogTime = AtomicLong(0L)

    // Cooldown para evitar enviar múltiples logs seguidos de la misma consulta (60 segundos)
    private const val CONSULTATION_LOG_COOLDOWN_MS = 60_000L

    // Tiempo de espera para el log de arranque (5 minutos = 300,000 ms)
    private const val STARTUP_LOG_DELAY_MS = 5 * 60 * 1000L

    fun isAutoLogEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_KEY_AUTO_LOG, false)
    }

    fun setAutoLogEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEY_AUTO_LOG, enabled).apply()
        Log.d(TAG, "Envío automático de logs cambiado a: $enabled")
    }

    /**
     * Programar el envío de log automático de arranque a los 5 minutos del inicio de la app.
     * Es seguro llamarlo en MosiApp u onCreate de MainActivity (solo se programa una vez por proceso).
     */
    fun programarLogArranque(context: Context) {
        if (startupScheduled.getAndSet(true)) return

        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            executor.execute {
                enviarLogSiCorresponde(appContext, "Arranque de la aplicación (5 min)")
            }
        }, STARTUP_LOG_DELAY_MS)
        Log.d(TAG, "⏰ Programado envío de log de arranque en ${STARTUP_LOG_DELAY_MS / 1000}s")
    }

    /**
     * Disparar el envío de log automático al finalizar una consulta.
     * Cuenta con cooldown para no duplicar si se llama desde varios eventos cercanos.
     */
    fun enviarLogFinConsulta(context: Context) {
        val now = System.currentTimeMillis()
        val last = lastConsultationLogTime.get()
        if (now - last < CONSULTATION_LOG_COOLDOWN_MS) {
            Log.d(TAG, "Omitiendo envío de log de fin de consulta por cooldown (${(now - last) / 1000}s)")
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            if (lastConsultationLogTime.compareAndSet(last, System.currentTimeMillis())) {
                enviarLogSiCorresponde(appContext, "Fin de consulta")
            }
        }
    }

    private fun enviarLogSiCorresponde(context: Context, motivo: String) {
        if (!isAutoLogEnabled(context)) {
            Log.d(TAG, "Envío automático deshabilitado. No se envía log ($motivo).")
            return
        }

        if (!EmailSender.estaConfigurado(context)) {
            Log.w(TAG, "⚠️ No se pudo enviar log automático ($motivo): SMTP no está configurado.")
            return
        }

        Log.d(TAG, "🚀 Generando y enviando log automático por $motivo...")
        val config = EmailSender.cargarConfig(context)
        val archivo = ErrorLogHelper.generarLogDeErrores(context)

        if (archivo != null && archivo.exists()) {
            val fechaStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
            val resultado = EmailSender.enviar(
                config = config,
                destino = DESTINO_SOPORTE,
                asunto = "Log automático ($motivo) — Quiosco MOSI",
                cuerpoHtml = """
                    <h2>Log automático de quiosco MOSI</h2>
                    <p><b>Motivo:</b> $motivo</p>
                    <p><b>Fecha y hora:</b> $fechaStr</p>
                    <p>Se adjunta el archivo con el registro de log del quiosco.</p>
                """.trimIndent(),
                adjunto = archivo,
                nombreAdjunto = "mosi_log_auto.log"
            )

            if (resultado.isSuccess) {
                Log.d(TAG, "✅ Log automático ($motivo) enviado exitosamente a $DESTINO_SOPORTE")
            } else {
                Log.e(TAG, "❌ Error al enviar log automático ($motivo): ${resultado.exceptionOrNull()?.message}")
            }
        } else {
            Log.e(TAG, "❌ No se pudo generar el archivo de log para envío automático ($motivo)")
        }
    }
}
