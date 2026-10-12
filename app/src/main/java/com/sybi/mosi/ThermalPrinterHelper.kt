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

        val portPath = prefs.getString("${TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER}_port", "/dev/ttyS1") ?: "/dev/ttyS1"
        val baudRate = SerialBaudConfig.get(prefs, TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER)

        val file = File(portPath)
        if (!file.exists()) {
            Log.e(TAG, "❌ Puerto de impresora térmica $portPath no existe")
            return false
        }

        var outputStream: OutputStream? = null

        try {
            try {
                val serialPort = SerialPort.getSerialPort(portPath, baudRate)
                outputStream = serialPort.outputStream
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ No se pudo abrir con SerialPort, usando FileOutputStream: ${e.message}")
                outputStream = FileOutputStream(file)
            }

            if (outputStream == null) {
                Log.e(TAG, "❌ No se pudo obtener OutputStream para $portPath")
                return false
            }

            val charset = Charset.forName("CP437")

            // ESC/POS commands
            val INIT = byteArrayOf(0x1B, 0x40)
            val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01)
            val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00)
            val BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01)
            val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00)
            val FEED_AND_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00)

            outputStream.write(INIT)
            outputStream.flush()

            fun writeLine(text: String, bold: Boolean = false, alignCenter: Boolean = false) {
                if (alignCenter) outputStream.write(ALIGN_CENTER) else outputStream.write(ALIGN_LEFT)
                if (bold) outputStream.write(BOLD_ON) else outputStream.write(BOLD_OFF)
                outputStream.write((text + "\n").toByteArray(charset))
                outputStream.flush()
            }

            fun writeDivider() {
                writeLine("----------------------------------------")
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

            val fechaHora = resultados.firstOrNull()?.fecha_medicion ?: android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", java.util.Date()).toString()
            writeLine("Fecha/Hora: $fechaHora")
            writeDivider()

            // --- SECCIÓN DE RESULTADOS SEGÚN PREFERENCIAS DE DISPOSITIVOS ---
            val showAlturaPeso = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
            val showComposicion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
            val showPresion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
            val showOxigeno = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
            val showTemperatura = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
            val showEcg = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

            writeLine("RESULTADOS DE MEDICIÓN", bold = true, alignCenter = false)

            for (r in resultados) {
                // 1. Altura y Peso
                if (showAlturaPeso && (r.altura.isNotBlank() || r.peso.isNotBlank())) {
                    writeLine("--- ALTURA Y PESO ---", bold = true, alignCenter = false)
                    if (r.altura.isNotBlank()) writeLine("Altura: ${r.altura} cm")
                    if (r.peso.isNotBlank()) writeLine("Peso: ${r.peso} kg")
                    if (r.imc.isNotBlank()) writeLine("IMC: ${r.imc}")
                }

                // 2. Composición Corporal
                if (showComposicion && (r.grasa_corporal.isNotBlank() || r.agua_corporal.isNotBlank() || r.masa_muscular.isNotBlank() || r.metabolismo_basal.isNotBlank())) {
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

                // 3. Presión Arterial
                if (showPresion && r.sistolica.isNotBlank() && r.diastolica.isNotBlank()) {
                    writeLine("--- PRESIÓN ARTERIAL ---", bold = true, alignCenter = false)
                    writeLine("Presión: ${r.sistolica}/${r.diastolica} mmHg")
                    if (r.pulso.isNotBlank()) writeLine("Pulso: ${r.pulso} bpm")
                }

                // 4. Oxígeno
                if (showOxigeno && r.spo2.isNotBlank()) {
                    writeLine("--- OXÍGENO EN SANGRE ---", bold = true, alignCenter = false)
                    writeLine("SpO2: ${r.spo2}%")
                    if (r.frecuencia_pulso.isNotBlank()) writeLine("Pulso: ${r.frecuencia_pulso} bpm")
                    if (r.indice_perfusion.isNotBlank()) writeLine("Índice Perfusión: ${r.indice_perfusion}")
                }

                // 5. Temperatura
                if (showTemperatura && r.temperatura.isNotBlank()) {
                    writeLine("--- TEMPERATURA CORPORAL ---", bold = true, alignCenter = false)
                    writeLine("Temperatura: ${r.temperatura} °C / ${r.temperatura_f} °F")
                }

                // 6. ECG
                if (showEcg && (r.frecuencia_cardiaca.isNotBlank() || r.resultado_ecg.isNotBlank())) {
                    writeLine("--- ECG ---", bold = true, alignCenter = false)
                    writeLine("Frec. Cardíaca: ${r.frecuencia_cardiaca} bpm")
                    if (r.eje_p.isNotBlank()) writeLine("Eje P/QRS/T: ${r.eje_p}/${r.eje_qrs}/${r.eje_t}")
                    if (r.intervalo_pr.isNotBlank()) writeLine("Int. PR / QRS: ${r.intervalo_pr}/${r.duracion_qrs} ms")
                    if (r.intervalo_qt.isNotBlank()) writeLine("Int. QT / QTc: ${r.intervalo_qt}/${r.qt_corregido} ms")
                    if (r.resultado_ecg.isNotBlank()) {
                        writeLine("Ritmo: ${r.resultado_ecg}")
                    }
                }
            }

            writeDivider()
            writeLine("¡Gracias por su visita!", bold = true, alignCenter = true)
            writeLine("\n\n\n")
            outputStream.write(FEED_AND_CUT)
            outputStream.flush()

            Log.i(TAG, "✅ Ticket impreso exitosamente en $portPath")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error imprimiendo ticket: ${e.message}", e)
            return false
        } finally {
            try {
                outputStream?.close()
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
}
