package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import com.sybi.mosi.measurement.LineChartView
import com.sybi.mosi.measurement.RangeBarView
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Informe de salud completo al estilo del PDF MediTech: por cada bloque (IMC, composición,
 * presión, oxígeno, temperatura, ECG) muestra a la izquierda los valores de la medición más
 * reciente (con barras de rango donde aplica) y a la derecha las gráficas de tendencia de las
 * últimas mediciones (actual + hasta 20 anteriores del mismo paciente).
 */
class InformeActivity : BaseActivity() {

    companion object {
        private const val TAG = "InformeActivity"
        const val EXTRA_ID_LOCAL = "id_local"
        private const val MAX_MEDICIONES = 21 // actual + 20 anteriores
    }

    private lateinit var topBar: View
    private lateinit var container: LinearLayout
    private var idLocal: Long = 0L
    private var paciente: Paciente? = null
    private var mediciones: List<Resultado> = emptyList() // DESC: [0] = más reciente
    private var colorTema: Int = Color.parseColor("#0F3E82")

    private val uiHandler = Handler(Looper.getMainLooper())
    private val d get() = resources.displayMetrics.density

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nuevo = intent.getStringExtra("new_color") ?: return
            colorTema = runCatching { Color.parseColor(nuevo) }.getOrDefault(colorTema)
            topBar.setBackgroundColor(colorTema)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_informe)

        topBar = findViewById(R.id.topInformeBar)
        container = findViewById(R.id.informeContainer)

        idLocal = intent.getLongExtra(EXTRA_ID_LOCAL, 0L)

        colorTema = runCatching {
            Color.parseColor(getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
        }.getOrDefault(Color.parseColor("#0F3E82"))
        topBar.setBackgroundColor(colorTema)
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        findViewById<Button>(R.id.btnBackInforme).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnImprimirInforme).setOnClickListener {
            android.widget.Toast.makeText(this, "La exportación a PDF del informe se agregará en una siguiente versión", android.widget.Toast.LENGTH_SHORT).show()
        }

        cargarDatos()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver) } catch (_: Exception) {}
    }

    private fun cargarDatos() {
        container.addView(TextView(this).apply {
            text = "Cargando informe..."
            textSize = 18f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, dp(60), 0, 0)
        })

        Thread {
            runBlocking {
                val db = AppDatabase.getInstance(this@InformeActivity)
                paciente = if (idLocal != 0L) db.pacienteDao().obtenerPacientePorIdLocal(idLocal) else null
                mediciones = if (idLocal != 0L) {
                    db.resultadoDao().obtenerResultadosPorIdLocal(idLocal).take(MAX_MEDICIONES)
                } else {
                    // Invitado (id_local=0): el historial de id_local=0 es compartido por todos
                    // los invitados, así que solo mostramos la medición más reciente (la actual),
                    // sin tendencias, para no mezclar datos de otras personas.
                    db.resultadoDao().obtenerResultadosPorIdLocal(0L).take(1)
                }
            }
            uiHandler.post {
                container.removeAllViews()
                if (mediciones.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "No hay mediciones registradas para este paciente."
                        textSize = 16f
                        setTextColor(Color.parseColor("#757575"))
                        gravity = Gravity.CENTER
                        setPadding(0, dp(60), 0, 0)
                    })
                } else {
                    construirInforme()
                }
            }
        }.start()
    }

    // ── Construcción del informe ─────────────────────────────────────────────

    private fun construirInforme() {
        val actual = mediciones.first()
        val asc = mediciones.reversed() // cronológico: antiguo -> reciente

        agregarCabecera(actual)

        // IMC
        agregarSeccion("IMC",
            columnaValores(
                tarjetaDato("Altura", fmt(actual.altura, "cm")),
                tarjetaDato("Peso", fmt(actual.peso, "kg")),
                rangeBar(segmentosImc(), actual.imc.f())
            ),
            columnaGraficas(
                grafica("Tendencia de cambio de IMC", tendencia(asc) { it.imc.f() })
            )
        )

        // Composición corporal
        agregarSeccion("Composición corporal",
            columnaValores(
                tarjetaDato("Peso", fmt(actual.peso, "kg")),
                tarjetaDato("Masa muscular", fmt(actual.masa_muscular, "kg")),
                tarjetaDato("Masa grasa", fmt(actual.grasa_corporal_kg, "kg")),
                tarjetaDato("Agua corporal", fmt(actual.agua_corporal_kg, "kg")),
                tarjetaDato("Proteína", fmt(actual.proteina, "kg")),
                tarjetaDato("Sal inorgánica", fmt(actual.minerales, "kg")),
                tarjetaDato("Tasa de agua corporal", fmt(actual.agua_corporal, "%")),
                tarjetaDato("Metabólico basal", fmt(actual.metabolismo_basal, "Kcal")),
                etiquetaBarra("Tasa de grasa corporal"),
                rangeBar(segmentosEstandar(), actual.grasa_corporal.f()),
                etiquetaBarra("Grado de grasa visceral"),
                rangeBar(segmentosVisceral(), actual.grasa_visceral.f())
            ),
            columnaGraficas(
                grafica("Tendencia de cambio de peso", tendencia(asc) { it.peso.f() }),
                grafica("Tendencia de cambio de masa muscular", tendencia(asc) { it.masa_muscular.f() }),
                grafica("Tendencia de la tasa de grasa corporal", tendencia(asc) { it.grasa_corporal.f() }),
                grafica("Tendencia de grado de grasa visceral", tendencia(asc) { it.grasa_visceral.f() })
            )
        )

        // Presión arterial
        agregarSeccion("Presión arterial",
            columnaValores(
                tarjetaDato("Presión sistólica", fmt(actual.sistolica, "mmHg")),
                tarjetaDato("Presión diastólica", fmt(actual.diastolica, "mmHg")),
                tarjetaDato("Pulso", fmt(actual.pulso, "Veces / min")),
                etiquetaBarra("Presión sistólica"),
                rangeBar(segmentosPresion(), actual.sistolica.f())
            ),
            columnaGraficas(
                grafica("Tendencia de la presión sistólica", tendencia(asc) { it.sistolica.f() }),
                grafica("Tendencia de la presión diastólica", tendencia(asc) { it.diastolica.f() })
            )
        )

        // Oxígeno en sangre
        agregarSeccion("Oxígeno en sangre",
            columnaValores(
                tarjetaDato("Frecuencia del pulso", fmt(actual.frecuencia_pulso, "Veces / min")),
                etiquetaBarra("Oxígeno en sangre"),
                rangeBar(segmentosSpo2(), actual.spo2.f())
            ),
            columnaGraficas(
                grafica("Tendencia de oxígeno en sangre", tendencia(asc) { it.spo2.f() })
            )
        )

        // Temperatura corporal
        agregarSeccion("Temperatura corporal",
            columnaValores(
                tarjetaDato("Temperatura corporal", fmt(actual.temperatura, "°C")),
                etiquetaBarra("Temperatura corporal"),
                rangeBar(segmentosTemperatura(), actual.temperatura.f())
            ),
            columnaGraficas(
                grafica("Tendencia de la temperatura corporal", tendencia(asc) { it.temperatura.f() })
            )
        )

        // ECG
        agregarSeccionEcg(actual)
    }

    private fun agregarCabecera(actual: Resultado) {
        val p = paciente
        val nombre = listOfNotNull(p?.nombre, p?.apellido_paterno, p?.apellido_materno)
            .joinToString(" ").trim().ifBlank { "Paciente" }
        val id = (p?.id_usuario_web?.takeIf { it > 0 } ?: idLocal).toString().padStart(10, '0')
        val genero = when ((p?.genero ?: "").uppercase()) {
            "M", "MASCULINO", "H" -> "Masculino"
            "F", "FEMENINO" -> "Femenino"
            else -> "-"
        }

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        fila.addView(textoCabecera("ID: $id"), pesoParam())
        fila.addView(textoCabecera("Nombre: $nombre"), pesoParam(2f))
        fila.addView(textoCabecera("Género: $genero"), pesoParam())
        fila.addView(textoCabecera("Medir el tiempo: ${actual.fecha_medicion.ifBlank { "-" }}"), pesoParam(1.5f))

        container.addView(tarjeta(fila))
    }

    /** Una sección: banda de título + fila de 2 columnas (valores | gráficas). */
    private fun agregarSeccion(titulo: String, columnaIzq: View, columnaDer: View) {
        container.addView(bandaTitulo(titulo))
        val fila = LinearLayout(this).apply {
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Valores en rejilla de 3 columnas
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
                filaEcg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(filaEcg)
            }
            filaEcg!!.addView(tarjetaDato(label, valor), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // Imagen del ECG (desde ruta_ecg)
        if (actual.ruta_ecg.isNotBlank()) {
            val f = File(actual.ruta_ecg)
            if (f.exists()) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()?.let { bmp ->
                    col.addView(ImageView(this).apply {
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

    // ── Columnas ─────────────────────────────────────────────────────────────

    private fun columnaValores(vararg vistas: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vistas.forEach { col.addView(it) }
        return tarjeta(col)
    }

    private fun columnaGraficas(vararg vistas: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        vistas.forEach { col.addView(it) }
        return tarjeta(col)
    }

    // ── Piezas ───────────────────────────────────────────────────────────────

    private fun tarjetaDato(label: String, valor: String): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        fila.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fila.addView(TextView(this).apply {
            text = valor
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return fila
    }

    private fun etiquetaBarra(texto: String): View = TextView(this).apply {
        text = texto
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#374151"))
        setPadding(dp(4), dp(10), dp(4), dp(2))
    }

    private fun rangeBar(segmentos: List<RangeBarView.Segmento>, valor: Float?): View {
        return RangeBarView(this).apply {
            setSegmentos(segmentos)
            if (valor != null) setValor(valor.toDouble())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                .apply { topMargin = dp(2); bottomMargin = dp(6) }
        }
    }

    private fun grafica(titulo: String, datos: List<LineChartView.Punto>): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        col.addView(TextView(this).apply {
            text = titulo
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(dp(2), 0, 0, dp(2))
        })
        if (datos.size < 2) {
            col.addView(TextView(this).apply {
                text = "Sin suficientes datos para la tendencia"
                textSize = 11f
                setTextColor(Color.parseColor("#9E9E9E"))
                setPadding(dp(2), dp(8), 0, dp(8))
            })
        } else {
            col.addView(LineChartView(this).apply {
                setLineColor(colorTema)
                setData(datos)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90))
            })
        }
        return col
    }

    // ── Contenedores visuales ────────────────────────────────────────────────

    private fun tarjeta(contenido: View): View {
        val wrap = LinearLayout(this).apply {
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

    private fun bandaTitulo(texto: String): View = TextView(this).apply {
        text = texto
        textSize = 17f
        setTypeface(null, Typeface.BOLD)
        setTextColor(colorTema)
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun textoCabecera(texto: String) = TextView(this).apply {
        text = texto
        textSize = 13f
        setTextColor(Color.parseColor("#111827"))
    }

    // ── Datos / tendencias ───────────────────────────────────────────────────

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
                val d = SimpleDateFormat(f, Locale.getDefault()).parse(raw)
                if (d != null) return SimpleDateFormat("dd/MM", Locale.getDefault()).format(d)
            }
        }
        return raw.take(5)
    }

    // ── Definición de las barras de rango (segmentos del PDF) ────────────────

    private fun segmentosImc() = listOf(
        RangeBarView.Segmento("Estándar bajo", 10.0, 18.5, Color.parseColor("#1E88E5")),
        RangeBarView.Segmento("Estándar", 18.5, 24.9, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Alto estándar", 24.9, 40.0, Color.parseColor("#E37214"))
    )

    private fun segmentosEstandar() = listOf(
        RangeBarView.Segmento("Estándar bajo", 0.0, 10.0, Color.parseColor("#1E88E5")),
        RangeBarView.Segmento("Estándar", 10.0, 20.0, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Alto estándar", 20.0, 50.0, Color.parseColor("#E37214"))
    )

    private fun segmentosVisceral() = listOf(
        RangeBarView.Segmento("Estándar", 0.0, 9.0, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Alto", 9.0, 14.0, Color.parseColor("#E37214")),
        RangeBarView.Segmento("Muy alto", 14.0, 30.0, Color.parseColor("#D32F2F"))
    )

    private fun segmentosPresion() = listOf(
        RangeBarView.Segmento("Demasiado bajo", 40.0, 90.0, Color.parseColor("#1E88E5")),
        RangeBarView.Segmento("Normal", 90.0, 140.0, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Demasiado alto", 140.0, 200.0, Color.parseColor("#D32F2F"))
    )

    private fun segmentosSpo2() = listOf(
        RangeBarView.Segmento("Estándar bajo", 70.0, 90.0, Color.parseColor("#FBC02D")),
        RangeBarView.Segmento("Estándar", 90.0, 95.0, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Alto estándar", 95.0, 100.0, Color.parseColor("#1E88E5"))
    )

    private fun segmentosTemperatura() = listOf(
        RangeBarView.Segmento("Demasiado bajo", 32.0, 36.0, Color.parseColor("#1E88E5")),
        RangeBarView.Segmento("Normal", 36.0, 37.5, Color.parseColor("#32A852")),
        RangeBarView.Segmento("Demasiado alto", 37.5, 43.0, Color.parseColor("#D32F2F"))
    )

    // ── Utilidades ───────────────────────────────────────────────────────────

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
