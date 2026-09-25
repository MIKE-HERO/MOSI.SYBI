package com.sybi.mosi.measurement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Gestiona el estado de conexión de dispositivos USB/serial.
 * Notifica cambios a quien lo use mediante un callback.
 */
class DeviceConnectionManager(
    private val context: Context,
    private val onStatusChanged: () -> Unit
) {
    companion object {
        private const val TAG = "DeviceConnectionManager"
    }

    var serialConnected = false
        private set
    var oxygenReady = false
        private set
    var oxygenPresent = false
        private set

    private var deviceCheckReceiver: BroadcastReceiver? = null
    private var usbStatusReceiver: BroadcastReceiver? = null

    fun register() {
        deviceCheckReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "CONNECTED_DEVICES_RESPONSE") {
                    val serialCount = intent.getIntExtra("serial_count", 0)
                    serialConnected = serialCount > 0
                    oxygenReady = intent.getBooleanExtra("oxygen_ready", false)
                    oxygenPresent = intent.getBooleanExtra("oxygen_present", false)

                    Log.d(TAG, "🔌 Serial=$serialConnected, O2Ready=$oxygenReady, O2Present=$oxygenPresent")
                    onStatusChanged()
                }
            }
        }

        usbStatusReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == "USB_DEVICE_STATUS_CHANGED") {
                    query()
                }
            }
        }

        LocalBroadcastManager.getInstance(context).apply {
            registerReceiver(deviceCheckReceiver!!, IntentFilter("CONNECTED_DEVICES_RESPONSE"))
            registerReceiver(usbStatusReceiver!!, IntentFilter("USB_DEVICE_STATUS_CHANGED"))
        }
    }

    fun unregister() {
        LocalBroadcastManager.getInstance(context).apply {
            deviceCheckReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
            usbStatusReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        }
    }

    fun query() {
        LocalBroadcastManager.getInstance(context)
            .sendBroadcast(Intent("QUERY_CONNECTED_DEVICES"))
    }

    /**
     * ¿El dispositivo para este tipo de medición está disponible?
     */
    fun isAvailableFor(commandType: String): Boolean = when (commandType) {
        "OXIGENO" -> oxygenReady || oxygenPresent
        "ALTURA_PESO", "COMPOSICION", "PRESION", "TEMPERATURA" -> serialConnected
        "ECG" -> true
        else -> false
    }
}