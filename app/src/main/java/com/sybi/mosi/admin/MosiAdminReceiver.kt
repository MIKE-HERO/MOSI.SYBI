package com.sybi.mosi.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receptor del administrador del dispositivo. Si la app se configura como propietario del
 * dispositivo (device owner), al activarse concede sola los permisos que necesita (cámara,
 * micrófono, ubicación, almacenamiento) y ya no vuelve a pedirlos.
 *
 * Activación (una sola vez por equipo, sin cuentas de usuario configuradas):
 *   adb shell dpm set-device-owner com.sybi.mosi/.admin.MosiAdminReceiver
 */
class MosiAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("MosiAdmin", "Administrador del dispositivo activado")
        PermisosAdmin.aplicar(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i("MosiAdmin", "Administrador del dispositivo desactivado")
    }
}
