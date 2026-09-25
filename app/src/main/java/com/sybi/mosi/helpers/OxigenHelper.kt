package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class OxigenHelper(private val context: Context) {

    companion object {
        private const val TAG = "OxigenHelper"
        private const val MIN_RESULTS_TO_CONFIRM = 15 // ✅ Cambiado a 15 pulsos
    }

    var lastSpO2: Int? = null
    var lastPulseRate: Int? = null
    var lastPI: Double? = null

    private val dataBuffer = mutableListOf<Byte>()
    private val MAX_BUFFER_SIZE = 512

    // Lista para acumular resultados
    private val resultList = mutableListOf<IntArray>() // [SpO2, PulseRate, PI*10]

    fun parseData(data: ByteArray) {
        try {
            dataBuffer.addAll(data.toList())

            if (dataBuffer.size > MAX_BUFFER_SIZE) {
                dataBuffer.clear()
                return
            }

            var found = true
            while (found) {
                found = false

                val startIndex = findPacketStart()

                if (startIndex >= 0) {
                    if (startIndex > 0) {
                        for (i in 0 until startIndex) dataBuffer.removeAt(0)
                    }

                    if (dataBuffer.size >= 3) {
                        val packetType = dataBuffer[2].toInt() and 0xFF

                        when (packetType) {
                            0x51 -> {
                                if (dataBuffer.size >= 7) {
                                    val packet = dataBuffer.subList(0, 7).toByteArray()
                                    processStatusPacket(packet)
                                    for (i in 0 until 7) dataBuffer.removeAt(0)
                                    found = true
                                }
                            }
                            0x52 -> {
                                if (dataBuffer.size >= 7) {
                                    val packet = dataBuffer.subList(0, 7).toByteArray()
                                    processWavePacket(packet)
                                    for (i in 0 until 7) dataBuffer.removeAt(0)
                                    found = true
                                }
                            }
                            0x53 -> {
                                if (dataBuffer.size >= 11) {
                                    val packet = dataBuffer.subList(0, 11).toByteArray()
                                    processResultPacket(packet)
                                    for (i in 0 until 11) dataBuffer.removeAt(0)
                                    found = true
                                }
                            }
                            else -> {
                                if (dataBuffer.isNotEmpty()) dataBuffer.removeAt(0)
                                found = true
                            }
                        }
                    }
                } else {
                    if (dataBuffer.isNotEmpty()) {
                        val firstAA = dataBuffer.indexOfFirst { it.toInt() and 0xFF == 0xAA }
                        if (firstAA > 0) {
                            for (i in 0 until firstAA) dataBuffer.removeAt(0)
                            found = true
                        } else if (firstAA == -1) {
                            dataBuffer.clear()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear: ${e.message}")
            dataBuffer.clear()
        }
    }

    private fun findPacketStart(): Int {
        for (i in 0 until dataBuffer.size - 1) {
            if ((dataBuffer[i].toInt() and 0xFF) == 0xAA &&
                (dataBuffer[i + 1].toInt() and 0xFF) == 0x55) {
                return i
            }
        }
        return -1
    }

    private fun processStatusPacket(packet: ByteArray) {
        if (packet.size < 7) return
        val state = packet[5].toInt() and 0xFF
        when {
            (state and 0x04) == 0x04 -> Log.d(TAG, "Oxímetro: Verificar sonda")
            (state and 0x08) == 0x08 -> Log.d(TAG, "Oxímetro: Sonda desconectada")
            (state and 0x10) == 0x10 -> Log.d(TAG, "Oxímetro: Sonda desconectada (reservado)")
            else -> Log.d(TAG, "Oxímetro: Estado OK")
        }
    }

    private fun processWavePacket(packet: ByteArray) {
        if (packet.size >= 7) {
            val waveValue = packet[5].toInt() and 0xFF
            val waveIntent = Intent("OXYGEN_WAVE_DATA")
            waveIntent.putExtra("value", waveValue)
            LocalBroadcastManager.getInstance(context).sendBroadcast(waveIntent)
        }
    }

    private fun processResultPacket(packet: ByteArray) {
        if (packet.size < 11) return

        val spO2 = packet[5].toInt() and 0xFF
        val pulseRate = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        val pi = packet[8].toInt() and 0xFF

        Log.d(TAG, "📥 Paquete de resultado: SpO2=$spO2, Pulse=$pulseRate, PI=$pi")

        if (spO2 in 35..100 && pulseRate in 25..300) {
            resultList.add(intArrayOf(spO2, pulseRate, pi))
            Log.d(TAG, "📊 Resultados acumulados: ${resultList.size}/$MIN_RESULTS_TO_CONFIRM")

            if (resultList.size >= MIN_RESULTS_TO_CONFIRM) {
                val avgSpO2 = resultList.map { it[0] }.average().toInt()
                val avgPulse = resultList.map { it[1] }.average().toInt()
                val avgPI = resultList.map { it[2] }.average() / 10.0

                lastSpO2 = avgSpO2
                lastPulseRate = avgPulse
                lastPI = avgPI

                Log.d(TAG, "✅ CONFIRMADO: SpO2=$avgSpO2%, PR=$avgPulse bpm, PI=$avgPI")

                val intent = Intent("DEVICE_DATA_RECEIVED")
                intent.putExtra("type", "OXIGENO")
                intent.putExtra("spo2", avgSpO2)
                intent.putExtra("pulse_rate", avgPulse)
                intent.putExtra("pi", avgPI)
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

                val resultIntent = Intent("OXYGEN_RESULT_RECEIVED")
                LocalBroadcastManager.getInstance(context).sendBroadcast(resultIntent)

                resultList.clear()
            }
        }
    }

    // ✅ FUNCIÓN NUEVA: Limpiar todo para una nueva medición
    fun resetForNewMeasurement() {
        Log.d(TAG, "🧹 Limpiando datos para nueva medición")
        lastSpO2 = null
        lastPulseRate = null
        lastPI = null
        dataBuffer.clear()
        resultList.clear()
    }

    fun resetAllResults() {
        resetForNewMeasurement()
    }
}