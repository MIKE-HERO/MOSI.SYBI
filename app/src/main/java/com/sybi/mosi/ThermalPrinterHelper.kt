package com.sybi.mosi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.serialport.api.SerialPort
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset

object ThermalPrinterHelper {
    private const val TAG = "ThermalPrinterHelper"

    // ============================================
    // CONFIGURACIÓN DE PÁGINA DE CÓDIGOS
    // ============================================
    private const val CODEPAGE_NUM = 16
    private const val CODEPAGE_CHARSET = "windows-1252"
    private const val USAR_ACENTOS = true

    // ============================================
    // CONFIGURACIÓN DEL QR PARA SP-RMD8CS1H
    // ============================================
    // 120x120 px es ideal para el ancho de 384 puntos de la SP-RMD8.
    // Cabe perfectamente y es escaneable.
    private const val QR_WIDTH = 120
    private const val QR_HEIGHT = 120

    /**
     * Imprime el ticket de resultados en la impresora térmica.
     * (Esta firma NO cambia, tu ResultsActivity sigue funcionando igual).
     */
    fun imprimirTicket(
        context: Context,
        paciente: Paciente?,
        resultados: List<Resultado>,
        customTitle: String? = null,
        customSubtitle: String? = null
    ): Boolean {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER, true)
        if (!enabled) {
            Log.w(TAG, "⚠️ Impresora térmica desactivada en ajustes")
            return false
        }

        val r = resultados.firstOrNull()
        if (r == null) {
            Log.w(TAG, "⚠️ Lista de resultados vacía, no se imprime nada")
            return false
        }
        Log.d(TAG, "🖨️ Imprimiendo UNA sola medición: id=${r.id_resultado}, fecha=${r.fecha_medicion}")

        val portPath = prefs.getString("${TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER}_port", "/dev/ttyS1") ?: "/dev/ttyS1"
        val baudRate = SerialBaudConfig.get(prefs, TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER)

        val file = File(portPath)
        if (!file.exists()) {
            Log.e(TAG, "❌ Puerto de impresora térmica $portPath no existe")
            return false
        }

        var outputStream: OutputStream? = null
        var serialPort: SerialPort? = null

        try {
            try {
                serialPort = SerialPort.getSerialPort(portPath, baudRate)
                outputStream = serialPort.outputStream
                Log.d(TAG, "✅ Puerto abierto con SerialPort (modo raw)")
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ SerialPort falló (${e.message}), usando FileOutputStream")
                outputStream = FileOutputStream(file)
            }

            if (outputStream == null) {
                Log.e(TAG, "❌ No se pudo obtener OutputStream para $portPath")
                return false
            }

            val charset = Charset.forName(CODEPAGE_CHARSET)

            // ============================================
            // COMANDOS ESC/POS
            // ============================================
            val INIT = byteArrayOf(0x1B, 0x40)
            val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
            val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
            val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
            val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
            val REVERSE_OFF = byteArrayOf(0x1D, 0x42, 0x00)
            val UPSIDE_DOWN_OFF = byteArrayOf(0x1B, 0x7B, 0x00)
            val SELECT_CODEPAGE = byteArrayOf(0x1B, 0x74, CODEPAGE_NUM.toByte())
            val FEED_LINES = byteArrayOf(0x1B, 0x64, 0x05)
            val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

            // INICIALIZACIÓN ROBUSTA
            outputStream.write(INIT)
            outputStream.write(REVERSE_OFF)
            outputStream.write(UPSIDE_DOWN_OFF)
            outputStream.write(SELECT_CODEPAGE)
            outputStream.flush()
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            outputStream.write(INIT)
            outputStream.write(REVERSE_OFF)
            outputStream.write(UPSIDE_DOWN_OFF)
            outputStream.write(SELECT_CODEPAGE)
            outputStream.flush()
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            fun String.prepararParaImpresora(): String {
                return if (USAR_ACENTOS) this else this.sanitizarParaImpresora()
            }

            fun writeLine(text: String, bold: Boolean = false, alignCenter: Boolean = false) {
                if (alignCenter) outputStream.write(ALIGN_CENTER) else outputStream.write(ALIGN_LEFT)
                if (bold) outputStream.write(BOLD_ON) else outputStream.write(BOLD_OFF)
                outputStream.write((text.prepararParaImpresora() + "\n").toByteArray(charset))
                outputStream.flush()
            }

            fun writeDivider() {
                writeLine("--------------------------------")
            }

            // --- TÍTULO Y SUBTÍTULO ---
            val appPrefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val defaultTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
            val title = customTitle?.takeIf { it.isNotBlank() } ?: appPrefs.getString("PdfTitle", defaultTitle) ?: defaultTitle
            val subtitle = customSubtitle?.takeIf { it.isNotBlank() } ?: appPrefs.getString("PdfSubtitle", "") ?: ""

            writeLine("")
            writeLine(title, bold = true, alignCenter = true)
            if (subtitle.isNotBlank()) {
                writeLine(subtitle, bold = false, alignCenter = true)
            }
            writeDivider()

            // --- DATOS DEL PACIENTE ---
            writeLine("DATOS DEL PACIENTE", bold = true, alignCenter = false)
            val nombreCompleto = "${paciente?.nombre ?: ""} ${paciente?.apellido_paterno ?: ""} ${paciente?.apellido_materno ?: ""}".trim().ifEmpty { "Público General" }
            writeLine("Nombre: $nombreCompleto")

            val fechaNac = paciente?.fecha_nacimiento ?: ""
            val edad = calcularEdad(fechaNac)
            if (edad >= 0) {
                writeLine("Edad: $edad años")
            }
            val genero = when (paciente?.genero?.uppercase()) {
                "M", "MASCULINO" -> "Masculino"
                "F", "FEMENINO" -> "Femenino"
                else -> paciente?.genero ?: "No especificado"
            }
            writeLine("Género: $genero")

            val fechaHora = r.fecha_medicion.ifBlank {
                android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", java.util.Date()).toString()
            }
            writeLine("Fecha/Hora: $fechaHora")
            writeDivider()

            // --- PREFERENCIAS ---
            val showAlturaPeso = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
            val showComposicion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
            val showPresion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
            val showOxigeno = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
            val showTemperatura = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
            val showEcg = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

            writeLine("RESULTADOS DE MEDICIÓN", bold = true, alignCenter = true)

            if (showAlturaPeso && (r.altura.isNotBlank() || r.peso.isNotBlank())) {
                writeLine("")
                writeLine("--- ALTURA Y PESO ---", bold = true, alignCenter = true)
                if (r.altura.isNotBlank()) writeLine("Altura: ${r.altura} cm")
                if (r.peso.isNotBlank()) writeLine("Peso: ${r.peso} kg")
                if (r.imc.isNotBlank()) writeLine("IMC: ${r.imc}")
            }

            if (showComposicion && (r.grasa_corporal.isNotBlank() || r.agua_corporal.isNotBlank() || r.masa_muscular.isNotBlank() || r.metabolismo_basal.isNotBlank())) {
                writeLine("")
                writeLine("--- COMPOSICIÓN CORPORAL ---", bold = true, alignCenter = true)
                if (r.grasa_corporal.isNotBlank()) writeLine("Grasa Corporal: ${r.grasa_corporal}% (${r.grasa_corporal_kg} kg)")
                if (r.agua_corporal.isNotBlank()) writeLine("Agua Corporal: ${r.agua_corporal}% (${r.agua_corporal_kg} kg)")
                if (r.masa_muscular.isNotBlank()) writeLine("Masa Muscular: ${r.masa_muscular} kg")
                if (r.masa_libre_grasa.isNotBlank()) writeLine("Masa Libre Grasa: ${r.masa_libre_grasa} kg")
                if (r.proteina.isNotBlank()) writeLine("Proteína: ${r.proteina} kg")
                if (r.minerales.isNotBlank()) writeLine("Minerales: ${r.minerales} kg")
                if (r.metabolismo_basal.isNotBlank()) writeLine("Metabolismo Basal: ${r.metabolismo_basal} kcal")
                if (r.grasa_visceral.isNotBlank()) writeLine("Grasa Visceral: ${r.grasa_visceral}")
                if (r.peso_ideal.isNotBlank()) writeLine("Peso Ideal: ${r.peso_ideal} kg")
                if (r.tipo_grasa.isNotBlank()) writeLine("Tipo de Grasa: ${r.tipo_grasa}")
            }

            if (showPresion && r.sistolica.isNotBlank() && r.diastolica.isNotBlank()) {
                writeLine("")
                writeLine("--- PRESIÓN ARTERIAL ---", bold = true, alignCenter = true)
                writeLine("Presión: ${r.sistolica}/${r.diastolica} mmHg")
                if (r.pulso.isNotBlank()) writeLine("Pulso: ${r.pulso} bpm")
            }

            if (showOxigeno && r.spo2.isNotBlank()) {
                writeLine("")
                writeLine("--- OXÍGENO EN SANGRE ---", bold = true, alignCenter = true)
                writeLine("SpO2: ${r.spo2}%")
                if (r.frecuencia_pulso.isNotBlank()) writeLine("Pulso: ${r.frecuencia_pulso} bpm")
                if (r.indice_perfusion.isNotBlank()) writeLine("Índice Perfusión: ${r.indice_perfusion}")
            }

            if (showTemperatura && r.temperatura.isNotBlank()) {
                writeLine("")
                writeLine("--- TEMPERATURA CORPORAL ---", bold = true, alignCenter = true)
                writeLine("Temperatura: ${r.temperatura} °C / ${r.temperatura_f} °F")
            }

            if (showEcg && (r.frecuencia_cardiaca.isNotBlank() || r.resultado_ecg.isNotBlank())) {
                writeLine("")
                writeLine("--- ELECTROCARDIOGRAMA ---", bold = true, alignCenter = true)
                writeLine("Frec. Cardíaca: ${r.frecuencia_cardiaca} bpm")
                if (r.eje_p.isNotBlank()) writeLine("Eje P/QRS/T: ${r.eje_p}/${r.eje_qrs}/${r.eje_t}")
                if (r.intervalo_pr.isNotBlank()) writeLine("Int. PR / QRS: ${r.intervalo_pr}/${r.duracion_qrs} ms")
                if (r.intervalo_qt.isNotBlank()) writeLine("Int. QT / QTc: ${r.intervalo_qt}/${r.qt_corregido} ms")
                if (r.resultado_ecg.isNotBlank()) {
                    writeLine("Ritmo: ${r.resultado_ecg}")
                }
            }

            writeDivider()
            writeLine("¡Gracias por su visita!", bold = true, alignCenter = true)

            // ✅ AVANZAR LÍNEAS Y CORTAR
            outputStream.write(FEED_LINES)
            outputStream.flush()
            try { Thread.sleep(150) } catch (_: InterruptedException) {}

            outputStream.write(CUT)
            outputStream.flush()
            try { Thread.sleep(150) } catch (_: InterruptedException) {}

            outputStream.write(INIT)
            outputStream.flush()

            try {
                if (outputStream is FileOutputStream) {
                    outputStream.fd.sync()
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo hacer fsync: ${e.message}")
            }

            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            Log.i(TAG, "✅ Ticket impreso exitosamente en $portPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error imprimiendo ticket: ${e.message}", e)
            return false
        } finally {
            try {
                outputStream?.flush()
                if (outputStream is FileOutputStream) {
                    try { outputStream.fd.sync() } catch (_: Exception) {}
                }
                outputStream?.close()
            } catch (_: Exception) {}

            try {
                serialPort?.close()
            } catch (_: Exception) {}
        }
    }

    private fun calcularEdad(fechaNacimiento: String): Int {
        return try {
            val parts = fechaNacimiento.split("/")
            if (parts.size == 3) {
                val year = parts[2].toInt()
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                currentYear - year
            } else -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun String.sanitizarParaImpresora(): String {
        return this
            .replace("á", "a").replace("é", "e").replace("í", "i")
            .replace("ó", "o").replace("ú", "u").replace("ü", "u")
            .replace("Á", "A").replace("É", "E").replace("Í", "I")
            .replace("Ó", "O").replace("Ú", "U").replace("Ü", "U")
            .replace("ñ", "n").replace("Ñ", "N")
            .replace("¿", "?").replace("¡", "!")
            .replace("°", "o")
            .replace("–", "-").replace("—", "-")
            .replace("“", "\"").replace("”", "\"")
            .replace("‘", "'").replace("’", "'")
    }

    // =====================================================================
    // QR: Funciones para imprimir un QR de prueba (NO usadas por ResultsActivity,
    // pero las dejo por si ocupas el botón de prueba en Ajustes).
    // =====================================================================

    fun imprimirTestQr(context: Context, qrText: String): Boolean {
        val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER, true)
        if (!enabled) {
            Log.w(TAG, "⚠️ Impresora térmica desactivada en ajustes")
            return false
        }

        val portPath = prefs.getString("${TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER}_port", "/dev/ttyS1") ?: "/dev/ttyS1"
        val baudRate = SerialBaudConfig.get(prefs, TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER)

        val file = File(portPath)
        if (!file.exists()) {
            Log.e(TAG, "❌ Puerto de impresora térmica $portPath no existe")
            return false
        }

        var outputStream: OutputStream? = null
        var serialPort: SerialPort? = null

        try {
            try {
                serialPort = SerialPort.getSerialPort(portPath, baudRate)
                outputStream = serialPort.outputStream
                Log.d(TAG, "✅ Puerto abierto con SerialPort (modo raw)")
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ SerialPort falló (${e.message}), usando FileOutputStream")
                outputStream = FileOutputStream(file)
            }

            if (outputStream == null) {
                Log.e(TAG, "❌ No se pudo obtener OutputStream para $portPath")
                return false
            }

            val charset = Charset.forName(CODEPAGE_CHARSET)

            val INIT = byteArrayOf(0x1B, 0x40)
            val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
            val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
            val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
            val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
            val REVERSE_OFF = byteArrayOf(0x1D, 0x42, 0x00)
            val UPSIDE_DOWN_OFF = byteArrayOf(0x1B, 0x7B, 0x00)
            val SELECT_CODEPAGE = byteArrayOf(0x1B, 0x74, CODEPAGE_NUM.toByte())
            val FEED_LINES = byteArrayOf(0x1B, 0x64, 0x05)
            val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

            outputStream.write(INIT)
            outputStream.write(REVERSE_OFF)
            outputStream.write(UPSIDE_DOWN_OFF)
            outputStream.write(SELECT_CODEPAGE)
            outputStream.flush()
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            fun writeLine(text: String, bold: Boolean = false, alignCenter: Boolean = false) {
                if (alignCenter) outputStream.write(ALIGN_CENTER) else outputStream.write(ALIGN_LEFT)
                if (bold) outputStream.write(BOLD_ON) else outputStream.write(BOLD_OFF)
                val textToPrint = if (USAR_ACENTOS) text else text.sanitizarParaImpresora()
                outputStream.write((textToPrint + "\n").toByteArray(charset))
                outputStream.flush()
            }

            writeLine("")
            writeLine("PRUEBA DE IMPRESIÓN QR", bold = true, alignCenter = true)
            writeLine("--------------------------------", alignCenter = true)
            writeLine("Contenido del QR:", bold = false, alignCenter = true)
            writeLine(qrText, bold = false, alignCenter = true)
            writeLine("")

            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            val rasterBytes = generarRasterQrConZxing(qrText, QR_WIDTH, QR_HEIGHT)
            if (rasterBytes != null) {
                outputStream.write(ALIGN_CENTER)
                outputStream.flush()
                outputStream.writeThrottled(rasterBytes, chunkSize = 64, delayMs = 25)
                outputStream.write("\n".toByteArray(charset))
                outputStream.flush()
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            } else {
                Log.e(TAG, "❌ No se pudo generar la imagen raster QR")
            }

            writeLine("")
            writeLine("--------------------------------", alignCenter = true)

            outputStream.write(FEED_LINES)
            outputStream.flush()
            try { Thread.sleep(200) } catch (_: InterruptedException) {}

            outputStream.write(CUT)
            outputStream.flush()
            try { Thread.sleep(200) } catch (_: InterruptedException) {}

            outputStream.write(INIT)
            outputStream.flush()

            if (outputStream is FileOutputStream) {
                try { outputStream.fd.sync() } catch (_: Exception) {}
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) {}

            Log.i(TAG, "✅ QR de prueba impreso exitosamente en $portPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al imprimir QR de prueba: ${e.message}", e)
            return false
        } finally {
            try {
                outputStream?.flush()
                if (outputStream is FileOutputStream) {
                    try { outputStream.fd.sync() } catch (_: Exception) {}
                }
                outputStream?.close()
            } catch (_: Exception) {}

            try {
                serialPort?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Envía los datos en trozos pequeños para no saturar el búfer de la impresora.
     * CRÍTICO para la SP-RMD8CS1H (3K de búfer).
     */
    private fun OutputStream.writeThrottled(bytes: ByteArray, chunkSize: Int = 64, delayMs: Long = 25) {
        var offset = 0
        while (offset < bytes.size) {
            val count = Math.min(chunkSize, bytes.size - offset)
            this.write(bytes, offset, count)
            this.flush()
            offset += count
            if (delayMs > 0 && offset < bytes.size) {
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun generarBitmapQr(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error generando QR con ZXing: ${e.message}", e)
            null
        }
    }

    private fun generarRasterQrConZxing(text: String, width: Int, height: Int): ByteArray? {
        val bitmap = generarBitmapQr(text, width, height) ?: return null
        return bitmapToEscPosRaster(bitmap)
    }

    /**
     * Convierte un Bitmap al comando GS v 0 (Raster Bit Image).
     * La SP-RMD8CS1H lo soporta bien, siempre que se envíe en trozos (writeThrottled).
     */
    private fun bitmapToEscPosRaster(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthInBytes = (width + 7) / 8

        val stream = ByteArrayOutputStream()
        // Comando GS v 0 m xL xH yL yH
        stream.write(0x1D)
        stream.write(0x76)
        stream.write(0x30)
        stream.write(0x00)
        stream.write(widthInBytes % 256)
        stream.write(widthInBytes / 256)
        stream.write(height % 256)
        stream.write(height / 256)

        for (y in 0 until height) {
            for (xByte in 0 until widthInBytes) {
                var byteVal = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    if (x < width) {
                        val pixel = bitmap.getPixel(x, y)
                        val alpha = (pixel shr 24) and 0xFF
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                        if (alpha > 128 && luminance < 128) {
                            byteVal = byteVal or (0x80 shr bit)
                        }
                    }
                }
                stream.write(byteVal)
            }
        }
        return stream.toByteArray()
    }
}