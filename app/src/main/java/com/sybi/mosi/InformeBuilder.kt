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
 * Construye el informe de salud (estilo MediTech) dentro de un contenedor dado.
 *
 * NOTA IMPORTANTE SOBRE EL PIE DE PÁGINA:
 * El pie "Página X de Y" YA NO se construye aquí. Lo dibuja directamente ResultsActivity
 * sobre el Canvas del PDF, anclado abajo-derecha de la hoja A4, para que no dependa del
 * alto del contenido ni se recorte con él.
 */
class InformeBuilder(
    private val ctx: Activity,
    private val container: LinearLayout,
    private val colorTema: Int,
    // Logos/razón social del cliente (subrogado), ya resueltos y cacheados en disco
    // por ClienteLogoCache.obtener() ANTES de construir la UI (esta clase no hace red).
    // Si es null, se usa el logo local único de siempre (AppPrefs.LogoPath) y no se
    // agrega pie de página con dirección.
    private val clienteLogo: ClienteLogoCache.Logos? = null,
    // Bitmaps ya decodificados en un hilo de fondo (ver InformeBuilder.cargarLogoBitmap),
    // para que construir cada página del PDF no tenga que decodificar SVGs/crear un
    // ImageLoader nuevo en el hilo principal. Si no se proveen, se decodifican aquí
    // mismo como antes (usado por las pantallas en vivo, que no pasan clienteLogo).
    private val logoIzquierdoBitmapPrecargado: Bitmap? = null,
    private val logoDerechoBitmapPrecargado: Bitmap? = null,
    // La foto del paciente (ver InformeBuilder.cargarFotoPaciente) se mostraba en una miniatura
    // de 75x85dp pero se decodificaba a su resolución ORIGINAL de cámara cada vez — hasta 3
    // veces por PDF (una por página) — sin ningún downsampling. Precargada aquí, ya reducida al
    // tamaño real que se necesita, evita ese gasto de memoria y CPU repetido.
    private val fotoPacientePrecargada: Bitmap? = null
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
        val asc = mediciones.reversed()

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

    /**
     * Calcula cuántas páginas tendrá el PDF según las mediciones activas en DevicePrefs.
     * Devuelve al menos 1.
     */
    fun calcularTotalPaginas(): Int {
        val prefs = ctx.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showAlturaPeso = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
        val showComposicion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
        val showPresion = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
        val showOxigeno = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
        val showTemperatura = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
        val showEcg = prefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

        var total = 0
        if (showAlturaPeso || showComposicion) total++
        if (showPresion || showOxigeno || showTemperatura) total++
        if (showEcg) total++
        return total.coerceAtLeast(1)
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

        // La página 1 NO lleva espaciador: el encabezado de app ya da el aire superior.
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

        agregarEncabezadoApp()
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

        agregarEncabezadoApp()
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
                rangeBar(RangosSalud.masaMuscular(isMale, age), muscleRate, "%", anchosIguales = true),
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
                rangeBar(RangosSalud.presionSistolica(), actual.sistolica.f(), " mmHg", anchosIguales = true),
                etiquetaBarra("Presión diastólica"),
                rangeBar(RangosSalud.presionDiastolica(), actual.diastolica.f(), " mmHg", anchosIguales = true),
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
     * Espaciador superior de páginas 2 y 3. Su alto debe igualar el del encabezado de app
     * de la página 1 para que las 3 páginas arranquen al mismo nivel.
     *
     * Alto del encabezado de app = padding sup (20) + alto logo (70) + padding inf (20) + bottomMargin (10) = 120dp
     */
    private fun agregarEspaciadorSuperior() {
        container.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)
            )
        })
    }

    private fun agregarEncabezadoApp() {
        val appPrefs = ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val defaultAppTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
        val pdfTitle = appPrefs.getString("PdfTitle", defaultAppTitle)?.takeIf { it.isNotBlank() } ?: defaultAppTitle
        val pdfSubtitle = appPrefs.getString("PdfSubtitle", "") ?: ""
        // El logo izquierdo del cliente (si hay) manda sobre el logo local único de siempre.
        val logoPath = clienteLogo?.logoIzquierdoPath ?: appPrefs.getString("LogoPath", null)

        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#DCE7ED"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(0)
                bottomMargin = dp(10)
            }
        }

        val logoIv = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(70)).apply {
                marginEnd = dp(16)
            }
        }

        val directBmp = logoIzquierdoBitmapPrecargado ?: loadLogoBitmapDirect(ctx, logoPath)
        if (directBmp != null) {
            logoIv.clearColorFilter()
            logoIv.setImageBitmap(directBmp)
            headerLayout.addView(logoIv)
        } else if (!logoPath.isNullOrBlank()) {
            logoIv.clearColorFilter()
            cargarLogoAsincrono(ctx, logoIv, logoPath)
            headerLayout.addView(logoIv)
        } else {
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
            setTextColor(Color.parseColor("#102A43"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        if (pdfSubtitle.isNotBlank()) {
            textCol.addView(TextView(ctx).apply {
                text = pdfSubtitle
                textSize = 14f
                setTextColor(Color.parseColor("#6B7280"))
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }

        textCol.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(colorTema)
                cornerRadius = dp(2).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(7)
            }
        })

        headerLayout.addView(textCol)

        // Logo derecho del cliente (opcional, segundo logo del header). Se reserva SIEMPRE
        // el mismo ancho que el logo izquierdo, haya o no imagen, para que el título quede
        // centrado respecto a la hoja y no se recorra hacia la izquierda cuando falta.
        val logoDerechoPath = clienteLogo?.logoDerechoPath
        val logoDerIv = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(70)).apply {
                marginStart = dp(16)
            }
        }
        if (!logoDerechoPath.isNullOrBlank()) {
            val bmpDer = logoDerechoBitmapPrecargado ?: loadLogoBitmapDirect(ctx, logoDerechoPath)
            if (bmpDer != null) {
                logoDerIv.clearColorFilter()
                logoDerIv.setImageBitmap(bmpDer)
            }
        }
        headerLayout.addView(logoDerIv)

        container.addView(headerLayout)
    }

    companion object {
        /**
         * Línea de dirección combinada (dirección + C.P. + estado) del cliente, lista para
         * el pie de página. El pie en sí NO se dibuja aquí: se ancla al fondo de cada hoja
         * (junto a "Página X de Y") directo en el Canvas del PDF, en ResultsActivity —
         * igual que el número de página — para que no dependa de cuánto contenido haya
         * arriba en esa página.
         */
        fun formatearDireccionCliente(logo: ClienteLogoCache.Logos?): String? {
            logo ?: return null
            val partes = listOfNotNull(
                logo.direccion?.trim()?.ifBlank { null },
                logo.codigoPostal?.trim()?.ifBlank { null }?.let { "C.P. $it" },
                logo.estado?.trim()?.ifBlank { null }
            )
            return partes.joinToString(", ").ifBlank { null }
        }

        // ImageLoader de Coil compartido: crearlo configura caché en disco/OkHttp, y antes
        // se instanciaba uno nuevo por cada logo de cada página (hasta 6 veces por PDF) en
        // el hilo principal. Reusar uno solo evita ese costo repetido.
        @Volatile
        private var sharedImageLoader: ImageLoader? = null

        private fun obtenerImageLoader(context: Context): ImageLoader {
            return sharedImageLoader ?: synchronized(this) {
                sharedImageLoader ?: ImageLoader.Builder(context.applicationContext)
                    .components { add(SvgDecoder.Factory()) }
                    .build()
                    .also { sharedImageLoader = it }
            }
        }

        /**
         * Decodifica la foto del paciente (base64, foto de cámara a resolución completa)
         * reducida al tamaño real en que se muestra en el informe (una miniatura de 75x85dp),
         * en vez de decodificarla entera para luego aplastarla en un ImageView pequeño. Pensada
         * para llamarse desde un hilo de fondo ANTES de construir las páginas del PDF, igual que
         * cargarLogoBitmap.
         */
        fun cargarFotoPaciente(base64Foto: String, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
            return try {
                val bytes = Base64.decode(base64Foto, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

                var sample = 1
                var w = bounds.outWidth
                var h = bounds.outHeight
                while (w / (sample * 2) >= targetWidthPx && h / (sample * 2) >= targetHeightPx) {
                    sample *= 2
                }

                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Decodifica el bitmap de un logo (PNG/SVG/etc. ya cacheado en disco). Pensada para
         * llamarse desde un hilo de fondo ANTES de construir las páginas del PDF (ver
         * ResultsActivity.generarPdfBytes), para que el hilo principal solo tenga que hacer
         * medir/layout/draw de Views, no decodificación de imágenes.
         */
        fun cargarLogoBitmap(context: Context, path: String?): Bitmap? {
            val original: Bitmap? = try {
                if (!path.isNullOrBlank()) {
                    val esSvg = path.substringAfterLast('.', "").equals("svg", ignoreCase = true)
                    if (esSvg) {
                        decodificarSvgSincrono(context, path)
                    } else {
                        val file = File(path)
                        if (file.exists()) {
                            BitmapFactory.decodeFile(file.absolutePath)
                        } else {
                            val uri = Uri.parse(path)
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                BitmapFactory.decodeStream(inputStream)
                            }
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

            return original?.let { aplanarSobreBlanco(it) }
        }

        private fun decodificarSvgSincrono(context: Context, path: String): Bitmap? {
            return try {
                val file = File(path)
                val data: Any = if (file.exists()) file else Uri.parse(path)
                val request = ImageRequest.Builder(context).data(data).build()
                val resultado = kotlinx.coroutines.runBlocking { obtenerImageLoader(context).execute(request) }
                resultado.drawable?.toBitmap()
            } catch (e: Exception) {
                null
            }
        }

        private fun aplanarSobreBlanco(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, 0f, 0f, null)
            return out
        }
    }

    private fun loadLogoBitmapDirect(context: Context, path: String?): Bitmap? = cargarLogoBitmap(context, path)

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
        val nombres = paciente?.nombre?.trim().orEmpty().ifBlank { "-" }
        val apellidos = listOfNotNull(paciente?.apellido_paterno, paciente?.apellido_materno)
            .joinToString(" ").trim().ifBlank { "-" }

        val edad = paciente?.calcularEdad()?.takeIf { it > 0 }?.let { "$it años" } ?: "-"
        val genero = when ((paciente?.genero ?: "").uppercase()) {
            "M", "MASCULINO", "H" -> "Masculino"
            "F", "FEMENINO" -> "Femenino"
            else -> "-"
        }

        val folio = paciente?.folio?.trim().orEmpty().ifBlank { "-" }

        val (fecha, hora) = separarFechaHora(actual.fecha_medicion)
        val fechaHoraStr = if (hora != "-") "$fecha $hora" else fecha

        val tabla = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        val fila1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        fila1.addView(celdaCabecera("Nombre(s)", nombres), pesoParam(2f))
        fila1.addView(celdaCabecera("Edad", edad), pesoParam(1f))
        fila1.addView(celdaCabecera("Fecha y hora", fechaHoraStr), pesoParam(1.8f))

        val fila2 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        fila2.addView(celdaCabecera("Apellidos", apellidos), pesoParam(2f))
        fila2.addView(celdaCabecera("Género", genero), pesoParam(1f))
        fila2.addView(celdaCabecera("Folio", folio), pesoParam(1.8f))

        tabla.addView(fila1)
        tabla.addView(fila2)

        val fotoContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(75), dp(85)).apply {
                marginStart = dp(12)
                gravity = Gravity.CENTER_VERTICAL
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F3F4F6"))
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            clipToOutline = true
        }

        val fotoIv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val fotoBitmap: Bitmap? = fotoPacientePrecargada ?: paciente?.foto
            ?.takeIf { it.isNotBlank() }
            ?.let { cargarFotoPaciente(it, dp(75), dp(85)) }

        if (fotoBitmap != null) {
            fotoIv.setImageBitmap(fotoBitmap)
        } else {
            fotoIv.setImageResource(R.drawable.ic_face)
            fotoIv.setColorFilter(Color.parseColor("#9CA3AF"), PorterDuff.Mode.SRC_IN)
            fotoIv.scaleType = ImageView.ScaleType.FIT_CENTER
            fotoIv.setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        fotoContainer.addView(fotoIv)

        val cabeceraRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        cabeceraRoot.addView(tabla, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cabeceraRoot.addView(fotoContainer)

        container.addView(tarjeta(cabeceraRoot))
    }

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

        val fila1 = listOf(
            "Frecuencia" to fmtUnidad(actual.frecuencia_cardiaca, "bpm"),
            "Eje P" to fmtUnidad(actual.eje_p, "°"),
            "Eje QRS" to fmtUnidad(actual.eje_qrs, "°"),
            "Eje T" to fmtUnidad(actual.eje_t, "°")
        )

        val fila2 = listOf(
            "Intervalo PR" to fmtUnidad(actual.intervalo_pr, "ms"),
            "Intervalo QT" to fmtUnidad(actual.intervalo_qt, "ms"),
            "QT corregido" to fmtUnidad(actual.qt_corregido, "ms"),
            "Duración QRS" to fmtUnidad(actual.duracion_qrs, "ms")
        )

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

    private fun rangeBar(
        segmentos: List<RangeBarView.Segmento>,
        valor: Float?,
        sufijo: String = "",
        anchosIguales: Boolean = false
    ): View {
        return RangeBarView(ctx).apply {
            setSegmentos(segmentos)
            setAnchosIguales(anchosIguales)
            if (valor != null) setValor(valor.toDouble(), sufijo)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68))
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
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(2), dp(2), dp(2), dp(10)) }
        }
        wrap.addView(contenido)
        return wrap
    }

    /**
     * Barra separadora de cada apartado ("Altura y peso", "Composición corporal",
     * "Presión arterial", "Oxígeno en sangre", "Temperatura corporal"...): una franja
     * de color de fondo (no solo texto coloreado) para que se note claramente el corte
     * entre secciones, incluso en escala de grises.
     */
    private fun bandaTitulo(texto: String): View = TextView(ctx).apply {
        text = texto
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(colorTema)
            cornerRadius = dp(4).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(6)
        }
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
