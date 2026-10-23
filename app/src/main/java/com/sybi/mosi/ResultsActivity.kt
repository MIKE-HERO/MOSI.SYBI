package com.sybi.mosi

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.repository.MedicionesSender
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream

class ResultsActivity : BaseActivity() {

    companion object {
        private const val TAG = "ResultsActivity"
    }

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var resultsContainer: LinearLayout
    private lateinit var btnStartConsultation: LinearLayout
    private lateinit var btnPrintResults: LinearLayout
    private lateinit var btnPrintTicket: LinearLayout
    private lateinit var btnEmailResults: LinearLayout
    private lateinit var btnExitResults: View

    private var LinearLayout.text: CharSequence?
        get() = (getChildAt(1) as? TextView)?.text
        set(value) {
            (getChildAt(1) as? TextView)?.text = value
        }

    private var idLocal: Long = 0L
    private var paciente: Paciente? = null
    private var medicionesCargadas: List<com.sybi.mosi.database.Resultado> = emptyList()
    private var envioExitoso = false

    private var idUsuarioWebCargado: Int = 0

    private var resultadoActual: com.sybi.mosi.database.Resultado? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var tvPlaceholder: TextView

    private var enviandoMediciones = false
    private var medicionesEnviadas = false
    private var contadorMensajesEnvio = 0
    private val envioHandler = Handler(Looper.getMainLooper())
    private val envioRunnable = object : Runnable {
        override fun run() {
            if (!enviandoMediciones) return
            contadorMensajesEnvio++
            val mensaje = when (contadorMensajesEnvio) {
                1 -> "Enviando mediciones..."
                2 -> "Enviando mediciones... aún trabajando"
                3 -> "Enviando ECG, puede tardar unos segundos..."
                else -> "Enviando mediciones... (${contadorMensajesEnvio * 2}s)"
            }
            Toast.makeText(this@ResultsActivity, mensaje, Toast.LENGTH_SHORT).show()
            envioHandler.postDelayed(this, 2000L)
        }
    }

    // ✅ HTML del reporte generado UNA SOLA VEZ y reutilizado por imprimir y correo
    private var htmlReporte: String? = null

    // ✅ Flag para bloquear la navegación mientras el diálogo de impresión está abierto
    @Volatile private var impresionEnCurso = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_results)

        topBar = findViewById(R.id.topResultsBar)
        bottomBar = findViewById(R.id.bottomResultsBar)
        resultsContainer = findViewById(R.id.resultsContainer)
        btnStartConsultation = findViewById(R.id.btnStartConsultation)
        btnPrintResults = findViewById(R.id.btnPrintResults)
        btnPrintTicket = findViewById(R.id.btnPrintTicket)
        btnEmailResults = findViewById(R.id.btnEmailResults)
        btnExitResults = findViewById(R.id.btnExitResults)

        idLocal = intent.getLongExtra("id_local", 0L)
        Log.d(TAG, "📥 id_local recibido: $idLocal")

        tvPlaceholder = TextView(this).apply {
            text = "Recopilando resultados..."
            textSize = 20f
            setTextColor(Color.parseColor("#757575"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 80, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resultsContainer.addView(tvPlaceholder)

        btnStartConsultation.isEnabled = false

        // Tema
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        topBar.setBackgroundColor(Color.parseColor(savedColor))
        bottomBar.setBackgroundColor(Color.parseColor(savedColor))

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    topBar.setBackgroundColor(Color.parseColor(newColor))
                    bottomBar.setBackgroundColor(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Botón "Iniciar consulta"
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val telemedicineEnabled = devicePrefs.getBoolean("telemedicine_enabled", false)

        if (telemedicineEnabled) {
            btnStartConsultation.visibility = View.VISIBLE
            btnStartConsultation.setOnClickListener {
                if (!envioExitoso) {
                    Toast.makeText(
                        this,
                        "Espera a que se envíen los resultados antes de iniciar la consulta",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                if (idUsuarioWebCargado <= 0) {
                    Toast.makeText(
                        this,
                        "Este paciente no está dado de alta en el sistema del doctor",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                iniciarVideollamada(idUsuarioWebCargado)
            }
        } else {
            btnStartConsultation.visibility = View.GONE
        }

        // --- Visibilidad según preferencias de informes ---
        val allowPrint = devicePrefs.getBoolean(
            ReportSettingsActivity.PREF_ALLOW_PRINT, true
        )
        val allowEmail = devicePrefs.getBoolean(
            ReportSettingsActivity.PREF_ALLOW_EMAIL, true
        )
        val allowTicketPrint = devicePrefs.getBoolean(
            ReportSettingsActivity.PREF_ALLOW_TICKET_PRINT, true
        )

        btnPrintResults.visibility = if (allowPrint) View.VISIBLE else View.GONE
        btnPrintTicket.visibility = if (allowTicketPrint) View.VISIBLE else View.GONE
        btnEmailResults.visibility = if (allowEmail) View.VISIBLE else View.GONE

        btnPrintResults.setOnClickListener {
            imprimirResultados()
        }

        btnPrintTicket.setOnClickListener {
            imprimirTicketResultados()
        }

        btnEmailResults.setOnClickListener {
            mostrarDialogoEnviarCorreo()
        }

        btnExitResults.setOnClickListener {
            if (impresionEnCurso) {
                Toast.makeText(this, "Espera a que termine la impresión", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            clearResults()
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            )
            startActivity(intent)
            finish()
        }

        cargarPacienteYRenderizar()
    }

    private fun cargarPacienteYRenderizar() {
        val idResultadoIntent = intent.getLongExtra("id_resultado", 0L)

        Thread {
            var mediciones: List<com.sybi.mosi.database.Resultado> = emptyList()
            var seleccionado: com.sybi.mosi.database.Resultado? = null

            runBlocking {
                val db = AppDatabase.getInstance(this@ResultsActivity)

                if (idResultadoIntent != 0L) {
                    // ✅ Vengo de la tabla de resultados: cargar ESA medición
                    val rSeleccionado = db.resultadoDao().obtenerResultadoPorId(idResultadoIntent)
                    if (rSeleccionado != null) {
                        idLocal = rSeleccionado.id_local
                        paciente = if (idLocal != 0L) db.pacienteDao().obtenerPacientePorIdLocal(idLocal) else null
                        val historial = if (idLocal != 0L) {
                            db.resultadoDao().obtenerResultadosPorIdLocal(idLocal).take(21)
                        } else {
                            listOf(rSeleccionado)
                        }
                        mediciones = listOf(rSeleccionado) + historial.filter { it.id_resultado != rSeleccionado.id_resultado }
                        seleccionado = rSeleccionado
                        Log.d(TAG, "📌 Resultado SELECCIONADO: id=${rSeleccionado.id_resultado}")
                    }
                }

                if (mediciones.isEmpty()) {
                    // ✅ Vengo de una medición recién hecha: usar la más reciente
                    paciente = if (idLocal != 0L) {
                        db.pacienteDao().obtenerPacientePorIdLocal(idLocal)
                    } else null

                    mediciones = if (idLocal != 0L) {
                        db.resultadoDao().obtenerResultadosPorIdLocal(idLocal).take(21)
                    } else {
                        db.resultadoDao().obtenerResultadosPorIdLocal(0L).take(1)
                    }
                    seleccionado = mediciones.firstOrNull()
                    Log.d(TAG, "📌 Resultado MÁS RECIENTE: id=${seleccionado?.id_resultado}")
                }
            }

            medicionesCargadas = mediciones
            resultadoActual = seleccionado   // ✅ Guardar el actual

            val p = paciente

            // Mismo motivo que en generarPdfBytes(): decodificar el logo (y la foto del
            // paciente) AQUÍ, en este hilo de fondo, no dentro del uiHandler.post de abajo.
            // Esta vista EN PANTALLA (no el PDF) se reconstruye cada vez que se abre
            // ResultsActivity —incluyendo desde el histórico de mediciones— y antes hacía ese
            // trabajo síncrono dentro del hilo principal; con hasta 21 mediciones de historial
            // y una tablet de 2GB de RAM, eso es justo lo que puede congelar la app el tiempo
            // suficiente para que el sistema muestre "Quiosco no responde".
            val logoPath = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("LogoPath", null)
            val logoBitmap = InformeBuilder.cargarLogoBitmap(this@ResultsActivity, logoPath)
            val fotoBitmap = p?.foto
                ?.takeIf { it.isNotBlank() }
                ?.let { InformeBuilder.cargarFotoPaciente(it, dp(75), dp(85)) }

            uiHandler.post {
                tvPlaceholder.visibility = View.GONE
                resultsContainer.removeAllViews()
                val colorTema = runCatching {
                    Color.parseColor(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
                }.getOrDefault(Color.parseColor("#0F3E82"))
                val builder = InformeBuilder(
                    this@ResultsActivity, resultsContainer, colorTema,
                    logoIzquierdoBitmapPrecargado = logoBitmap,
                    fotoPacientePrecargada = fotoBitmap
                )
                if (mediciones.isEmpty()) {
                    builder.mensajeVacio("No hay mediciones registradas para este paciente.")
                } else {
                    builder.construir(p, idLocal, mediciones)
                }

                if (p?.correo.isNullOrBlank()) {
                    addEmailWarning()
                }

                htmlReporte = buildResultsHtml()
            }

            if (p != null && p.id_usuario_web != null) {
                idUsuarioWebCargado = p.id_usuario_web!!
                if (!medicionesEnviadas) {
                    medicionesEnviadas = true
                    enviarMedicionesAlDoctor(p)
                }
            }
        }.start()
    }

    // ==========================================
    // ENVIAR MEDICIONES A LA API
    // ==========================================
    private fun enviarMedicionesAlDoctor(p: Paciente) {
        uiHandler.post {
            btnStartConsultation.isEnabled = false
            btnStartConsultation.text = "Enviando resultados..."

            enviandoMediciones = true
            contadorMensajesEnvio = 0
            envioHandler.post(envioRunnable)
        }

        Thread {
            try {
                val sender = MedicionesSender(this@ResultsActivity)

                val idUsuarioWeb = p.id_usuario_web
                if (idUsuarioWeb == null) {
                    Log.w(TAG, "⚠️ Paciente sin id_usuario_web")
                    uiHandler.post {
                        detenerMensajesEnvio()
                        btnStartConsultation.isEnabled = true
                        btnStartConsultation.text = "Iniciar consulta"
                    }
                    return@Thread
                }

                val requestData = runBlocking {
                    sender.buildFromPrefs(
                        idUsuarioWeb = idUsuarioWeb,
                        idLocal = idLocal
                    )
                }

                Log.d(TAG, "📤 Enviando mediciones...")
                Log.d(TAG, "   id_UsuarioWeb: $idUsuarioWeb")

                val exito = runBlocking { sender.enviar(requestData) }

                uiHandler.post {
                    detenerMensajesEnvio()
                    envioExitoso = exito
                    btnStartConsultation.isEnabled = true
                    btnStartConsultation.text = "Iniciar consulta"

                    Toast.makeText(
                        this@ResultsActivity,
                        if (exito) "✅ Resultados enviados al doctor"
                        else "⚠️ No se pudieron enviar los resultados",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error enviando: ${e.message}", e)
                uiHandler.post {
                    detenerMensajesEnvio()
                    btnStartConsultation.isEnabled = true
                    btnStartConsultation.text = "Iniciar consulta"
                    Toast.makeText(this@ResultsActivity, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun detenerMensajesEnvio() {
        enviandoMediciones = false
        envioHandler.removeCallbacks(envioRunnable)
    }

    private fun iniciarVideollamada(idUsuarioWeb: Int) {
        Log.d(TAG, "🎬 Iniciando videollamada (Ruta A) con id_usuario_web=$idUsuarioWeb")

        val intent = Intent(this, TelemedicineActivity::class.java).apply {
            putExtra(TelemedicineActivity.EXTRA_ID_USUARIO_WEB, idUsuarioWeb)
        }
        startActivity(intent)
    }

    private fun clearResults() {
        getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE).edit().clear().apply()
        htmlReporte = null
    }

    override fun onBackPressed() {
        if (impresionEnCurso) {
            Toast.makeText(this, "Espera a que termine la impresión", Toast.LENGTH_SHORT).show()
            return
        }

        clearResults()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        )
        startActivity(intent)
        super.onBackPressed()
        finish()
    }

    override fun onResume() {
        super.onResume()
        impresionEnCurso = false
        Log.d(TAG, "▶️ ResultsActivity en primer plano")
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerMensajesEnvio()
        clearResults()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "🔄 onNewIntent — ResultsActivity ya estaba viva")

        val nuevoIdLocal = intent.getLongExtra("id_local", 0L)
        val nuevoIdResultado = intent.getLongExtra("id_resultado", 0L)

        // ✅ Si el intent NO trae datos (por ejemplo, cuando se trae la activity
        // al frente desde el visor de PDF o desde PrintShare), no recargamos nada.
        if (nuevoIdLocal == 0L && nuevoIdResultado == 0L) {
            Log.d(TAG, "⏭️ onNewIntent sin extras: solo se trajo al frente, no se recarga")
            return
        }

        // ✅ Sí trae datos: es una navegación real desde la tabla o desde el flujo
        // de medición. Recargamos todo.
        Log.d(TAG, "🔁 onNewIntent con datos: id_local=$nuevoIdLocal, id_resultado=$nuevoIdResultado")
        idLocal = nuevoIdLocal
        resultadoActual = null
        medicionesCargadas = emptyList()
        paciente = null
        htmlReporte = null
        medicionesEnviadas = false
        envioExitoso = false
        idUsuarioWebCargado = 0

        tvPlaceholder.visibility = View.VISIBLE
        resultsContainer.removeAllViews()
        resultsContainer.addView(tvPlaceholder)
        cargarPacienteYRenderizar()
    }

    // ── Render en pantalla ────────────────────────────────

    private fun renderResultadosSinEcg() {
        val prefs = getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)

        val height = prefs.getFloat("height", 0f)
        val weight = prefs.getFloat("weight", 0f)
        val imc = prefs.getFloat("imc", 0f)
        if (height > 0 && weight > 0) {
            addSection("Altura/Peso")
            addResultRow("Altura: %.1f cm".format(height), "Peso: %.3f kg".format(weight))
            addResultRow("IMC: %.1f".format(imc), "")
            addDivider()
        }

        val fatRate = prefs.getFloat("fat_rate", 0f)
        val waterRate = prefs.getFloat("water_rate", 0f)
        val muscle = prefs.getFloat("muscle", 0f)
        val metabolism = prefs.getInt("metabolism", 0)
        val visceralFat = prefs.getFloat("visceral_fat", 0f)
        val idealWeight = prefs.getFloat("ideal_weight", 0f)
        val protein = prefs.getFloat("protein", 0f)
        val mineral = prefs.getFloat("mineral", 0f)
        val fatKg = prefs.getFloat("fat", 0f)
        val waterKg = prefs.getFloat("water", 0f)
        val notFat = prefs.getFloat("not_fat", 0f)
        val fatType = prefs.getInt("fat_type", 0)

        if (fatRate > 0 || waterRate > 0 || muscle > 0 || metabolism > 0) {
            addSection("Composición Corporal")
            if (fatRate > 0) addResultRow("Tasa de Grasa Corporal: %.1f%%".format(fatRate), "")
            if (waterRate > 0) addResultRow("Tasa de Agua Corporal: %.1f%%".format(waterRate), "")
            if (fatKg > 0) addResultRow("Grasa Corporal: %.1f kg".format(fatKg), "")
            if (waterKg > 0) addResultRow("Agua Corporal: %.1f kg".format(waterKg), "")
            if (muscle > 0) addResultRow("Masa Muscular: %.1f kg".format(muscle), "")
            if (notFat > 0) addResultRow("Masa Libre de Grasa: %.1f kg".format(notFat), "")
            if (protein > 0) addResultRow("Proteína: %.1f kg".format(protein), "")
            if (mineral > 0) addResultRow("Minerales: %.1f kg".format(mineral), "")
            if (metabolism > 0) addResultRow("Metabolismo Basal: %d kcal".format(metabolism), "")
            if (visceralFat > 0) addResultRow("Grasa Visceral: %.1f".format(visceralFat), "")
            if (idealWeight > 0) addResultRow("Peso Ideal: %.1f kg".format(idealWeight), "")
            if (fatType > 0) addResultRow("Tipo de Grasa: $fatType", "")
            addDivider()
        }

        val systolic = prefs.getInt("systolic", 0)
        val diastolic = prefs.getInt("diastolic", 0)
        val pulse = prefs.getInt("pulse", 0)
        if (systolic > 0) {
            addSection("Presión Arterial")
            addResultRow("Sistólica: %d mmHg".format(systolic), "Diastólica: %d mmHg".format(diastolic))
            addResultRow("Pulso: %d bpm".format(pulse), "")
            addDivider()
        }

        val temperature = prefs.getFloat("temperature", 0f)
        val temperatureF = prefs.getFloat("temperature_f", 0f)
        if (temperature > 0) {
            addSection("Temperatura Corporal")
            addResultRow("Temperatura: %.1f °C / %.1f °F".format(temperature, temperatureF), "")
            addDivider()
        }

        val spo2 = prefs.getInt("spo2", 0)
        val pulseRate = prefs.getInt("pulse_rate", 0)
        val pi = prefs.getFloat("pi", 0f)
        if (spo2 > 0) {
            addSection("Oxígeno en Sangre")
            addResultRow("SpO2: %d%%".format(spo2), "Pulso: %d bpm".format(pulseRate))
            addResultRow("PI: %.1f".format(pi), "")
            addDivider()
        }

        val heartRate = prefs.getInt("ecg_heart_rate", 0)
        val ecgImageString = prefs.getString("ecg_image", null)
        if (heartRate > 0 || ecgImageString != null) {
            addSection("ECG")
            addResultRow("Frecuencia Cardíaca: %d bpm".format(heartRate), "")

            if (ecgImageString != null) {
                tvPlaceholder = TextView(this).apply {
                    text = "Cargando imagen de ECG..."
                    textSize = 14f
                    setTextColor(Color.parseColor("#757575"))
                    setPadding(0, 10, 0, 10)
                    tag = "ecg_placeholder"
                }
                resultsContainer.addView(tvPlaceholder)
            }
            addDivider()
        }
    }

    private fun cargarEcgEnBackground() {
        val prefs = getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)
        val ecgImageString = prefs.getString("ecg_image", null) ?: return

        Thread {
            try {
                val imageBytes = Base64.decode(ecgImageString, Base64.NO_WRAP)
                val ecgImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                if (ecgImage != null) {
                    uiHandler.post {
                        val placeholder = resultsContainer.findViewWithTag<TextView>("ecg_placeholder")
                        if (placeholder != null) {
                            val index = resultsContainer.indexOfChild(placeholder)
                            resultsContainer.removeViewAt(index)

                            val imageView = ImageView(this).apply {
                                setImageBitmap(ecgImage)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                setPadding(0, 10, 0, 10)
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                            resultsContainer.addView(imageView, index)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error decodificando ECG: ${e.message}", e)
            }
        }.start()
    }

    // ── Helpers de UI en pantalla ────────────────────────

    private fun addSection(title: String) {
        resultsContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#0F3E82"))
            setPadding(0, 20, 0, 10)
        })
    }

    private fun addResultRow(leftText: String, rightText: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 5, 0, 5)
        }
        row.addView(TextView(this).apply {
            text = leftText
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (rightText.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = rightText
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        resultsContainer.addView(row)
    }

    private fun addDivider() {
        resultsContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 10, 0, 10)
        })
    }

    private fun addEmailWarning() {
        resultsContainer.addView(TextView(this).apply {
            text = "⚠️ El paciente no tiene un correo electrónico registrado.\nLos resultados solo podrán ser consultados en físico o mediante el portal web."
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD_ITALIC)
            setTextColor(Color.parseColor("#D32F2F"))
            setPadding(20, 30, 20, 20)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
    }

    // ==========================================
// IMPRESIÓN DE TICKET (CORREGIDO)
// ==========================================
    private fun imprimirTicketResultados() {
        if (impresionEnCurso) {
            Toast.makeText(this, "Ya hay una impresión en curso", Toast.LENGTH_SHORT).show()
            return
        }
        impresionEnCurso = true

        btnPrintResults.isEnabled = false
        btnPrintTicket.isEnabled = false
        btnEmailResults.isEnabled = false

        // ✅ Usar el resultado actual (el seleccionado o el más reciente)
        val r = resultadoActual
        if (r == null) {
            impresionEnCurso = false
            btnPrintResults.isEnabled = true
            btnPrintTicket.isEnabled = true
            btnEmailResults.isEnabled = true
            Toast.makeText(this, "No hay resultado para imprimir", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "🖨️ Imprimiendo ticket del resultado id=${r.id_resultado}")

        Thread {
            try {
                val success = ThermalPrinterHelper.imprimirTicket(
                    context = this@ResultsActivity,
                    paciente = paciente,
                    resultados = listOf(r),   // ✅ UNA SOLA MEDICIÓN
                    customTitle = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("PdfTitle", null),
                    customSubtitle = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("PdfSubtitle", null)
                )
                uiHandler.post {
                    impresionEnCurso = false
                    btnPrintResults.isEnabled = true
                    btnPrintTicket.isEnabled = true
                    btnEmailResults.isEnabled = true
                    Toast.makeText(
                        this@ResultsActivity,
                        if (success) "🖨️ Ticket impreso correctamente" else "❌ Error al imprimir ticket",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error imprimiendo ticket: ${e.message}", e)
                uiHandler.post {
                    impresionEnCurso = false
                    btnPrintResults.isEnabled = true
                    btnPrintTicket.isEnabled = true
                    btnEmailResults.isEnabled = true
                    Toast.makeText(this@ResultsActivity, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun imprimirResultados() {
        if (impresionEnCurso) {
            Toast.makeText(this, "Ya hay una impresión en curso", Toast.LENGTH_SHORT).show()
            return
        }

        impresionEnCurso = true
        val dialogoGenerando = mostrarDialogoGenerandoInforme("Generando informe, por favor espera…")

        Thread {
            try {
                var pdfBytes = generarPdfBytes()
                if (pdfBytes == null) {
                    uiHandler.post {
                        dialogoGenerando.dismiss()
                        impresionEnCurso = false
                        Toast.makeText(this, "No se pudo generar el PDF (tardó demasiado o falló). Intenta de nuevo.", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }

                // Impresión directa a la impresora de red (IPP), sin diálogo ni visor.
                // Si no hay IP configurada en Ajustes, cae al comportamiento previo (abrir visor de PDF).
                val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                val printerIp = devicePrefs.getString("printer_ip", "")?.trim() ?: ""
                if (printerIp.isNotEmpty()) {
                    val port = devicePrefs.getInt("printer_port", 631)
                    val path = devicePrefs.getString("printer_path", "/ipp/print") ?: "/ipp/print"
                    val jobName = "Resultados ${paciente?.nombre ?: "Paciente"}"
                    val res = IppPrinter.imprimirPdf(
                        host = printerIp,
                        port = port,
                        path = path,
                        jobName = jobName,
                        pdf = pdfBytes
                    )
                    uiHandler.post {
                        dialogoGenerando.dismiss()
                        impresionEnCurso = false
                        Toast.makeText(
                            this,
                            if (res.ok) "🖨️ ${res.mensaje}" else "Error de impresión: ${res.mensaje}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@Thread
                }

                val nombreArchivo = "Resultados_${paciente?.nombre?.replace(" ", "_") ?: "Paciente"}_${System.currentTimeMillis()}.pdf"
                val carpetaDescargas = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    ""
                )
                if (!carpetaDescargas.exists()) carpetaDescargas.mkdirs()

                val archivo = File(carpetaDescargas, nombreArchivo)
                FileOutputStream(archivo).use { it.write(pdfBytes) }

                Log.d(TAG, "📄 PDF guardado: ${archivo.absolutePath}")

                // De aquí en adelante solo se usa el archivo en disco (PdfViewerActivity lo abre
                // por ruta, no por bytes en memoria). Soltar esta referencia — y pedirle al GC
                // que la recoja antes de que el visor empiece a reservar sus propios bitmaps —
                // ayuda en un equipo de 2GB de RAM, donde el pico de memoria justo en esta
                // transición (informe recién construido + primera página del visor) es el
                // momento más sensible de todo el flujo.
                pdfBytes = null
                System.gc()

                uiHandler.post {
                    dialogoGenerando.dismiss()
                    try {
                        // Visor propio (PdfViewerActivity) en vez de delegar a una app externa
                        // vía ACTION_VIEW: en la tablet real (Android 7.1, 2GB de RAM) abrir un
                        // visor externo justo después de generar el PDF podía quedarse en
                        // pantalla negra sin aviso — un crash ahí ocurre en OTRO proceso, fuera
                        // del alcance de cualquier try/catch de esta app.
                        val intent = Intent(this@ResultsActivity, PdfViewerActivity::class.java).apply {
                            putExtra(PdfViewerActivity.EXTRA_PDF_PATH, archivo.absolutePath)
                        }
                        startActivity(intent)
                        impresionEnCurso = false
                    } catch (e: Throwable) {
                        impresionEnCurso = false
                        Toast.makeText(
                            this,
                            "No se pudo abrir el informe. El archivo está en: ${archivo.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Throwable) {
                // Throwable (no solo Exception): un error de memoria u otro problema grave al
                // construir el PDF (p. ej. decodificando un logo muy pesado) no debe tumbar toda
                // la app — se cancela el informe con un mensaje en vez de un crash.
                Log.e(TAG, "❌ Error generando PDF: ${e.message}", e)
                uiHandler.post {
                    if (dialogoGenerando.isShowing) dialogoGenerando.dismiss()
                    impresionEnCurso = false
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ==========================================
    // ENVÍO POR CORREO DE RESULTADOS
    // ==========================================
    private fun mostrarDialogoEnviarCorreo() {
        val p = paciente
        val correoActual = p?.correo ?: ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(10))
        }

        val tvWarning = TextView(this).apply {
            text = "⚠️ El paciente no tiene un correo electrónico registrado, por favor agregue un correo electrónico."
            textSize = 14f
            setTextColor(Color.parseColor("#D32F2F"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            visibility = if (correoActual.isBlank()) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(tvWarning)

        val label = TextView(this).apply {
            text = "Correo electrónico del paciente:"
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(label)

        val etCorreo = EditText(this).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = "ejemplo@correo.com"
            setText(correoActual)
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ContextCompat.getDrawable(this@ResultsActivity, android.R.drawable.editbox_background_normal)
        }
        container.addView(etCorreo)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enviar resultados por correo")
            .setView(container)
            .setPositiveButton("Enviar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        setupDialogKeyboardBehavior(dialog)
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nuevoCorreo = etCorreo.text.toString().trim()

            if (nuevoCorreo.isBlank()) {
                Toast.makeText(this, "Por favor ingrese un correo electrónico", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(nuevoCorreo).matches()) {
                Toast.makeText(this, "Por favor ingrese un correo electrónico válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Guardar en BD si cambió o si el paciente no tenía correo
            Thread {
                if (p != null && (p.correo != nuevoCorreo)) {
                    val pacienteActualizado = p.copy(correo = nuevoCorreo)
                    runBlocking {
                        AppDatabase.getInstance(this@ResultsActivity)
                            .pacienteDao()
                            .actualizarPaciente(pacienteActualizado)
                    }
                    this@ResultsActivity.paciente = pacienteActualizado
                    Log.d(TAG, "✅ Correo guardado en BD para paciente local ${p.id_local}: $nuevoCorreo")
                }

                uiHandler.post {
                    dialog.dismiss()
                    enviarResultadosPorCorreoA(nuevoCorreo)
                }
            }.start()
        }
    }

    /**
     * Generar el PDF (varias páginas, logos, gráficas) puede tardar varios segundos reales en
     * este hardware — sin este diálogo la UI se ve "congelada" sin ninguna explicación mientras
     * dura. No se puede cancelar (el trabajo ya en curso en el hilo principal no se puede
     * interrumpir a medias), así que solo informa; se cierra cuando el llamador termina.
     */
    private fun mostrarDialogoGenerandoInforme(mensaje: String): AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        container.addView(ProgressBar(this).apply {
            isIndeterminate = true
        })
        container.addView(TextView(this).apply {
            text = mensaje
            textSize = 15f
            setPadding(dp(16), 0, 0, 0)
        })
        return AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .show()
    }

    private fun enviarResultadosPorCorreoA(destinatario: String) {
        if (!EmailSender.estaConfigurado(this)) {
            AlertDialog.Builder(this)
                .setTitle("Correo no configurado")
                .setMessage("Para enviar por correo, primero configura el servidor de envío (SMTP) en Ajustes → Configuración de informes.")
                .setPositiveButton("Configurar ahora") { _, _ ->
                    startActivity(Intent(this, SmtpSettingsActivity::class.java))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }

        btnEmailResults.isEnabled = false
        val textoOriginal = btnEmailResults.text
        btnEmailResults.text = "Enviando correo..."
        val dialogoGenerando = mostrarDialogoGenerandoInforme("Generando informe, por favor espera…")

        Thread {
            try {
                val pdfBytes = generarPdfBytes()
                if (pdfBytes == null) {
                    uiHandler.post {
                        dialogoGenerando.dismiss()
                        btnEmailResults.isEnabled = true
                        btnEmailResults.text = textoOriginal
                        Toast.makeText(this, "No se pudo generar el PDF (tardó demasiado o falló). Intenta de nuevo.", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                uiHandler.post {
                    dialogoGenerando.dismiss()
                    Toast.makeText(this, "Enviando resultados a $destinatario...", Toast.LENGTH_SHORT).show()
                }

                val nombrePaciente = paciente?.nombre?.replace(" ", "_") ?: "Paciente"
                val archivo = File(getExternalFilesDir(null), "Resultados_${nombrePaciente}_${System.currentTimeMillis()}.pdf")
                FileOutputStream(archivo).use { it.write(pdfBytes) }

                val config = EmailSender.cargarConfig(this)
                val nombreMostrar = listOfNotNull(
                    paciente?.nombre, paciente?.apellido_paterno, paciente?.apellido_materno
                ).joinToString(" ").trim().ifBlank { "Paciente" }
                val cuerpo = "<p>Estimado/a $nombreMostrar,</p>" +
                        "<p>Adjunto encontrará el informe de su medición realizada en el módulo de salud.</p>" +
                        "<p>Saludos.</p>"

                val resultado = EmailSender.enviar(
                    config = config,
                    destino = destinatario,
                    asunto = "Informe de resultados - $nombreMostrar",
                    cuerpoHtml = cuerpo,
                    adjunto = archivo,
                    nombreAdjunto = "Informe_$nombrePaciente.pdf"
                )

                uiHandler.post {
                    btnEmailResults.isEnabled = true
                    btnEmailResults.text = textoOriginal
                    if (resultado.isSuccess) {
                        Toast.makeText(this, "✅ Resultados enviados a $destinatario", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "❌ No se pudo enviar: ${resultado.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                // Throwable: mismo criterio que en imprimirResultados() — un error grave al
                // construir el PDF no debe tumbar la app completa.
                Log.e(TAG, "❌ Error enviando correo: ${e.message}", e)
                uiHandler.post {
                    if (dialogoGenerando.isShowing) dialogoGenerando.dismiss()
                    btnEmailResults.isEnabled = true
                    btnEmailResults.text = textoOriginal
                    Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ==========================================
// GENERAR PDF EN MEMORIA
// ==========================================

    private fun generarPdfBytes(): ByteArray? {
        var pdfBytes: ByteArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        // Red/descarga de logos y datos de sucursal ANTES de entrar al hilo principal
        // (este método siempre se llama desde un hilo en segundo plano).
        //
        // El pie de página debe mostrar la dirección de la SUCURSAL (no la del
        // cliente pagante) + el nombre del proyecto. En este sistema cada
        // "cliente" representa en realidad un proyecto/programa (ej. "Tarjeta
        // Rosa"), así que los logos siguen pidiéndose por idCliente, pero usando
        // el idCliente que trae la sucursal activa (más confiable que el guardado
        // por separado en ajustes, que podría haber quedado desincronizado).
        val idSucursal = TelemedicineSettingsActivity.getSucursal(this)
        val datosSucursal = runCatching { SucursalDatosCache.obtener(this, idSucursal) }
            .onFailure { Log.w(TAG, "No se pudieron resolver datos de sucursal: ${it.message}") }
            .getOrNull()

        val idClienteEfectivo = datosSucursal?.idCliente?.takeIf { it > 0 }?.toString()
            ?: TelemedicineSettingsActivity.getCliente(this)

        val clienteLogoBase = runCatching { ClienteLogoCache.obtener(this, idClienteEfectivo) }
            .onFailure { Log.w(TAG, "No se pudieron resolver logos de cliente: ${it.message}") }
            .getOrNull()

        // Dirección combinada de la sucursal (domicilio + colonia + municipio),
        // reemplazando la "dirección del cliente pagante" en el pie de página.
        // El nombre del proyecto (nombreProyecto) reemplaza la razón social ahí mismo.
        val direccionSucursal = datosSucursal?.let { s ->
            listOfNotNull(
                s.domicilio?.trim()?.ifBlank { null },
                s.colonia?.trim()?.ifBlank { null },
                s.municipio?.trim()?.ifBlank { null }
            ).joinToString(", ").ifBlank { null }
        }

        val clienteLogo = when {
            datosSucursal != null -> (clienteLogoBase ?: ClienteLogoCache.Logos(
                idCliente = datosSucursal.idCliente,
                razonSocial = null, direccion = null, codigoPostal = null, estado = null, rfc = null,
                logoIzquierdoPath = null, logoDerechoPath = null, firmaPath = null,
                nombreFirma = null, puesto = null
            )).copy(
                razonSocial = datosSucursal.nombreProyecto?.trim()?.ifBlank { null },
                direccion = direccionSucursal,
                codigoPostal = datosSucursal.codigoPostal,
                estado = datosSucursal.estado
            )
            else -> clienteLogoBase
        }

        // Decodificar los logos (incluye SVG vía Coil) AQUÍ, en el hilo de fondo, no dentro
        // del uiHandler.post de abajo: cada página del informe reconstruye su encabezado y
        // antes volvía a decodificar ambos logos desde cero (hasta 6 veces por PDF) en el
        // hilo principal, que es la causa real de que la generación se sintiera "congelada"
        // incluso con el diálogo de progreso ya agregado (el hilo principal sigue bloqueado
        // igual mientras decodifica). Decodificando una sola vez aquí, el hilo principal solo
        // hace medir/layout/draw de Views.
        val logoPathIzquierdo = clienteLogo?.logoIzquierdoPath
            ?: getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("LogoPath", null)
        val logoIzquierdoBitmap = InformeBuilder.cargarLogoBitmap(this, logoPathIzquierdo)
        val logoDerechoBitmap = clienteLogo?.logoDerechoPath
            ?.takeIf { it.isNotBlank() }
            ?.let { InformeBuilder.cargarLogoBitmap(this, it) }

        // Misma razón que los logos: la foto del paciente se mostraba en una miniatura de
        // 75x85dp pero se decodificaba a su resolución ORIGINAL de cámara, hasta 3 veces por
        // PDF (una por página) — un gasto de memoria/CPU importante en un equipo de 2GB de RAM.
        // Se decodifica una sola vez aquí, ya reducida al tamaño real que se necesita.
        val fotoPacienteBitmap = paciente?.foto
            ?.takeIf { it.isNotBlank() }
            ?.let { InformeBuilder.cargarFotoPaciente(it, dp(75), dp(85)) }

        uiHandler.post {
            try {
                val colorTema = runCatching {
                    Color.parseColor(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
                }.getOrDefault(Color.parseColor("#0F3E82"))

                val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                val showAlturaPeso = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
                val showComposicion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
                val showPresion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
                val showOxigeno = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
                val showTemperatura = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
                val showEcg = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

                val meds = medicionesCargadas
                val p = paciente

                val pageWidth = 1240
                val pageHeight = 1754
                val marginLeft = 60f
                val marginTop = 60f
                val targetWidth = pageWidth - (marginLeft * 2).toInt() // 1120px

                // Escala uniforme aplicada al contenido de las 3 páginas.
                // Reduce este valor si el contenido se corta; súbelo si sobra espacio.
                val escalaMaxima = 0.92f

                // Alto reservado abajo para el pie de página (evita que el contenido lo pise)
                val altoPie = 105f

                val pdfDocument = android.graphics.pdf.PdfDocument()
                var pageNumber = 1

                // Paint del pie, reutilizado
                val piePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#607789")
                    textSize = 18f
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
                val pieTituloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#36566C")
                    textSize = 17f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.LEFT
                }

                // Pie de cliente (razón social + dirección), anclado al fondo de cada hoja
                // igual que "Página X de Y" (no depende de cuánto contenido haya arriba).
                val clienteRazonSocial = clienteLogo?.razonSocial?.trim()?.ifBlank { null }
                val clienteDireccion = InformeBuilder.formatearDireccionCliente(clienteLogo)
                val clienteRazonPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#6B7280")
                    textSize = 18f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val clienteDireccionPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#9CA3AF")
                    textSize = 16f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val clienteLineaPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E5E7EB")
                    strokeWidth = 1.5f
                }

                fun agregarPaginaAlPdf(numPagina: Int, totalPaginas: Int, crearContenido: (LinearLayout) -> Unit) {
                    val pageContainer = LinearLayout(this@ResultsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.WHITE)
                        clipChildren = false
                        clipToPadding = false
                    }
                    crearContenido(pageContainer)

                    val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

                    pageContainer.measure(widthSpec, heightSpec)
                    pageContainer.layout(0, 0, targetWidth, pageContainer.measuredHeight)
                    val altoDisponible = pageHeight - marginTop - altoPie
                    val escalaPorAlto = if (pageContainer.measuredHeight > 0)
                        altoDisponible / pageContainer.measuredHeight.toFloat() else escalaMaxima
                    val escalaContenido = minOf(escalaMaxima, escalaPorAlto).coerceAtLeast(0.68f)

                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo
                        .Builder(pageWidth, pageHeight, pageNumber++)
                        .create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.parseColor("#F7FAFC"))

                    // Dibujo del contenido con escala uniforme
                    canvas.save()
                    canvas.translate(marginLeft, marginTop)
                    canvas.scale(escalaContenido, escalaContenido, 0f, 0f)
                    pageContainer.draw(canvas)
                    canvas.restore()

                    // Pie de página anclado abajo-derecha de la hoja A4
                    val textoPie = "Página $numPagina de $totalPaginas"
                    val xPie = pageWidth - marginLeft
                    val yPie = pageHeight - 34f
                    canvas.drawText(textoPie, xPie, yPie, piePaint)
                    canvas.drawText("INFORME DE SALUD INTEGRAL", marginLeft, yPie, pieTituloPaint)
                    canvas.drawLine(marginLeft, yPie - 34f, pageWidth - marginLeft, yPie - 34f, clienteLineaPaint)

                    // Pie del cliente (razón social + dirección), centrado y anclado
                    // justo arriba del número de página, en TODAS las hojas.
                    if (clienteRazonSocial != null || clienteDireccion != null) {
                        val xCentro = pageWidth / 2f
                        var y = yPie - 48f
                        if (clienteDireccion != null) {
                            canvas.drawText(clienteDireccion, xCentro, y, clienteDireccionPaint)
                            y -= 26f
                        }
                        if (clienteRazonSocial != null) {
                            canvas.drawText(clienteRazonSocial, xCentro, y, clienteRazonPaint)
                            y -= 16f
                        }
                        canvas.drawLine(xCentro - 160f, y, xCentro + 160f, y, clienteLineaPaint)
                    }

                    pdfDocument.finishPage(page)
                }

                if (meds.isNotEmpty()) {
                    // Calcular total de páginas según lo activo en DevicePrefs
                    var totalPaginas = 0
                    if (showAlturaPeso || showComposicion) totalPaginas++
                    if (showPresion || showOxigeno || showTemperatura) totalPaginas++
                    if (showEcg) totalPaginas++
                    if (totalPaginas == 0) totalPaginas = 1

                    var paginaActual = 0

                    // Página 1: Altura y peso + Composición corporal
                    if (showAlturaPeso || showComposicion) {
                        paginaActual++
                        val numPag = paginaActual
                        agregarPaginaAlPdf(numPag, totalPaginas) { container ->
                            val builder = InformeBuilder(this@ResultsActivity, container, colorTema, clienteLogo, logoIzquierdoBitmap, logoDerechoBitmap, fotoPacienteBitmap)
                            builder.construirPagina1(p, idLocal, meds)
                        }
                    }

                    // Página 2: Presión arterial + Oxígeno + Temperatura corporal
                    if (showPresion || showOxigeno || showTemperatura) {
                        paginaActual++
                        val numPag = paginaActual
                        agregarPaginaAlPdf(numPag, totalPaginas) { container ->
                            val builder = InformeBuilder(this@ResultsActivity, container, colorTema, clienteLogo, logoIzquierdoBitmap, logoDerechoBitmap, fotoPacienteBitmap)
                            builder.construirPagina2(p, idLocal, meds)
                        }
                    }

                    // Página 3: Electrocardiograma
                    if (showEcg) {
                        paginaActual++
                        val numPag = paginaActual
                        agregarPaginaAlPdf(numPag, totalPaginas) { container ->
                            val builder = InformeBuilder(this@ResultsActivity, container, colorTema, clienteLogo, logoIzquierdoBitmap, logoDerechoBitmap, fotoPacienteBitmap)
                            builder.construirPagina3(p, idLocal, meds)
                        }
                    }
                } else {
                    agregarPaginaAlPdf(1, 1) { container ->
                        val builder = InformeBuilder(this@ResultsActivity, container, colorTema, clienteLogo, logoIzquierdoBitmap, logoDerechoBitmap, fotoPacienteBitmap)
                        builder.mensajeVacio("No hay mediciones registradas para este paciente.")
                    }
                }

                val output = java.io.ByteArrayOutputStream()
                pdfDocument.writeTo(output)
                pdfDocument.close()

                pdfBytes = output.toByteArray()
                Log.d(TAG, "✅ PDF generado correctamente (${pdfBytes?.size ?: 0} bytes, ${pageNumber - 1} páginas)")
            } catch (e: Throwable) {
                // Throwable: aquí es donde realmente se decodifican logos/SVGs e imágenes de
                // cada página — un OutOfMemoryError con una imagen muy pesada no debe tumbar
                // toda la app, solo cancelar este informe (pdfBytes queda null).
                Log.e(TAG, "❌ Error generando PDF por páginas: ${e.message}", e)
            } finally {
                latch.countDown()
            }
        }

        // El dibujo real (medir/layout/draw de cada página, con logos y gráficas) corre en el
        // hilo principal porque InformeBuilder lo requiere, y en este hardware puede tardar más
        // de lo que tomaría en un equipo normal. El valor de retorno de await() SÍ se revisa
        // ahora: antes se ignoraba, así que a los 10s se asumía "falló" y se le avisaba eso al
        // usuario, pero el Runnable ya encolado en el hilo principal seguía corriendo de todas
        // formas (no se puede cancelar a medias) — la app quedaba congelada un rato más sin
        // ningún mensaje nuevo. Con un margen más realista y comprobando el resultado, evitamos
        // avisar una "falla" falsa mientras el trabajo sigue en curso.
        val terminoATiempo = try {
            latch.await(45, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Hilo interrumpido esperando el PDF", e)
            false
        }
        if (!terminoATiempo) {
            Log.e(TAG, "⏰ Generar el PDF tardó más de 45s; el hilo principal puede seguir ocupado terminándolo")
        }

        return pdfBytes
    }

    // ==========================================
    // CONSTRUIR HTML DEL REPORTE (UNA SOLA VEZ)
    // ==========================================
    private fun buildResultsHtml(): String {
        val prefs = getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE)
        val sb = StringBuilder()

        val nombrePaciente = listOfNotNull(
            paciente?.nombre,
            paciente?.apellido_paterno,
            paciente?.apellido_materno
        ).joinToString(" ").trim().ifBlank { "Paciente" }

        val fecha = java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm", java.util.Locale.getDefault()
        ).format(java.util.Date())

        sb.append("""
        <html><head><meta charset="utf-8">
        <style>
            body { font-family: Arial, sans-serif; padding: 24px; color: #333; }
            h1 { text-align: center; color: #0F3E82; font-size: 24px; margin-bottom: 4px; }
            .subtitle { text-align: center; color: #666; font-size: 13px; margin-bottom: 28px; }
            h2 { color: #0F3E82; border-bottom: 2px solid #0F3E82;
                 padding-bottom: 4px; margin-top: 26px; font-size: 17px; }
            table { width: 100%; border-collapse: collapse; margin-top: 8px; }
            td { padding: 5px 8px; font-size: 14px; }
            .label { color: #555; }
            .value { color: #111; font-weight: bold; }
        </style></head><body>
    """.trimIndent())

        sb.append("<h1>Reporte de Resultados</h1>")
        sb.append("<div class='subtitle'>Paciente: $nombrePaciente &nbsp;|&nbsp; Fecha: $fecha</div>")

        val height = prefs.getFloat("height", 0f)
        val weight = prefs.getFloat("weight", 0f)
        val imc = prefs.getFloat("imc", 0f)
        if (height > 0 && weight > 0) {
            sb.append("<h2>Altura / Peso</h2><table>")
            sb.append("<tr><td class='label'>Altura</td><td class='value'>%.1f cm</td></tr>".format(height))
            sb.append("<tr><td class='label'>Peso</td><td class='value'>%.3f kg</td></tr>".format(weight))
            sb.append("<tr><td class='label'>IMC</td><td class='value'>%.1f</td></tr>".format(imc))
            sb.append("</table>")
        }

        val fatRate = prefs.getFloat("fat_rate", 0f)
        val waterRate = prefs.getFloat("water_rate", 0f)
        val muscle = prefs.getFloat("muscle", 0f)
        val metabolism = prefs.getInt("metabolism", 0)
        val visceralFat = prefs.getFloat("visceral_fat", 0f)
        val idealWeight = prefs.getFloat("ideal_weight", 0f)
        val protein = prefs.getFloat("protein", 0f)
        val mineral = prefs.getFloat("mineral", 0f)
        val fatKg = prefs.getFloat("fat", 0f)
        val waterKg = prefs.getFloat("water", 0f)
        val notFat = prefs.getFloat("not_fat", 0f)
        val fatType = prefs.getInt("fat_type", 0)

        if (fatRate > 0 || waterRate > 0 || muscle > 0 || metabolism > 0) {
            sb.append("<h2>Composición Corporal</h2><table>")
            if (fatRate > 0)     sb.append("<tr><td class='label'>Tasa de Grasa Corporal</td><td class='value'>%.1f%%</td></tr>".format(fatRate))
            if (waterRate > 0)   sb.append("<tr><td class='label'>Tasa de Agua Corporal</td><td class='value'>%.1f%%</td></tr>".format(waterRate))
            if (fatKg > 0)       sb.append("<tr><td class='label'>Grasa Corporal</td><td class='value'>%.1f kg</td></tr>".format(fatKg))
            if (waterKg > 0)     sb.append("<tr><td class='label'>Agua Corporal</td><td class='value'>%.1f kg</td></tr>".format(waterKg))
            if (muscle > 0)      sb.append("<tr><td class='label'>Masa Muscular</td><td class='value'>%.1f kg</td></tr>".format(muscle))
            if (notFat > 0)      sb.append("<tr><td class='label'>Masa Libre de Grasa</td><td class='value'>%.1f kg</td></tr>".format(notFat))
            if (protein > 0)     sb.append("<tr><td class='label'>Proteína</td><td class='value'>%.1f kg</td></tr>".format(protein))
            if (mineral > 0)     sb.append("<tr><td class='label'>Minerales</td><td class='value'>%.1f kg</td></tr>".format(mineral))
            if (metabolism > 0)  sb.append("<tr><td class='label'>Metabolismo Basal</td><td class='value'>%d kcal</td></tr>".format(metabolism))
            if (visceralFat > 0) sb.append("<tr><td class='label'>Grasa Visceral</td><td class='value'>%.1f</td></tr>".format(visceralFat))
            if (idealWeight > 0) sb.append("<tr><td class='label'>Peso Ideal</td><td class='value'>%.1f kg</td></tr>".format(idealWeight))
            if (fatType > 0)     sb.append("<tr><td class='label'>Tipo de Grasa</td><td class='value'>$fatType</td></tr>")
            sb.append("</table>")
        }

        val systolic = prefs.getInt("systolic", 0)
        val diastolic = prefs.getInt("diastolic", 0)
        val pulse = prefs.getInt("pulse", 0)
        if (systolic > 0) {
            sb.append("<h2>Presión Arterial</h2><table>")
            sb.append("<tr><td class='label'>Sistólica</td><td class='value'>%d mmHg</td></tr>".format(systolic))
            sb.append("<tr><td class='label'>Diastólica</td><td class='value'>%d mmHg</td></tr>".format(diastolic))
            sb.append("<tr><td class='label'>Pulso</td><td class='value'>%d bpm</td></tr>".format(pulse))
            sb.append("</table>")
        }

        val temperature = prefs.getFloat("temperature", 0f)
        val temperatureF = prefs.getFloat("temperature_f", 0f)
        if (temperature > 0) {
            sb.append("<h2>Temperatura Corporal</h2><table>")
            sb.append("<tr><td class='label'>Temperatura</td><td class='value'>%.1f °C / %.1f °F</td></tr>".format(temperature, temperatureF))
            sb.append("</table>")
        }

        val spo2 = prefs.getInt("spo2", 0)
        val pulseRate = prefs.getInt("pulse_rate", 0)
        val pi = prefs.getFloat("pi", 0f)
        if (spo2 > 0) {
            sb.append("<h2>Oxígeno en Sangre</h2><table>")
            sb.append("<tr><td class='label'>SpO2</td><td class='value'>%d%%</td></tr>".format(spo2))
            sb.append("<tr><td class='label'>Pulso</td><td class='value'>%d bpm</td></tr>".format(pulseRate))
            sb.append("<tr><td class='label'>PI</td><td class='value'>%.1f</td></tr>".format(pi))
            sb.append("</table>")
        }

        val heartRate = prefs.getInt("ecg_heart_rate", 0)
        val ecgImageString = prefs.getString("ecg_image", null)
        if (heartRate > 0 || ecgImageString != null) {
            sb.append("<h2>ECG</h2><table>")
            if (heartRate > 0) {
                sb.append("<tr><td class='label'>Frecuencia Cardíaca</td><td class='value'>%d bpm</td></tr>".format(heartRate))
            }
            sb.append("</table>")
            if (ecgImageString != null) {
                sb.append("<img src='data:image/png;base64,$ecgImageString' style='width:100%; margin-top:8px;'/>")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }



    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
