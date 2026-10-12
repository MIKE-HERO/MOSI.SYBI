package com.sybi.mosi

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

/**
 * Sincroniza el logo "de marca" de la app (AppPrefs.LogoPath — se usa en toda
 * la app: pantalla principal, PDF, etc.) con el logo izquierdo del cliente
 * activo del kiosco, descargado vía ClienteLogoCache. Se dispara al guardar
 * la configuración de telemedicina (cliente/sucursal) en Ajustes, para que al
 * cambiar de cliente el logo de la app cambie con él automáticamente.
 *
 * Reemplaza cualquier logo anterior (manual o de otro cliente). Si el cliente
 * activo no tiene logo registrado, NO toca el logo actual — se deja el que
 * hubiera, para no dejar la app sin marca por un dato faltante en el servidor.
 *
 * Bloqueante (descarga + copia de archivo): llamar desde un hilo en segundo plano.
 */
object LogoSyncHelper {

    private const val TAG = "LogoSyncHelper"

    fun sincronizarLogoDeCliente(context: Context, idSucursal: String, idClienteRespaldo: String) {
        val ctx = context.applicationContext
        try {
            // El idCliente de la sucursal activa es más confiable que el guardado
            // por separado en Ajustes (podría haber quedado desincronizado); si no
            // hay datos de sucursal, se usa el idCliente guardado directamente.
            val datosSucursal = SucursalDatosCache.obtener(ctx, idSucursal)
            val idCliente = datosSucursal?.idCliente?.takeIf { it > 0 }?.toString()
                ?: idClienteRespaldo
            if (idCliente.isBlank()) {
                Log.d(TAG, "Sin idCliente resuelto; no se sincroniza el logo de app")
                return
            }

            val logos = ClienteLogoCache.obtener(ctx, idCliente)
            val origenPath = logos?.logoIzquierdoPath
            if (origenPath.isNullOrBlank()) {
                Log.d(TAG, "El cliente $idCliente no tiene logo registrado; se conserva el logo actual")
                return
            }
            val origen = File(origenPath)
            if (!origen.exists()) return

            val extension = origen.extension.ifBlank { "png" }
            val destino = File(ctx.filesDir, "logo.$extension")
            origen.copyTo(destino, overwrite = true)

            ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
                .putString("LogoPath", destino.absolutePath)
                .apply()

            val intent = Intent("ACTION_UPDATE_LOGO").putExtra("logo_path", destino.absolutePath)
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)

            Log.d(TAG, "✅ Logo de app sincronizado con el cliente $idCliente")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar el logo del cliente: ${e.message}")
        }
    }
}
