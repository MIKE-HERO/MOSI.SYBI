package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class OxigenHelper(private val context: Context) {

    companion object {
        private const val TAG = "OxigenHelper"

        /** Nº de resultados válidos y estables necesarios para confirmar la medición. */
        private const val MIN_RESULTS_TO_CONFIRM = 6

        /** Tamaño máximo del buffer de bytes pendientes. */
        private const val MAX_BUFFER_SIZE = 512

        /** Máxima variación permitida (max-min) dentro de la ventana de resultados. */
        private const val MAX_SPO2_SPREAD = 10
        private const val MAX_PR_SPREAD = 10

        /** Tope de seguridad para evitar crecimiento ilimitado de la lista. */
        private const val MAX_RESULTS_HARD_LIMIT = MIN_RESULTS_TO_CONFIRM * 3

        /** Tabla CRC8 del protocolo del oxímetro (idéntica al SDK oficial). */
        private val CRC8_TABLE = intArrayOf(
            0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
            157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
            35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
            190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
            70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
            219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
            101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
            248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
            140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
            17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
            175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
            50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
            202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
            87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
            233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
            116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
        )
    }

    // ─────────────────────────── Estado público ───────────────────────────

    var lastSpO2: Int? = null
        private set
    var lastPulseRate: Int? = null
        private set
    var lastPI: Double? = null
        private set

    // ─────────────────────────── Estado interno ───────────────────────────

    private val dataBuffer = mutableListOf<Byte>()
    private var hasFingerDetected = false
    private var measurementStartTime = System.currentTimeMillis()

    /** Resultados válidos acumulados: [SpO2, PulseRate, PI] */
    private val resultList = mutableListOf<IntArray>()

    // Ventana de estabilidad
    private var maxSpO2 = 0
    private var minSpO2 = Int.MAX_VALUE
    private var maxPR = 0
    private var minPR = Int.MAX_VALUE

    // ─────────────────────────── API pública ───────────────────────────

    fun parseData(data: ByteArray) {
        try {
            dataBuffer.addAll(data.toList())

            if (dataBuffer.size > MAX_BUFFER_SIZE) {
                Log.w(TAG, "⚠️ Buffer excedido, limpiando")
                dataBuffer.clear()
                return
            }

            var found = true
            while (found) {
                found = false

                val startIndex = findPacketStart()

                if (startIndex >= 0) {
                    if (startIndex > 0) {
                        repeat(startIndex) { dataBuffer.removeAt(0) }
                    }

                    if (dataBuffer.size >= 3) {
                        val packetType = dataBuffer[2].toInt() and 0xFF

                        when (packetType) {
                            0x51 -> {
                                if (dataBuffer.size >= 7) {
                                    val packet = dataBuffer.subList(0, 7).toByteArray()
                                    processStatusPacket(packet)
                                    repeat(7) { dataBuffer.removeAt(0) }
                                    found = true
                                }
                            }
                            0x52 -> {
                                if (dataBuffer.size >= 7) {
                                    val packet = dataBuffer.subList(0, 7).toByteArray()
                                    processWavePacket(packet)
                                    repeat(7) { dataBuffer.removeAt(0) }
                                    found = true
                                }
                            }
                            0x53 -> {
                                if (dataBuffer.size >= 11) {
                                    val packet = dataBuffer.subList(0, 11).toByteArray()
                                    processResultPacket(packet)
                                    repeat(11) { dataBuffer.removeAt(0) }
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
                            repeat(firstAA) { dataBuffer.removeAt(0) }
                            found = true
                        } else if (firstAA == -1) {
                            dataBuffer.clear()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al parsear: ${e.message}", e)
            dataBuffer.clear()
        }
    }

    /** Limpia todo el estado para iniciar una nueva medición. */
    fun resetForNewMeasurement() {
        Log.d(TAG, "🧹 Limpiando datos para nueva medición")
        lastSpO2 = null
        lastPulseRate = null
        lastPI = null
        hasFingerDetected = false
        measurementStartTime = System.currentTimeMillis()
        dataBuffer.clear()
        resetWindow()
    }

    fun resetAllResults() {
        resetForNewMeasurement()
    }

    // ─────────────────────────── Parseo de paquetes ───────────────────────────

    private fun findPacketStart(): Int {
        for (i in 0 until dataBuffer.size - 1) {
            if ((dataBuffer[i].toInt() and 0xFF) == 0xAA &&
                (dataBuffer[i + 1].toInt() and 0xFF) == 0x55
            ) {
                return i
            }
        }
        return -1
    }

    private fun processStatusPacket(packet: ByteArray) {
        if (packet.size < 7) return

        // Validar CRC (byte 6) sobre bytes 0..5
        if (!validateCrc(packet, dataEnd = 5, crcIndex = 6)) {
            Log.w(TAG, "⚠️ 0x51 CRC inválido, descartado")
            return
        }

        val elapsed = System.currentTimeMillis() - measurementStartTime
        val state = packet[5].toInt() and 0xFF
        when {
            (state and 0x04) == 0x04 || (state and 0x08) == 0x08 || (state and 0x10) == 0x10 -> {
                hasFingerDetected = false
                if (elapsed >= 3000) {
                    Log.d(TAG, "Oxímetro: Sonda desconectada")
                }
            }
            else -> {
                hasFingerDetected = true
            }
        }
    }

    private fun processWavePacket(packet: ByteArray) {
        if (packet.size < 7) return

        if (!validateCrc(packet, dataEnd = 5, crcIndex = 6)) {
            // El wave es solo visual; no hace falta spamear logs por CRC inválido
            return
        }

        val elapsed = System.currentTimeMillis() - measurementStartTime
        val waveValue = if (elapsed < 3000) {
            128 // Línea recta durante los primeros 3 segundos
        } else {
            packet[5].toInt() and 0xFF // Datos reales después de 3 segundos
        }

        val waveIntent = Intent("OXYGEN_WAVE_DATA")
        waveIntent.putExtra("value", waveValue)
        LocalBroadcastManager.getInstance(context).sendBroadcast(waveIntent)
    }

    private fun processResultPacket(packet: ByteArray) {
        if (packet.size < 11) return

        // 1) CRC8 sobre bytes 0..9, comparado con byte 10
        if (!validateCrc(packet, dataEnd = 9, crcIndex = 10)) {
            Log.w(TAG, "⚠️ 0x53 CRC inválido, descartado")
            return
        }

        // 2) Estado del sensor (byte 9): solo aceptamos 0x00 (DETECTING)
        val elapsed = System.currentTimeMillis() - measurementStartTime
        val state = packet[9].toInt() and 0xFF
        if (state != 0x00) {
            hasFingerDetected = false
            if (elapsed >= 3000) {
                Log.d(TAG, "⚠️ 0x53 descartado por estado del sensor: 0x${state.toString(16)}")
                resetWindow()
            }
            return
        }

        val spO2 = packet[5].toInt() and 0xFF
        val pulseRate = (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
        val pi = packet[8].toInt() and 0xFF

        Log.d(TAG, "📥 Paquete de resultado: SpO2=$spO2, Pulse=$pulseRate, PI=$pi")

        // 3) Rango absoluto (descarta 0/0/0 y valores absurdos)
        if (spO2 !in 35..100 || pulseRate !in 25..300) {
            Log.d(TAG, "⚠️ Fuera de rango, se resetea ventana")
            hasFingerDetected = false
            resetWindow()
            return
        }

        hasFingerDetected = true

        // 4) Filtro de estabilidad (max-min dentro de la ventana)
        if (resultList.isNotEmpty()) {
            if (spO2 > maxSpO2 + MAX_SPO2_SPREAD || spO2 < minSpO2 - MAX_SPO2_SPREAD ||
                pulseRate > maxPR + MAX_PR_SPREAD || pulseRate < minPR - MAX_PR_SPREAD
            ) {
                Log.d(TAG, "⚠️ Salto brusco (SpO2=$spO2, PR=$pulseRate), se resetea ventana")
                resetWindow()
                // Reintroducimos este dato como primera muestra de la nueva ventana
            }
        }

        // Actualizar ventana y acumular
        if (spO2 > maxSpO2) maxSpO2 = spO2
        if (spO2 < minSpO2) minSpO2 = spO2
        if (pulseRate > maxPR) maxPR = pulseRate
        if (pulseRate < minPR) minPR = pulseRate

        resultList.add(intArrayOf(spO2, pulseRate, pi))

        val spreadSpO2 = maxSpO2 - minSpO2
        val spreadPR = maxPR - minPR
        Log.d(
            TAG,
            "📊 Resultados acumulados: ${resultList.size}/$MIN_RESULTS_TO_CONFIRM " +
                    "(spread SpO2=$spreadSpO2, PR=$spreadPR)"
        )

        // Tope de seguridad
        if (resultList.size > MAX_RESULTS_HARD_LIMIT) {
            Log.w(TAG, "⚠️ Lista de resultados excedida, reseteando ventana")
            resetWindow()
            return
        }

        // 5) Confirmar si hay suficientes muestras y son estables
        if (resultList.size >= MIN_RESULTS_TO_CONFIRM) {
            if (spreadSpO2 > MAX_SPO2_SPREAD || spreadPR > MAX_PR_SPREAD) {
                Log.d(TAG, "⚠️ Ventana inestable, se resetea")
                resetWindow()
                return
            }

            val avgSpO2 = resultList.map { it[0] }.average().toInt()
            val avgPulse = resultList.map { it[1] }.average().toInt()
            val avgPI = resultList.map { it[2] }.average() / 10.0

            lastSpO2 = avgSpO2
            lastPulseRate = avgPulse
            lastPI = avgPI

            Log.d(TAG, "✅ CONFIRMADO: SpO2=$avgSpO2%, PR=$avgPulse bpm, PI=$avgPI")

            val intent = Intent("DEVICE_DATA_RECEIVED").apply {
                putExtra("type", "OXIGENO")
                putExtra("spo2", avgSpO2)
                putExtra("pulse_rate", avgPulse)
                putExtra("pi", avgPI)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

            LocalBroadcastManager.getInstance(context)
                .sendBroadcast(Intent("OXYGEN_RESULT_RECEIVED"))

            resetWindow()
        }
    }

    // ─────────────────────────── Utilidades ───────────────────────────

    /**
     * Valida el CRC8 del paquete.
     * @param packet bytes del paquete
     * @param dataEnd índice del último byte de datos (inclusive) sobre el que se calcula
     * @param crcIndex índice del byte que contiene el CRC recibido
     */
    private fun validateCrc(packet: ByteArray, dataEnd: Int, crcIndex: Int): Boolean {
        if (packet.size <= crcIndex || packet.size <= dataEnd) return false
        val received = packet[crcIndex].toInt() and 0xFF
        val computed = crc8CheckValue(packet, 0, dataEnd)
        return received == computed
    }

    /**
     * CRC8 con la tabla específica del protocolo del oxímetro.
     * Idéntico al método `crc8CheckValue` del SDK oficial.
     */
    private fun crc8CheckValue(bs: ByteArray, start: Int, end: Int): Int {
        if (bs.isEmpty() || bs.size <= end || start > end || start < 0) return 0
        var crc = 0
        for (i in start..end) {
            val idx = ((bs[i].toInt() and 0xFF) xor crc) and 0xFF
            crc = if (idx >= CRC8_TABLE.size) 0 else CRC8_TABLE[idx]
        }
        return crc
    }

    private fun resetWindow() {
        resultList.clear()
        maxSpO2 = 0
        minSpO2 = Int.MAX_VALUE
        maxPR = 0
        minPR = Int.MAX_VALUE
    }
}