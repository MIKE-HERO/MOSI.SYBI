package com.sybi.mosi.network

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket

object NetworkSpeedHelper {

    fun obtenerDiagnosticoRedCompleto(context: Context): String {
        val status = ConnectivityStatusProvider.get(context)
        val sb = StringBuilder()

        sb.appendLine("Tipo de transporte: ${status.transport.uppercase()}")
        sb.appendLine("WiFi Habilitado: ${if (status.wifiEnabled) "SÍ" else "NO"}")
        sb.appendLine("WiFi Conectado: ${if (status.wifiConnected) "SÍ" else "NO"}")
        if (status.ssid != null) {
            sb.appendLine("SSID WiFi: ${status.ssid}")
        }
        sb.appendLine("Ethernet Conectado: ${if (status.ethernetConnected) "SÍ" else "NO"}")
        sb.appendLine("Acceso a Internet (Validado): ${if (status.internetAvailable) "SÍ" else "NO"}")

        val pingTimeMs = medirLatencia("8.8.8.8", 53, 2000)
        if (pingTimeMs >= 0) {
            sb.appendLine("Latencia DNS (8.8.8.8:53): ${pingTimeMs} ms")
        } else {
            sb.appendLine("Latencia DNS (8.8.8.8:53): Sin respuesta / Timeout")
        }

        return sb.toString().trimEnd()
    }

    private fun medirLatencia(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val startTime = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            System.currentTimeMillis() - startTime
        } catch (_: Exception) {
            -1L
        }
    }
}
