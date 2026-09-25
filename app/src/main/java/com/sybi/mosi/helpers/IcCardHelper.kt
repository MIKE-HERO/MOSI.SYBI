package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class IcCardHelper(private val context: Context) {

    companion object {
        private const val TAG = "IcCardHelper"
    }

    private val stringBuilder = StringBuilder()

    fun parseData(data: ByteArray) {
        val text = String(data, Charsets.US_ASCII)
        Log.d(TAG, "💳 Lector IC RX bytes: ${data.joinToString(" ") { "%02X".format(it) }} | text: '${text.trim()}'")

        for (char in text) {
            if (char == '\r' || char == '\n') {
                val cardCode = stringBuilder.toString().trim()
                stringBuilder.clear()
                if (cardCode.isNotEmpty()) {
                    processCardCode(cardCode)
                }
            } else {
                // Permitir dígitos (0-9) y letras hexadecimales (A-F, a-f)
                if (char.isLetterOrDigit()) {
                    stringBuilder.append(char)
                    // Si el búfer alcanza 10 o más caracteres válidos sin salto de línea, procesar también
                    if (stringBuilder.length >= 10) {
                        val cardCode = stringBuilder.toString().trim()
                        if (cardCode.length in 6..20 && cardCode.all { it.isLetterOrDigit() }) {
                            stringBuilder.clear()
                            processCardCode(cardCode)
                        }
                    }
                }
            }
        }
    }

    private fun processCardCode(code: String) {
        val cleanCode = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleanCode.length in 6..20) {
            Log.d(TAG, "✅ Tarjeta IC leída correctamente: $cleanCode")

            val intent = Intent("DEVICE_DATA_RECEIVED")
            intent.putExtra("type", "IC_CARD")
            intent.putExtra("card_number", cleanCode)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        } else {
            Log.d(TAG, "⚠️ Código de tarjeta ignorado por longitud inválida (${cleanCode.length}): $cleanCode")
        }
    }

    fun resetResults() {
        stringBuilder.clear()
    }
}
