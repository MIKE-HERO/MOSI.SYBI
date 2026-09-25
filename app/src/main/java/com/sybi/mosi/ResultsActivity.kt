package com.sybi.mosi

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
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.repository.MedicionesSender
import kotlinx.coroutines.runBlocking

class ResultsActivity : BaseActivity() {

    companion object {
        private const val TAG = "ResultsActivity"
    }

    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var resultsContainer: LinearLayout
    private lateinit var btnStartConsultation: Button
    private lateinit var btnPrintResults: Button
    private lateinit var btnExitResults: View

    private var idLocal: Long = 0L
    private var paciente: Paciente? = null
    private var envioExitoso = false

    // ✅ Guardar el id_usuario_web en cuanto se cargue el paciente
    //    para evitar race conditions al tocar el botón.
    private var idUsuarioWebCargado: Int = 0

    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var tvPlaceholder: TextView

    // ✅ Control de mensajes periódicos durante el envío
    private var enviandoMediciones = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_results)

        topBar = findViewById(R.id.topResultsBar)
        bottomBar = findViewById(R.id.bottomResultsBar)
        resultsContainer = findViewById(R.id.resultsContainer)
        btnStartConsultation = findViewById(R.id.btnStartConsultation)
        btnPrintResults = findViewById(R.id.btnPrintResults)
        btnExitResults = findViewById(R.id.btnExitResults)

        idLocal = intent.getLongExtra("id_local", 0L)
        Log.d(TAG, "📥 id_local recibido: $idLocal")

        // ✅ Placeholder inicial — se ve al instante
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

                // ✅ Ruta A: lanzar TelemedicineActivity,
                //    que carga la pantalla de médicos disponibles.
                iniciarVideollamada(idUsuarioWebCargado)
            }
        } else {
            btnStartConsultation.visibility = View.GONE
        }

        btnPrintResults.setOnClickListener {
            Toast.makeText(this, "Funcionalidad de impresión próximamente", Toast.LENGTH_SHORT).show()
        }

        btnExitResults.setOnClickListener {
            clearResults()
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            startActivity(intent)
            finish()
        }

        cargarPacienteYRenderizar()
    }

    private fun cargarPacienteYRenderizar() {
        Thread {
            runBlocking {
                val db = AppDatabase.getInstance(this@ResultsActivity)
                paciente = if (idLocal != 0L) {
                    db.pacienteDao().obtenerPacientePorIdLocal(idLocal)
                } else null

                val p = paciente

                uiHandler.post {
                    tvPlaceholder.visibility = View.GONE
                    renderResultadosSinEcg()

                    // ✅ Validar si tiene correo para mostrar advertencia
                    if (p?.correo.isNullOrBlank()) {
                        addEmailWarning()
                    }
                }

                // ✅ Enviar solo si tiene id_usuario_web
                if (p != null && p.id_usuario_web != null) {
                    idUsuarioWebCargado = p.id_usuario_web!!
                    enviarMedicionesAlDoctor(p)
                } else {
                    Log.d(TAG, "⏭️ Sin id_usuario_web, no se envía")
                }

                cargarEcgEnBackground()
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

            // ✅ Arrancar los mensajes periódicos
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

    // ✅ Detener los mensajes periódicos de envío
    private fun detenerMensajesEnvio() {
        enviandoMediciones = false
        envioHandler.removeCallbacks(envioRunnable)
    }

    /**
     * ✅ Ruta A: lanza TelemedicineActivity, que se encarga de cargar
     *    la pantalla de médicos disponibles y todo el flujo de Angular.
     */
    private fun iniciarVideollamada(idUsuarioWeb: Int) {
        Log.d(TAG, "🎬 Iniciando videollamada (Ruta A) con id_usuario_web=$idUsuarioWeb")

        val intent = Intent(this, TelemedicineActivity::class.java).apply {
            putExtra(TelemedicineActivity.EXTRA_ID_USUARIO_WEB, idUsuarioWeb)
        }
        startActivity(intent)
    }

    private fun clearResults() {
        getSharedPreferences("ResultsPrefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    override fun onBackPressed() {
        clearResults()
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        detenerMensajesEnvio()
        clearResults()
    }

    // ── Render sin ECG (rápido) ──────────────────────────

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

    // ── Helpers de UI ────────────────────────────────────

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
}