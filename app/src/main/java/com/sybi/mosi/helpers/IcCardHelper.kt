package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class IcCardHelper(private val context: Context) {

    companion object {
        private const val TAG = "IcCardHelper"
    }

    fun parseData(data: ByteArray) {
        if (data.isEmpty()) return
        Log.d(TAG, "💳 Lector IC RX (${data.size} bytes): ${bytesToHex(data)}")

        // ===== Trama de 4 bytes (sin checksum) =====
        if (data.size == 4) {
            try {
                val cardId = bytesToCardId(data, 0)
                Log.d(TAG, "✅ Tarjeta IC procesada (4 bytes, sin checksum): $cardId")
                broadcastCard(cardId)
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando 4 bytes de tarjeta: ${e.message}")
            }
            return
        }

        // ===== Trama de 5 bytes (con checksum = suma de los primeros 4 bytes, mod 256) =====
        if (data.size == 5) {
            try {
                val suma = (0 until 4).sumOf { data[it].toInt() and 0xFF } and 0xFF
                val checksumRecibido = data[4].toInt() and 0xFF

                if (suma != checksumRecibido) {
                    Log.w(
                        TAG,
                        "⚠️ Checksum inválido: esperado=%02X recibido=%02X trama=%s".format(
                            suma, checksumRecibido, bytesToHex(data)
                        )
                    )
                    return
                }

                val cardId = bytesToCardId(data, 0)
                Log.d(TAG, "✅ Tarjeta IC procesada (5 bytes, checksum OK): $cardId")
                broadcastCard(cardId)
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando 5 bytes de tarjeta: ${e.message}")
            }
            return
        }

        // ===== Tramas de más de 5 bytes: intentar validar como 5+checksum =====
        if (data.size > 5) {
            // Tomamos los primeros 5 bytes como trama con checksum
            val cinco = data.copyOfRange(0, 5)
            val suma = (0 until 4).sumOf { cinco[it].toInt() and 0xFF } and 0xFF
            val checksumRecibido = cinco[4].toInt() and 0xFF

            if (suma == checksumRecibido) {
                val cardId = bytesToCardId(cinco, 0)
                Log.d(TAG, "✅ Tarjeta IC procesada (${data.size} bytes, checksum OK en primeros 5): $cardId")
                broadcastCard(cardId)
                return
            }

            // Si no cuadra el checksum en los primeros 5, probamos tomar los últimos 4 bytes
            val ultimos4 = data.copyOfRange(data.size - 4, data.size)
            val cardId = bytesToCardId(ultimos4, 0)
            Log.d(TAG, "⚠️ Checksum no válido en trama larga, usando últimos 4 bytes: $cardId")
            broadcastCard(cardId)
            return
        }

        // ===== Fallback: texto ASCII =====
        val text = String(data, Charsets.US_ASCII).trim()
        if (text.isNotEmpty()) {
            val cleanCard = text.filter { it.isDigit() }
            if (cleanCard.isNotEmpty()) {
                val cardId = cleanCard.trimStart('0').ifEmpty { "0" }
                Log.d(TAG, "✅ Tarjeta IC de texto procesada: $cardId")
                broadcastCard(cardId)
            }
        }
    }

    /**
     * Convierte 4 bytes (big-endian) a un número decimal sin ceros a la izquierda.
     * @param data trama completa
     * @param offset posición desde donde tomar los 4 bytes
     */
    private fun bytesToCardId(data: ByteArray, offset: Int): String {
        val b0 = (data[offset].toInt() and 0xFF).toLong()
        val b1 = (data[offset + 1].toInt() and 0xFF).toLong()
        val b2 = (data[offset + 2].toInt() and 0xFF).toLong()
        val b3 = (data[offset + 3].toInt() and 0xFF).toLong()

        val cardInt = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        return cardInt.toString()
    }

    private fun broadcastCard(cardId: String) {
        val intent = Intent("DEVICE_DATA_RECEIVED")
        intent.putExtra("type", "IC_CARD")
        intent.putExtra("card_number", cardId)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun resetResults() {}
}