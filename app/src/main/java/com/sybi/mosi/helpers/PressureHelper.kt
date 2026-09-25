package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class PressureHelper(private val context: Context) {

    companion object {
        private const val TAG = "PressureHelper"
    }

    var lastSystolic: Int? = null
    var lastDiastolic: Int? = null
    var lastPulse: Int? = null

    fun parseData(data: ByteArray) {
        val hexString = bytesToHex(data)
        val bytes = hexString.split(" ")

        if (bytes.size >= 8 && bytes[0].equals("D0", ignoreCase = true) && bytes[1].equals("C2", ignoreCase = true)) {
            try {
                val systolic = bytes[4].toInt(16)
                val diastolic = bytes[5].toInt(16)
                val pulse = bytes[6].toInt(16)

                if (systolic in 60..250 && diastolic in 30..180 && pulse in 30..200) {
                    lastSystolic = systolic
                    lastDiastolic = diastolic
                    lastPulse = pulse

                    val intent = Intent("DEVICE_DATA_RECEIVED")
                    intent.putExtra("type", "PRESION")
                    intent.putExtra("systolic", systolic)
                    intent.putExtra("diastolic", diastolic)
                    intent.putExtra("pulse", pulse)
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

                    Log.d(TAG, "✅ Presión: $systolic/$diastolic")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al parsear presión: ${e.message}")
            }
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun resetAllResults() {
        lastSystolic = null
        lastDiastolic = null
        lastPulse = null
    }
}