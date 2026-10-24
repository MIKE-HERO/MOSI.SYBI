package com.sybi.mosi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.sybi.mosi.network.LogoClienteDto
import com.sybi.mosi.network.RetrofitClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
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

    /** BLOQUEANTE. Devuelve null si el cliente no tiene logos (ni locales ni de servidor). */
    fun obtener(context: Context, idCliente: String): Logos? {
        if (idCliente.isBlank()) return null
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val dto: LogoClienteDto? = obtenerDto(prefs, idCliente)
        val dirLogos = File(ctx.cacheDir, "clienteLogos").apply { mkdirs() }

        // Un override local (puesto a mano desde ClienteSettingsActivity) gana SIEMPRE sobre
        // lo que traiga el servidor para ese slot: no se re-descarga ni se sobrescribe.
        val izq = rutaOverride(ctx, idCliente, "izq")
            ?: dto?.logoIzquierdo?.let { descargarSiHaceFalta(dirLogos, idCliente, "izq", it) }
        val der = rutaOverride(ctx, idCliente, "der")
            ?: dto?.logoDerecho?.let { descargarSiHaceFalta(dirLogos, idCliente, "der", it) }
        val firma = rutaOverride(ctx, idCliente, "firma")
            ?: dto?.firma?.let { descargarSiHaceFalta(dirLogos, idCliente, "firma", it) }

        if (dto == null && izq == null && der == null && firma == null) return null

        return Logos(
            idCliente = dto?.idCliente ?: idCliente.toIntOrNull(),
            razonSocial = dto?.razonSocial,
            direccion = dto?.direccion,
            codigoPostal = dto?.codigoPostal,
            estado = dto?.estado,
            rfc = dto?.rfc,
            logoIzquierdoPath = izq,
            logoDerechoPath = der,
            firmaPath = firma,
            nombreFirma = dto?.nombreFirma,
            puesto = dto?.puesto
        )
    }

    /** Nombre + etiqueta de cada slot de imagen que puede tener un cliente. */
    val SLOTS: List<Pair<String, String>> = listOf(
        "izq" to "Logo izquierdo",
        "der" to "Logo derecho",
        "firma" to "Firma"
    )

    private fun dirOverrides(ctx: Context): File =
        File(ctx.filesDir, "clienteLogosOverride").apply { mkdirs() }

    /** Archivo de override local para ese cliente/slot, si existe (sea cual sea su extensión). */
    fun archivoOverride(context: Context, idCliente: String, slot: String): File? {
        val dir = dirOverrides(context.applicationContext)
        return dir.listFiles { f -> f.nameWithoutExtension == "${idCliente}_$slot" }?.firstOrNull()
    }

    private fun rutaOverride(ctx: Context, idCliente: String, slot: String): String? =
        archivoOverride(ctx, idCliente, slot)?.absolutePath

    /**
     * Ruta de la imagen ACTUAL de un slot para previsualizar en ClienteSettingsActivity:
     * el override local si hay uno, si no la última copia ya descargada del servidor (sin
     * forzar una descarga nueva — eso lo hace [obtener] cuando de verdad hace falta el logo).
     */
    fun rutaImagenSlotActual(context: Context, idCliente: String, slot: String): File? {
        val ctx = context.applicationContext
        archivoOverride(ctx, idCliente, slot)?.let { return it }
        val dirDescargas = File(ctx.cacheDir, "clienteLogos")
        // ".url" (el marcador de caché que guarda de qué URL vino la imagen, usado por
        // intentarDescargar para no redescargar si no cambió) comparte el mismo
        // nameWithoutExtension que la imagen real (p. ej. "155_izq.svg" y "155_izq.url" son
        // ambos "155_izq") — sin excluirlo, listFiles() podía devolver el marcador en vez de
        // la imagen según el orden del filesystem, y la miniatura se quedaba sin mostrar nada.
        return dirDescargas.listFiles { f ->
            f.nameWithoutExtension == "${idCliente}_$slot" && !f.extension.equals("url", ignoreCase = true)
        }?.firstOrNull()
    }

    /** Quita el override local de un slot (vuelve a depender de lo que traiga el servidor). */
    fun quitarOverride(context: Context, idCliente: String, slot: String) {
        archivoOverride(context.applicationContext, idCliente, slot)?.delete()
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

    /**
     * La API devuelve las URLs de logos/firmas tal cual quedó el nombre del archivo en el
     * servidor (p. ej. ".../136/68374e1871692_LOGO ML OK.svg", con espacio literal sin
     * codificar). `URL(url).openConnection()` manda ese espacio crudo en la petición y el
     * servidor (IIS) responde 400 Bad Request, así que el logo "existe" pero nunca se
     * encuentra. Aquí se reconstruye la URI codificando correctamente el path (espacios,
     * acentos, etc.) sin tocar esquema/host/puerto.
     */
    private fun normalizarUrl(url: String): URL {
        return try {
            URI(url).toURL()
        } catch (e: Exception) {
            val tmp = URL(url)
            URI(tmp.protocol, tmp.userInfo, tmp.host, tmp.port, tmp.path, tmp.query, null).toURL()
        }
    }

    private fun intentarDescargar(dir: File, idCliente: String, slot: String, url: String, ext: String): String? {
        val file = File(dir, "${idCliente}_$slot.$ext")
        val marcadorUrl = File(dir, "${idCliente}_$slot.url")

        // Ya está cacheada la imagen de esta misma URL: no volver a descargar.
        if (file.exists() && marcadorUrl.exists() && marcadorUrl.readText() == url) {
            return file.absolutePath
        }

        // Se descarga primero a un archivo temporal y solo se reemplaza el definitivo si la
        // descarga termina completa: si se escribiera directo sobre `file` y la conexión se
        // cortara a medias (p. ej. el servidor no manda el certificado intermedio y falla el
        // TLS mid-transfer), `file.outputStream()` ya habría truncado cualquier copia buena
        // anterior, dejando un archivo corrupto que luego se reusaría como si fuera válido.
        val temp = File(dir, "${idCliente}_$slot.$ext.tmp")
        return try {
            val conn = (normalizarUrl(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 15000
            }
            val codigo = conn.responseCode
            if (codigo !in 200..299) {
                conn.disconnect()
                return null
            }
            conn.inputStream.use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
            conn.disconnect()
            temp.copyTo(file, overwrite = true)
            temp.delete()
            marcadorUrl.writeText(url)
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo descargar logo ($slot, .$ext) del cliente $idCliente: ${e.message}")
            temp.delete()
            // Si ya había una copia previa COMPLETA (de otra URL), mejor usarla que no mostrar nada.
            if (file.exists()) file.absolutePath else null
        }
    }

    // Detecta el patrón "<svg>...<image ... xlink:href="data:image/png;base64,XXXX" ...>...</svg>":
    // no es un SVG vectorial, es una imagen rasterizada (a veces de varios MB / miles de px)
    // simplemente envuelta en una etiqueta <svg> para que el servidor pueda servirla como ".svg".
    private val patronImagenEmbebida = Regex(
        "xlink:href=\"data:image/(?:png|jpe?g);base64,([A-Za-z0-9+/=\\s]+)\"",
        RegexOption.IGNORE_CASE
    )

    /**
     * Decodifica en miniatura un logo que llegó como ".svg" pero cuyo contenido es en realidad
     * una imagen rasterizada embebida en base64 (ver [patronImagenEmbebida]).
     *
     * Por qué hace falta: el renderer de SVG (androidsvg, vía Coil SvgDecoder) decodifica esa
     * imagen embebida a su RESOLUCIÓN COMPLETA antes de componerla en el lienzo, sin importar
     * qué tan chico sea el ImageView de destino — hay logos reales en producción de 4000x2000px
     * (~33 MB de bitmap crudo) que el servidor entrega "envueltos" así. Decodificar eso para una
     * miniatura de 70dp de alto, tres veces seguidas (izq/der/firma), es lo que agota la memoria
     * en una tablet de 2GB de RAM y deja los logos sin mostrarse (fallback a ic_info) aunque la
     * descarga haya sido exitosa.
     *
     * Aquí se extrae el base64 embebido y se decodifica directo con BitmapFactory +
     * inSampleSize, el mismo truco de downsampling real que ya usa el resto de la app.
     *
     * Devuelve null si el archivo no es SVG, o si SÍ es un SVG vectorial genuino (sin imagen
     * embebida) — en ese caso el caller debe seguir usando el pipeline normal de Coil/SvgDecoder.
     */
    fun decodificarSvgEnvueltoComoImagen(file: File, anchoObjetivoPx: Int): Bitmap? {
        if (!file.extension.equals("svg", ignoreCase = true)) return null
        return try {
            val texto = file.bufferedReader().use { it.readText() }
            val match = patronImagenEmbebida.find(texto) ?: return null
            val base64 = match.groupValues[1].replace("\\s".toRegex(), "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null

            var sample = 1
            val objetivo = anchoObjetivoPx.coerceAtLeast(1)
            while (bounds.outWidth / (sample * 2) >= objetivo) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo decodificar la imagen embebida del SVG ${file.name}: ${e.message}")
            null
        }
    }
}
