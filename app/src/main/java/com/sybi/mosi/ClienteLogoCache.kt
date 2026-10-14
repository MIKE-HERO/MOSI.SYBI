package com.sybi.mosi

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.sybi.mosi.network.LogoClienteDto
import com.sybi.mosi.network.RetrofitClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Logos y razón social del cliente (subrogado) para el encabezado y pie de
 * página del PDF de resultados, vía GET usuariosWeb/logosCliente.
 *
 * Las imágenes llegan como URLs https (no base64), así que se descargan y se
 * cachean en disco para no depender de internet en cada impresión. También
 * se cachea la respuesta (razón social/dirección) por si el cliente no tiene
 * red al momento de imprimir.
 *
 * Todo bloqueante: llamar siempre desde un hilo en segundo plano.
 */
object ClienteLogoCache {

    private const val TAG = "ClienteLogoCache"
    private const val PREFS = "ClienteLogoCache"

    data class Logos(
        val idCliente: Int?,
        val razonSocial: String?,
        val direccion: String?,
        val codigoPostal: String?,
        val estado: String?,
        val rfc: String?,
        val logoIzquierdoPath: String?,
        val logoDerechoPath: String?,
        val firmaPath: String?,
        val nombreFirma: String?,
        val puesto: String?
    )

    /** BLOQUEANTE. Devuelve null si el cliente no tiene logos o no se pudo resolver nada. */
    fun obtener(context: Context, idCliente: String): Logos? {
        if (idCliente.isBlank()) return null
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val dto: LogoClienteDto = obtenerDto(prefs, idCliente) ?: return null

        val dirLogos = File(ctx.cacheDir, "clienteLogos").apply { mkdirs() }
        val izq = descargarSiHaceFalta(dirLogos, idCliente, "izq", dto.logoIzquierdo)
        val der = descargarSiHaceFalta(dirLogos, idCliente, "der", dto.logoDerecho)
        val firma = descargarSiHaceFalta(dirLogos, idCliente, "firma", dto.firma)

        return Logos(
            idCliente = dto.idCliente,
            razonSocial = dto.razonSocial,
            direccion = dto.direccion,
            codigoPostal = dto.codigoPostal,
            estado = dto.estado,
            rfc = dto.rfc,
            logoIzquierdoPath = izq,
            logoDerechoPath = der,
            firmaPath = firma,
            nombreFirma = dto.nombreFirma,
            puesto = dto.puesto
        )
    }

    private fun obtenerDto(prefs: android.content.SharedPreferences, idCliente: String): LogoClienteDto? {
        val remoto = try {
            val resp = RetrofitClient.apiService.obtenerLogosCliente(idCliente).execute()
            if (resp.isSuccessful) resp.body()?.logos else null
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar logos del cliente $idCliente: ${e.message}")
            null
        }

        if (remoto != null) {
            prefs.edit().putString("dto_$idCliente", Gson().toJson(remoto)).apply()
            return remoto
        }

        // Sin respuesta útil del servidor (sin red, error, o cliente sin logos):
        // usar lo último que se haya cacheado para este cliente, si existe.
        val cachedJson = prefs.getString("dto_$idCliente", null) ?: return null
        return try {
            Gson().fromJson(cachedJson, LogoClienteDto::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Caché de logos de cliente $idCliente corrupta: ${e.message}")
            null
        }
    }

    /**
     * Resuelve el logo de un slot (izq/der/firma) PREFIRIENDO su versión SVG:
     * se prueba la misma ruta que dio la API pero con extensión .svg antes
     * que el archivo "normal" (el que realmente trae logoIzquierdo/Derecho);
     * si el SVG no existe en el servidor, se usa el archivo normal tal cual.
     * Si ninguno se puede resolver, devuelve null (InformeBuilder cae al logo
     * por defecto de la app).
     */
    private fun descargarSiHaceFalta(dir: File, idCliente: String, slot: String, url: String?): String? {
        if (url.isNullOrBlank()) return null

        val urlSvg = construirUrlSvgHermana(url)
        if (urlSvg != null) {
            val pathSvg = intentarDescargar(dir, idCliente, slot, urlSvg, "svg")
            if (pathSvg != null) return pathSvg
        }

        val ext = url.substringAfterLast('.', "png").take(4).ifBlank { "png" }
        return intentarDescargar(dir, idCliente, slot, url, ext)
    }

    /**
     * Misma ruta que [url] pero cambiando su extensión a .svg (p. ej.
     * ".../archivo.png" -> ".../archivo.svg"). Devuelve null si [url] ya es
     * un .svg (no hay "hermano" que buscar) o si no tiene extensión real.
     */
    private fun construirUrlSvgHermana(url: String): String? {
        val ultimoPunto = url.lastIndexOf('.')
        val ultimaBarra = url.lastIndexOf('/')
        if (ultimoPunto <= ultimaBarra) return null
        val extensionActual = url.substring(ultimoPunto + 1)
        if (extensionActual.equals("svg", ignoreCase = true)) return null
        return url.substring(0, ultimoPunto) + ".svg"
    }

    private fun intentarDescargar(dir: File, idCliente: String, slot: String, url: String, ext: String): String? {
        val file = File(dir, "${idCliente}_$slot.$ext")
        val marcadorUrl = File(dir, "${idCliente}_$slot.url")

        // Ya está cacheada la imagen de esta misma URL: no volver a descargar.
        if (file.exists() && marcadorUrl.exists() && marcadorUrl.readText() == url) {
            return file.absolutePath
        }

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
            }
            val codigo = conn.responseCode
            if (codigo !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            conn.disconnect()
            marcadorUrl.writeText(url)
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar logo ($slot, .$ext) del cliente $idCliente: ${e.message}")
            // Si ya había una copia previa (de otra URL), mejor usarla que no mostrar nada.
            if (file.exists()) file.absolutePath else null
        }
    }
}
