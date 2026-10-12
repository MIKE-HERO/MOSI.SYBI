package com.sybi.mosi

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.sybi.mosi.network.DatosSucursalDto
import com.sybi.mosi.network.RetrofitClient

/**
 * Dirección de la SUCURSAL activa del kiosco y nombre del proyecto, para el
 * pie de página del PDF. No es lo mismo que el cliente pagante: en este
 * sistema cada "cliente" (idCliente) representa un proyecto/programa, así
 * que el pie debe mostrar la sucursal + su proyecto, no la razón social del
 * cliente a secas.
 *
 * Se cachea la respuesta en disco por si no hay red al momento de imprimir
 * (igual que ClienteLogoCache). Bloqueante: llamar desde un hilo en segundo plano.
 */
object SucursalDatosCache {

    private const val TAG = "SucursalDatosCache"
    private const val PREFS = "SucursalDatosCache"

    /** BLOQUEANTE. Devuelve null si la sucursal no existe o no se pudo resolver nada. */
    fun obtener(context: Context, idSucursal: String): DatosSucursalDto? {
        if (idSucursal.isBlank()) return null
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val remoto = try {
            val resp = RetrofitClient.apiService.obtenerDatosSucursal(idSucursal).execute()
            if (resp.isSuccessful) resp.body()?.sucursal else null
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar datos de sucursal $idSucursal: ${e.message}")
            null
        }

        if (remoto != null) {
            prefs.edit().putString("dto_$idSucursal", Gson().toJson(remoto)).apply()
            return remoto
        }

        // Sin respuesta útil (sin red, error, o sucursal inexistente):
        // usar lo último cacheado para esta sucursal, si existe.
        val cachedJson = prefs.getString("dto_$idSucursal", null) ?: return null
        return try {
            Gson().fromJson(cachedJson, DatosSucursalDto::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Caché de datos de sucursal $idSucursal corrupta: ${e.message}")
            null
        }
    }
}
