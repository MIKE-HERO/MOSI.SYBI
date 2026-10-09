package com.sybi.mosi

import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
    private lateinit var btnStartConsultation: Button
    private lateinit var btnPrintResults: Button
    private lateinit var btnEmailResults: Button
    private lateinit var btnVerInforme: Button
    private lateinit var btnExitResults: View

    private var idLocal: Long = 0L
    private var paciente: Paciente? = null
    private var medicionesCargadas: List<com.sybi.mosi.database.Resultado> = emptyList()
    private var envioExitoso = false

    private var idUsuarioWebCargado: Int = 0

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

    // ✅ Overlay flotante para regresar desde PrintShare
    private var overlayView: View? = null
    private var monitorVisorActivo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_results)

        topBar = findViewById(R.id.topResultsBar)
        bottomBar = findViewById(R.id.bottomResultsBar)
        resultsContainer = findViewById(R.id.resultsContainer)
        btnStartConsultation = findViewById(R.id.btnStartConsultation)
        btnPrintResults = findViewById(R.id.btnPrintResults)
        btnEmailResults = findViewById(R.id.btnEmailResults)
        btnVerInforme = findViewById(R.id.btnVerInforme)
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

        btnPrintResults.visibility = if (allowPrint) View.VISIBLE else View.GONE
        btnEmailResults.visibility = if (allowEmail) View.VISIBLE else View.GONE

        btnPrintResults.setOnClickListener {
            if (htmlReporte == null) {
                Toast.makeText(this, "Esperando datos del paciente...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            imprimirResultados()
        }

        btnEmailResults.setOnClickListener {
            if (htmlReporte == null) {
                Toast.makeText(this, "Esperando datos del paciente...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enviarResultadosPorCorreo()
        }

        // El informe ya es el contenido de esta pantalla, así que el botón que abría el
        // informe aparte ya no hace falta.
        btnVerInforme.visibility = View.GONE

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
        Thread {
            var mediciones: List<com.sybi.mosi.database.Resultado> = emptyList()
            runBlocking {
                val db = AppDatabase.getInstance(this@ResultsActivity)
                paciente = if (idLocal != 0L) {
                    db.pacienteDao().obtenerPacientePorIdLocal(idLocal)
                } else null

                // Mediciones para el informe (actual + hasta 20 anteriores). Para invitados
                // (id_local=0, historial compartido) solo la actual.
                mediciones = if (idLocal != 0L) {
                    db.resultadoDao().obtenerResultadosPorIdLocal(idLocal).take(21)
                } else {
                    db.resultadoDao().obtenerResultadosPorIdLocal(0L).take(1)
                }
                medicionesCargadas = mediciones

                val p = paciente

                uiHandler.post {
                    tvPlaceholder.visibility = View.GONE
                    // El contenido de la pantalla final es el informe visual (mismo que
                    // InformeActivity), no la lista de texto.
                    resultsContainer.removeAllViews()
                    val colorTema = runCatching {
                        Color.parseColor(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
                    }.getOrDefault(Color.parseColor("#0F3E82"))
                    val builder = InformeBuilder(this@ResultsActivity, resultsContainer, colorTema)
                    if (mediciones.isEmpty()) {
                        builder.mensajeVacio("No hay mediciones registradas para este paciente.")
                    } else {
                        builder.construir(p, idLocal, mediciones)
                    }

                    if (p?.correo.isNullOrBlank()) {
                        addEmailWarning()
                    }

                    // ✅ Generar el HTML UNA SOLA VEZ, ya con los datos del paciente cargados
                    htmlReporte = buildResultsHtml()
                    Log.d(TAG, "📝 HTML del reporte generado (${htmlReporte?.length ?: 0} chars)")
                }

                if (p != null && p.id_usuario_web != null) {
                    idUsuarioWebCargado = p.id_usuario_web!!
                    // ✅ Evitar reenvíos si la activity se recrea
                    if (!medicionesEnviadas) {
                        medicionesEnviadas = true
                        enviarMedicionesAlDoctor(p)
                    } else {
                        Log.d(TAG, "⏭️ Mediciones ya enviadas, no se repite")
                    }
                } else {
                    Log.d(TAG, "⏭️ Sin id_usuario_web, no se envía")
                }
                // El ECG ya lo muestra el informe (desde ruta_ecg), no hace falta cargarlo aparte.
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
        finish()
    }

    override fun onResume() {
        super.onResume()
        quitarBotonFlotanteRegreso()
        impresionEnCurso = false
        monitorVisorActivo = false
        Log.d(TAG, "▶️ ResultsActivity en primer plano, overlay limpiado")
    }

    override fun onDestroy() {
        super.onDestroy()
        quitarBotonFlotanteRegreso()
        detenerMensajesEnvio()
        clearResults()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "🔄 onNewIntent — ResultsActivity ya estaba viva")
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
    // IMPRESIÓN DE RESULTADOS (abre PrintShare)
    // ==========================================
    private fun imprimirResultados() {
        if (impresionEnCurso) {
            Toast.makeText(this, "Ya hay una impresión en curso", Toast.LENGTH_SHORT).show()
            return
        }

        // ✅ NO tocamos isEnabled ni el texto del botón. Solo el flag interno.
        impresionEnCurso = true

        Thread {
            try {
                val pdfBytes = generarPdfBytes()
                if (pdfBytes == null) {
                    uiHandler.post {
                        impresionEnCurso = false
                        Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
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

                uiHandler.post {
                    try {
                        val uri = FileProvider.getUriForFile(
                            this@ResultsActivity,
                            "$packageName.fileprovider",
                            archivo
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        mostrarBotonFlotanteRegreso()
                    } catch (e: ActivityNotFoundException) {
                        impresionEnCurso = false
                        Toast.makeText(
                            this,
                            "No hay visor de PDF instalado. El archivo está en: ${archivo.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error generando PDF: ${e.message}", e)
                uiHandler.post {
                    impresionEnCurso = false
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ==========================================
    // ENVÍO POR CORREO (temporal: genera PDF y guarda copia)
    // ==========================================
    private fun enviarResultadosPorCorreo() {
        val destinatario = paciente?.correo
        if (destinatario.isNullOrBlank()) {
            Toast.makeText(this, "El paciente no tiene correo registrado", Toast.LENGTH_LONG).show()
            return
        }

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
        Toast.makeText(this, "Enviando resultados a $destinatario...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val pdfBytes = generarPdfBytes()
                if (pdfBytes == null) {
                    uiHandler.post {
                        btnEmailResults.isEnabled = true
                        btnEmailResults.text = textoOriginal
                        Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
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
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error enviando correo: ${e.message}", e)
                uiHandler.post {
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

        uiHandler.post {
            try {
                val pdfContainer = LinearLayout(this@ResultsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                    setPadding(30, 30, 30, 30)
                }

                val colorTema = runCatching {
                    Color.parseColor(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
                }.getOrDefault(Color.parseColor("#0F3E82"))

                val builder = InformeBuilder(this@ResultsActivity, pdfContainer, colorTema)
                val meds = medicionesCargadas
                val p = paciente

                if (meds.isEmpty()) {
                    builder.mensajeVacio("No hay mediciones registradas para este paciente.")
                } else {
                    builder.construir(p, idLocal, meds)
                }

                // Ancho y alto A4 estándar (1240 x 1754 px a 150 DPI) con márgenes de impresión seguros
                val pageWidth = 1240
                val pageHeight = 1754
                val marginLeft = 60f
                val marginTop = 60f
                val targetWidth = (pageWidth - (marginLeft * 2).toInt()) // 1120px ancho de contenido
                val contentHeightPerPage = (pageHeight - (marginTop * 2).toInt()) // 1634px alto por página

                val widthSpec = View.MeasureSpec.makeMeasureSpec(targetWidth, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

                pdfContainer.measure(widthSpec, heightSpec)
                val totalHeight = maxOf(pdfContainer.measuredHeight, 1)
                pdfContainer.layout(0, 0, targetWidth, totalHeight)

                val pdfDocument = android.graphics.pdf.PdfDocument()
                val totalPages = maxOf(1, (totalHeight + contentHeightPerPage - 1) / contentHeightPerPage)

                for (i in 0 until totalPages) {
                    val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    val canvas = page.canvas

                    val yOffset = i * contentHeightPerPage

                    canvas.save()
                    // Clip y traslación con márgenes de impresión (evita cortes en PrintShare y fotocopiadoras)
                    canvas.clipRect(marginLeft, marginTop, marginLeft + targetWidth, marginTop + contentHeightPerPage)
                    canvas.translate(marginLeft, marginTop - yOffset.toFloat())
                    pdfContainer.draw(canvas)
                    canvas.restore()

                    pdfDocument.finishPage(page)
                }

                val output = java.io.ByteArrayOutputStream()
                pdfDocument.writeTo(output)
                pdfDocument.close()

                pdfBytes = output.toByteArray()
                Log.d(TAG, "✅ PDF generado correctamente con el diseño visual del informe (${pdfBytes?.size ?: 0} bytes, $totalPages páginas)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error generando PDF desde InformeBuilder: ${e.message}", e)
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "⏰ Timeout al generar PDF", e)
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

    // ==========================================
    // OVERLAY FLOTANTE PARA REGRESAR DESDE PRINTSHARE
    // ==========================================
    private fun mostrarBotonFlotanteRegreso() {
        // Si ya hay uno, no crear otro
        if (overlayView != null) return

        // Verificar permiso
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.w(TAG, "⚠️ Sin permiso SYSTEM_ALERT_WINDOW, usando timer de respaldo")
                iniciarTimerRegresoFallback()
                return
            }
        }

        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

            // Contenedor con el botón
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#0F3E82"))
                    cornerRadius = dp(28).toFloat()
                    setStroke(dp(2), Color.WHITE)
                }
                elevation = dp(8).toFloat()
            }

            val btn = Button(this).apply {
                text = "← Regresar a Resultados"
                setTextColor(Color.WHITE)
                textSize = 16f
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F3E82"))
                setOnClickListener {
                    quitarBotonFlotanteRegreso()
                    traerResultsActivityAlFrente()
                }
            }

            container.addView(btn)

            val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                android.view.WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                x = dp(20)
                y = dp(20)
            }

            windowManager.addView(container, params)
            overlayView = container
            Log.d(TAG, "✅ Botón flotante de regreso mostrado")

            // Timer de respaldo por si el usuario no toca el botón
            iniciarTimerRegresoFallback()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error mostrando overlay: ${e.message}", e)
            iniciarTimerRegresoFallback()
        }
    }

    private fun quitarBotonFlotanteRegreso() {
        try {
            overlayView?.let {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error quitando overlay: ${e.message}")
        }
        overlayView = null
    }

    private fun iniciarTimerRegresoFallback() {
        uiHandler.postDelayed({
            Log.d(TAG, "⏰ Timer de respaldo: regresando a ResultsActivity")
            quitarBotonFlotanteRegreso()
            traerResultsActivityAlFrente()
        }, 120_000L)  // 2 minutos
    }

    private fun traerResultsActivityAlFrente() {
        monitorVisorActivo = false

        val intent = Intent(this, ResultsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // ✅ SIN FLAG_ACTIVITY_NEW_TASK
            // ✅ SIN putExtra: no queremos recrear la activity, solo traerla al frente
        }
        startActivity(intent)
        Log.d(TAG, "🔙 ResultsActivity traída al frente")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}