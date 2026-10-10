package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
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
        const val PREF_PRINTER_IP = "printer_ip"
    }

    private val allSwitches = mutableListOf<Switch>()
    private lateinit var etPdfTitle: EditText
    private lateinit var etPdfSubtitle: EditText

    private val appPrefs by lazy { getSharedPreferences("AppPrefs", Context.MODE_PRIVATE) }

    private val logoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            actualizarPrevisualizacion()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_settings)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.sideBarLayout)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                applySwitchTint(Color.parseColor(newColor))
                actualizarPrevisualizacion()
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

        // IP de la impresora de red (impresión directa por IPP)
        val etPrinterIp = findViewById<EditText>(R.id.etPrinterIp)
        etPrinterIp.setText(prefs.getString(PREF_PRINTER_IP, "") ?: "")
        etPrinterIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(PREF_PRINTER_IP, s.toString().trim()).apply()
            }
        })

        applySwitchTint(Color.parseColor(savedColor))

        // Personalización de PDF
        etPdfTitle = findViewById(R.id.etPdfTitle)
        etPdfSubtitle = findViewById(R.id.etPdfSubtitle)

        val defaultTitle = appPrefs.getString("AppTitle", "MÓDULO DE SALUD INTEGRAL") ?: "MÓDULO DE SALUD INTEGRAL"
        val savedPdfTitle = appPrefs.getString("PdfTitle", defaultTitle) ?: defaultTitle
        val savedPdfSubtitle = appPrefs.getString("PdfSubtitle", "") ?: ""

        etPdfTitle.setText(savedPdfTitle)
        etPdfSubtitle.setText(savedPdfSubtitle)

        actualizarPrevisualizacion()

        etPdfTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val title = s.toString()
                appPrefs.edit().putString("PdfTitle", title).apply()
                actualizarPrevisualizacion()
            }
        })

        etPdfSubtitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val subtitle = s.toString()
                appPrefs.edit().putString("PdfSubtitle", subtitle).apply()
                actualizarPrevisualizacion()
            }
        })
    }

    private fun actualizarPrevisualizacion() {
        val container = findViewById<android.widget.LinearLayout>(R.id.previewInformeContainer) ?: return
        container.removeAllViews()

        val colorTema = runCatching {
            Color.parseColor(appPrefs.getString("BackgroundColor", "#0F3E82"))
        }.getOrDefault(Color.parseColor("#0F3E82"))

        val builder = InformeBuilder(this, container, colorTema)

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
