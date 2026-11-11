package com.sybi.mosi

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

object SpeedTestHelper {
    private const val TAG = "SpeedTestHelper"

    // Servidores oficiales de Fast.com (Netflix). No bloquean y son muy estables.
    // 25 MB de descarga (para promediar bien las variaciones de la red)
    private const val URL_DESCARGA = "https://ipv4-c001-qro001-ix.1.oca.nflxvideo.net/speedtest/range/0-25000000"

    // Para subir, usamos Cloudflare que no tiene límites de subida en su endpoint
    private const val URL_SUBIDA = "https://speed.cloudflare.com/__up"
    private const val URL_LATENCIA = "https://www.google.com/generate_204"
    private const val URL_IP = "https://api.ipify.org"

    private const val TIMEOUT_MS = 30000L // Damos hasta 30 segundos para tolerar la inestabilidad

    data class ResultadoSpeedTest(
        val bajadaMbps: Double?,
        val subidaMbps: Double?,
        val latenciaMs: Long?,
        val ipPublica: String?,
        val error: String? = null
    ) {
        fun resumenTexto(): String = buildString {
            appendLine("IP pública: ${ipPublica ?: "No disponible"}")
            appendLine("Latencia: ${latenciaMs?.let { "$it ms" } ?: "No disponible"}")
            appendLine("Bajada: ${bajadaMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "No disponible"}")
            appendLine("Subida: ${subidaMbps?.let { String.format(Locale.US, "%.2f Mbps", it) } ?: "No disponible"}")
            if (error != null) append("Nota: $error")
        }
    }

    suspend fun ejecutarTest(): ResultadoSpeedTest {
        Log.d(TAG, "🌐 Iniciando test de velocidad...")
        val ip = obtenerIpPublica()
        val bajada = medirBajada()
        val subida = medirSubida()
        val latencia = medirLatenciaHttp()
        val error = when {
            bajada == null && subida == null -> "No se pudo medir bajada ni subida."
            bajada == null -> "Falló test de bajada"
            subida == null -> "Falló test de subida"
            else -> null
        }
        val resultado = ResultadoSpeedTest(bajada, subida, latencia, ip, error)
        Log.d(TAG, "✅ Test terminado:\n${resultado.resumenTexto()}")
        return resultado
    }

    private suspend fun medirBajada(): Double? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(URL_DESCARGA).build()
            val inicio = System.nanoTime()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                // Leer el cuerpo completo para forzar la descarga
                val bytes = response.body?.bytes() ?: return@withContext null
                val fin = System.nanoTime()

                // Cálculo: (Bytes * 8 bits) / segundos = bps. Luego a Mbps.
                val segundos = (fin - inicio) / 1_000_000_000.0
                if (segundos <= 0) null else (bytes.size * 8.0 / segundos) / 1_000_000.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error bajada: ${e.message}")
            null
        }
    }

    private suspend fun medirSubida(): Double? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

            // Generar 5 MB de datos aleatorios para subir
            val datos = ByteArray(5 * 1024 * 1024) { (it % 256).toByte() }
            val body = datos.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder().url(URL_SUBIDA).post(body).build()

            val inicio = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val fin = System.nanoTime()

                val segundos = (fin - inicio) / 1_000_000_000.0
                if (segundos <= 0) null else (datos.size * 8.0 / segundos) / 1_000_000.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error subida: ${e.message}")
            null
        }
    }

    private suspend fun medirLatenciaHttp(): Long? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(URL_LATENCIA).build()
            val inicio = System.nanoTime()
            client.newCall(request).execute().use { it.body?.bytes() }
            (System.nanoTime() - inicio) / 1_000_000
        } catch (e: Exception) {
            Log.w(TAG, "Error latencia: ${e.message}")
            null
        }
    }

    private suspend fun obtenerIpPublica(): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(URL_IP).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.trim() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error IP: ${e.message}")
            null
        }
    }
}