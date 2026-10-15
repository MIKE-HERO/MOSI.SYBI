package com.sybi.mosi.network

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.sybi.mosi.DeviceInfoActivity

/**
 * Payload local para telemetria. Se puede enviar al backend cuando se defina
 * el endpoint, sin acoplar la deteccion de red a una Activity.
 */
data class DeviceTelemetry(
    val deviceId: String,
    val serial: String,
    val cabin: String,
    val clientId: String,
    val branchId: String,
    val appVersion: String,
    val androidVersion: String,
    val timestampMillis: Long,
    val wifiEnabled: Boolean,
    val wifiConnected: Boolean,
    val ethernetConnected: Boolean,
    val internetAvailable: Boolean,
    val ssid: String?,
    val transport: String
)

object DeviceTelemetryCollector {
    fun collect(context: Context): DeviceTelemetry {
        val network = ConnectivityStatusProvider.get(context)
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()

        return DeviceTelemetry(
            deviceId = deviceId,
            serial = DeviceInfoActivity.getSerie(context),
            cabin = DeviceInfoActivity.getCabina(context),
            clientId = DeviceInfoActivity.getCliente(context),
            branchId = DeviceInfoActivity.getSucursal(context),
            appVersion = appVersion,
            androidVersion = Build.VERSION.RELEASE,
            timestampMillis = System.currentTimeMillis(),
            wifiEnabled = network.wifiEnabled,
            wifiConnected = network.wifiConnected,
            ethernetConnected = network.ethernetConnected,
            internetAvailable = network.internetAvailable,
            ssid = network.ssid,
            transport = network.transport
        )
    }
}
