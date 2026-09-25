package com.sybi.mosi.measurement

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Orquesta el flujo de mediciones: envía comandos, maneja timers,
 * coordina oxígeno y notifica a la UI mediante callbacks.
 */
class MeasurementController(
    private val context: Context,
    private val state: MeasurementState,
    private val deviceManager: DeviceConnectionManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatusMessage(message: String, color: String)
        fun onMeasurementComplete()
        fun onMeasurementError()
        fun onButtonVisibilityChanged()
        fun onOxygenResult(spo2: Int, pulseRate: Int, pi: Double)
    }

    companion object {
        private const val TAG = "MeasurementController"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var resultRunnable: Runnable? = null
    private var errorRunnable: Runnable? = null
    private var initializationRunnable: Runnable? = null
    private var statusMessageRunnable: Runnable? = null

    private val commandSentMap = mutableMapOf<String, Boolean>()
    private var isFirstEntry = true

    fun reset() {
        isFirstEntry = true
        commandSentMap.clear()
    }

    fun sendCommand(commandType: String, delayMs: Long = 3000) {
        if (commandSentMap[commandType] == true) return

        initializationRunnable?.let { handler.removeCallbacks(it) }
        initializationRunnable = Runnable {
            // ✅ Validar AQUÍ, después del delay, cuando ya llegó la respuesta del query
            if (!deviceManager.isAvailableFor(commandType)) {
                callbacks.onStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
                return@Runnable
            }
            val intent = Intent("SEND_READ_COMMAND").putExtra("command_type", commandType)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            commandSentMap[commandType] = true
            Log.d(TAG, "📨 Comando enviado: $commandType")
        }
        handler.postDelayed(initializationRunnable!!, delayMs)
    }

    fun forceSendCommand(commandType: String, delayMs: Long = 3000) {
        commandSentMap.remove(commandType)
        sendCommand(commandType, delayMs)
    }

    fun startOxygenMeasurement(delayMs: Long = 3000, timeoutMs: Long = 30000) {
        if (!deviceManager.isAvailableFor("OXIGENO")) {
            callbacks.onStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
            return
        }

        resetOxygenState()
        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("RESET_OXYGEN_MEASURE"))

        callbacks.onStatusMessage("Inicializando oxímetro...", "#FF9800")

        initializationRunnable?.let { handler.removeCallbacks(it) }
        initializationRunnable = Runnable {
            if (!deviceManager.isAvailableFor("OXIGENO")) {
                callbacks.onStatusMessage("Error: no se encuentra el dispositivo", "#F44336")
                return@Runnable
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("START_OXYGEN_MEASURE"))
            commandSentMap["OXIGENO"] = true
            callbacks.onStatusMessage("Coloque su dedo en el oxímetro", "#FF9800")

            resultRunnable = object : Runnable {
                override fun run() {
                    if (state.hasPendingResult) {
                        callbacks.onOxygenResult(state.pendingSpO2, state.pendingPulseRate, state.pendingPI)
                        LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("STOP_OXYGEN_MEASURE"))
                        callbacks.onMeasurementComplete()
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            handler.postDelayed(resultRunnable!!, 1000)

            errorRunnable = Runnable {
                if (!state.hasPendingResult) {
                    LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("STOP_OXYGEN_MEASURE"))
                    callbacks.onStatusMessage("Error de medición, favor de repetir", "#F44336")
                    callbacks.onMeasurementError()
                }
            }
            handler.postDelayed(errorRunnable!!, timeoutMs)
        }
        handler.postDelayed(initializationRunnable!!, delayMs)
    }

    fun resetOxygenState() {
        state.hasPendingResult = false
        state.pendingSpO2 = 0
        state.pendingPulseRate = 0
        state.pendingPI = 0.0
    }

    fun cancelTimers() {
        resultRunnable?.let { handler.removeCallbacks(it) }
        errorRunnable?.let { handler.removeCallbacks(it) }
        initializationRunnable?.let { handler.removeCallbacks(it) }
        statusMessageRunnable?.let { handler.removeCallbacks(it) }
    }

    fun postStatusDelayed(message: String, color: String, delayMs: Long) {
        statusMessageRunnable?.let { handler.removeCallbacks(it) }
        statusMessageRunnable = Runnable { callbacks.onStatusMessage(message, color) }
        handler.postDelayed(statusMessageRunnable!!, delayMs)
    }

    fun postDelayed(action: () -> Unit, delayMs: Long) {
        handler.postDelayed(action, delayMs)
    }

    fun destroy() {
        cancelTimers()
    }
}