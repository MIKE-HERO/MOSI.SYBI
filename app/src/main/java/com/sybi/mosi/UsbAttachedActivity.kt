package com.sybi.mosi

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Sin interfaz: solo existe para que Android relacione las cámaras USB con esta app y permita
 * marcar "usar siempre", de modo que el permiso USB se guarde y no se vuelva a pedir. Se cierra
 * al instante para no interrumpir lo que esté en pantalla (p. ej. una medición).
 */
class UsbAttachedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("UsbAttached", "Dispositivo USB de video conectado: ${intent?.action}")
        finish()
    }
}
