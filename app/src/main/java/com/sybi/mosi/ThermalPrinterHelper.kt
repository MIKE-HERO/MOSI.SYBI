package com.sybi.mosi

import android.content.Context
import android.util.Log
import android.serialport.api.SerialPort
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.Charset

object ThermalPrinterHelper {
    private const val TAG = "ThermalPrinterHelper"

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

            writeLine("RESULTADOS DE MEDICIÓN", bold = true, alignCenter = false)

            if (showAlturaPeso && (r.altura.isNotBlank() || r.peso.isNotBlank())) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- ALTURA Y PESO ---", bold = true, alignCenter = false)
                if (r.altura.isNotBlank()) writeLine("Altura: ${r.altura} cm")
                if (r.peso.isNotBlank()) writeLine("Peso: ${r.peso} kg")
                if (r.imc.isNotBlank()) writeLine("IMC: ${r.imc}")
            }

            if (showComposicion && (r.grasa_corporal.isNotBlank() || r.agua_corporal.isNotBlank() || r.masa_muscular.isNotBlank() || r.metabolismo_basal.isNotBlank())) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- COMPOSICIÓN CORPORAL ---", bold = true, alignCenter = false)
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
                writeLine("--- PRESIÓN ARTERIAL ---", bold = true, alignCenter = false)
                writeLine("Presión: ${r.sistolica}/${r.diastolica} mmHg")
                if (r.pulso.isNotBlank()) writeLine("Pulso: ${r.pulso} bpm")
            }

            if (showOxigeno && r.spo2.isNotBlank()) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- OXÍGENO EN SANGRE ---", bold = true, alignCenter = false)
                writeLine("SpO2: ${r.spo2}%")
                if (r.frecuencia_pulso.isNotBlank()) writeLine("Pulso: ${r.frecuencia_pulso} bpm")
                if (r.indice_perfusion.isNotBlank()) writeLine("Índice Perfusión: ${r.indice_perfusion}")
            }

            if (showTemperatura && r.temperatura.isNotBlank()) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- TEMPERATURA CORPORAL ---", bold = true, alignCenter = false)
                writeLine("Temperatura: ${r.temperatura} °C / ${r.temperatura_f} °F")
            }

            if (showEcg && (r.frecuencia_cardiaca.isNotBlank() || r.resultado_ecg.isNotBlank())) {
                writeLine("")   // ✅ Espacio antes de la sección
                writeLine("--- ELECTROCARDIOGRAMA ---", bold = true, alignCenter = false)
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
                val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
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
}