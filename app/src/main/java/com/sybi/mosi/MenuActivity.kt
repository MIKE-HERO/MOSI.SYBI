package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MenuActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_menu)

        val rootView = findViewById<View>(android.R.id.content)
        val btnBack = findViewById<ImageButton>(R.id.btnBackMenu)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                rootView.setBackgroundColor(Color.parseColor("#EDF4F8"))
                val colorInt = try {
                    Color.parseColor(newColor)
                } catch (_: Exception) {
                    Color.parseColor("#31536A")
                }
                btnBack?.setColorFilter(colorInt)
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val savedColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", "#31536A")
                ?: "#31536A"
        } else {
            prefs.getString("BackgroundColor", "#31536A")
                ?: "#31536A"
        }
        val themeColorInt = try {
            Color.parseColor(savedColorHex)
        } catch (_: Exception) {
            Color.parseColor("#31536A")
        }

        rootView.setBackgroundColor(Color.parseColor("#EDF4F8"))
        btnBack?.setColorFilter(themeColorInt)

        // Botón Regresar
        btnBack?.setOnClickListener {
            finish()
        }

        // Botón Reiniciar
        findViewById<View>(R.id.btnRebootDevice)?.setOnClickListener {
            reiniciarDispositivo()
        }

        // Botón Cerrar App
        findViewById<View>(R.id.btnCloseApp)?.setOnClickListener {
            cerrarAplicacion()
        }

        // 1. Pacientes
        findViewById<LinearLayout>(R.id.btnPatientTableMenu)?.setOnClickListener {
            startActivity(Intent(this, PatientTableActivity::class.java))
        }

        // 2. Historial de mediciones
        findViewById<LinearLayout>(R.id.btnResultTableMenu)?.setOnClickListener {
            startActivity(Intent(this, ResultTableActivity::class.java))
        }

        // 3. Ajustes Básicos (NUEVO)
        findViewById<LinearLayout>(R.id.btnBasicSettingsMenu)?.setOnClickListener {
            startActivity(Intent(this, BasicSettingsActivity::class.java))
        }

        // 4. Ajustes Avanzados (Configuración)
        findViewById<LinearLayout>(R.id.btnSettingsMenu)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 5. Reporte Diario
        findViewById<View>(R.id.btnDailyReportMenu)?.setOnClickListener {
            mostrarDialogoReporteDiario()
        }
    }

    override fun onResume() {
        super.onResume()
        val btnBack = findViewById<ImageButton>(R.id.btnBackMenu)
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val savedColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", "#31536A")
                ?: "#31536A"
        } else {
            prefs.getString("BackgroundColor", "#31536A")
                ?: "#31536A"
        }
        val themeColorInt = try {
            Color.parseColor(savedColorHex)
        } catch (_: Exception) {
            Color.parseColor("#31536A")
        }
        btnBack?.setColorFilter(themeColorInt)
    }

    private fun reiniciarDispositivo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_reboot_title)
            .setMessage(R.string.dialog_reboot_msg)
            .setPositiveButton(R.string.btn_yes) { _, _ -> ejecutarReiniciodeEquipo() }
            .setNegativeButton(R.string.btn_no, null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun ejecutarReiniciodeEquipo() {
        var exito = false
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.reboot(null)
            exito = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (!exito) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                process.waitFor()
                exito = true
            } catch (e: Exception) {
                try {
                    val process = Runtime.getRuntime().exec("reboot")
                    process.waitFor()
                    exito = true
                } catch (e2: Exception) {
                    e2.printStackTrace()
                }
            }
        }

        if (!exito) {
            try {
                val intent = Intent("android.intent.action.REBOOT")
                intent.putExtra("nowait", 1)
                intent.putExtra("interval", 1)
                intent.putExtra("window", 0)
                sendBroadcast(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this,
                    "No se pudo reiniciar el dispositivo automáticamente",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun cerrarAplicacion() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_close_app_title)
            .setMessage(R.string.dialog_close_app_msg)
            .setPositiveButton(R.string.btn_yes) { _, _ -> finishAffinity() }
            .setNegativeButton(R.string.btn_no, null)
            .show()
    }

    private fun mostrarDialogoReporteDiario() {
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
            setText(AutoLogHelper.DESTINO_SOPORTE)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val paddingPx = (20 * resources.displayMetrics.density).toInt()
        val contenedor = android.widget.FrameLayout(this).apply {
            setPadding(paddingPx, paddingPx / 2, paddingPx, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Reporte diario")
            .setMessage("¿A qué correo deseas enviar el reporte diario de atenciones del día?")
            .setView(contenedor)
            .setPositiveButton("Enviar") { _, _ ->
                val destino = input.text.toString().trim()
                if (destino.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(destino).matches()) {
                    Toast.makeText(this, "Ingresa un correo válido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                enviarReporteDiarioPorCorreo(destino)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun enviarReporteDiarioPorCorreo(destino: String) {
        Toast.makeText(this, "Generando y enviando reporte diario...", Toast.LENGTH_SHORT).show()

        val config = EmailSender.cargarConfig(this)

        lifecycleScope.launch(Dispatchers.IO) {
            val pdfFile = DailyReportManager.generarPdfReporteDiario(this@MenuActivity)
            val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())

            val resultado = if (pdfFile != null && pdfFile.exists()) {
                EmailSender.enviar(
                    config = config,
                    destino = destino,
                    asunto = "Reporte diario — Quiosco MOSI ($dateStr)",
                    cuerpoHtml = "<p>Se adjunta el reporte diario de atenciones del quiosco médico correspondiente al día <b>$dateStr</b>.</p>",
                    adjunto = pdfFile,
                    nombreAdjunto = "mosi_reporte_diario.pdf"
                )
            } else {
                Result.failure(IllegalStateException("No se pudo generar el reporte PDF del día"))
            }

            withContext(Dispatchers.Main) {
                if (resultado.isSuccess) {
                    Toast.makeText(this@MenuActivity, "✅ Reporte diario enviado a $destino", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this@MenuActivity,
                        "❌ Error al enviar el reporte diario: ${resultado.exceptionOrNull()?.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}