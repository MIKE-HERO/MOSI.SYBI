package com.sybi.mosi

import android.app.Application
import android.content.Context
import android.util.Log
import com.sybi.mosi.helpers.FaceBiometricsCache
import com.sybi.mosi.updater.UpdateActivity
import com.sybi.mosi.updater.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MosiApp : Application() {

    companion object {
        private const val TAG = "MosiApp"
        private var hasCheckedUpdate = false
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Asegurar que existan los puertos seriales por defecto en SharedPreferences desde el inicio
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        DeviceDefaults.ensureDefaultPorts(devicePrefs)

        // Iniciar precarga de biometría de todos los pacientes desde que inicia la app
        FaceBiometricsCache.preload(this)

        // Programar el envío automático del log de arranque a los 5 minutos del inicio
        AutoLogHelper.programarLogArranque(this)

        // Registrar incidente de reencendido/arranque y monitoreo de conectividad para el reporte diario
        DailyReportManager.recordBootIncident(this)
        com.sybi.mosi.network.NetworkMonitor.start(this)

        // --- BÚSQUEDA AUTOMÁTICA DE ACTUALIZACIONES SOLO AL ARRANCAR LA APP (COLD START / ENCENDIDO) ---
        if (!hasCheckedUpdate) {
            hasCheckedUpdate = true
            applicationScope.launch {
                delay(4000) // Esperar 4 segundos para estabilizar la red y el Wi-Fi tras el encendido
                try {
                    val updateManager = UpdateManager(applicationContext)
                    val pInfo = try {
                        packageManager.getPackageInfo(packageName, 0)
                    } catch (_: Exception) {
                        null
                    }
                    val currentVersion = pInfo?.versionName ?: "1.0.0"
                    val releases = updateManager.fetchReleases()
                    if (releases.isNotEmpty()) {
                        val latestRelease = releases[0]
                        if (updateManager.isNewerVersion(latestRelease.tagName, currentVersion)) {
                            Log.i(TAG, "🚀 [Arranque] Nueva versión detectada en GitHub (${latestRelease.tagName}). Lanzando UpdateActivity...")
                            UpdateActivity.start(
                                applicationContext,
                                latestRelease.downloadUrl,
                                latestRelease.tagName
                            )
                        } else {
                            Log.i(TAG, "✅ [Arranque] La app está actualizada (v$currentVersion).")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error verificando actualizaciones al arrancar: ${e.message}", e)
                }
            }
        }
    }
}
