package com.sybi.mosi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Helper para medir y registrar la velocidad de internet, la latencia (ping)
 * y el estado de la conexión. Los diagnósticos se incluyen en los logs que se envían por correo.
 */
object NetworkSpeedHelper {

    private const val TAG = "NetworkSpeedHelper"

    @Volatile
    private var ultimoEnvioResumen: String = "Sin envíos recientes registrados"

    data class SpeedInfo(
        val transport: String,
        val pingMs: Long,
        val downKbps: Int,
        val upKbps: Int,
        val redCalidad: String,
        val detalles: String
    )

    /**
     * Mide la latencia (ping) hacia el servidor principal en milisegundos.
     * Es bloqueante: llamar desde hilo secundario.
     */
    fun medirPing(host: String = "www.sybiml.com", port: Int = 443, timeoutMs: Int = 3000): Long {
        val start = SystemClock.elapsedRealtime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            Log.d(TAG, "📡 Ping a $host:$port = ${elapsed}ms")
            elapsed
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ping fallido a $host:$port: ${e.message}")
            -1L
        }
    }

    /**
     * Obtiene la información técnica del estado de la red y estima anchos de banda y latencia.
     */
    fun medirEstadoRed(context: Context): SpeedInfo {
        val appContext = context.applicationContext
        val status = ConnectivityStatusProvider.get(appContext)

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0

        val pingMs = medirPing()

        val calidad = when {
            !status.internetAvailable -> "Sin internet"
            pingMs > 1000 || (upKbps in 1..200) -> "Muy lenta / Inestable"
            pingMs > 400 || (upKbps in 201..500) -> "Lenta"
            pingMs in 1..400 -> "Buena"
            else -> "Aceptable (sin ping)"
        }

        val downStr = formatBandwidth(downKbps)
        val upStr = formatBandwidth(upKbps)
        val pingStr = if (pingMs >= 0) "${pingMs}ms" else "Error/Timeout"

        val detalles = "Transporte: ${status.transport.uppercase()} | Ping: $pingStr | " +
                "Subida estim: $upStr | Bajada estim: $downStr | Calidad: $calidad"

        Log.i(TAG, "🌐 [Diagnóstico de Red] $detalles")

        return SpeedInfo(
            transport = status.transport,
            pingMs = pingMs,
            downKbps = downKbps,
            upKbps = upKbps,
            redCalidad = calidad,
            detalles = detalles
        )
    }

    /**
     * Registra la velocidad real alcanzada durante el envío de un payload (ej. mediciones).
     */
    fun registrarEnvio(tamanoBytes: Long, duracionMs: Long) {
        if (duracionMs <= 0 || tamanoBytes <= 0) return

        val duracionSeg = duracionMs / 1000.0
        val kbps = (tamanoBytes * 8.0 / 1024.0) / duracionSeg
        val mbps = kbps / 1024.0
        val tamanoKb = tamanoBytes / 1024.0

        val fechaStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        val resumen = if (mbps >= 1.0) {
            "Último envío ($fechaStr): %.2f KB en %.1fs a %.2f Mbps (%.0f kbps)".format(
                Locale.US, tamanoKb, duracionSeg, mbps, kbps
            )
        } else {
            "Último envío ($fechaStr): %.2f KB en %.1fs a %.1f kbps".format(
                Locale.US, tamanoKb, duracionSeg, kbps
            )
        }

        ultimoEnvioResumen = resumen
        Log.i(TAG, "🚀 [VELOCIDAD REAL DE ENVÍO] $resumen")
    }

    fun obtenerUltimoEnvioResumen(): String = ultimoEnvioResumen

    /**
     * Devuelve una cadena formateada con todo el diagnóstico de velocidad e internet para incluir en logs y correos.
     */
    fun obtenerDiagnosticoRedCompleto(context: Context): String {
        val info = medirEstadoRed(context)
        return buildString {
            appendLine("--- Diagnóstico de Velocidad de Internet ---")
            appendLine("Conexión: ${info.detalles}")
            appendLine("Envío de datos: $ultimoEnvioResumen")
            appendLine("-------------------------------------------")
        }
    }

    private fun formatBandwidth(kbps: Int): String {
        return when {
            kbps <= 0 -> "Desconocido"
            kbps >= 1024 -> "%.2f Mbps".format(Locale.US, kbps / 1024.0)
            else -> "$kbps kbps"
        }
    }
}
