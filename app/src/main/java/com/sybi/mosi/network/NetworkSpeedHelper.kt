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
            if (status.signalDbm != null) {
                sb.appendLine("Señal WiFi (RSSI): ${status.signalDbm} dBm (Nivel ${status.signalLevel ?: 0}/4)")
            }
        }
        sb.appendLine("Ethernet Conectado: ${if (status.ethernetConnected) "SÍ" else "NO"}")
        sb.appendLine("Acceso a Internet (Validado): ${if (status.internetAvailable) "SÍ" else "NO"}")
        if (status.interfaceName != null || status.localIp != null) {
            sb.appendLine("Interfaz local: ${status.interfaceName ?: "N/A"} (${status.localIp ?: "N/A"})")
        }
        if (status.downlinkKbps > 0 || status.upstreamKbps > 0) {
            sb.appendLine("Est. Ancho de Banda (OS): Descarga ${status.downlinkKbps / 1000.0} Mbps / Subida ${status.upstreamKbps / 1000.0} Mbps")
        }

        val latenciaDns = medirLatenciaSocket("8.8.8.8", 53, 2000)
        if (latenciaDns >= 0) {
            sb.appendLine("Latencia DNS (8.8.8.8:53): ${latenciaDns} ms")
        } else {
            sb.appendLine("Latencia DNS (8.8.8.8:53): Sin respuesta / Timeout")
        }

        val latenciaServer = medirLatenciaSocket("www.sybiml.com", 443, 2000)
        if (latenciaServer >= 0) {
            sb.appendLine("Latencia API (www.sybiml.com:443): ${latenciaServer} ms")
        } else {
            sb.appendLine("Latencia API (www.sybiml.com:443): Sin respuesta / Timeout")
        }

        return sb.toString().trimEnd()
    }

    fun medirLatenciaSocket(host: String = "www.sybiml.com", port: Int = 443, timeoutMs: Int = 2000): Long {
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
