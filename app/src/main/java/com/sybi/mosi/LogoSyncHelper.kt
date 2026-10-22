package com.sybi.mosi

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

/**
 * Sincroniza el logo "de marca" de la app (AppPrefs.LogoPath — se usa en toda
 * la app: pantalla principal, PDF, etc.) con uno de los logos del cliente
 * activo del kiosco, descargado/override vía ClienteLogoCache. Se dispara al
 * guardar la configuración de telemedicina (cliente/sucursal) en Ajustes, para
 * que al cambiar de cliente el logo de la app cambie con él automáticamente,
 * y también cuando el usuario reemplaza manualmente una imagen en Ajustes.
 *
 * Por defecto usa el logo IZQUIERDO (el principal). Si el usuario eligió
 * explícitamente el logo DERECHO como el que quiere ver en la app (vía
 * [recordarSlotPreferido]), ese se respeta en sincronizaciones futuras para
 * ese mismo cliente, hasta que elija lo contrario.
 *
 * Reemplaza cualquier logo anterior (manual o de otro cliente). Si el cliente
 * activo no tiene logo registrado en el slot preferido, NO toca el logo
 * actual — se deja el que hubiera, para no dejar la app sin marca por un dato
 * faltante en el servidor.
 *
 * Bloqueante (descarga + copia de archivo): llamar desde un hilo en segundo plano.
 */
object LogoSyncHelper {

    private const val TAG = "LogoSyncHelper"
    private const val PREFS = "AppPrefs"
    private const val PREF_SLOT_PREFIX = "LogoSlotPreferido_"

    /** Recuerda que, para este cliente, el logo que debe verse en la app es el del [slot]
     *  dado ("izq" o "der") — para que una futura sincronización (p. ej. al "Guardar") no
     *  lo pise de vuelta con el izquierdo por defecto. */
    private fun recordarSlotPreferido(ctx: Context, idCliente: String, slot: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("$PREF_SLOT_PREFIX$idCliente", slot)
            .apply()
    }

    private fun slotPreferido(ctx: Context, idCliente: String): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("$PREF_SLOT_PREFIX$idCliente", "izq") ?: "izq"

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
            val origenPath = when (slotPreferido(ctx, idCliente)) {
                "der" -> logos?.logoDerechoPath ?: logos?.logoIzquierdoPath
                else -> logos?.logoIzquierdoPath
            }
            aplicarComoLogoDeApp(ctx, idCliente, origenPath)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo sincronizar el logo del cliente: ${e.message}")
        }
    }

    /**
     * Aplica de inmediato el archivo [origenPath] (override local recién elegido por el
     * usuario para el slot "izq" o "der") como logo de la app, y recuerda esa elección
     * para que futuras sincronizaciones de este cliente la respeten.
     */
    fun aplicarLogoSeleccionado(context: Context, idCliente: String, slot: String, origenPath: String) {
        val ctx = context.applicationContext
        try {
            recordarSlotPreferido(ctx, idCliente, slot)
            aplicarComoLogoDeApp(ctx, idCliente, origenPath)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo aplicar el logo seleccionado: ${e.message}")
        }
    }

    private fun aplicarComoLogoDeApp(ctx: Context, idCliente: String, origenPath: String?) {
        if (origenPath.isNullOrBlank()) {
            Log.d(TAG, "El cliente $idCliente no tiene logo registrado en ese slot; se conserva el logo actual")
            return
        }
        val origen = File(origenPath)
        if (!origen.exists()) return

        val extension = origen.extension.ifBlank { "png" }
        val destino = File(ctx.filesDir, "logo.$extension")
        origen.copyTo(destino, overwrite = true)

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("LogoPath", destino.absolutePath)
            .apply()

        val intent = Intent("ACTION_UPDATE_LOGO").putExtra("logo_path", destino.absolutePath)
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)

        Log.d(TAG, "✅ Logo de app sincronizado con el cliente $idCliente")
    }
}
