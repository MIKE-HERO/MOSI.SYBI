package com.sybi.mosi.network

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.*

object RetrofitClient {

    private const val BASE_URL = "https://www.sybiml.com/APIRest/Usuario_mosi/public/"
    //private const val BASE_URL = "https://desarrollo.sybiml3.com/APIRest/Usuario_mosi/public/"
    private const val API_KEY = "dc5cdc3d48c15c6f1130e72e68ffdec3b0b127000fd0d46584b556cce62f86ba"
    private val dnsExecutor: ExecutorService by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mosi-dns").apply { isDaemon = true }
        }
    }

    val apiService: ApiService by lazy {

        val logging = HttpLoggingInterceptor { message ->
            Log.d("OkHttp", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        // DNS con timeout de 30 segundos para redes lentas
        val timeoutDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val future = dnsExecutor.submit(Callable {
                    Dns.SYSTEM.lookup(hostname)
                })
                return try {
                    future.get(30, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    future.cancel(true)
                    throw java.net.UnknownHostException("DNS lookup timeout for $hostname")
                }
            }
        }

        val client = OkHttpClient.Builder()
            .dns(timeoutDns)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("X-Api-Key", API_KEY)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)   // ✅ Adaptado para conexiones lentas
            .readTimeout(180, TimeUnit.SECONDS)    // ✅ 3 min de lectura sin corte prematuro
            .writeTimeout(180, TimeUnit.SECONDS)   // ✅ 3 min de escritura para subidas lentas
            .callTimeout(0, TimeUnit.SECONDS)      // ✅ Sin límite de tiempo global estricto
            .retryOnConnectionFailure(true)        // ✅ Reintentar automáticamente en fallos
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
