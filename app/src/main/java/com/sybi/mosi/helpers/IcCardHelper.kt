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
        Log.d(TAG, "💳 Lector IC RX text: ${text.trim()}")

        for (char in text) {
            if (char == '\r' || char == '\n') {
                val cardCode = stringBuilder.toString().trim()
                stringBuilder.clear()
                if (cardCode.isNotEmpty()) {
                    processCardCode(cardCode)
                }
            } else {
                stringBuilder.append(char)
                // Si acumulamos 10 o más dígitos y viene sin salto de línea explícito
                if (stringBuilder.length >= 10) {
                    val cardCode = stringBuilder.toString().trim()
                    if (cardCode.length == 10 && cardCode.all { it.isDigit() }) {
                        stringBuilder.clear()
                        processCardCode(cardCode)
                    }
                }
            }
        }
    }

    private fun processCardCode(code: String) {
        // Filtrar y tomar los 10 dígitos numéricos de la tarjeta
        val cleanCode = code.filter { it.isDigit() }
        if (cleanCode.length >= 10) {
            val cardId = cleanCode.takeLast(10)
            Log.d(TAG, "✅ Tarjeta IC leída correctamente: $cardId")

            val intent = Intent("DEVICE_DATA_RECEIVED")
            intent.putExtra("type", "IC_CARD")
            intent.putExtra("card_number", cardId)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    fun resetResults() {
        stringBuilder.clear()
    }
}