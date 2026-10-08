package com.sybi.mosi

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    /**
     * Agrega el informe al contenedor. [mediciones] viene en orden DESC ([0] = más reciente).
     */
    fun construir(paciente: Paciente?, idLocal: Long, mediciones: List<Resultado>) {
        val actual = mediciones.first()
        val asc = mediciones.reversed() // cronológico: antiguo -> reciente

        val isMale = (paciente?.genero ?: "").uppercase().let { it == "M" || it == "MASCULINO" || it == "H" }
        val age = paciente?.calcularEdad()?.takeIf { it > 0 } ?: 30

        val pesoF = actual.peso.f()
        val masaMuscularF = actual.masa_muscular.f()
        val muscleRate = if (pesoF != null && pesoF > 0 && masaMuscularF != null)
            (masaMuscularF / pesoF * 100f) else null

        agregarCabecera(paciente, idLocal, actual)

        agregarSeccion("IMC",
            columnaValores(
                tarjetaDato("Altura", fmt(actual.altura, "cm")),
                tarjetaDato("Peso", fmt(actual.peso, "kg")),
                rangeBar(RangosSalud.imc(), actual.imc.f())
            ),
            columnaGraficas(
                grafica("Tendencia de cambio de IMC", tendencia(asc) { it.imc.f() })
            )
        )

        agregarSeccion("Composición corporal",
            columnaValores(
                tarjetaDato("Masa muscular", fmt(actual.masa_muscular, "kg")),
                tarjetaDato("Masa grasa", fmt(actual.grasa_corporal_kg, "kg")),
                tarjetaDato("Agua corporal", fmt(actual.agua_corporal_kg, "kg")),
                tarjetaDato("Proteína", fmt(actual.proteina, "kg")),
                tarjetaDato("Sal inorgánica", fmt(actual.minerales, "kg")),
                tarjetaDato("Metabólico basal", fmt(actual.metabolismo_basal, "Kcal")),
                etiquetaBarra("Tasa de grasa corporal"),
                rangeBar(RangosSalud.grasaCorporal(isMale, age), actual.grasa_corporal.f()),
                etiquetaBarra("Masa muscular"),
                rangeBar(RangosSalud.masaMuscular(isMale, age), muscleRate),
                etiquetaBarra("Tasa de agua corporal"),
                rangeBar(RangosSalud.aguaCorporal(isMale, age), actual.agua_corporal.f()),
                etiquetaBarra("Grado de grasa visceral"),
                rangeBar(RangosSalud.grasaVisceral(), actual.grasa_visceral.f())
            ),
            columnaGraficas(
                grafica("Tendencia de masa muscular", tendencia(asc) { it.masa_muscular.f() }),
                grafica("Tendencia de la tasa de grasa corporal", tendencia(asc) { it.grasa_corporal.f() }),
                grafica("Tendencia de la tasa de agua corporal", tendencia(asc) { it.agua_corporal.f() }),
                grafica("Tendencia de grado de grasa visceral", tendencia(asc) { it.grasa_visceral.f() })
            )
        )

        agregarSeccion("Presión arterial",
            columnaValores(
                tarjetaDato("Presión sistólica", fmt(actual.sistolica, "mmHg")),
                tarjetaDato("Presión diastólica", fmt(actual.diastolica, "mmHg")),
                tarjetaDato("Pulso", fmt(actual.pulso, "Veces / min")),
                etiquetaBarra("Presión sistólica"),
                rangeBar(RangosSalud.presionSistolica(), actual.sistolica.f())
            ),
            columnaGraficas(
                grafica("Tendencia de la presión sistólica", tendencia(asc) { it.sistolica.f() }),
                grafica("Tendencia de la presión diastólica", tendencia(asc) { it.diastolica.f() })
            )
        )

        agregarSeccion("Oxígeno en sangre",
            columnaValores(
                tarjetaDato("Frecuencia del pulso", fmt(actual.frecuencia_pulso, "Veces / min")),
                etiquetaBarra("Oxígeno en sangre"),
                rangeBar(RangosSalud.spo2(), actual.spo2.f())
            ),
            columnaGraficas(
                grafica("Tendencia de oxígeno en sangre", tendencia(asc) { it.spo2.f() })
            )
        )

        agregarSeccion("Temperatura corporal",
            columnaValores(
                tarjetaDato("Temperatura corporal", fmt(actual.temperatura, "°C")),
                etiquetaBarra("Temperatura corporal"),
                rangeBar(RangosSalud.temperatura(), actual.temperatura.f())
            ),
            columnaGraficas(
                grafica("Tendencia de la temperatura corporal", tendencia(asc) { it.temperatura.f() })
            )
        )

        agregarSeccionEcg(actual)
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

    // ── Secciones ──────────────────────────────────────────────────────────────

    private fun agregarCabecera(paciente: Paciente?, idLocal: Long, actual: Resultado) {
        val nombre = listOfNotNull(paciente?.nombre, paciente?.apellido_paterno, paciente?.apellido_materno)
            .joinToString(" ").trim().ifBlank { "Paciente" }
        val id = (paciente?.id_usuario_web?.takeIf { it > 0 } ?: idLocal).toString().padStart(10, '0')
        val genero = when ((paciente?.genero ?: "").uppercase()) {
            "M", "MASCULINO", "H" -> "Masculino"
            "F", "FEMENINO" -> "Femenino"
            else -> "-"
        }

        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fila.addView(textoCabecera("ID: $id"), pesoParam())
        fila.addView(textoCabecera("Nombre: $nombre"), pesoParam(2f))
        fila.addView(textoCabecera("Género: $genero"), pesoParam())
        fila.addView(textoCabecera("Medir el tiempo: ${actual.fecha_medicion.ifBlank { "-" }}"), pesoParam(1.5f))

        container.addView(tarjeta(fila))
    }

    private fun agregarSeccion(titulo: String, columnaIzq: View, columnaDer: View) {
        container.addView(bandaTitulo(titulo))
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        fila.addView(columnaIzq, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        fila.addView(columnaDer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(fila)
    }

    private fun agregarSeccionEcg(actual: Resultado) {
        container.addView(bandaTitulo("ECG"))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val datos = listOf(
            "Frecuencia" to fmt(actual.frecuencia_cardiaca, "bpm"),
            "Eje P" to actual.eje_p, "Eje QRS" to actual.eje_qrs, "Eje T" to actual.eje_t,
            "Intervalo PR" to actual.intervalo_pr, "Duración QRS" to actual.duracion_qrs,
            "Intervalo QT" to actual.intervalo_qt, "QT corregido" to actual.qt_corregido,
            "Onda RV5" to actual.onda_rv5, "Onda SV1" to actual.onda_sv1,
            "Resultado" to actual.resultado_ecg
        ).filter { it.second.isNotBlank() && it.second != "0" }

        var filaEcg: LinearLayout? = null
        datos.forEachIndexed { i, (label, valor) ->
            if (i % 3 == 0) {
                filaEcg = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(filaEcg)
            }
            filaEcg!!.addView(tarjetaDato(label, valor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        if (actual.ruta_ecg.isNotBlank()) {
            val f = File(actual.ruta_ecg)
            if (f.exists()) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { bmp ->
                    col.addView(ImageView(ctx).apply {
                        setImageBitmap(bmp)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { topMargin = dp(8) }
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

    private fun tarjetaDato(label: String, valor: String): View {
        val fila = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fila.addView(TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fila.addView(TextView(ctx).apply {
            text = valor
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
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

    private fun rangeBar(segmentos: List<RangeBarView.Segmento>, valor: Float?): View {
        return RangeBarView(ctx).apply {
            setSegmentos(segmentos)
            if (valor != null) setValor(valor.toDouble())
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
            elevation = dp(2).toFloat()
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

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun pesoParam(peso: Float = 1f) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, peso)
}
