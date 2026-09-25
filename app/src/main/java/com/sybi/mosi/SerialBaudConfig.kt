package com.sybi.mosi

import android.content.SharedPreferences

/**
 * Velocidad (baudios) de cada equipo conectado por puerto serie.
 *
 * Valores por defecto tomados del SDK del fabricante (SDK_I6, baudRate() de cada helper):
 * - Altura/peso/composición (placa de 8 electrodos, HWFatHelper): 115200
 * - Presión (Pulse wave 7000, Mbb7000Helper): 9600
 * - Temperatura (TM512, TemperLSTM512Helper): 9600
 * Se pueden cambiar en "Dispositivos de medición" si el modelo instalado es distinto.
 */
object SerialBaudConfig {
    /** Lista del selector: de 600 a 115200, de 100 en 100. */
    val OPTIONS: List<Int> = (600..115200 step 100).toList()

    /** Velocidades que acepta un puerto serie de Linux; las demás las rechaza la librería. */
    val STANDARD = setOf(600, 1200, 1800, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)

    /** Límites para un valor personalizado. */
    const val CUSTOM_MIN = 50
    const val CUSTOM_MAX = 4_000_000

    fun defaultFor(deviceKey: String): Int = when (deviceKey) {
        "device_altura_peso" -> 115200
        else -> 9600
    }

    fun isStandard(baud: Int) = baud in STANDARD

    fun get(prefs: SharedPreferences, deviceKey: String): Int =
        prefs.getInt("${deviceKey}_baud", defaultFor(deviceKey))

    fun set(prefs: SharedPreferences, deviceKey: String, baud: Int) {
        prefs.edit().putInt("${deviceKey}_baud", baud).apply()
    }
}
