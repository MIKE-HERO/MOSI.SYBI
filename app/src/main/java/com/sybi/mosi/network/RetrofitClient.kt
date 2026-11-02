package com.sybi.mosi.network

import android.util.Log
import com.sybi.mosi.MosiApp
import com.sybi.mosi.integration.MosiSecureStore
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

        // DNS con timeout manual de 10 segundos
        val timeoutDns = object : Dns {
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

        val client = OkHttpClient.Builder()
            .dns(timeoutDns)
            .addInterceptor { chain ->
                val token = MosiSecureStore.token(MosiApp.instance)
                val requestBuilder = chain.request().newBuilder()
                if (token.isNotBlank()) requestBuilder.addHeader("Authorization", "Bearer $token")
                val request = requestBuilder.build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)      // ✅ timeout total de la petición
            .retryOnConnectionFailure(true)         // ✅ reintentar en fallos transitorios
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
