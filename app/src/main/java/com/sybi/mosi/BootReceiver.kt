package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receptor de arranque y reemplazo de paquete.
 * Inicia MainActivity automáticamente cuando el equipo enciende o cuando se actualiza la app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(i)
        }
    }
}
