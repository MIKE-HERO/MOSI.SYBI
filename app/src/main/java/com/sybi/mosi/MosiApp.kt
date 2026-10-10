package com.sybi.mosi

import android.app.Application
import com.sybi.mosi.helpers.FaceBiometricsCache

class MosiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Iniciar precarga de biometría de todos los pacientes desde que inicia la app
        FaceBiometricsCache.preload(this)
    }
}
