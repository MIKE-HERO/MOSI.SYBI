package com.sybi.mosi

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import com.sybi.mosi.measurement.LineChartView
import com.sybi.mosi.measurement.RangeBarView
import com.sybi.mosi.measurement.RangosSalud
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Construye el informe de salud (estilo MediTech) dentro de un contenedor dado. Por cada
 * bloque (IMC, composición, presión, oxígeno, temperatura, ECG) agrega a la izquierda los
 * valores de la medición más reciente con barras de rango (usando los umbrales que la app ya
 * define en [RangosSalud]), y a la derecha las gráficas de tendencia de la medición actual +
 * hasta 20 anteriores del mismo paciente.
 *
 * Lo usan tanto [InformeActivity] (pantalla propia de informe) como [ResultsActivity] (donde
 * el informe es el contenido de la pantalla final), para no duplicar la lógica.
 */
class InformeBuilder(
    private val ctx: Activity,
    private val container: LinearLayout,
    private val colorTema: Int
) {
    private val d = ctx.resources.displayMetrics.density

    init {
        container.clipChildren = false
        container.clipToPadding = false
    }

    /**
     * Agrega el informe completo en flujo continuo (usado para pantallas).
     */
    fun construir(paciente: Paciente?, idLocal: Long, mediciones: List<Resultado>) {
        if (mediciones.isEmpty()) {
            mensajeVacio("No hay mediciones registradas para este paciente.")
            return
        }

        val actual = mediciones.first()
        val asc = mediciones.reversed() // cronológico: antiguo -> reciente

        val isMale = (paciente?.genero ?: "").uppercase().let { it == "M" || it == "MASCULINO" || it == "H" }
        val age = paciente?.calcularEdad()?.takeIf { it > 0 } ?: 30

        val pesoF = actual.peso.f()
        val masaMuscularF = actual.masa_muscular.f()
        val muscleRate = if (pesoF != null && pesoF > 0 && masaMuscularF != null)
            (masaMuscularF / pesoF * 100f) else null

        val devicePrefs = ctx.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showAlturaPeso = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
        val showComposicion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
        val showPresion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
        val showOxigeno = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
        val showTemperatura = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
        val showEcg = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

        agregarEncabezadoApp()
        agregarCabecera(paciente, actual)

        if (showAlturaPeso) agregarSeccionAlturaPeso(actual, asc)
        if (showComposicion) agregarSeccionComposicion(actual, asc, isMale, age, muscleRate)
        if (showPresion) agregarSeccionPresion(actual, asc)
        if (showOxigeno) agregarSeccionOxigeno(actual, asc)
        if (showTemperatura) agregarSeccionTemperatura(actual, asc)
        if (showEcg) agregarSeccionEcg(actual)
    }

    /** Página 1 del PDF: Altura y peso + Composición corporal */
    fun construirPagina1(paciente: Paciente?, idLocal: Long, mediciones: List<Resultado>) {
        if (mediciones.isEmpty()) return
        val actual = mediciones.first()
        val asc = mediciones.reversed()
        val isMale = (paciente?.genero ?: "").uppercase().let { it == "M" || it == "MASCULINO" || it == "H" }
        val age = paciente?.calcularEdad()?.takeIf { it > 0 } ?: 30
        val pesoF = actual.peso.f()
        val masaMuscularF = actual.masa_muscular.f()
        val muscleRate = if (pesoF != null && pesoF > 0 && masaMuscularF != null) (masaMuscularF / pesoF * 100f) else null

        val devicePrefs = ctx.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showAlturaPeso = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
        val showComposicion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)

        // Margen superior para que el header no quede cortado
        agregarEspaciadorSuperior()

        agregarEncabezadoApp()
        agregarCabecera(paciente, actual)

        if (showAlturaPeso) agregarSeccionAlturaPeso(actual, asc)
        if (showComposicion) agregarSeccionComposicion(actual, asc, isMale, age, muscleRate)
    }

    /** Página 2 del PDF: Presión arterial + Oxígeno + Temperatura corporal */
    fun construirPagina2(paciente: Paciente?, idLocal: Long, mediciones: List<Resultado>) {
        if (mediciones.isEmpty()) return
        val actual = mediciones.first()
        val asc = mediciones.reversed()

        val devicePrefs = ctx.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showPresion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
        val showOxigeno = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
        val showTemperatura = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)

        // Margen superior para que el header no quede cortado en páginas 2 y 3
        agregarEspaciadorSuperior()

        agregarCabecera(paciente, actual)

        if (showPresion) agregarSeccionPresion(actual, asc)
        if (showOxigeno) agregarSeccionOxigeno(actual, asc)
        if (showTemperatura) agregarSeccionTemperatura(actual, asc)
    }

    /** Página 3 del PDF: Electrocardiograma */
    fun construirPagina3(paciente: Paciente?, idLocal: Long, mediciones: List<Resultado>) {
        if (mediciones.isEmpty()) return
        val actual = mediciones.first()

        val devicePrefs = ctx.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showEcg = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

        // Margen superior para que el header no quede cortado
        agregarEspaciadorSuperior()

        agregarCabecera(paciente, actual)

        if (showEcg) agregarSeccionEcg(actual)
    }

    fun mensajeVacio(texto: String) {
        container.addView(TextView(ctx).apply {
            text = texto
            textSize = 16f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, 0)
        })
    }

    // ── Secciones individuales ──────────────────────────────────────────────────

    private fun agregarSeccionAlturaPeso(actual: Resultado, asc: List<Resultado>) {
        agregarSeccion("Altura y peso",
            columnaValores(
                tarjetaDato("Altura", fmt(actual.altura, "cm")),
                tarjetaDato("Peso", fmt(actual.peso, "kg")),
                etiquetaBarra("IMC"),
                rangeBar(RangosSalud.imc(), actual.imc.f())
            ),
            columnaGraficas(
                grafica("Tendencia de cambio de IMC", tendencia(asc) { it.imc.f() })
            )
        )
    }

    private fun agregarSeccionComposicion(actual: Resultado, asc: List<Resultado>, isMale: Boolean, age: Int, muscleRate: Float?) {
        agregarSeccion("Composición corporal",
            columnaValores(
                etiquetaBarra("Tasa de masa muscular"),
                rangeBar(RangosSalud.masaMuscular(isMale, age), muscleRate, "%"),
                etiquetaBarra("Tasa de grasa corporal"),
                rangeBar(RangosSalud.grasaCorporal(isMale, age), actual.grasa_corporal.f(), "%"),
                etiquetaBarra("Tasa de agua corporal"),
                rangeBar(RangosSalud.aguaCorporal(isMale, age), actual.agua_corporal.f(), "%"),
                etiquetaBarra("Grado de grasa visceral"),
                rangeBar(RangosSalud.grasaVisceral(), actual.grasa_visceral.f()),
                tablaTresColumnas(
                    listOf(
                        "Masa muscular" to fmt(actual.masa_muscular, "kg"),
                        "Masa grasa" to fmt(actual.grasa_corporal_kg, "kg"),
                        "Agua corporal" to fmt(actual.agua_corporal_kg, "kg")
                    ),
                    listOf(
                        "Masa proteica" to fmt(actual.proteina, "kg"),
                        "Masa mineral" to fmt(actual.minerales, "kg"),
                        "Metabolismo basal" to fmt(actual.metabolismo_basal, "Kcal")
                    )
                )
            ),
            columnaGraficas(
                grafica("Tendencia de masa muscular", tendencia(asc) { it.masa_muscular.f() }),
                grafica("Tendencia de la tasa de grasa corporal", tendencia(asc) { it.grasa_corporal.f() }),
                grafica("Tendencia de la tasa de agua corporal", tendencia(asc) { it.agua_corporal.f() }),
                grafica("Tendencia de grado de grasa visceral", tendencia(asc) { it.grasa_visceral.f() })
            )
        )
    }

    private fun agregarSeccionPresion(actual: Resultado, asc: List<Resultado>) {
        agregarSeccion("Presión arterial",
            columnaValores(
                etiquetaBarra("Presión sistólica"),
                rangeBar(RangosSalud.presionSistolica(), actual.sistolica.f(), " mmHg"),
                etiquetaBarra("Presión diastólica"),
                rangeBar(RangosSalud.presionDiastolica(), actual.diastolica.f(), " mmHg"),
                etiquetaBarra("Pulso"),
                rangeBar(RangosSalud.pulso(), actual.pulso.f(), " bpm")
            ),
            columnaGraficas(
                grafica("Tendencia de la presión sistólica", tendencia(asc) { it.sistolica.f() }),
                grafica("Tendencia de la presión diastólica", tendencia(asc) { it.diastolica.f() })
            )
        )
    }

    private fun agregarSeccionOxigeno(actual: Resultado, asc: List<Resultado>) {
        agregarSeccion("Oxígeno en sangre",
            columnaValores(
                etiquetaBarra("Oxígeno en sangre"),
                rangeBar(RangosSalud.spo2(), actual.spo2.f(), "%"),
                etiquetaBarra("Frecuencia del pulso"),
                rangeBar(RangosSalud.pulso(), actual.frecuencia_pulso.f(), " bpm"),
                etiquetaBarra("Índice de perfusión"),
                rangeBar(RangosSalud.indicePerfusion(), actual.indice_perfusion.f(), "%")
            ),
            columnaGraficas(
                grafica("Tendencia de oxígeno en sangre", tendencia(asc) { it.spo2.f() })
            )
        )
    }

    private fun agregarSeccionTemperatura(actual: Resultado, asc: List<Resultado>) {
        agregarSeccion("Temperatura corporal",
            columnaValores(
                etiquetaBarra("Temperatura corporal"),
                rangeBar(RangosSalud.temperatura(), actual.temperatura.f(), " °C")
            ),
            columnaGraficas(
                grafica("Tendencia de la temperatura corporal", tendencia(asc) { it.temperatura.f() })
            )
        )
    }

    // ── Secciones ──────────────────────────────────────────────────────────────

    /**
     * Agrega un pequeño espacio al inicio de cada página del PDF para evitar que el
     * encabezado quede cortado por el borde superior de la página.
     */
    private fun agregarEspaciadorSuperior() {
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16)
            )
        })
    }

    private fun agregarEncabezadoApp() {
        val appPrefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val defaultAppTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
        val pdfTitle = appPrefs.getString("PdfTitle", defaultAppTitle)?.takeIf { it.isNotBlank() } ?: defaultAppTitle
        val pdfSubtitle = appPrefs.getString("PdfSubtitle", "") ?: ""
        val logoPath = appPrefs.getString("LogoPath", null)

        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Padding generoso para que el logo no quede pegado al borde
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(10)
            }
            // NO usar elevation: el Canvas del PDF no dibuja sombras y recorta el contenido
        }

        val logoIv = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(70)).apply {
                marginEnd = dp(16)
            }
        }

        // Carga del logotipo puro sobre el encabezado sin recuadros de fondo
        val directBmp = loadLogoBitmapDirect(ctx, logoPath)
        if (directBmp != null) {
            logoIv.clearColorFilter()
            logoIv.setImageBitmap(directBmp)
            headerLayout.addView(logoIv)
        } else if (!logoPath.isNullOrBlank()) {
            logoIv.clearColorFilter()
            cargarLogoAsincrono(ctx, logoIv, logoPath)
            headerLayout.addView(logoIv)
        } else {
            // Logotipo por defecto: aplicar tinte con el color del tema para que resalte sobre el fondo blanco
            logoIv.setImageResource(R.drawable.sybi_logo_blanco)
            logoIv.setColorFilter(colorTema, PorterDuff.Mode.SRC_IN)
            headerLayout.addView(logoIv)
        }

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        textCol.addView(TextView(ctx).apply {
            text = pdfTitle
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(colorTema)
        })

        if (pdfSubtitle.isNotBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = pdfSubtitle
                textSize = 14f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, dp(2), 0, 0)
            })
        }

        headerLayout.addView(textCol)
        container.addView(headerLayout)
    }

    private fun loadLogoBitmapDirect(context: Context, path: String?): Bitmap? {
        val original: Bitmap? = try {
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    val uri = Uri.parse(path)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
            } else {
                ContextCompat.getDrawable(context, R.drawable.sybi_logo_blanco)?.toBitmap()
            }
        } catch (_: Exception) {
            try {
                ContextCompat.getDrawable(context, R.drawable.sybi_logo_blanco)?.toBitmap()
            } catch (_: Exception) {
                null
            }
        }

        // Aplanar el PNG transparente sobre fondo blanco para que el PDF
        // no muestre el canal alfa como negro.
        return original?.let { aplanarSobreBlanco(it) }
    }

    /**
     * Dibuja [src] sobre un Bitmap blanco del mismo tamaño, eliminando la transparencia.
     * Así el PDF (que no compone alfa contra el padre) muestra fondo blanco.
     */
    private fun aplanarSobreBlanco(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun cargarLogoAsincrono(context: Context, imageView: ImageView, path: String?) {
        try {
            if (!path.isNullOrBlank()) {
                val imageLoader = ImageLoader.Builder(context)
                    .components { add(SvgDecoder.Factory()) }
                    .build()

                val file = File(path)
                val data: Any = if (file.exists()) file else Uri.parse(path)

                val request = ImageRequest.Builder(context)
                    .data(data)
                    .target(imageView)
                    .error(R.drawable.sybi_logo_blanco)
                    .placeholder(R.drawable.sybi_logo_blanco)
                    .build()

                imageLoader.enqueue(request)
            } else {
                imageView.setImageResource(R.drawable.sybi_logo_blanco)
            }
        } catch (_: Exception) {
            imageView.setImageResource(R.drawable.sybi_logo_blanco)
        }
    }

    private fun agregarCabecera(paciente: Paciente?, actual: Resultado) {
        // ── Datos descompuestos ──────────────────────────────────────────────
        val nombres = paciente?.nombre?.trim().orEmpty().ifBlank { "-" }
        val apellidos = listOfNotNull(paciente?.apellido_paterno, paciente?.apellido_materno)
            .joinToString(" ").trim().ifBlank { "-" }

        val edad = paciente?.calcularEdad()?.takeIf { it > 0 }?.let { "$it años" } ?: "-"
        val genero = when ((paciente?.genero ?: "").uppercase()) {
            "M", "MASCULINO", "H" -> "Masculino"
            "F", "FEMENINO" -> "Femenino"
            else -> "-"
        }

        // Separar fecha y hora del campo fecha_medicion
        val (fecha, hora) = separarFechaHora(actual.fecha_medicion)

        // ── Construcción de la tabla 2x3 ─────────────────────────────────────
        val tabla = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Fila 1: Nombre(s) | Edad | Fecha
        val fila1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fila1.addView(celdaCabecera("Nombre(s)", nombres), pesoParam(2f))
        fila1.addView(celdaCabecera("Edad", edad), pesoParam())
        fila1.addView(celdaCabecera("Fecha", fecha), pesoParam(1.5f))

        // Fila 2: Apellidos | Género | Hora
        val fila2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        fila2.addView(celdaCabecera("Apellidos", apellidos), pesoParam(2f))
        fila2.addView(celdaCabecera("Género", genero), pesoParam())
        fila2.addView(celdaCabecera("Hora", hora), pesoParam(1.5f))

        tabla.addView(fila1)
        tabla.addView(fila2)

        container.addView(tarjeta(tabla))
    }

    /**
     * Crea una celda de la cabecera con el estilo: etiqueta en gris pequeña arriba,
     * valor en negrita debajo.
     */
    private fun celdaCabecera(label: String, valor: String): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(8), dp(2))
        }
        col.addView(TextView(ctx).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#6B7280"))
            setTypeface(null, Typeface.NORMAL)
        })
        col.addView(TextView(ctx).apply {
            text = valor
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, dp(2), 0, 0)
        })
        return col
    }

    /**
     * Separa el campo fecha_medicion en (fecha, hora).
     * Acepta formatos:
     *  - "dd/MM/yyyy HH:mm:ss"  -> ("dd/MM/yyyy", "HH:mm:ss")
     *  - "dd/MM/yyyy HH:mm"     -> ("dd/MM/yyyy", "HH:mm")
     *  - "dd/MM/yyyy"           -> ("dd/MM/yyyy", "-")
     *  - "yyyy-MM-dd HH:mm:ss"  -> ("dd/MM/yyyy", "HH:mm:ss")  (reformateado)
     *  - "yyyy-MM-dd HH:mm"     -> ("dd/MM/yyyy", "HH:mm")
     *  - "yyyy-MM-dd"           -> ("dd/MM/yyyy", "-")
     * Si no se puede parsear, se devuelve el string original como fecha y "-" como hora.
     */
    private fun separarFechaHora(raw: String): Pair<String, String> {
        if (raw.isBlank()) return "-" to "-"

        val formatos = listOf(
            "dd/MM/yyyy HH:mm:ss" to "dd/MM/yyyy",
            "dd/MM/yyyy HH:mm"    to "dd/MM/yyyy",
            "dd/MM/yyyy"          to "dd/MM/yyyy",
            "yyyy-MM-dd HH:mm:ss" to "dd/MM/yyyy",
            "yyyy-MM-dd HH:mm"    to "dd/MM/yyyy",
            "yyyy-MM-dd"          to "dd/MM/yyyy"
        )
        for ((patron, salidaFecha) in formatos) {
            runCatching {
                val dt = SimpleDateFormat(patron, Locale.getDefault()).parse(raw)
                if (dt != null) {
                    val fecha = SimpleDateFormat(salidaFecha, Locale.getDefault()).format(dt)
                    val tieneHora = patron.contains("HH")
                    val hora = if (tieneHora)
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(dt)
                    else "-"
                    return fecha to hora
                }
            }
        }
        return raw to "-"
    }

    private fun agregarSeccion(titulo: String, columnaIzq: View, columnaDer: View) {
        container.addView(bandaTitulo(titulo))
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        fila.addView(columnaIzq, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        fila.addView(columnaDer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        container.addView(fila)
    }

    private fun agregarSeccionEcg(actual: Resultado) {
        container.addView(bandaTitulo("Electrocardiograma de 6 derivaciones"))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Texto del resultado general de ECG en renglón solo (alineado a la izquierda)
        if (actual.resultado_ecg.isNotBlank() && actual.resultado_ecg != "0") {
            col.addView(
                TextView(ctx).apply {
                    text = actual.resultado_ecg
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#111827"))
                    setPadding(dp(2), dp(2), dp(2), dp(8))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Fila 1: Frecuencia, Eje P, Eje QRS, Eje T
        val fila1 = listOf(
            "Frecuencia" to fmtUnidad(actual.frecuencia_cardiaca, "bpm"),
            "Eje P" to fmtUnidad(actual.eje_p, "°"),
            "Eje QRS" to fmtUnidad(actual.eje_qrs, "°"),
            "Eje T" to fmtUnidad(actual.eje_t, "°")
        )

        // Fila 2: Intervalo PR, Intervalo QT, QT corregido, Duración QRS
        val fila2 = listOf(
            "Intervalo PR" to fmtUnidad(actual.intervalo_pr, "ms"),
            "Intervalo QT" to fmtUnidad(actual.intervalo_qt, "ms"),
            "QT corregido" to fmtUnidad(actual.qt_corregido, "ms"),
            "Duración QRS" to fmtUnidad(actual.duracion_qrs, "ms")
        )

        // Fila 3: Onda RV5, Onda SV1 (en las primeras 2 columnas de las 4)
        val fila3 = listOf(
            "Onda RV5" to fmtUnidad(actual.onda_rv5, "mV"),
            "Onda SV1" to fmtUnidad(actual.onda_sv1, "mV")
        )

        col.addView(crearFilaConDivisores(fila1, 4))
        col.addView(crearFilaConDivisores(fila2, 4))
        col.addView(crearFilaConDivisores(fila3, 4))

        var ecgLoaded = false
        if (actual.ruta_ecg.isNotBlank()) {
            val f = File(actual.ruta_ecg)
            if (f.exists()) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { bmp ->
                    col.addView(ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = dp(10) }
                    })
                    ecgLoaded = true
                }
            }
        }

        if (!ecgLoaded) {
            val prefs = ctx.getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)
            val ecgImageString = prefs.getString("ecg_image", null)
            if (!ecgImageString.isNullOrBlank()) {
                runCatching {
                    val bytes = Base64.decode(ecgImageString, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()?.let { bmp ->
                    col.addView(ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = dp(10) }
                    })
                }
            }
        }

        container.addView(tarjeta(col))
    }

    // ── Columnas y piezas ────────────────────────────────────────────────────

    private fun columnaValores(vararg vistas: View): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        vistas.forEach { col.addView(it) }
        return tarjeta(col)
    }

    private fun columnaGraficas(vararg vistas: View): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        vistas.forEach { col.addView(it) }
        return tarjeta(col)
    }

    private fun crearFilaConDivisores(
        items: List<Pair<String, String>>,
        numCols: Int
    ): View {
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(4), 0, dp(4))
        }

        for (colIndex in 0 until numCols) {
            if (colIndex > 0) {
                val divider = View(ctx).apply {
                    setBackgroundColor(Color.parseColor("#E0E0E0"))
                    layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        setMargins(dp(4), dp(2), dp(4), dp(2))
                    }
                }
                fila.addView(divider)
            }

            if (colIndex < items.size) {
                val (label, valStr) = items[colIndex]
                fila.addView(
                    tarjetaDato(label, valStr, paddingEndDp = 2),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            } else {
                fila.addView(
                    View(ctx),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
        }
        return fila
    }

    private fun tablaTresColumnas(
        r1: List<Pair<String, String>>,
        r2: List<Pair<String, String>>
    ): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        col.addView(crearFilaConDivisores(r1, 3))
        col.addView(crearFilaConDivisores(r2, 3))
        return col
    }

    private fun tarjetaDato(label: String, valor: String, paddingEndDp: Int = 2): View {
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(paddingEndDp), dp(4))
        }
        fila.addView(TextView(ctx).apply {
            text = label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#374151"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fila.addView(TextView(ctx).apply {
            text = valor
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        return fila
    }

    private fun etiquetaBarra(texto: String): View = TextView(ctx).apply {
        text = texto
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#374151"))
        setPadding(dp(4), dp(10), dp(4), dp(2))
    }

    private fun rangeBar(segmentos: List<RangeBarView.Segmento>, valor: Float?, sufijo: String = ""): View {
        return RangeBarView(ctx).apply {
            setSegmentos(segmentos)
            if (valor != null) setValor(valor.toDouble(), sufijo)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                .apply { topMargin = dp(2); bottomMargin = dp(6) }
        }
    }

    private fun grafica(titulo: String, datos: List<LineChartView.Punto>): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(TextView(ctx).apply {
            text = titulo
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(dp(2), 0, 0, dp(2))
        })
        if (datos.size < 2) {
            col.addView(TextView(ctx).apply {
                text = "Sin suficientes datos para la tendencia"
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(2), dp(8), 0, dp(8))
            })
        } else {
            col.addView(LineChartView(ctx).apply {
                setLineColor(colorTema)
                setData(datos)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90))
            })
        }
        return col
    }

    private fun tarjeta(contenido: View): View {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                // Borde sutil en lugar de sombra: el PDF (Canvas) no dibuja elevation
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            // Se elimina elevation y outlineProvider: no se renderizan en Canvas del PDF
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(2), dp(2), dp(2), dp(10)) }
        }
        wrap.addView(contenido)
        return wrap
    }

    private fun bandaTitulo(texto: String): View = TextView(ctx).apply {
        text = texto
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(colorTema)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun textoCabecera(texto: String) = TextView(ctx).apply {
        text = texto
        textSize = 13f
        setTextColor(Color.parseColor("#111827"))
    }

    // ── Datos / utilidades ───────────────────────────────────────────────────

    private fun tendencia(asc: List<Resultado>, selector: (Resultado) -> Float?): List<LineChartView.Punto> {
        return asc.mapNotNull { r ->
            val v = selector(r) ?: return@mapNotNull null
            if (v <= 0f) return@mapNotNull null
            LineChartView.Punto(v, etiquetaFecha(r.fecha_medicion))
        }
    }

    private fun etiquetaFecha(raw: String): String {
        if (raw.isBlank()) return ""
        val formatos = listOf("dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "dd/MM/yyyy", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (f in formatos) {
            runCatching {
                val dt = SimpleDateFormat(f, Locale.getDefault()).parse(raw)
                if (dt != null) return SimpleDateFormat("dd/MM", Locale.getDefault()).format(dt)
            }
        }
        return raw.take(5)
    }

    private fun String?.f(): Float? = this?.trim()?.replace(",", ".")?.toFloatOrNull()

    private fun fmt(valor: String, unidad: String): String {
        val limpio = valor.trim()
        if (limpio.isBlank() || limpio == "0" || limpio == "0.0") return "-"
        return "$limpio $unidad"
    }

    private fun fmtUnidad(valor: String, unidad: String): String {
        val limpio = valor.trim()
        if (limpio.isBlank() || limpio == "0" || limpio == "0.0") return "-"
        if (limpio.endsWith(unidad) || (unidad == "°" && limpio.endsWith("°"))) return limpio
        return if (unidad == "°") "$limpio°" else "$limpio $unidad"
    }

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun pesoParam(peso: Float = 1f) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
}