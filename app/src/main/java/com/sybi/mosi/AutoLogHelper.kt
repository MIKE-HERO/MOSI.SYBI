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
    private val lastMedicionLogTime = AtomicLong(0L)
    private val lastTeleconsultaLogTime = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startupLogRunnable: Runnable? = null

    // Cooldown para evitar enviar múltiples logs seguidos (15 segundos)
    private const val LOG_COOLDOWN_MS = 15_000L

    // Tiempo de espera para el log de arranque (5 minutos = 300,000 ms)
    private const val STARTUP_LOG_DELAY_MS = 5 * 60 * 1000L

    @Volatile
    private var testVelocidadArranque: SpeedTestHelper.ResultadoSpeedTest? = null
    @Volatile
    private var testVelocidadEnProgreso = false

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
     * Inicia inmediatamente el test de velocidad de internet en segundo plano.
     * Es seguro llamarlo en MosiApp u onCreate de MainActivity (solo se programa una vez por proceso).
     */
    fun programarLogArranque(context: Context) {
        if (startupScheduled.getAndSet(true)) return

        val appContext = context.applicationContext

        // Iniciar test de velocidad automático al arrancar la app
        ejecutarTestVelocidadArranque()

        val runnable = Runnable {
            executor.execute {
                enviarLogSiCorresponde(appContext, "Arranque de la aplicación (5 min)")
            }
        }
        startupLogRunnable = runnable
        mainHandler.postDelayed(runnable, STARTUP_LOG_DELAY_MS)
        Log.d(TAG, "⏰ Programado envío de log de arranque en ${STARTUP_LOG_DELAY_MS / 1000}s con test de velocidad automático.")
    }

    /**
     * Cancela el envío del log automático de arranque si aún estaba pendiente.
     */
    fun cancelarLogArranque() {
        startupLogRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            startupLogRunnable = null
            Log.d(TAG, "🛑 Cancelado log automático de arranque programado.")
        }
    }

    private fun ejecutarTestVelocidadArranque() {
        if (testVelocidadEnProgreso) return
        testVelocidadEnProgreso = true
        executor.execute {
            try {
                Log.d(TAG, "🌐 Ejecutando test de velocidad automático de arranque...")
                val resultado = kotlinx.coroutines.runBlocking {
                    SpeedTestHelper.ejecutarTest()
                }
                testVelocidadArranque = resultado
                Log.d(TAG, "✅ Test de velocidad de arranque completado:\n${resultado.resumenTexto()}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en test de velocidad de arranque: ${e.message}")
            } finally {
                testVelocidadEnProgreso = false
            }
        }
    }

    /**
     * Ejecuta síncronamente un test de velocidad y actualiza los resultados guardados.
     * Útil para logs manuales donde se requiere información en tiempo real.
     */
    fun ejecutarYGuardarTestVelocidad(): SpeedTestHelper.ResultadoSpeedTest = kotlinx.coroutines.runBlocking {
        Log.d(TAG, "🌐 Ejecutando test de velocidad actualizado para log manual...")
        val resultado = SpeedTestHelper.ejecutarTest()
        testVelocidadArranque = resultado
        resultado
    }

    /**
     * Obtiene el estado de la red e internet junto con los resultados del test de velocidad
     * ejecutado al arranque para incluirlo en el resumen del log.
     */
    fun obtenerResumenRedYVelocidad(context: Context): String {
        val status = com.sybi.mosi.network.ConnectivityStatusProvider.get(context)
        val sb = StringBuilder()

        val redTipo = when {
            status.ethernetConnected -> "ETHERNET"
            status.wifiConnected -> "WIFI${if (status.ssid != null) " (${status.ssid})" else ""}"
            else -> "SIN CONEXIÓN (NINGUNA)"
        }
        sb.appendLine("Tipo de conexión: $redTipo")
        if (status.wifiConnected && status.signalDbm != null) {
            sb.appendLine("Señal WiFi: ${status.signalDbm} dBm (Nivel ${status.signalLevel ?: 0}/4)")
        }
        sb.appendLine("Acceso a Internet (Validado): ${if (status.internetAvailable) "SÍ" else "NO"}")
        if (status.interfaceName != null || status.localIp != null) {
            sb.appendLine("Interfaz local: ${status.interfaceName ?: "N/A"} (${status.localIp ?: "N/A"})")
        }

        val test = testVelocidadArranque
        if (test != null) {
            sb.appendLine("IP pública: ${test.ipPublica ?: "No disponible"}")
            sb.appendLine("Latencia: ${test.latenciaMs?.let { "$it ms" } ?: "No disponible"}")
            sb.appendLine("Bajada: ${test.bajadaMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "No disponible"}")
            sb.appendLine("Subida: ${test.subidaMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "No disponible"}")
            if (test.error != null) {
                sb.appendLine("Nota SpeedTest: ${test.error}")
            }
        } else if (testVelocidadEnProgreso) {
            sb.appendLine("Test de velocidad: En progreso...")
        } else {
            ejecutarTestVelocidadArranque()
            sb.appendLine("Test de velocidad: No ejecutado aún (Iniciado ahora)")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Realiza una prueba rápida y segura de conectividad y consulta (lectura) a la API REST de sybiml.
     */
    fun obtenerResumenServicioWeb(): String = kotlinx.coroutines.runBlocking {
        val sb = StringBuilder()
        val latenciaSocket = com.sybi.mosi.network.NetworkSpeedHelper.medirLatenciaSocket("www.sybiml.com", 443, 3000)
        if (latenciaSocket >= 0) {
            sb.appendLine("• Latencia Servidor (www.sybiml.com:443): ${latenciaSocket} ms")
        } else {
            sb.appendLine("• Latencia Servidor (www.sybiml.com:443): Sin respuesta / Timeout")
        }

        val inicio = System.currentTimeMillis()
        try {
            val response = com.sybi.mosi.network.RetrofitClient.apiService.obtenerClientes()
            val tiempoMs = System.currentTimeMillis() - inicio
            if (response.isSuccessful) {
                val totalClientes = response.body()?.clientes?.size ?: 0
                sb.appendLine("• Consulta API REST (GET /clientes): OK (HTTP ${response.code()}) en ${tiempoMs} ms [$totalClientes registros]")
            } else {
                sb.appendLine("• Consulta API REST (GET /clientes): RESPUESTA HTTP ${response.code()} en ${tiempoMs} ms")
            }
        } catch (e: Exception) {
            val tiempoMs = System.currentTimeMillis() - inicio
            sb.appendLine("• Consulta API REST (GET /clientes): ERROR (${e.message ?: "Sin respuesta"}) [${tiempoMs} ms]")
        }

        sb.toString().trimEnd()
    }

    /**
     * Disparar el envío de log automático al salir de la pantalla de resultados (Fin de medición).
     */
    fun enviarLogFinMedicion(context: Context) {
        cancelarLogArranque()
        enviarLogConTimestampCheck(context, "Fin de medición", "fin_medicion", lastMedicionLogTime)
    }

    /**
     * Disparar el envío de log automático al finalizar una llamada de telemedicina.
     */
    fun enviarLogFinTeleconsulta(context: Context) {
        cancelarLogArranque()
        enviarLogConTimestampCheck(context, "Fin de teleconsulta", "fin_teleconsulta", lastTeleconsultaLogTime)
    }

    /**
     * Compatibilidad hacia atrás.
     */
    fun enviarLogFinConsulta(context: Context) {
        enviarLogFinTeleconsulta(context)
    }

    private fun enviarLogConTimestampCheck(context: Context, motivo: String, tipoLog: String, atomicTime: AtomicLong) {
        val now = System.currentTimeMillis()
        val last = atomicTime.get()
        if (now - last < LOG_COOLDOWN_MS) {
            Log.d(TAG, "Omitiendo envío de log ($motivo) por cooldown (${(now - last) / 1000}s)")
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            if (atomicTime.compareAndSet(last, System.currentTimeMillis())) {
                enviarLogSiCorresponde(appContext, motivo, tipoLog)
            }
        }
    }

    private fun enviarLogSiCorresponde(context: Context, motivo: String, tipoLog: String = "auto") {
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
        val tagArchivo = if (tipoLog != "auto") tipoLog else when {
            motivo.contains("Arranque", ignoreCase = true) -> "arranque"
            motivo.contains("teleconsulta", ignoreCase = true) -> "fin_teleconsulta"
            motivo.contains("medición", ignoreCase = true) || motivo.contains("medicion", ignoreCase = true) -> "fin_medicion"
            else -> "auto"
        }
        val archivo = ErrorLogHelper.generarLogDeErrores(context, tagArchivo)

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
                nombreAdjunto = archivo.name
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
