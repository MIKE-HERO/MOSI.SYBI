package com.sybi.mosi

import android.app.Application
import android.content.Context
import android.util.Log
import com.sybi.mosi.helpers.FaceBiometricsCache
import com.sybi.mosi.helpers.LocaleHelper
import com.sybi.mosi.integration.MedicionesSyncWorker
import com.sybi.mosi.integration.MosiStartupReporter
import com.sybi.mosi.integration.MosiTelemetryWorker
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
        lateinit var instance: MosiApp
            private set
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LocaleHelper.applyLanguage(this)
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

        // Estos trabajos no se programan hasta que la cabina tiene un token individual.
        MosiTelemetryWorker.schedule(this)
        MedicionesSyncWorker.schedule(this, 20L)
        MosiStartupReporter.reportInstalledUpdate(this)

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
                            Log.i(TAG, "Nueva versión MOSI disponible: ${latestRelease.tagName}")
                            UpdateActivity.start(
                                applicationContext,
                                latestRelease.downloadUrl,
                                latestRelease.tagName,
                                sha256 = latestRelease.sha256,
                                updateId = latestRelease.updateId,
                                requestId = latestRelease.requestId
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
