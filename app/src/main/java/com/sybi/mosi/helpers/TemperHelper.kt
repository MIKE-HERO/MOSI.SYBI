package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class TemperHelper(private val context: Context) {

    companion object {
        private const val TAG = "TemperHelper"
        private val TM512_BODY = Regex("Body:\\s*(\\d{1,2}(?:\\.\\d+)?)")

        /**
         * Termómetro Langshi TM512 (según el SDK del fabricante, TemperLSTM512Helper): envía TEXTO, no tramas
         * binarias, por ejemplo "…Body:36.52…\r". Devuelve la temperatura en °C redondeada a 1 decimal,
         * o null si los datos no tienen ese formato.
         */
        fun parseTm512Celsius(data: ByteArray): Double? {
            val text = String(data, Charsets.US_ASCII)
            val value = TM512_BODY.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return Math.round(value * 10) / 10.0
        }
    }

    var lastTemperature: Double? = null
    var lastTemperatureF: Double? = null
    var lastBodyMode: String = ""

    fun parseData(data: ByteArray) {
        // Formato de texto del TM512 ("Body:36.5")
        parseTm512Celsius(data)?.let { celsius ->
            Log.d(TAG, "🌡️ TM512 (texto): ${String(data, Charsets.US_ASCII).trim()}")
            publish(celsius, "Body")
            return
        }

        // Formato binario (trama de 7 bytes, inicio 5A / A5)
        if (data.size < 7) return

        val hexString = bytesToHex(data)
        val bytes = hexString.split(" ")

        if (bytes.size >= 7) {
            try {
                val byte3 = bytes[3].toInt(16)
                val byte4 = bytes[4].toInt(16)
                val tempValue = ((byte4 shl 8) or byte3)

                val mode = when (bytes[0].toInt(16)) {
                    0x5A -> "Body"
                    0xA5 -> "Surface"
                    else -> ""
                }
                publish(tempValue / 100.0, mode)
            } catch (e: Exception) {
                Log.e(TAG, "Error al parsear temperatura: ${e.message}")
            }
        }
    }

    private fun publish(celsius: Double, mode: String) {
        lastTemperature = celsius
        lastTemperatureF = (celsius * 9 / 5) + 32
        lastBodyMode = mode

        if (celsius in 20.0..45.0) {
            val intent = Intent("DEVICE_DATA_RECEIVED")
            intent.putExtra("type", "TEMPERATURA")
            intent.putExtra("temperature", celsius)
            intent.putExtra("temperature_f", lastTemperatureF!!)
            intent.putExtra("mode", mode)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

            Log.d(TAG, "✅ Temperatura: ${celsius}°C")
        } else {
            Log.w(TAG, "⚠️ Temperatura fuera de rango (20–45 °C), se ignora: $celsius")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun resetAllResults() {
        lastTemperature = null
        lastTemperatureF = null
        lastBodyMode = ""
    }
}