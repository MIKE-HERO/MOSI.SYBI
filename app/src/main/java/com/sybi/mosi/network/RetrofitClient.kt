package com.sybi.mosi.network

import android.util.Log
import com.sybi.mosi.MosiApp
import com.sybi.mosi.integration.MosiSecureStore
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.*

object RetrofitClient {

    private const val TAG = "RetrofitClient"

    private const val BASE_URL = "https://www.sybiml.com/APIRest/Usuario_mosi/public/"
    private const val API_KEY = "dc5cdc3d48c15c6f1130e72e68ffdec3b0b127000fd0d46584b556cce62f86ba"
    //private const val BASE_URL = "https://desarrollo.sybiml3.com/APIRest/Usuario_mosi/public/"

    // ============================================================
    // ESTABILIDAD DE CONEXIÓN
    // ============================================================
    // OkHttp mantiene las conexiones del pool 5 minutos por defecto. En el
    // quiosco pasan más de 5 minutos entre un paciente y el siguiente, así que
    // la conexión caduca y CADA envío vuelve a pagar TCP + TLS (≈3 RTT; con los
    // ~70 ms de latencia medidos al servidor son ≈210 ms antes de que salga el
    // primer byte del cuerpo). Alargar el keepalive y precalentar la conexión
    // antes del POST elimina ese tramo del camino crítico.
    private const val KEEPALIVE_MINUTOS = 15L
    private const val MAX_CONEXIONES_INACTIVAS = 5

    // El DNS del quiosco se degrada bajo carga de la LAN (medido subiendo de
    // 13 ms a 100 ms, con caídas puntuales). La IP del servidor no cambia a ese
    // ritmo, así que se cachea y, si una resolución falla, se sirve la entrada
    // vencida en vez de tumbar la petición.
    private const val DNS_TTL_MS = 5 * 60 * 1000L

    private val dnsExecutor: ExecutorService by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mosi-dns").apply { isDaemon = true }
        }
    }

    /**
     * DNS con timeout propio de 10 s (el lookup del sistema puede quedarse
     * colgado indefinidamente) envuelto en una caché con TTL.
     */
    private class DnsConCache(private val delegado: Dns) : Dns {

        private class Entrada(val direcciones: List<InetAddress>, val resueltoEnMs: Long)

        private val cache = ConcurrentHashMap<String, Entrada>()

        override fun lookup(hostname: String): List<InetAddress> {
            val ahora = System.currentTimeMillis()

            cache[hostname]?.let { entrada ->
                if (ahora - entrada.resueltoEnMs < DNS_TTL_MS) {
                    return entrada.direcciones
                }
            }

            return try {
                val frescas = delegado.lookup(hostname)
                if (frescas.isNotEmpty()) {
                    cache[hostname] = Entrada(frescas, ahora)
                }
                frescas
            } catch (e: Exception) {
                // Preferimos una IP vencida a no tener ninguna: el servidor no
                // cambia de dirección cada cinco minutos, pero el DNS del
                // quiosco sí falla de forma intermitente.
                val vencida = cache[hostname]
                if (vencida != null) {
                    val edadSeg = (ahora - vencida.resueltoEnMs) / 1000
                    Log.w(TAG, "⚠️ DNS falló para $hostname (${e.javaClass.simpleName}); usando entrada en caché de hace ${edadSeg}s")
                    vencida.direcciones
                } else {
                    throw e
                }
            }
        }
    }

    private val dnsSistemaConTimeout = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val future = dnsExecutor.submit(Callable {
                Dns.SYSTEM.lookup(hostname)
            })
            return try {
                future.get(10, TimeUnit.SECONDS)
            } catch (e: Exception) {
                future.cancel(true)
                throw java.net.UnknownHostException("DNS lookup timeout for $hostname")
            }
        }
    }

    /**
     * Cliente OkHttp compartido. Se expone para poder precalentar la conexión
     * y para que cualquier otra llamada reutilice el mismo pool y la misma
     * caché de DNS.
     */
    val httpClient: OkHttpClient by lazy {

        val logging = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
        }

        OkHttpClient.Builder()
            .dns(DnsConCache(dnsSistemaConTimeout))
            .connectionPool(
                ConnectionPool(MAX_CONEXIONES_INACTIVAS, KEEPALIVE_MINUTOS, TimeUnit.MINUTES)
            )
            // Sobre HTTP/2 mantiene viva la conexión y detecta las medio
            // abiertas (el log del quiosco registró un "Sin respuesta" del
            // servidor con el DNS respondiendo bien). Sobre HTTP/1.1 no tiene
            // efecto, así que es inocuo.
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val requestBuilder = chain.request().newBuilder()
                    .header("X-Api-Key", API_KEY)
                val token = MosiSecureStore.token(MosiApp.instance)
                if (token.isNotBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)     // ✅ Ampliado a 30s para cargas de ~1MB (ECG)
            .callTimeout(60, TimeUnit.SECONDS)      // ✅ Ampliado a 60s el timeout total
            .retryOnConnectionFailure(true)         // ✅ reintentar en fallos transitorios
            .build()
    }

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    /**
     * Abre (o reutiliza) la conexión TCP+TLS al servidor y la deja en el pool
     * para que el POST siguiente no pague el handshake.
     *
     * Se hace con un HEAD a la raíz de la API: el código de respuesta da igual
     * —incluso un 403 o un 404 sirven—, lo único que importa es que la conexión
     * quede establecida y agrupada. Debe llamarse desde un hilo de fondo.
     *
     * @return milisegundos que tardó, o -1 si no se pudo establecer.
     */
    fun precalentarConexion(): Long {
        return try {
            val inicio = System.currentTimeMillis()
            val peticion = Request.Builder().url(BASE_URL).head().build()
            httpClient.newCall(peticion).execute().use { /* el código es irrelevante */ }
            val duracion = System.currentTimeMillis() - inicio
            Log.d(TAG, "🔥 Conexión precalentada en ${duracion} ms")
            duracion
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ No se pudo precalentar la conexión: ${e.javaClass.simpleName}: ${e.message}")
            -1L
        }
    }
}
