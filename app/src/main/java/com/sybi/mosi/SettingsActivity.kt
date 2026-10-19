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
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ocultar SOLO la barra de estado (Hora, batería, señal)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)

        // --- RECEPTOR DE CAMBIO DE COLOR ---
        val rootView = findViewById<View>(android.R.id.content)
        val btnBack = findViewById<ImageButton>(R.id.btnBackSettings)

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

        // Cargar el color guardado al inicio
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
        // ------------------------------------------------

        // 0.1 Botón: Reiniciar Equipo
        findViewById<View>(R.id.btnRebootDevice)?.setOnClickListener {
            reiniciarDispositivo()
        }

        // 0.2 Botón: Cerrar Aplicación
        findViewById<View>(R.id.btnCloseApp)?.setOnClickListener {
            cerrarAplicacion()
        }

        // 0.3 Botón Regresar
        btnBack?.setOnClickListener {
            finish()
        }

        // 1. Botón: Ajustes básicos
        findViewById<LinearLayout>(R.id.btnBasicSettings)?.setOnClickListener {
            startActivity(Intent(this, BasicSettingsActivity::class.java))
        }

        // 2. Botón: Configuración del LOGOTIPO
        findViewById<LinearLayout>(R.id.btnLogoSettings)?.setOnClickListener {
            startActivity(Intent(this, LogoSettingsActivity::class.java))
        }

        // 3. Botón: Elementos de prueba
        findViewById<LinearLayout>(R.id.btnElementsTest)?.setOnClickListener {
            startActivity(Intent(this, TestElementsActivity::class.java))
        }

        // 4. Botón: Ajustes de usuario
        findViewById<LinearLayout>(R.id.btnUserSettings)?.setOnClickListener {
            startActivity(Intent(this, UserSettingsActivity::class.java))
        }

        // 5. Botón: Configuración de telemedicina
        findViewById<LinearLayout>(R.id.btnTelemedicineSettings)?.setOnClickListener {
            startActivity(Intent(this, TelemedicineSettingsActivity::class.java))
        }

        // 8. Botón: Información del dispositivo
        findViewById<LinearLayout>(R.id.btnDeviceInfo)?.setOnClickListener {
            startActivity(Intent(this, DeviceInfoActivity::class.java))
        }

        // 9. Botón: Configuración de contraseña
        findViewById<LinearLayout>(R.id.btnPasswordSettings)?.setOnClickListener {
            startActivity(Intent(this, PasswordSettingsActivity::class.java))
        }

        // 10. Botón: Testeo y calibración de micrófono
        findViewById<LinearLayout>(R.id.btnMicTest)?.setOnClickListener {
            startActivity(Intent(this, CalibTestActivity::class.java))
        }

        // 11. Botón: Pantalla publicitaria
        findViewById<LinearLayout>(R.id.btnAdvertisingScreen)?.setOnClickListener {
            startActivity(Intent(this, VideoLoopControlActivity::class.java))
        }

        // 12. Configuración de informes
        findViewById<LinearLayout>(R.id.btnReportSettings)?.setOnClickListener {
            startActivity(Intent(this, ReportSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val btnBack = findViewById<ImageButton>(R.id.btnBackSettings)
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
            .setTitle("Reiniciar Equipo")
            .setMessage("¿Está seguro de que desea reiniciar el dispositivo?")
            .setPositiveButton("Sí") { _, _ -> ejecutarReiniciodeEquipo() }
            .setNegativeButton("No", null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun ejecutarReiniciodeEquipo() {
        var exito = false

        // Intento 1: PowerManager (app de sistema / firma)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.reboot(null)
            exito = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Intento 2: Shell / Root
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

        // Intento 3: Broadcast
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
            .setTitle("Cerrar Aplicación")
            .setMessage("¿Está seguro de que desea cerrar la aplicación?")
            .setPositiveButton("Sí") { _, _ -> finishAffinity() }
            .setNegativeButton("No", null)
            .show()
    }
}
