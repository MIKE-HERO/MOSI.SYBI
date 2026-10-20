package com.sybi.mosi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class CalibTestActivity : BaseActivity() {

    private lateinit var sideBarLayout: View
    private lateinit var btnBack: View
    private lateinit var btnTestMic: Button
    private lateinit var btnTestCamera: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnSendLog: Button
    private lateinit var etCardResult: android.widget.EditText

    private var icCardReceiver: BroadcastReceiver? = null
    private var onPermissionsGrantedAction: (() -> Unit)? = null

    companion object {
        private const val TAG = "CalibTestActivity"
        private const val TEST_MIC_URL = "https://es.mictests.com/"
        private const val TEST_CAMERA_URL = "https://es.webcamtests.com/"
        private const val REQUEST_PERMISSIONS_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_calib_test)

        sideBarLayout = findViewById(R.id.themeAccentBar)
        btnBack = findViewById(R.id.btnBackMicTest)
        btnTestMic = findViewById(R.id.btnTestMic)
        btnTestCamera = findViewById(R.id.btnTestCamera)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnSendLog = findViewById(R.id.btnSendLog)
        etCardResult = findViewById(R.id.etCardResult)

        // --- RECEPTOR DE TARJETA IC ---
        icCardReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "DEVICE_DATA_RECEIVED" && intent.getStringExtra("type") == "IC_CARD") {
                    val cardNumber = intent.getStringExtra("card_number") ?: return
                    Log.d(TAG, "💳 Tarjeta IC escaneada en CalibTest: $cardNumber")
                    etCardResult.setText("")
                    etCardResult.setText(cardNumber)
                    Toast.makeText(this@CalibTestActivity, "Tarjeta IC leída: $cardNumber", Toast.LENGTH_SHORT).show()
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(icCardReceiver!!, IntentFilter("DEVICE_DATA_RECEIVED"))

        // --- RECEPTOR DE CAMBIO DE TEMA ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    aplicarColorTema(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        aplicarColorTema(savedColor)

        btnBack.setOnClickListener {
            finish()
        }

        btnTestMic.setOnClickListener {
            verificarPermisosYComenzar { abrirTestUrl(TEST_MIC_URL) }
        }

        btnTestCamera.setOnClickListener {
            verificarPermisosYComenzar { abrirTestUrl(TEST_CAMERA_URL) }
        }

        btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }

        btnSendLog.setOnClickListener { mostrarDialogoEnviarLog() }
    }

    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            sideBarLayout.setBackgroundColor(colorInt)
            val colorStateList = ColorStateList.valueOf(colorInt)
            btnTestMic.backgroundTintList = colorStateList
            btnTestCamera.backgroundTintList = colorStateList
            btnCalibrate.backgroundTintList = colorStateList
            btnSendLog.backgroundTintList = colorStateList
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color de tema: ${e.message}")
        }
    }

    private fun verificarPermisosYComenzar(onGranted: () -> Unit) {
        onPermissionsGrantedAction = onGranted
        val permisos = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGrantedAction?.invoke()
            } else {
                Toast.makeText(this, "Se necesitan permisos de cámara y micrófono para el test", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirTestUrl(url: String) {
        Log.d(TAG, "🌐 Abriendo test en CustomTabs: $url")
        CustomTabsHelper.openUrl(this, url, preferFirefox = true)
    }

    // ==========================================
    // ENVIAR LOG DE ERRORES
    // ==========================================
    private fun mostrarDialogoEnviarLog() {
        if (!EmailSender.estaConfigurado(this)) {
            Toast.makeText(
                this,
                "Configura primero el correo en Configuración de informes > Configurar envío de correo (SMTP)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "correo@ejemplo.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val paddingPx = (20 * resources.displayMetrics.density).toInt()
        val contenedor = android.widget.FrameLayout(this).apply {
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(input)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Enviar log de errores")
            .setMessage("¿A qué correo deseas enviar el log de errores de esta sesión?")
            .setView(contenedor)
            .setPositiveButton("Enviar") { _, _ ->
                val destino = input.text.toString().trim()
                if (destino.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(destino).matches()) {
                    Toast.makeText(this, "Ingresa un correo válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                enviarLogPorCorreo(destino)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarLogPorCorreo(destino: String) {
        btnSendLog.isEnabled = false
        btnSendLog.text = "Enviando..."

        val config = EmailSender.cargarConfig(this)

        Thread {
            val archivo = ErrorLogHelper.generarLogDeErrores(this)
            val resultado = if (archivo != null) {
                EmailSender.enviar(
                    config = config,
                    destino = destino,
                    asunto = "Log de errores — Quiosco MOSI",
                    cuerpoHtml = "<p>Se adjunta el log con los errores registrados por el quiosco.</p>",
                    adjunto = archivo,
                    nombreAdjunto = "mosi_errores.log"
                )
            } else {
                Result.failure(IllegalStateException("No se pudo generar el archivo de log"))
            }

            runOnUiThread {
                btnSendLog.isEnabled = true
                btnSendLog.text = "Enviar"
                if (resultado.isSuccess) {
                    Toast.makeText(this, "Log enviado a $destino", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        "Error al enviar el log: ${resultado.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        icCardReceiver?.let {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            } catch (_: Exception) {}
        }
    }
}
