package com.sybi.mosi

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.util.Log
import com.sybi.mosi.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gestor para registrar eventos de atención diaria (teleconsultas, errores de sensores,
 * cortes de red, desconexiones de puertos y reencendidos de equipo) y generar el PDF del
 * Reporte Diario para ser enviado por correo.
 */
object DailyReportManager {

    private const val TAG = "DailyReportManager"
    private const val PREFS_NAME = "DailyReportPrefs"

    data class ItemReporteDiario(
        val hora: String,
        val nombrePaciente: String,
        val estatusMedicion: String,
        val detalleError: String,
        val realizoTeleconsulta: String,
        val duracionTeleconsulta: String
    )

    data class ItemIncidente(
        val hora: String,
        val tipo: String,
        val descripcion: String
    )

    // ── MÉTODOS DE REGISTRO DE EVENTOS Y TELEMEDICINA ──────────────────────────────

    fun onTelemedicineStarted(context: Context, idUsuarioWeb: Int) {
        if (idUsuarioWeb <= 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong("telemed_start_$idUsuarioWeb", System.currentTimeMillis()).apply()
        Log.d(TAG, "Telemedicina iniciada para idUsuarioWeb=$idUsuarioWeb")
    }

    fun onTelemedicineEnded(context: Context, idUsuarioWeb: Int) {
        if (idUsuarioWeb <= 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startTime = prefs.getLong("telemed_start_$idUsuarioWeb", 0L)
        val now = System.currentTimeMillis()
        val durationMs = if (startTime > 0L) now - startTime else 0L
        val minutes = Math.max(1, Math.round(durationMs / 60000.0f))

        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val key = "telemed_done_${idUsuarioWeb}_$dateStr"
        prefs.edit()
            .putInt(key, minutes)
            .remove("telemed_start_$idUsuarioWeb")
            .apply()

        Log.d(TAG, "Telemedicina finalizada para idUsuarioWeb=$idUsuarioWeb. Duración: $minutes min")
    }

    fun recordSessionError(context: Context, idLocal: Long, errorMessage: String) {
        if (errorMessage.isBlank()) return
        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "error_${idLocal}_$dateStr"
        val existing = prefs.getString(key, "") ?: ""
        val updated = if (existing.isBlank()) errorMessage else "$existing; $errorMessage"
        prefs.edit().putString(key, updated).apply()
        Log.d(TAG, "Error de sesión registrado para idLocal=$idLocal: $errorMessage")
    }

    // ── MÉTODOS DE REGISTRO DE INCIDENTES DE SISTEMA ──────────────────────

    fun recordBootIncident(context: Context) {
        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val lastBootKey = "last_boot_$dateStr"
        val lastBootTime = prefs.getString(lastBootKey, "") ?: ""
        if (lastBootTime == timeStr) return

        prefs.edit().putString(lastBootKey, timeStr).apply()
        val desc = "Inicio/Reencendido de equipo registrado a las $timeStr"
        recordIncident(context, timeStr, "Reencendido", desc)
    }

    fun recordInternetLost(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong("offline_start_ms", 0L) == 0L) {
            prefs.edit().putLong("offline_start_ms", System.currentTimeMillis()).apply()
            Log.d(TAG, "Registrado inicio de desconexión a internet")
        }
    }

    fun recordInternetRestored(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val startMs = prefs.getLong("offline_start_ms", 0L)
        if (startMs > 0L) {
            val now = System.currentTimeMillis()
            val durationMin = Math.max(1, Math.round((now - startMs) / 60000.0f))
            val timeStartStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMs))
            val timeEndStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))

            val desc = "Se perdió la conexión a las $timeStartStr - Se restableció a las $timeEndStr (Duración: $durationMin min)"
            recordIncident(context, timeStartStr, "Conexión a Internet", desc)

            prefs.edit().remove("offline_start_ms").apply()
            Log.d(TAG, "Registrado fin de desconexión a internet: $desc")
        }
    }

    private var lastPortErrorTime = 0L
    private var lastPortErrorMsg = ""

    fun recordPortIncident(context: Context, portName: String, errorMessage: String) {
        val now = System.currentTimeMillis()
        if (now - lastPortErrorTime < 120_000L && lastPortErrorMsg == errorMessage) {
            return
        }
        lastPortErrorTime = now
        lastPortErrorMsg = errorMessage

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val desc = "Error en puerto serie/USB ($portName): $errorMessage"
        recordIncident(context, timeStr, "Puerto Serie / USB", desc)
    }

    fun recordIncident(context: Context, hora: String, tipo: String, descripcion: String) {
        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = System.currentTimeMillis()
        val value = "$hora|$tipo|$descripcion"
        prefs.edit().putString("inc_$dateStr#$timestamp", value).apply()
        Log.d(TAG, "Incidente registrado [$tipo]: $descripcion")
    }

    suspend fun obtenerIncidentesDelDia(context: Context): List<ItemIncidente> = withContext(Dispatchers.IO) {
        val dateTodayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lista = mutableListOf<ItemIncidente>()

        val allEntries = prefs.all
        val prefix = "inc_$dateTodayStr#"

        val incidentKeys = allEntries.keys.filter { it.startsWith(prefix) }.sorted()
        for (key in incidentKeys) {
            val valStr = allEntries[key] as? String ?: continue
            val partes = valStr.split("|", limit = 3)
            if (partes.size == 3) {
                lista.add(
                    ItemIncidente(
                        hora = partes[0],
                        tipo = partes[1],
                        descripcion = partes[2]
                    )
                )
            }
        }

        // Si está actualmente sin conexión a internet
        val offlineStartMs = prefs.getLong("offline_start_ms", 0L)
        if (offlineStartMs > 0L) {
            val timeStartStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(offlineStartMs))
            lista.add(
                ItemIncidente(
                    hora = timeStartStr,
                    tipo = "Conexión a Internet",
                    descripcion = "Se perdió la conexión a las $timeStartStr - Sin conexión al momento del reporte"
                )
            )
        }

        lista
    }

    // ── OBTENER ATENCIONES DEL DÍA ────────────────────────────────────────

    suspend fun obtenerAtencionesDelDia(context: Context): List<ItemReporteDiario> = withContext(Dispatchers.IO) {
        val dateTodayStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        val db = AppDatabase.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val todosLosResultados = db.resultadoDao().obtenerTodosLosResultados()
        val resultadosHoy = todosLosResultados.filter { r ->
            r.fecha_medicion.startsWith(dateTodayStr)
        }

        val lista = mutableListOf<ItemReporteDiario>()

        for (r in resultadosHoy) {
            val paciente = if (r.id_local > 0L) {
                db.pacienteDao().obtenerPacientePorIdLocal(r.id_local)
            } else null

            val nombre = if (paciente != null) {
                listOfNotNull(paciente.nombre, paciente.apellido_paterno, paciente.apellido_materno)
                    .joinToString(" ")
                    .trim()
                    .ifBlank { "Paciente ID ${r.id_local}" }
            } else {
                "Paciente Invitado"
            }

            val horaStr = try {
                val partes = r.fecha_medicion.split(" ")
                if (partes.size > 1) partes[1].take(5) else "—"
            } catch (_: Exception) {
                "—"
            }

            // Evaluar estado de medición y errores registrados
            val erroresAnomalías = mutableListOf<String>()
            val errorGuardado = prefs.getString("error_${r.id_local}_$dateTodayStr", "") ?: ""
            if (errorGuardado.isNotBlank()) {
                erroresAnomalías.add(errorGuardado)
            }

            if (r.sistolica.isBlank() && r.diastolica.isBlank() && r.temperatura.isBlank() && r.spo2.isBlank()) {
                erroresAnomalías.add("Sin mediciones capturadas")
            }

            val estatusMedicion = if (erroresAnomalías.isEmpty()) "OK (Todo bien)" else "Con error"
            val detalleError = if (erroresAnomalías.isEmpty()) "Sin errores" else erroresAnomalías.joinToString("; ")

            // Teleconsulta
            val durationTelemed = if (r.id_usuario_web > 0) {
                prefs.getInt("telemed_done_${r.id_usuario_web}_$dateTodayStr", -1)
            } else -1

            val realizoTele = if (durationTelemed > 0) "Sí" else "No"
            val duracionStr = if (durationTelemed > 0) "$durationTelemed min" else "—"

            lista.add(
                ItemReporteDiario(
                    hora = horaStr,
                    nombrePaciente = nombre,
                    estatusMedicion = estatusMedicion,
                    detalleError = detalleError,
                    realizoTeleconsulta = realizoTele,
                    duracionTeleconsulta = duracionStr
                )
            )
        }

        lista
    }

    // ── GENERACIÓN DEL PDF DE REPORTE DIARIO ─────────────────────────

    suspend fun generarPdfReporteDiario(context: Context): File? = withContext(Dispatchers.IO) {
        try {
            val dateTimeStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val atenciones = obtenerAtencionesDelDia(context)

            // Datos de cliente/sucursal/equipo
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            val nombreCliente = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, "")
                ?.ifBlank { null } ?: DeviceInfoActivity.getCliente(context).ifBlank { "No especificado" }

            val nombreSucursal = devicePrefs.getString(TelemedicineSettingsActivity.PREF_SUCURSAL_NOMBRE, "")
                ?.ifBlank { null } ?: DeviceInfoActivity.getSucursal(context).ifBlank { "No especificada" }

            val serieEquipo = DeviceInfoActivity.getSerie(context).ifBlank { "Desconocida" }

            val pdfDocument = PdfDocument()

            // A4 dimensions: 595 x 842 points
            val pageWidth = 595
            val pageHeight = 842
            val margin = 36f

            // Paints
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F3E82")
                textSize = 22f
                isFakeBoldText = true
            }

            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4A5568")
                textSize = 11f
            }

            val metaHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A202C")
                textSize = 10f
                isFakeBoldText = true
            }

            val metaValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2D3748")
                textSize = 10f
            }

            val tableHeaderBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F3E82")
            }

            val tableHeaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9.5f
                isFakeBoldText = true
            }

            val tableRowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2D3748")
                textSize = 9f
            }

            val rowBgAltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F7FAFC")
            }

            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E2E8F0")
                strokeWidth = 1f
            }

            val statusOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#2E7D32") // Verde
                textSize = 9f
                isFakeBoldText = true
            }

            val statusErrorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#C62828") // Rojo
                textSize = 9f
                isFakeBoldText = true
            }

            var currentPageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas

            var y = margin

            // DIBUJAR ENCABEZADO Y METADATOS
            fun dibujarEncabezado() {
                canvas.drawText("Reporte Diario de Atenciones", margin, y + 20f, titlePaint)
                canvas.drawText("Quiosco Médico MOSI", pageWidth - margin - 140f, y + 20f, subtitlePaint)
                y += 35f

                canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
                y += 15f

                // Caja de Metadatos
                val metaBoxHeight = 55f
                val boxBgPaint = Paint().apply { color = Color.parseColor("#EDF2F7") }
                canvas.drawRect(margin, y, pageWidth - margin, y + metaBoxHeight, boxBgPaint)

                // Renglón 1: Cliente y Sucursal
                canvas.drawText("Cliente:", margin + 10f, y + 18f, metaHeaderPaint)
                canvas.drawText(nombreCliente, margin + 55f, y + 18f, metaValuePaint)

                canvas.drawText("Sucursal:", margin + 270f, y + 18f, metaHeaderPaint)
                canvas.drawText(nombreSucursal, margin + 325f, y + 18f, metaValuePaint)

                // Renglón 2: No. serie del equipo y Fecha y hora del reporte
                canvas.drawText("No. serie del equipo:", margin + 10f, y + 38f, metaHeaderPaint)
                canvas.drawText(serieEquipo, margin + 115f, y + 38f, metaValuePaint)

                canvas.drawText("Fecha y hora del reporte:", margin + 270f, y + 38f, metaHeaderPaint)
                canvas.drawText(dateTimeStr, margin + 390f, y + 38f, metaValuePaint)

                y += metaBoxHeight + 20f
            }

            dibujarEncabezado()

            // DIBUJAR ENCABEZADO DE LA TABLA DE ATENCIONES
            val colWidths = floatArrayOf(45f, 130f, 85f, 65f, 55f, 143f) // Suma = 523 (ancho disponible = 595 - 72 = 523)
            val rowHeight = 22f

            fun dibujarEncabezadoTabla() {
                canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, tableHeaderBgPaint)

                var x = margin + 5f
                val headers = arrayOf("Hora", "Paciente", "Medición", "Teleconsulta", "Duración", "Detalle de Error / Obs.")
                for (i in headers.indices) {
                    canvas.drawText(headers[i], x, y + 15f, tableHeaderTextPaint)
                    x += colWidths[i]
                }
                y += rowHeight
            }

            dibujarEncabezadoTabla()

            if (atenciones.isEmpty()) {
                canvas.drawText("No se registraron atenciones de pacientes el día de hoy.", margin + 10f, y + 20f, metaValuePaint)
                y += rowHeight
            } else {
                var rowIndex = 0
                for (item in atenciones) {
                    // Verificar fin de página
                    if (y + rowHeight > pageHeight - margin - 30f) {
                        pdfDocument.finishPage(page)
                        currentPageNum++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = margin
                        dibujarEncabezado()
                        dibujarEncabezadoTabla()
                    }

                    if (rowIndex % 2 == 1) {
                        canvas.drawRect(margin, y, pageWidth - margin, y + rowHeight, rowBgAltPaint)
                    }

                    var x = margin + 5f

                    // Hora
                    canvas.drawText(item.hora, x, y + 15f, tableRowTextPaint)
                    x += colWidths[0]

                    // Paciente (recortar si es muy largo)
                    val nombreCorto = if (item.nombrePaciente.length > 22) item.nombrePaciente.take(20) + ".." else item.nombrePaciente
                    canvas.drawText(nombreCorto, x, y + 15f, tableRowTextPaint)
                    x += colWidths[1]

                    // Estatus Medicion
                    val pStatus = if (item.estatusMedicion.startsWith("OK")) statusOkPaint else statusErrorPaint
                    canvas.drawText(item.estatusMedicion, x, y + 15f, pStatus)
                    x += colWidths[2]

                    // Teleconsulta
                    canvas.drawText(item.realizoTeleconsulta, x, y + 15f, tableRowTextPaint)
                    x += colWidths[3]

                    // Duracion
                    canvas.drawText(item.duracionTeleconsulta, x, y + 15f, tableRowTextPaint)
                    x += colWidths[4]

                    // Detalle Error
                    val errCorto = if (item.detalleError.length > 26) item.detalleError.take(24) + ".." else item.detalleError
                    canvas.drawText(errCorto, x, y + 15f, tableRowTextPaint)

                    canvas.drawLine(margin, y + rowHeight, pageWidth - margin, y + rowHeight, linePaint)

                    y += rowHeight
                    rowIndex++
                }
            }

            // ── SECCIÓN 2: INCIDENTES IDENTIFICADOS ──────────────────────
            y += 25f

            if (y + 50f > pageHeight - margin - 30f) {
                pdfDocument.finishPage(page)
                currentPageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = margin
                dibujarEncabezado()
            }

            val secTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F3E82")
                textSize = 12f
                isFakeBoldText = true
            }

            canvas.drawText("Incidentes Identificados (Puertos, Conectividad y Reencendidos)", margin, y + 15f, secTitlePaint)
            y += 25f

            val colWidthsInc = floatArrayOf(50f, 120f, 353f) // Suma = 523
            val rowHeightInc = 22f

            fun dibujarEncabezadoTablaIncidentes() {
                canvas.drawRect(margin, y, pageWidth - margin, y + rowHeightInc, tableHeaderBgPaint)
                var x = margin + 5f
                val headers = arrayOf("Hora", "Tipo de Incidente", "Descripción del Incidente")
                for (i in headers.indices) {
                    canvas.drawText(headers[i], x, y + 15f, tableHeaderTextPaint)
                    x += colWidthsInc[i]
                }
                y += rowHeightInc
            }

            dibujarEncabezadoTablaIncidentes()

            val incidentes = obtenerIncidentesDelDia(context)

            if (incidentes.isEmpty()) {
                canvas.drawText("No se registraron incidentes de conexión ni de sistema el día de hoy.", margin + 10f, y + 20f, metaValuePaint)
            } else {
                var incRowIndex = 0
                for (inc in incidentes) {
                    if (y + rowHeightInc > pageHeight - margin - 30f) {
                        pdfDocument.finishPage(page)
                        currentPageNum++
                        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                        page = pdfDocument.startPage(pageInfo)
                        canvas = page.canvas
                        y = margin
                        dibujarEncabezado()
                        dibujarEncabezadoTablaIncidentes()
                    }

                    if (incRowIndex % 2 == 1) {
                        canvas.drawRect(margin, y, pageWidth - margin, y + rowHeightInc, rowBgAltPaint)
                    }

                    var x = margin + 5f
                    canvas.drawText(inc.hora, x, y + 15f, tableRowTextPaint)
                    x += colWidthsInc[0]

                    canvas.drawText(inc.tipo, x, y + 15f, tableRowTextPaint)
                    x += colWidthsInc[1]

                    val descCorta = if (inc.descripcion.length > 68) inc.descripcion.take(65) + ".." else inc.descripcion
                    canvas.drawText(descCorta, x, y + 15f, tableRowTextPaint)

                    canvas.drawLine(margin, y + rowHeightInc, pageWidth - margin, y + rowHeightInc, linePaint)

                    y += rowHeightInc
                    incRowIndex++
                }
            }

            pdfDocument.finishPage(page)

            // Guardar archivo en cache
            val dir = File(context.cacheDir, "reportes").apply { mkdirs() }
            val file = File(dir, "mosi_reporte_diario.pdf")
            pdfDocument.writeTo(file.outputStream())
            pdfDocument.close()

            Log.d(TAG, "📄 PDF de Reporte Diario generado correctamente en: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error generando PDF de Reporte Diario: ${e.message}", e)
            null
        }
    }
}
