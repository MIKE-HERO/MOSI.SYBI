package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.serialport.api.SerialPort
import android.text.format.DateFormat
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.sybi.mosi.SerialBaudConfig
import com.sybi.mosi.TestElementsActivity
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Calendar
import java.util.Date

object ThermalPrinterHelper {
    private const val TAG = "ThermalPrinterHelper"

    // ============================================
    // MECÁNICA DE LA SP-RMD8 (manual Ver 1.07)
    // ============================================
    // 8 puntos/mm (203 dpi), 384 puntos/línea, 30 mm/s de velocidad de impresión
    // y 3 KB de búfer de recepción. De estos dos valores sale el ritmo al que se
    // le puede entregar una imagen sin desbordarla; ver escribirBandas().
    private const val BANDA_ALTO_PUNTOS = 24          // ESC * m=33 imprime 24 puntos por banda
    private const val PUNTOS_POR_SEGUNDO = 240        // 30 mm/s x 8 puntos/mm

    // ESC 1 n (1B 31 n) -> espaciado entre líneas, medido en LÍNEAS DE PUNTO.
    // El manual fija n=0 para gráficos y n=3 como valor por defecto del texto.
    private val ESPACIADO_GRAFICOS = byteArrayOf(0x1B, 0x31, 0x00)
    private val ESPACIADO_TEXTO = byteArrayOf(0x1B, 0x31, 0x03)

    // ============================================
    // CONFIGURACIÓN DE PÁGINA DE CÓDIGOS
    // ============================================
    // Opciones:
    //   0  -> CP437 (US, sin acentos)
    //   2  -> CP850 (Latin-1, con acentos pero sin ¿¡)
    //   16 -> CP1252 (Windows Latin-1, con acentos + ¿¡)  ← RECOMENDADO
    //   19 -> CP858 (Latin-1 + €)
    //
    // Cambia estos valores si tu impresora no soporta CP1252.
    private const val CODEPAGE_NUM = 16
    private const val CODEPAGE_CHARSET = "windows-1252"
    private const val USAR_ACENTOS = true

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

        // ✅ DEFENSIVO: usar SOLO el primer resultado
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

            // ✅ Charset según la página de códigos elegida
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
            // ✅ Seleccionar página de códigos: ESC t n
            val SELECT_CODEPAGE = byteArrayOf(0x1B, 0x74, CODEPAGE_NUM.toByte())
            val FEED_LINES = byteArrayOf(0x1B, 0x64, 0x05)
            val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

            // ============================================
            // INICIALIZACIÓN ROBUSTA
            // ============================================
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

            // ✅ Función auxiliar: sanitizar si no se usan acentos
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

            writeLine("")   // ✅ Espacio antes de la sección
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
                DateFormat.format("dd/MM/yyyy HH:mm", Date()).toString()
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
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- ALTURA Y PESO ---", bold = true, alignCenter = true)
                if (r.altura.isNotBlank()) writeLine("Altura: ${r.altura} cm")
                if (r.peso.isNotBlank()) writeLine("Peso: ${r.peso} kg")
                if (r.imc.isNotBlank()) writeLine("IMC: ${r.imc}")
            }

            if (showComposicion && (r.grasa_corporal.isNotBlank() || r.agua_corporal.isNotBlank() || r.masa_muscular.isNotBlank() || r.metabolismo_basal.isNotBlank())) {
                writeLine("")   // ✅ Espacio antes de la sección
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
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- PRESIÓN ARTERIAL ---", bold = true, alignCenter = true)
                writeLine("Presión: ${r.sistolica}/${r.diastolica} mmHg")
                if (r.pulso.isNotBlank()) writeLine("Pulso: ${r.pulso} bpm")
            }

            if (showOxigeno && r.spo2.isNotBlank()) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- OXÍGENO EN SANGRE ---", bold = true, alignCenter = true)
                writeLine("SpO2: ${r.spo2}%")
                if (r.frecuencia_pulso.isNotBlank()) writeLine("Pulso: ${r.frecuencia_pulso} bpm")
                if (r.indice_perfusion.isNotBlank()) writeLine("Índice Perfusión: ${r.indice_perfusion}")
            }

            if (showTemperatura && r.temperatura.isNotBlank()) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- TEMPERATURA CORPORAL ---", bold = true, alignCenter = true)
                writeLine("Temperatura: ${r.temperatura} °C / ${r.temperatura_f} °F")
            }

            if (showEcg && (r.frecuencia_cardiaca.isNotBlank() || r.resultado_ecg.isNotBlank())) {
                writeLine("")   // ✅ Espacio antes de la sección
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

            // ✅ RESET FINAL
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
                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                currentYear - year
            } else -1
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Reemplaza caracteres no soportados por equivalentes ASCII.
     * Se usa como plan B si la impresora no soporta la página de códigos elegida.
     */
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

    /**
     * Imprime un ticket de prueba con un código QR en la impresora térmica.
     */
    fun imprimirTestQr(
        context: Context,
        qrContent: String = "Impresión de QR de prueba",
        invertirOrientacion: Boolean = false // ✅ Corregido: Ahora por defecto es false
    ): Boolean {
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
            val UPSIDE_DOWN_ON = byteArrayOf(0x1B, 0x7B, 0x01)
            val SELECT_CODEPAGE = byteArrayOf(0x1B, 0x74, CODEPAGE_NUM.toByte())
            val FEED_LINES = byteArrayOf(0x1B, 0x64, 0x04)
            val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

            // Inicialización limpia
            outputStream.write(INIT)
            outputStream.write(REVERSE_OFF)
            outputStream.write(if (invertirOrientacion) UPSIDE_DOWN_ON else UPSIDE_DOWN_OFF)
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

            // Enviamos los elementos en orden inverso (Pie -> QR -> Título) para que
            // en la tira impresa el Título quede arriba, el QR en el centro y el Pie abajo.

            if (invertirOrientacion) {
                // --- 1. PIE DE TICKET (Se imprime primero -> queda ABAJO en el papel) ---
                writeLine("--------------------------------", alignCenter = true)
                writeLine("Texto QR: $qrContent", alignCenter = true)
                writeLine("")

                // --- 2. CÓDIGO QR (Se imprime segundo -> queda en el CENTRO del papel) ---
                // ✅ Corregido: Tamaño 192x192 y SIN rotar
                val qrBitmap = generarQrBitmap(qrContent, width = 192, height = 192)
                if (qrBitmap != null) {
                    outputStream.write(ALIGN_CENTER)
                    outputStream.flush()
                    outputStream.escribirBandas(bitmapToEscPosBands(qrBitmap), baudRate)
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                }

                writeLine("")

                // --- 3. TÍTULO Y SUBTÍTULO (Se imprime al final -> queda ARRIBA en el papel) ---
                writeLine("Escanee el siguiente código:", alignCenter = true)
                writeLine("--- PRUEBA DE CÓDIGO QR ---", bold = true, alignCenter = true)
                writeLine("")
            } else {
                // Modo estándar sin inversión (El correcto para que el QR se vea bien)
                writeLine("")
                writeLine("--- PRUEBA DE CÓDIGO QR ---", bold = true, alignCenter = true)
                writeLine("Escanee el siguiente código:", alignCenter = true)
                writeLine("")

                // ✅ Corregido: Tamaño 192x192 y SIN rotar
                val qrBitmap = generarQrBitmap(qrContent, width = 288, height = 288)
                if (qrBitmap != null) {
                    outputStream.write(ALIGN_CENTER)
                    outputStream.flush()
                    outputStream.escribirBandas(bitmapToEscPosBands(qrBitmap), baudRate)
                    try { Thread.sleep(500) } catch (_: InterruptedException) {}
                }

                writeLine("")
                writeLine("Texto QR: $qrContent", alignCenter = true)
                writeLine("--------------------------------", alignCenter = true)
            }

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

            Log.i(TAG, "✅ QR de prueba impreso exitosamente en $portPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error imprimiendo QR de prueba: ${e.message}", e)
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
     * Envia las bandas de una imagen al ritmo al que el cabezal las consume, no
     * al ritmo del cable.
     *
     * El buffer de recepcion de la SP-RMD8 es de 3 KB y un QR de 192x192 pesa
     * ~4.6 KB, asi que no cabe entero. A 9600 baudios eso da igual: el cable
     * entrega 960 B/s y el cabezal consume ~5820 B/s (30 mm/s = 240 lineas de
     * punto/s = 10 bandas/s x 582 bytes), o sea que vacia seis veces mas rapido
     * de lo que se llena y desbordar es imposible. Pero la impresora admite
     * hasta 115200, y ahi entrarian 11520 B/s contra esos mismos 5820 B/s de
     * consumo: el buffer revienta y se pierden bandas enteras.
     *
     * Por eso el freno no es un retardo fijo (que a 9600 solo hace lento algo
     * que ya era seguro, y a 115200 se queda corto) sino el tiempo real del
     * cabezal: 100 ms por banda de 24 puntos, descontando lo que el cable ya
     * tardo en transmitirla. Se autorregula a cualquier velocidad y nunca hay
     * mas de una banda de ventaja sobre el papel.
     */
    private fun OutputStream.escribirBandas(bandas: List<ByteArray>, baudRate: Int) {
        // 30 mm/s a 8 puntos/mm = 240 lineas de punto por segundo.
        val msPorBanda = (BANDA_ALTO_PUNTOS * 1000L) / PUNTOS_POR_SEGUNDO
        // 8N1: cada byte ocupa 10 bits en la linea (1 inicio + 8 datos + 1 paro).
        val baud = if (baudRate > 0) baudRate else 9600

        // ESC 1 0 -> espaciado de 0 lineas de punto. El manual lo indica asi
        // para graficos ("n=0 for bitmap printing"): cada LF cierra la linea e
        // imprime sus 24 puntos sin anadir ni uno de papel en blanco.
        this.write(ESPACIADO_GRAFICOS)
        this.flush()

        for (banda in bandas) {
            this.write(banda)
            this.flush()
            val msCable = (banda.size * 10L * 1000L) / baud
            val espera = msPorBanda - msCable
            if (espera > 0) {
                try { Thread.sleep(espera) } catch (_: InterruptedException) {}
            }
        }

        // Devolver el espaciado de texto antes de seguir escribiendo lineas.
        this.write(ESPACIADO_TEXTO)
        this.flush()
    }

    /**
     * Genera un Bitmap con el código QR deseado usando ZXing.
     */
    fun generarQrBitmap(content: String, width: Int = 288, height: Int = 288): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
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
            Log.e(TAG, "Error generando Bitmap de QR: ${e.message}", e)
            null
        }
    }

    /**
     * Convierte un Bitmap monocromático a bandas ESC * 33 (24-dot Double Density
     * Column Bit Image), una banda de 24 puntos de alto por elemento de la lista.
     *
     * Cada banda termina en LF, que es lo que cierra la línea de puntos. El LF
     * es obligatorio: el QR mide 192 puntos y la línea 384, así que sin él el
     * siguiente ESC * sigue rellenando la misma línea por la derecha y las
     * bandas salen apareadas de dos en dos. Intentar sustituirlo por ESC J
     * (avance en puntos, pero sin retorno de carro) produce justo eso.
     *
     * La separación la controla ESC 1 0, que escribirBandas() emite antes de la
     * primera banda. Antes se usaba ESC 3 24, y de ahí venían las franjas
     * blancas: ESC 3 mide en 1/180 de pulgada, así que 24 unidades son 3.39 mm,
     * mientras que 24 puntos a 203 dpi son 3.00 mm. Sobraban 0.39 mm de papel
     * en blanco en cada banda.
     *
     * Se devuelve la lista en vez de un solo ByteArray para poder marcar el
     * ritmo banda por banda; ver escribirBandas().
     */
    fun bitmapToEscPosBands(bitmap: Bitmap): List<ByteArray> {
        val width = bitmap.width
        val height = bitmap.height
        val bandas = ArrayList<ByteArray>()

        val nL = (width % 256).toByte()
        val nH = (width / 256).toByte()

        var y = 0
        while (y < height) {
            val stream = ByteArrayOutputStream()
            // Comando ESC * m nL nH (m = 33 / 0x21 para 24-dot double density: 203x203 dpi)
            stream.write(byteArrayOf(0x1B, 0x2A, 0x21, nL, nH))

            for (x in 0 until width) {
                for (k in 0 until 3) { // 3 bytes por columna (24 puntos verticales)
                    var b = 0
                    for (bit in 0 until 8) {
                        val pixelY = y + k * 8 + bit
                        if (pixelY < height) {
                            val pixel = bitmap.getPixel(x, pixelY)
                            val red = Color.red(pixel)
                            val green = Color.green(pixel)
                            val blue = Color.blue(pixel)
                            val alpha = Color.alpha(pixel)
                            val isBlack = alpha > 128 && (red + green + blue) / 3 < 128
                            if (isBlack) {
                                b = b or (1 shl (7 - bit))
                            }
                        }
                    }
                    stream.write(b)
                }
            }
            stream.write(0x0A) // Cierra e imprime la línea de 24 puntos.
            bandas.add(stream.toByteArray())
            y += BANDA_ALTO_PUNTOS
        }

        return bandas
    }

    // ✅ Esta función ya no se usa, pero la dejo comentada por si la necesitas en el futuro
    /*
    private fun Bitmap.rotate180(): Bitmap {
        val matrix = Matrix().apply { postRotate(180f) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
    */
}