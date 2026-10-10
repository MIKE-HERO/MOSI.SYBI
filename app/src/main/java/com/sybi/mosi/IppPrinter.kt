package com.sybi.mosi

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente IPP mínimo para imprimir un PDF directo a una impresora de red,
 * sin diálogo del sistema ni visor externo.
 *
 * Antes de imprimir consulta a la impresora qué formatos admite
 * (Get-Printer-Attributes) y elige el adecuado:
 *   - application/pdf si lo soporta,
 *   - si no, application/octet-stream (auto-detección) si está disponible.
 * Si no acepta ninguno de esos, devuelve un error indicando qué formatos sí
 * admite (para decidir el siguiente paso, p. ej. rasterizar).
 *
 * Envía una operación Print-Job (RFC 8011 / IPP 2.0) por HTTP al puerto IPP
 * de la impresora (631 por defecto). No agrega dependencias.
 */
object IppPrinter {

    private const val TAG = "IppPrinter"

    // Etiquetas delimitadoras / de valor (RFC 8011 §5.4)
    private const val TAG_OPERATION_ATTRS = 0x01
    private const val TAG_END_ATTRS = 0x03
    private const val TAG_CHARSET = 0x47
    private const val TAG_NATURAL_LANGUAGE = 0x48
    private const val TAG_URI = 0x45
    private const val TAG_KEYWORD = 0x44
    private const val TAG_NAME_WITHOUT_LANG = 0x42
    private const val TAG_MIME_MEDIA_TYPE = 0x49

    private const val OP_PRINT_JOB = 0x0002
    private const val OP_GET_PRINTER_ATTRIBUTES = 0x000B

    private const val FMT_PDF = "application/pdf"
    private const val FMT_OCTET = "application/octet-stream"

    data class Resultado(val ok: Boolean, val mensaje: String)

    /**
     * Imprime [pdf] en la impresora [host]:[port][path].
     * Bloqueante: llamar desde un hilo en segundo plano.
     */
    fun imprimirPdf(
        host: String,
        port: Int = 631,
        path: String = "/ipp/print",
        jobName: String = "MOSI",
        usuario: String = "kiosco",
        pdf: ByteArray
    ): Resultado {
        val hostLimpio = host.trim()
        if (hostLimpio.isEmpty()) {
            return Resultado(false, "No hay IP de impresora configurada")
        }
        val rutaNorm = if (path.startsWith("/")) path else "/$path"
        val printerUri = "ipp://$hostLimpio:$port$rutaNorm"
        val httpUrl = "http://$hostLimpio:$port$rutaNorm"

        // 1) Negociar formato según lo que soporte la impresora.
        val soportados = obtenerFormatosSoportados(httpUrl, printerUri, usuario)
        val formato: String = when {
            soportados == null -> FMT_OCTET // no se pudo preguntar: intentamos auto-detección
            soportados.contains(FMT_PDF) -> FMT_PDF
            soportados.contains(FMT_OCTET) -> FMT_OCTET
            else -> return Resultado(
                false,
                "La impresora no acepta PDF. Formatos que admite: ${soportados.joinToString(", ")}"
            )
        }
        Log.d(TAG, "Formato elegido=$formato (soportados=${soportados?.joinToString()})")

        // 2) Enviar el trabajo.
        return try {
            val ippRequest = construirPrintJob(printerUri, jobName, usuario, formato, pdf)
            val resp = postIpp(httpUrl, ippRequest) ?: return Resultado(false, "Sin respuesta de la impresora")

            val status = statusIpp(resp.cuerpo)
            if (resp.http !in 200..299) {
                return Resultado(false, "La impresora respondió HTTP ${resp.http}")
            }
            Log.d(TAG, "Print-Job status=0x${hex(status)} http=${resp.http}")

            if (status in 0x0000..0x00FF) {
                val nota = if (formato == FMT_OCTET) " (auto-detección)" else ""
                Resultado(true, "Enviado a la impresora$nota")
            } else {
                Resultado(false, "La impresora rechazó el trabajo: ${mensajeEstado(status)}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error imprimiendo por IPP a $httpUrl: ${e.message}", e)
            Resultado(false, "No se pudo contactar la impresora: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Get-Printer-Attributes -> document-format-supported
    // ------------------------------------------------------------------
    private fun obtenerFormatosSoportados(
        httpUrl: String,
        printerUri: String,
        usuario: String
    ): Set<String>? {
        return try {
            val bos = ByteArrayOutputStream()
            val out = DataOutputStream(bos)
            out.writeByte(0x02); out.writeByte(0x00)
            out.writeShort(OP_GET_PRINTER_ATTRIBUTES)
            out.writeInt(1)
            out.writeByte(TAG_OPERATION_ATTRS)
            escribirAtributo(out, TAG_CHARSET, "attributes-charset", "utf-8")
            escribirAtributo(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
            escribirAtributo(out, TAG_URI, "printer-uri", printerUri)
            escribirAtributo(out, TAG_NAME_WITHOUT_LANG, "requesting-user-name", usuario)
            // requested-attributes (multivalor)
            escribirAtributo(out, TAG_KEYWORD, "requested-attributes", "document-format-supported")
            escribirValorAdicional(out, TAG_KEYWORD, "document-format-default")
            out.writeByte(TAG_END_ATTRS)
            out.flush()

            val resp = postIpp(httpUrl, bos.toByteArray()) ?: return null
            if (resp.http !in 200..299) return null
            val valores = extraerValoresString(resp.cuerpo, "document-format-supported")
            if (valores.isEmpty()) null else valores.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudieron obtener formatos soportados: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Print-Job
    // ------------------------------------------------------------------
    private fun construirPrintJob(
        printerUri: String,
        jobName: String,
        usuario: String,
        documentFormat: String,
        pdf: ByteArray
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)

        out.writeByte(0x02); out.writeByte(0x00)        // version 2.0
        out.writeShort(OP_PRINT_JOB)                    // operation-id
        out.writeInt(1)                                 // request-id

        out.writeByte(TAG_OPERATION_ATTRS)
        escribirAtributo(out, TAG_CHARSET, "attributes-charset", "utf-8")
        escribirAtributo(out, TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        escribirAtributo(out, TAG_URI, "printer-uri", printerUri)
        escribirAtributo(out, TAG_NAME_WITHOUT_LANG, "requesting-user-name", usuario)
        escribirAtributo(out, TAG_NAME_WITHOUT_LANG, "job-name", jobName)
        escribirAtributo(out, TAG_MIME_MEDIA_TYPE, "document-format", documentFormat)
        out.writeByte(TAG_END_ATTRS)

        out.flush()
        bos.write(pdf)                                  // datos del documento
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------
    // Transporte HTTP
    // ------------------------------------------------------------------
    private data class Respuesta(val http: Int, val cuerpo: ByteArray)

    private fun postIpp(httpUrl: String, cuerpo: ByteArray): Respuesta? {
        val conn = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Accept", "application/ipp")
            setFixedLengthStreamingMode(cuerpo.size)
        }
        return try {
            conn.outputStream.use { it.write(cuerpo); it.flush() }
            val http = conn.responseCode
            val body = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            Respuesta(http, body)
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Parseo / helpers IPP
    // ------------------------------------------------------------------
    /** status-code IPP: bytes [2..3] del cuerpo (tras los 2 de versión). */
    private fun statusIpp(cuerpo: ByteArray): Int =
        if (cuerpo.size >= 4) ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[3].toInt() and 0xFF) else -1

    /** Extrae todos los valores string del atributo [nombre] en una respuesta IPP. */
    private fun extraerValoresString(resp: ByteArray, nombre: String): List<String> {
        val out = ArrayList<String>()
        var i = 8 // version(2)+status(2)+request-id(4)
        var nombreActual: String? = null
        while (i < resp.size) {
            val tag = resp[i].toInt() and 0xFF
            i++
            if (tag == TAG_END_ATTRS) break
            if (tag < 0x10) { // etiqueta delimitadora de grupo
                nombreActual = null
                continue
            }
            if (i + 2 > resp.size) break
            val nameLen = leerShort(resp, i); i += 2
            if (i + nameLen > resp.size) break
            val name = if (nameLen > 0) String(resp, i, nameLen, Charsets.US_ASCII) else null
            i += nameLen
            if (i + 2 > resp.size) break
            val valLen = leerShort(resp, i); i += 2
            if (i + valLen > resp.size) break
            val esString = tag in 0x40..0x4F
            val valor = if (esString) String(resp, i, valLen, Charsets.UTF_8) else null
            i += valLen

            val attr = name ?: nombreActual
            if (nameLen > 0) nombreActual = name
            if (attr == nombre && valor != null) out.add(valor)
        }
        return out
    }

    private fun leerShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun hex(v: Int): String = Integer.toHexString(v)

    private fun mensajeEstado(status: Int): String = when (status) {
        0x0400 -> "petición inválida (0x0400)"
        0x0401 -> "acceso prohibido (0x0401)"
        0x0402, 0x0403 -> "no autorizado (0x${hex(status)})"
        0x0406 -> "ruta IPP no encontrada (0x0406) — revisa la ruta de impresión"
        0x0408 -> "documento demasiado grande (0x0408)"
        0x040A -> "no acepta PDF por IPP directo (0x040A, formato no soportado)"
        0x040B -> "atributos no soportados (0x040B)"
        0x040C -> "esquema de URI no soportado (0x040C)"
        0x0501 -> "operación no soportada por la impresora (0x0501)"
        0x0506 -> "versión IPP no soportada (0x0506)"
        else -> "estado IPP 0x${hex(status)}"
    }

    private fun escribirAtributo(out: DataOutputStream, tag: Int, nombre: String, valor: String) {
        val nb = nombre.toByteArray(Charsets.US_ASCII)
        val vb = valor.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nb.size); out.write(nb)
        out.writeShort(vb.size); out.write(vb)
    }

    /** Valor adicional de un atributo multivalor (nombre de longitud 0). */
    private fun escribirValorAdicional(out: DataOutputStream, tag: Int, valor: String) {
        val vb = valor.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(0)
        out.writeShort(vb.size); out.write(vb)
    }
}
