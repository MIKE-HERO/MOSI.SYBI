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

        // ===== 1. Fallback: texto ASCII (ej. lectores que envían 10 dígitos decimales + \r\n) =====
        try {
            val text = String(data, Charsets.US_ASCII).trim()
            if (text.isNotEmpty() && text.all { it.isDigit() || it.isLetter() || it == '-' }) {
                val cleanCard = text.filter { it.isDigit() }
                if (cleanCard.isNotEmpty()) {
                    val cardId = cleanCard.trimStart('0').ifEmpty { "0" }
                    Log.d(TAG, "✅ Tarjeta IC de texto procesada: $cardId")
                    broadcastCard(cardId)
                    return
                }
            }
        } catch (_: Exception) {
            // No es texto ASCII válido, continuar con procesamiento binario
        }

        // ===== 2. Procesamiento binario (con o sin checksum al final) =====
        try {
            var body = data
            var hasValidChecksum = false

            // Verificar si hay checksum al final (para tramas de 5 o más bytes)
            if (data.size >= 5) {
                val lastByte = data[data.size - 1].toInt() and 0xFF
                val potentialBody = data.copyOfRange(0, data.size - 1)

                val sumChecksum = (0 until potentialBody.size).sumOf { potentialBody[it].toInt() and 0xFF } and 0xFF
                val xorChecksum = (0 until potentialBody.size).fold(0) { acc, i -> acc xor (potentialBody[i].toInt() and 0xFF) }

                if (sumChecksum == lastByte || xorChecksum == lastByte) {
                    Log.d(TAG, "✅ Checksum válido detectado al final (${data.size} bytes)")
                    body = potentialBody
                    hasValidChecksum = true
                }
            }

            val cardId = if (body.size >= 4) {
                bytesToCardId(body, 0)
            } else if (body.isNotEmpty()) {
                var value = 0L
                for (b in body) {
                    value = (value shl 8) or ((b.toInt() and 0xFF).toLong())
                }
                value.toString()
            } else {
                return
            }

            Log.d(TAG, "✅ Tarjeta IC binaria procesada (conChecksum=$hasValidChecksum): $cardId")
            broadcastCard(cardId)
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando tarjeta binaria: ${e.message}")
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