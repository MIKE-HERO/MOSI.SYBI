package com.sybi.mosi.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.sybi.mosi.DailyReportManager

/**
 * Monitorea el estado de la conexión a internet en segundo plano para registrar
 * interrupciones y restablecimientos en el reporte diario.
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"
    private var isRegistered = false

    fun start(context: Context) {
        if (isRegistered) return
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "🌐 Conexión a internet disponible")
                    DailyReportManager.recordInternetRestored(appContext)
                }

                override fun onLost(network: Network) {
                    Log.w(TAG, "⚠️ Conexión a internet perdida")
                    DailyReportManager.recordInternetLost(appContext)
                }
            })
            isRegistered = true
            Log.d(TAG, "✅ NetworkMonitor iniciado correctamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando NetworkMonitor: ${e.message}")
        }
    }
}
