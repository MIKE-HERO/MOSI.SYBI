package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.io.File

class ReportSettingsActivity : BaseActivity() {

    companion object {
        const val PREF_ALLOW_PRINT = "allow_print_results"
        const val PREF_ALLOW_EMAIL = "allow_email_results"
        const val PREF_ALLOW_TICKET_PRINT = "allow_ticket_print"
        const val PREF_PRINTER_IP = "printer_ip"
    }

    private val allSwitches = mutableListOf<Switch>()
    private lateinit var etPdfTitle: EditText
    private lateinit var etPdfSubtitle: EditText

    private val appPrefs by lazy { getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())

    private var logoBitmapPrecargado: Bitmap? = null

    private val logoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            recargarLogoBitmapYRenderizar()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_settings)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.themeAccentBar)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                applySwitchTint(Color.parseColor(newColor))
                actualizarPrevisualizacion()
                actualizarPrevisualizacionTicket()
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logoReceiver, IntentFilter("ACTION_UPDATE_LOGO"))

        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        findViewById<View>(R.id.btnBackReportSettings).setOnClickListener { finish() }

        findViewById<View>(R.id.btnConfigurarSmtp)?.setOnClickListener {
            startActivity(Intent(this, SmtpSettingsActivity::class.java))
        }

        // Switches
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        setupSwitch(R.id.switchEnablePrint, PREF_ALLOW_PRINT, prefs)
        setupSwitch(R.id.switchEnableEmail, PREF_ALLOW_EMAIL, prefs)
        setupSwitch(R.id.switchEnableTicketPrint, PREF_ALLOW_TICKET_PRINT, prefs)

        applySwitchTint(Color.parseColor(savedColor))

        // Personalización de PDF
        etPdfTitle = findViewById(R.id.etPdfTitle)
        etPdfSubtitle = findViewById(R.id.etPdfSubtitle)

        val defaultTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
        val savedPdfTitle = appPrefs.getString("PdfTitle", defaultTitle) ?: defaultTitle
        val savedPdfSubtitle = appPrefs.getString("PdfSubtitle", "") ?: ""

        etPdfTitle.setText(savedPdfTitle)
        etPdfSubtitle.setText(savedPdfSubtitle)

        actualizarPrevisualizacionTicket()
        recargarLogoBitmapYRenderizar()

        etPdfTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val title = s.toString()
                appPrefs.edit().putString("PdfTitle", title).apply()
                actualizarPrevisualizacion()
                actualizarPrevisualizacionTicket()
            }
        })

        etPdfSubtitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val subtitle = s.toString()
                appPrefs.edit().putString("PdfSubtitle", subtitle).apply()
                actualizarPrevisualizacion()
                actualizarPrevisualizacionTicket()
            }
        })
    }

    private fun recargarLogoBitmapYRenderizar() {
        val logoPath = appPrefs.getString("LogoPath", null)
        Thread {
            val bitmap = InformeBuilder.cargarLogoBitmap(this, logoPath)
            uiHandler.post {
                logoBitmapPrecargado = bitmap
                actualizarPrevisualizacion()
                actualizarPrevisualizacionTicket()
            }
        }.start()
    }

    private fun actualizarPrevisualizacion() {
        val container = findViewById<android.widget.LinearLayout>(R.id.previewInformeContainer) ?: return
        container.removeAllViews()

        val colorTema = runCatching {
            Color.parseColor(appPrefs.getString("BackgroundColor", "#0F3E82"))
        }.getOrDefault(Color.parseColor("#0F3E82"))

        val builder = InformeBuilder(
            this, container, colorTema,
            logoIzquierdoBitmapPrecargado = logoBitmapPrecargado
        )

        val samplePaciente = com.sybi.mosi.database.Paciente(
            id_local = 1L,
            id_usuario_web = 1234,
            nombre = "Juan",
            apellido_paterno = "Pérez",
            apellido_materno = "Gómez",
            fecha_nacimiento = "15/05/1990",
            genero = "M",
            telefono = "5551234567"
        )

        val sampleMediciones = listOf(
            com.sybi.mosi.database.Resultado(
                id_local = 1L,
                altura = "175.0",
                peso = "70.5",
                imc = "23.0",
                grasa_corporal = "18.5",
                grasa_corporal_kg = "13.0",
                agua_corporal = "55.0",
                agua_corporal_kg = "38.8",
                masa_muscular = "32.0",
                masa_libre_grasa = "57.5",
                proteina = "12.5",
                minerales = "3.2",
                metabolismo_basal = "1550",
                grasa_visceral = "4.0",
                peso_ideal = "68.0",
                tipo_grasa = "1",
                sistolica = "120",
                diastolica = "80",
                pulso = "72",
                temperatura = "36.6",
                temperatura_f = "97.8",
                spo2 = "98",
                frecuencia_pulso = "72",
                indice_perfusion = "2.1",
                frecuencia_cardiaca = "72",
                eje_p = "45",
                eje_qrs = "60",
                eje_t = "30",
                intervalo_pr = "140",
                duracion_qrs = "85",
                intervalo_qt = "380",
                qt_corregido = "410",
                onda_rv5 = "1.5",
                onda_sv1 = "0.8",
                resultado_ecg = "Ritmo sinusal normal",
                fecha_medicion = "07/10/2026 13:43"
            ),
            com.sybi.mosi.database.Resultado(
                id_local = 1L,
                altura = "175.0",
                peso = "71.0",
                imc = "23.5",
                grasa_corporal = "19.0",
                grasa_corporal_kg = "13.5",
                agua_corporal = "54.5",
                agua_corporal_kg = "38.5",
                masa_muscular = "31.5",
                masa_libre_grasa = "57.0",
                proteina = "12.2",
                minerales = "3.1",
                metabolismo_basal = "1540",
                grasa_visceral = "4.5",
                peso_ideal = "68.0",
                tipo_grasa = "1",
                sistolica = "122",
                diastolica = "82",
                pulso = "74",
                temperatura = "36.5",
                temperatura_f = "97.7",
                spo2 = "97",
                frecuencia_pulso = "74",
                indice_perfusion = "2.0",
                frecuencia_cardiaca = "74",
                eje_p = "45",
                eje_qrs = "60",
                eje_t = "30",
                intervalo_pr = "142",
                duracion_qrs = "86",
                intervalo_qt = "382",
                qt_corregido = "412",
                onda_rv5 = "1.4",
                onda_sv1 = "0.8",
                resultado_ecg = "Ritmo sinusal normal",
                fecha_medicion = "01/10/2026 10:15"
            )
        )

        builder.construir(samplePaciente, 1L, sampleMediciones)
    }

    private fun actualizarPrevisualizacionTicket() {
        val container = findViewById<android.widget.LinearLayout>(R.id.previewTicketContainer) ?: return
        container.removeAllViews()

        val title = etPdfTitle.text.toString().takeIf { it.isNotBlank() } ?: "MÓDULO DE SALUD INTEGRAL"
        val subtitle = etPdfSubtitle.text.toString().takeIf { it.isNotBlank() } ?: ""

        val textColor = Color.parseColor("#222222")
        val primaryColor = Color.parseColor("#0F3E82")

        fun addTicketLine(text: String, bold: Boolean = false, center: Boolean = false, size: Float = 11f, color: Int = textColor) {
            val tv = TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                else setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL)
                gravity = if (center) android.view.Gravity.CENTER else android.view.Gravity.START
                setPadding(0, 2, 0, 2)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(tv)
        }

        fun addDivider() {
            addTicketLine("--------------------------------", bold = false, center = true, color = Color.parseColor("#888888"))
        }

        addTicketLine(title, bold = true, center = true, size = 12f, color = primaryColor)
        if (subtitle.isNotBlank()) {
            addTicketLine(subtitle, bold = false, center = true, size = 10f)
        }
        addDivider()

        addTicketLine("DATOS DEL PACIENTE", bold = true, center = false, size = 11f, color = primaryColor)
        addTicketLine("Nombre: Juan Pérez Gómez")
        addTicketLine("Edad: 36 años")
        addTicketLine("Género: Masculino")
        addTicketLine("Fecha/Hora: 07/10/2026 13:43")
        addDivider()

        addTicketLine("RESULTADOS DE MEDICIÓN", bold = true, center = false, size = 11f, color = primaryColor)

        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val showAlturaPeso = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ALTURA_PESO, true)
        val showComposicion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_COMPOSICION, true)
        val showPresion = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_PRESION, true)
        val showOxigeno = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_OXIGENO, true)
        val showTemperatura = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_TEMPERATURA, true)
        val showEcg = devicePrefs.getBoolean(TestElementsActivity.PREF_DEVICE_ECG, true)

        // 1. Altura y Peso
        if (showAlturaPeso) {
            addTicketLine("--- ALTURA Y PESO ---", bold = true, center = false, color = primaryColor)
            addTicketLine("Altura: 175.0 cm")
            addTicketLine("Peso: 70.5 kg")
            addTicketLine("IMC: 23.0")
        }

        // 2. Composición Corporal
        if (showComposicion) {
            addTicketLine("--- COMPOSICIÓN CORPORAL ---", bold = true, center = false, color = primaryColor)
            addTicketLine("Grasa Corporal: 18.5% (13.0 kg)")
            addTicketLine("Agua Corporal: 55.0% (38.8 kg)")
            addTicketLine("Masa Muscular: 32.0 kg")
            addTicketLine("Masa Libre Grasa: 57.5 kg")
            addTicketLine("Proteína: 12.5 kg")
            addTicketLine("Minerales: 3.2 kg")
            addTicketLine("Metabolismo Basal: 1550 kcal")
            addTicketLine("Grasa Visceral: 4.0")
            addTicketLine("Peso Ideal: 68.0 kg")
            addTicketLine("Tipo de Grasa: 1")
        }

        // 3. Presión Arterial
        if (showPresion) {
            addTicketLine("--- PRESIÓN ARTERIAL ---", bold = true, center = false, color = primaryColor)
            addTicketLine("Presión: 120/80 mmHg")
            addTicketLine("Pulso: 72 bpm")
        }

        // 4. Oxígeno
        if (showOxigeno) {
            addTicketLine("--- OXÍGENO EN SANGRE ---", bold = true, center = false, color = primaryColor)
            addTicketLine("SpO2: 98%")
            addTicketLine("Pulso: 72 bpm")
            addTicketLine("Índice Perfusión: 2.1")
        }

        // 5. Temperatura
        if (showTemperatura) {
            addTicketLine("--- TEMPERATURA CORPORAL ---", bold = true, center = false, color = primaryColor)
            addTicketLine("Temperatura: 36.6 °C / 97.8 °F")
        }

        // 6. ECG
        if (showEcg) {
            addTicketLine("--- ECG ---", bold = true, center = false, color = primaryColor)
            addTicketLine("Frec. Cardíaca: 72 bpm")
            addTicketLine("Eje P/QRS/T: 45/60/30")
            addTicketLine("Int. PR / QRS: 140/85 ms")
            addTicketLine("Int. QT / QTc: 380/410 ms")
            addTicketLine("Ritmo: Ritmo sinusal normal")
        }
        addDivider()
        addTicketLine("¡Gracias por su visita!", bold = true, center = true, size = 10f)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(logoReceiver)
        } catch (_: Exception) {}
    }

    private fun loadLogo(imageView: ImageView, path: String?) {
        try {
            if (path.isNullOrBlank()) {
                imageView.setImageResource(R.drawable.sybi_logo_blanco)
                return
            }
            val imageLoader = ImageLoader.Builder(imageView.context)
                .components { add(SvgDecoder.Factory()) }
                .build()

            val file = File(path)
            val data: Any = if (file.exists()) file else Uri.parse(path)

            val request = ImageRequest.Builder(imageView.context)
                .data(data)
                .target(imageView)
                .error(R.drawable.sybi_logo_blanco)
                .placeholder(R.drawable.sybi_logo_blanco)
                .build()

            imageLoader.enqueue(request)
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.sybi_logo_blanco)
        }
    }

    private fun setupSwitch(switchId: Int, prefKey: String, prefs: android.content.SharedPreferences) {
        val sw = findViewById<Switch>(switchId)
        allSwitches.add(sw)
        sw.isChecked = prefs.getBoolean(prefKey, true)
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
    }

    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}
