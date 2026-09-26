package com.sybi.mosi

import android.content.Context
import android.content.SharedPreferences

object DeviceDefaults {

    // Constantes de claves (para no duplicar strings)
    const val PREF_DEVICE_ALTURA_PESO     = "device_altura_peso"
    const val PREF_DEVICE_PRESION         = "device_presion"
    const val PREF_DEVICE_TEMPERATURA     = "device_temperatura"
    const val PREF_DEVICE_IC_CARD         = "device_ic_card"

    /** Puerto serial por defecto para cada dispositivo (si no hay uno guardado). */
    val DEFAULT_PORTS: Map<String, String> = mapOf(
        PREF_DEVICE_ALTURA_PESO to "/dev/ttyS5",
        PREF_DEVICE_PRESION     to "/dev/ttyS2",
        PREF_DEVICE_TEMPERATURA to "/dev/ttyS7",
        PREF_DEVICE_IC_CARD     to "/dev/ttyS6"
    )

    fun ensureDefaultPorts(prefs: SharedPreferences) {
        val editor = prefs.edit()
        for ((key, port) in DEFAULT_PORTS) {
            if (!prefs.contains("${key}_port")) {
                editor.putString("${key}_port", port)
            }
        }
        editor.apply()
    }
}