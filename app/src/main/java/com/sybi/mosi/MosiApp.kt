package com.sybi.mosi

import android.app.Application
import android.content.Context
import com.sybi.mosi.helpers.FaceBiometricsCache

class MosiApp : Application() {
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
    }
}
