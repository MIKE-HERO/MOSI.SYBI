package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class CalibrateActivity : BaseActivity() {

    companion object {
        private const val TAG = "CalibrateActivity"
    }

    private lateinit var sideBarLayout: View
    private lateinit var btnBack: View
    private lateinit var switchCalibracion: Switch
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitulo: TextView
    private lateinit var tvInstrucciones: TextView
    private lateinit var tvLabelValor: TextView
    private lateinit var etValor: EditText
    private lateinit var btnCalibrate: Button
    private lateinit var tvLabelAltura: TextView
    private lateinit var tvLabelPeso: TextView

    /** true = Altura, false = Peso */
    private var modoAltura: Boolean = true
    private var calibrando = false
    private var colorTema: Int = Color.parseColor("#0F3E82")

    // ✅ Receiver de resultado de calibración enviado por SerialService
    private val calibResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != "CALIBRATION_RESULT") return

            val tipo = intent.getStringExtra("tipo") ?: ""
            val status = intent.getStringExtra("status") ?: ""
            val message = intent.getStringExtra("message")

            runOnUiThread {
                if (!calibrando) return@runOnUiThread

                resetearEstadoCalibracion()

                val tipoEsperado = if (modoAltura) "altura" else "peso"
                if (tipo != tipoEsperado) return@runOnUiThread

                when (status) {
                    "OK" -> {
                        val msg = if (modoAltura)
                            "✅ Calibración de altura completada correctamente"
                        else
                            "✅ Calibración de peso completada correctamente"
                        Toast.makeText(this@CalibrateActivity, msg, Toast.LENGTH_LONG).show()
                        Log.d(TAG, "Calibración OK - modoAltura=$modoAltura")
                    }
                    "ERROR" -> {
                        val msg = message ?: "❌ Error en la calibración. Verifique el objeto patrón"
                        Toast.makeText(this@CalibrateActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ✅ Receiver para cambio de tema
    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("new_color")?.let { aplicarColorTema(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_calibrate)

        sideBarLayout = findViewById(R.id.sideBarLayout)
        btnBack = findViewById(R.id.btnBackCalibrate)
        switchCalibracion = findViewById(R.id.switchCalibracion)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitulo = findViewById(R.id.tvSubtitulo)
        tvInstrucciones = findViewById(R.id.tvInstrucciones)
        tvLabelValor = findViewById(R.id.tvLabelValor)
        etValor = findViewById(R.id.etValor)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        tvLabelAltura = findViewById(R.id.tvLabelAltura)
        tvLabelPeso = findViewById(R.id.tvLabelPeso)

        // Registrar receiver de tema
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        aplicarColorTema(prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82")

        // Leer modo inicial desde el Intent (por defecto: altura)
        val modoInicial = intent.getStringExtra("modo_inicial") ?: "altura"
        modoAltura = (modoInicial == "altura")

        // ✅ Switch OFF = Altura, Switch ON = Peso
        switchCalibracion.isChecked = !modoAltura
        actualizarUI()

        switchCalibracion.setOnCheckedChangeListener { _, isChecked ->
            // ✅ isChecked = true → Peso ; isChecked = false → Altura
            modoAltura = !isChecked
            if (!calibrando) actualizarUI()
        }

        btnBack.setOnClickListener { finish() }
        btnCalibrate.setOnClickListener { onCalibrarClicked() }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(calibResultReceiver, IntentFilter("CALIBRATION_RESULT"))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(calibResultReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver)
        } catch (_: Exception) { }
    }

    // ============================================================
    // ACTUALIZACIÓN DINÁMICA DE UI
    // ============================================================
    private fun actualizarUI() {
        // Limpiar campo
        etValor.setText("")
        etValor.isEnabled = true
        btnCalibrate.isEnabled = true
        calibrando = false

        if (modoAltura) {
            tvTitle.text = "Calibrar altura"
            tvInstrucciones.text =
                "1. Coloque un objeto de altura conocida (regla, cinta métrica o persona) bajo el sensor de altura.\n\n" +
                        "2. Espere a que la lectura se estabilice.\n\n" +
                        "3. Ingrese la altura real del objeto patrón en centímetros.\n\n" +
                        "4. Presione 'Calibrar altura' para finalizar."
            tvLabelValor.text = "Altura real del objeto patrón (cm)"
            etValor.hint = "Ej: 170.0"
            btnCalibrate.text = "Calibrar altura"

            tvLabelAltura.setTextColor(colorTema)
            tvLabelPeso.setTextColor(Color.parseColor("#888888"))
        } else {
            tvTitle.text = "Calibrar báscula"
            tvInstrucciones.text =
                "1. Coloque un objeto de peso conocido sobre la báscula.\n\n" +
                        "2. Espere a que la lectura se estabilice.\n\n" +
                        "3. Ingrese el peso real del objeto patrón en kilogramos.\n\n" +
                        "4. Presione 'Calibrar peso' para finalizar."
            tvLabelValor.text = "Peso real del objeto patrón (kg)"
            etValor.hint = "Ej: 50.0"
            btnCalibrate.text = "Calibrar peso"

            tvLabelPeso.setTextColor(colorTema)
            tvLabelAltura.setTextColor(Color.parseColor("#888888"))
        }
    }

    // ============================================================
    // CALIBRAR
    // ============================================================
    private fun onCalibrarClicked() {
        val texto = etValor.text.toString().trim()
        val valor = texto.toDoubleOrNull()

        if (valor == null || valor <= 0.0) {
            val msg = if (modoAltura) "Ingrese una altura válida mayor a 0"
            else "Ingrese un peso válido mayor a 0"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        calibrando = true
        btnCalibrate.isEnabled = false
        etValor.isEnabled = false
        switchCalibracion.isEnabled = false

        val tipo = if (modoAltura) "altura" else "peso"
        Log.d(TAG, "📤 Enviando SEND_CALIBRATION_COMMAND: tipo=$tipo, valor=$valor")

        val intent = Intent("SEND_CALIBRATION_COMMAND")
        intent.putExtra("tipo", tipo)
        intent.putExtra("valor", valor)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // Timeout de seguridad (10s)
        btnCalibrate.postDelayed({
            if (calibrando) {
                resetearEstadoCalibracion()
                Toast.makeText(this, "No se recibió respuesta del dispositivo", Toast.LENGTH_LONG).show()
            }
        }, 10000)
    }

    private fun resetearEstadoCalibracion() {
        calibrando = false
        btnCalibrate.isEnabled = true
        etValor.isEnabled = true
        switchCalibracion.isEnabled = true
    }

    // ============================================================
    // TEMA
    // ============================================================
    private fun aplicarColorTema(hexColor: String) {
        try {
            colorTema = Color.parseColor(hexColor)
            sideBarLayout.setBackgroundColor(colorTema)
            btnCalibrate.backgroundTintList = ColorStateList.valueOf(colorTema)

            // ✅ Switch: siempre del color del tema (ON y OFF)
            val switchColor = ColorStateList.valueOf(colorTema)
            switchCalibracion.thumbTintList = switchColor
            switchCalibracion.trackTintList = switchColor

            // Resaltar label activo
            if (modoAltura) {
                tvLabelAltura.setTextColor(colorTema)
                tvLabelPeso.setTextColor(Color.parseColor("#888888"))
            } else {
                tvLabelPeso.setTextColor(colorTema)
                tvLabelAltura.setTextColor(Color.parseColor("#888888"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color: ${e.message}")
        }
    }
}