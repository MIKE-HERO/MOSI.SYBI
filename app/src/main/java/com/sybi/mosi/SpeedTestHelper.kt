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

    // ============================================================
    // ENDPOINTS
    // ============================================================
    // Antes se usaba un servidor OCA de Netflix con el nombre fijo
    // (ipv4-c001-qro001-ix...). Esos hostnames los reparte la API de fast.com
    // por sesión y caducan, así que el test de bajada terminaba fallando
    // siempre y cada log reportaba "Bajada: No disponible", haciendo parecer
    // que la red del quiosco estaba mal cuando no lo estaba.
    //
    // Cloudflare expone endpoints estables y simétricos, y ya se usaba el de
    // subida, así que ahora los dos lados del test salen del mismo host.
    private const val BYTES_DESCARGA = 5_000_000           // 5 MB
    private const val URL_DESCARGA = "https://speed.cloudflare.com/__down?bytes=$BYTES_DESCARGA"
    private const val URL_SUBIDA = "https://speed.cloudflare.com/__up"
    private const val URL_LATENCIA = "https://www.google.com/generate_204"
    private const val URL_IP = "https://api.ipify.org"

    // 1 MB de subida: con los enlaces medidos en los quioscos (decenas de Mbps)
    // basta para una lectura estable, y son 5 MB menos de tráfico en cada
    // arranque de la app.
    private const val BYTES_SUBIDA = 1024 * 1024

    private const val TIMEOUT_MS = 30000L // Damos hasta 30 segundos para tolerar la inestabilidad

    // Un solo cliente para todo el test: así las cuatro mediciones comparten
    // pool de conexiones y no se vuelve a resolver DNS en cada una.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

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
            val request = Request.Builder().url(URL_DESCARGA).build()
            val inicio = System.nanoTime()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Bajada: HTTP ${response.code} desde $URL_DESCARGA")
                    return@withContext null
                }
                // Leer el cuerpo completo para forzar la descarga
                val bytes = response.body?.bytes() ?: return@withContext null
                val fin = System.nanoTime()

                // Cálculo: (Bytes * 8 bits) / segundos = bps. Luego a Mbps.
                val segundos = (fin - inicio) / 1_000_000_000.0
                if (segundos <= 0) null else (bytes.size * 8.0 / segundos) / 1_000_000.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error bajada: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun medirSubida(): Double? = withContext(Dispatchers.IO) {
        try {
            val datos = ByteArray(BYTES_SUBIDA) { (it % 256).toByte() }
            val body = datos.toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder().url(URL_SUBIDA).post(body).build()

            val inicio = System.nanoTime()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Subida: HTTP ${response.code} desde $URL_SUBIDA")
                    return@withContext null
                }
                val fin = System.nanoTime()

                val segundos = (fin - inicio) / 1_000_000_000.0
                if (segundos <= 0) null else (datos.size * 8.0 / segundos) / 1_000_000.0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error subida: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun medirLatenciaHttp(): Long? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(URL_LATENCIA).build()
            val inicio = System.nanoTime()
            client.newCall(request).execute().use { it.body?.bytes() }
            (System.nanoTime() - inicio) / 1_000_000
        } catch (e: Exception) {
            Log.w(TAG, "Error latencia: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun obtenerIpPublica(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(URL_IP).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string()?.trim() else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error IP: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
