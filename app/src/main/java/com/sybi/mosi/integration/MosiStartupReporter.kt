package com.sybi.mosi.integration

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Confirma una instalación al reiniciar, incluso si Android reemplazó el proceso anterior... */
object MosiStartupReporter {
    private const val TAG = "MosiStartupReporter"

    fun reportInstalledUpdate(context: Context) {
        val pending = MosiSecureStore.actualizacionPendiente(context) ?: return
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
        if (installed.removePrefix("v") != pending.version.removePrefix("v")) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                MosiApiClient(context).reportarEventoActualizacion(
                    "instalacion",
                    pending.id,
                    installed,
                    pending.requestId
                )
                MosiSecureStore.limpiarActualizacionPendiente(context)
            } catch (e: Exception) {
                Log.w(TAG, "La instalación quedó pendiente de reportar: ${e.message}")
            }
        }
    }
}
