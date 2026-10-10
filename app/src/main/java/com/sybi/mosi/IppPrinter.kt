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
 * Envía una operación Print-Job (RFC 8011 / IPP 2.0) por HTTP al puerto IPP
 * de la impresora (631 por defecto) con el documento en application/pdf.
 * Funciona con impresoras de oficina modernas compatibles con IPP Everywhere /
 * AirPrint (la gran mayoría de láser e inyección de los últimos años).
 *
 * No agrega dependencias: arma el mensaje IPP binario a mano.
 */
object IppPrinter {

    private const val TAG = "IppPrinter"

    // Etiquetas IPP (RFC 8011 §5.4.2 / 5.4.4)
    private const val TAG_OPERATION_ATTRS = 0x01
    private const val TAG_END_ATTRS = 0x03
    private const val TAG_CHARSET = 0x47
    private const val TAG_NATURAL_LANGUAGE = 0x48
    private const val TAG_URI = 0x45
    private const val TAG_NAME_WITHOUT_LANG = 0x42
    private const val TAG_MIME_MEDIA_TYPE = 0x49

    private const val OP_PRINT_JOB = 0x0002

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

        return try {
            val ippRequest = construirPrintJob(printerUri, jobName, usuario, pdf)

            val conn = (URL(httpUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/ipp")
                setRequestProperty("Accept", "application/ipp")
                setFixedLengthStreamingMode(ippRequest.size)
            }

            conn.outputStream.use { it.write(ippRequest); it.flush() }

            val http = conn.responseCode
            val cuerpo = (if (http in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()

            if (http !in 200..299) {
                return Resultado(false, "La impresora respondió HTTP $http")
            }

            // status-code IPP: bytes [2..3] del cuerpo (tras los 2 de versión)
            val status = if (cuerpo.size >= 4) {
                ((cuerpo[2].toInt() and 0xFF) shl 8) or (cuerpo[3].toInt() and 0xFF)
            } else -1

            Log.d(TAG, "IPP status=0x${Integer.toHexString(status)} http=$http uri=$printerUri")

            // successful-ok = 0x0000, -ignored-attrs = 0x0001, -conflicting = 0x0002
            if (status in 0x0000..0x00FF) {
                Resultado(true, "Enviado a la impresora")
            } else {
                Resultado(false, "La impresora rechazó el trabajo (estado IPP 0x${Integer.toHexString(status)})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error imprimiendo por IPP a $httpUrl: ${e.message}", e)
            Resultado(false, "No se pudo contactar la impresora: ${e.message}")
        }
    }

    private fun construirPrintJob(
        printerUri: String,
        jobName: String,
        usuario: String,
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
        escribirAtributo(out, TAG_MIME_MEDIA_TYPE, "document-format", "application/pdf")
        out.writeByte(TAG_END_ATTRS)

        out.flush()
        bos.write(pdf)                                  // datos del documento
        return bos.toByteArray()
    }

    private fun escribirAtributo(out: DataOutputStream, tag: Int, nombre: String, valor: String) {
        val nb = nombre.toByteArray(Charsets.US_ASCII)
        val vb = valor.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(nb.size); out.write(nb)
        out.writeShort(vb.size); out.write(vb)
    }
}
