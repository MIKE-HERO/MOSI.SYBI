package com.sybi.mosi.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.sybi.mosi.integration.MosiApiClient
import com.sybi.mosi.integration.MosiSecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receptor de eventos para las sesiones de instalación silenciosa del PackageInstaller.
 * Cuando la instalación finaliza exitosamente en modo Device Owner, relanza automáticamente la aplicación.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_COMPLETE = "com.sybi.mosi.updater.ACTION_INSTALL_COMPLETE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_INSTALL_COMPLETE) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Log.i(TAG, "Se requiere confirmación del usuario para instalar la actualización.")
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirmIntent)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "✅ Actualización de la app completada exitosamente en segundo plano.")
                    Toast.makeText(context, "✅ Aplicación actualizada exitosamente", Toast.LENGTH_LONG).show()

                    // Relanzar la aplicación actualizada
                    reportResult(context, "instalacion", null)
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(launchIntent)
                    }
                }
                else -> {
                    reportResult(context, "error", "PackageInstaller status=$status: ${message.orEmpty()}")
                    Log.e(TAG, "❌ Falló la instalación silenciosa de la actualización: status=$status, msg=$message")
                }
            }
        }
    }

    private fun reportResult(context: Context, event: String, detail: String?) {
        val pending = MosiSecureStore.actualizacionPendiente(context) ?: return
        val async = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MosiApiClient(context).reportarEventoActualizacion(
                    event,
                    pending.id,
                    pending.version,
                    pending.requestId,
                    detail
                )
                if (event == "instalacion") MosiSecureStore.limpiarActualizacionPendiente(context)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo reportar resultado de instalación: ${e.message}")
            } finally {
                async.finish()
            }
        }
    }
}
