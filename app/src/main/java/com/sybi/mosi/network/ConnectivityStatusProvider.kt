package com.sybi.mosi.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** Estado de conectividad listo para mostrarse en UI o incluirse en telemetria. */
data class ConnectivityStatus(
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val ethernetConnected: Boolean,
    val internetAvailable: Boolean,
    val ssid: String?,
    val transport: String,
    val signalDbm: Int? = null,
    val signalLevel: Int? = null,
    val downlinkKbps: Int = -1,
    val upstreamKbps: Int = -1,
    val interfaceName: String? = null,
    val localIp: String? = null
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
        var downlinkKbps = -1
        var upstreamKbps = -1

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
            if (isUsable) {
                if (capabilities.linkDownstreamBandwidthKbps > 0) {
                    downlinkKbps = capabilities.linkDownstreamBandwidthKbps
                }
                if (capabilities.linkUpstreamBandwidthKbps > 0) {
                    upstreamKbps = capabilities.linkUpstreamBandwidthKbps
                }
            }
        }

        val rawSsid = runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
        val ssid = rawSsid
            ?.removeSurrounding("\"")
            ?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) }

        var signalDbm: Int? = null
        var signalLevel: Int? = null
        if (wifiConnected) {
            val info = runCatching { wifiManager.connectionInfo }.getOrNull()
            if (info != null && info.rssi != -127) {
                signalDbm = info.rssi
                signalLevel = runCatching { WifiManager.calculateSignalLevel(info.rssi, 5) }.getOrNull()
            }
        }

        var localIp: String? = null
        var interfaceName: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        localIp = addr.hostAddress
                        interfaceName = intf.name
                        break
                    }
                }
                if (localIp != null) break
            }
        } catch (_: Exception) {}

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
            transport = transport,
            signalDbm = signalDbm,
            signalLevel = signalLevel,
            downlinkKbps = downlinkKbps,
            upstreamKbps = upstreamKbps,
            interfaceName = interfaceName,
            localIp = localIp
        )
    }
}
