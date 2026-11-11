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

    // Buffer para el Maibobo
    private var buffer = ByteArray(0)

    fun parseData(data: ByteArray) {
        // 1. DETECCIÓN DEL BAUMANÓMETRO VIEJO (Empieza con D0 C2)
        if (data.size >= 8 && data[0] == 0xD0.toByte() && data[1] == 0xC2.toByte()) {
            try {
                val systolic = data[4].toInt() and 0xFF
                val diastolic = data[5].toInt() and 0xFF
                val pulse = data[6].toInt() and 0xFF

                if (systolic in 60..250 && diastolic in 30..180 && pulse in 30..200) {
                    enviarResultado(systolic, diastolic, pulse, "Viejo")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al parsear presión vieja: ${e.message}")
            }
            return // Si era el viejo, terminamos aquí
        }

        // 2. DETECCIÓN DEL MAIBOBO (Empieza con AA 80)
        buffer += data

        var index = 0
        while (index < buffer.size) {
            if (buffer[index] == 0xAA.toByte()) {
                if (buffer.size < index + 4) break

                if (buffer[index + 1] == 0x80.toByte() && buffer[index + 2] == 0x03.toByte()) {
                    val size = buffer[index + 3].toInt() and 0xFF

                    if (buffer.size < index + size + 5) break

                    var sum = 3 xor size
                    for (i in 0 until size) {
                        sum = sum xor (buffer[index + i + 4].toInt() and 0xFF)
                    }
                    val cks = buffer[index + size + 4].toInt() and 0xFF

                    if ((sum and 0xFF) == cks) {
                        val type1 = buffer[index + 4].toInt() and 0xFF
                        val type2 = buffer[index + 5].toInt() and 0xFF

                        if (size == 15 && type1 == 1 && type2 == 6) {
                            val sys1 = buffer[index + 13].toInt() and 0xFF
                            val sys2 = buffer[index + 14].toInt() and 0xFF
                            val dia1 = buffer[index + 15].toInt() and 0xFF
                            val dia2 = buffer[index + 16].toInt() and 0xFF
                            val pul1 = buffer[index + 17].toInt() and 0xFF
                            val pul2 = buffer[index + 18].toInt() and 0xFF

                            val systolic = (sys1 shl 8) or sys2
                            val diastolic = (dia1 shl 8) or dia2
                            val pulse = (pul1 shl 8) or pul2

                            if (systolic in 60..250 && diastolic in 30..180 && pulse in 30..200) {
                                enviarResultado(systolic, diastolic, pulse, "Maibobo")
                            }
                        }

                        buffer = buffer.copyOfRange(index + size + 5, buffer.size)
                        index = 0
                        continue
                    }
                }
            }
            index++
        }

        if (buffer.size > 1024) {
            buffer = ByteArray(0)
        }
    }

    private fun enviarResultado(sys: Int, dia: Int, pul: Int, modelo: String) {
        lastSystolic = sys
        lastDiastolic = dia
        lastPulse = pul

        val intent = Intent("DEVICE_DATA_RECEIVED")
        intent.putExtra("type", "PRESION")
        intent.putExtra("systolic", sys)
        intent.putExtra("diastolic", dia)
        intent.putExtra("pulse", pul)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

        Log.d(TAG, "✅ Presión recibida ($modelo): $sys/$dia, Pulso: $pul")
    }

    fun resetAllResults() {
        lastSystolic = null
        lastDiastolic = null
        lastPulse = null
        buffer = ByteArray(0)
    }
}