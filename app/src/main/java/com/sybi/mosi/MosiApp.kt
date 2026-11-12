package com.sybi.mosi

import android.app.Application
import android.content.Context
import com.sybi.mosi.helpers.FaceBiometricsCache
import com.sybi.mosi.helpers.LocaleHelper
import com.sybi.mosi.integration.MedicionesSyncWorker
import com.sybi.mosi.integration.MosiStartupReporter
import com.sybi.mosi.integration.MosiTelemetryWorker
import com.sybi.mosi.updater.MosiUpdateWorker

class MosiApp : Application() {

    companion object {
        lateinit var instance: MosiApp
            private set
    }

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

        // Limpieza de respaldo de logs antiguos (> 7 días)
        ErrorLogHelper.limpiarLogsAntiguos(this)

        // Iniciar el servicio Serial en primer plano para mantener vivo el proceso durante llamadas de telemedicina
        startService(android.content.Intent(this, SerialService::class.java))

        // Programar el envío automático del log de arranque a los 5 minutos del inicio
        AutoLogHelper.programarLogArranque(this)

        // Registrar incidente de reencendido/arranque y monitoreo de conectividad para el reporte diario
        DailyReportManager.recordBootIncident(this)
        com.sybi.mosi.network.NetworkMonitor.start(this)

        // Estos trabajos no se programan hasta que la cabina tiene un token individual.
        MosiTelemetryWorker.schedule(this)
        MedicionesSyncWorker.schedule(this, 20L)
        MosiStartupReporter.reportInstalledUpdate(this)

        // Mantener el canal de actualizaciones activo aun si la app permanece abierta.
        MosiUpdateWorker.schedule(this)
        MosiUpdateWorker.checkNow(this, 4L)
    }
}
