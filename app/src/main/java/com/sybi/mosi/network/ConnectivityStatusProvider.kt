package com.sybi.mosi.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Estado de conectividad listo para mostrarse en UI o incluirse en telemetria. */
data class ConnectivityStatus(
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val ethernetConnected: Boolean,
    val internetAvailable: Boolean,
    val ssid: String?,
    val transport: String
)

object ConnectivityStatusProvider {

    @SuppressLint("MissingPermission")
    fun get(context: Context): ConnectivityStatus {
        val appContext = context.applicationContext
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        var wifiConnected = false
        var ethernetConnected = false
        var internetAvailable = false

        connectivityManager.allNetworks.forEach { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            val isUsable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                wifiConnected = true
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernetConnected = true
            }
            if (isUsable && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                internetAvailable = true
            }
        }

        val rawSsid = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
        val ssid = rawSsid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) }

        val transport = when {
            ethernetConnected -> "ethernet"
            wifiConnected -> "wifi"
            else -> "none"
        }

        return ConnectivityStatus(
            wifiEnabled = wifiManager.isWifiEnabled,
            wifiConnected = wifiConnected,
            ethernetConnected = ethernetConnected,
            internetAvailable = internetAvailable,
            ssid = if (wifiConnected) ssid else null,
            transport = transport
        )
    }
}
