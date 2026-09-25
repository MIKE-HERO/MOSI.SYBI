package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ocultar SOLO la barra de estado (Hora, batería, señal)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)

        // --- RECEPTOR DE CAMBIO DE COLOR MEJORADO ---
        val rootView = findViewById<View>(android.R.id.content)
        val sideBar = findViewById<View>(R.id.sideBarLayout) // Busca la barra lateral

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    // 1. Cambia el fondo blanco de la pantalla
                    rootView.setBackgroundColor(Color.parseColor(newColor))
                    // 2. Cambia la barra lateral azul (si existe)
                    if (sideBar != null) {
                        sideBar.setBackgroundColor(Color.parseColor(newColor))
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        rootView.setBackgroundColor(Color.parseColor(savedColor))
        if (sideBar != null) {
            sideBar.setBackgroundColor(Color.parseColor(savedColor))
        }
        // ------------------------------------------------

        // 0.1 Botón: Reiniciar Equipo
        val btnReboot = findViewById<View>(R.id.btnRebootDevice)
        btnReboot?.setOnClickListener {
            reiniciarDispositivo()
        }

        // 0.2 Botón: Cerrar Aplicación
        val btnCloseApp = findViewById<View>(R.id.btnCloseApp)
        btnCloseApp?.setOnClickListener {
            cerrarAplicacion()
        }

        // 1. Botón Regresar (Barra azul izquierda)
        val btnBack = findViewById<View>(R.id.btnBackSettings)
        btnBack.setOnClickListener {
            finish()
        }

        // 1.1 Botón: Ajustes básicos
        val btnBasicSettings = findViewById<LinearLayout>(R.id.btnBasicSettings)
        btnBasicSettings?.setOnClickListener {
            startActivity(Intent(this, BasicSettingsActivity::class.java))
        }

        // 2. Botón: Configuración del LOGOTIPO
        val btnLogoLayout = findViewById<LinearLayout>(R.id.btnLogoSettings)
        btnLogoLayout.setOnClickListener {
            val intent = Intent(this, LogoSettingsActivity::class.java)
            startActivity(intent)
        }

        // 3. Botón: Elementos de prueba (segundo botón de la fila 1)
        val btnTestElements = findViewById<LinearLayout>(R.id.btnElementsTest)
        btnTestElements.setOnClickListener {
            startActivity(Intent(this, TestElementsActivity::class.java))
        }

        // 4. Botón: Ajustes de usuario
        val btnUserSettings = findViewById<LinearLayout>(R.id.btnUserSettings)
        btnUserSettings.setOnClickListener {
            startActivity(Intent(this, UserSettingsActivity::class.java))
        }

        // 5. Botón: Configuración de telemedicina
        val btnTelemedicine = findViewById<LinearLayout>(R.id.btnTelemedicineSettings)
        btnTelemedicine.setOnClickListener {
            startActivity(Intent(this, TelemedicineSettingsActivity::class.java))
        }

        // 6. ✅ Botón: Base de datos (NUEVO)
        val btnPatientTable = findViewById<LinearLayout>(R.id.btnPatientTable)
        btnPatientTable.setOnClickListener {
            startActivity(Intent(this, PatientTableActivity::class.java))
        }

        // 7. ✅ Botón: Base de datos de Resultados (NUEVO)
        val btnResultTable = findViewById<LinearLayout>(R.id.btnResultTable)
        btnResultTable.setOnClickListener {
            startActivity(Intent(this, ResultTableActivity::class.java))
        }

        // 8. ✅ Botón: Información del dispositivo (NUEVO)
        val btnDeviceInfo = findViewById<LinearLayout>(R.id.btnDeviceInfo)
        btnDeviceInfo.setOnClickListener {
            startActivity(Intent(this, DeviceInfoActivity::class.java))
        }

        // 9. ✅ Botón: Configuración de contraseña (NUEVO)
        val btnPasswordSettings = findViewById<LinearLayout>(R.id.btnPasswordSettings)
        btnPasswordSettings.setOnClickListener {
            startActivity(Intent(this, PasswordSettingsActivity::class.java))
        }

        // 10. ✅ Botón: Testeo y calibración de micrófono
        val btnMicTest = findViewById<LinearLayout>(R.id.btnMicTest)
        btnMicTest.setOnClickListener {
            startActivity(Intent(this, CalibTestActivity::class.java))
        }

        // 11. ✅ Botón: Ajustes de Android (abre los Settings del sistema)
        val btnAndroidSettings = findViewById<LinearLayout>(R.id.btnAndroidSettings)
        btnAndroidSettings.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback: por si algún fabricante no expone ACTION_SETTINGS
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
        }

        // 12. Botón: Pantalla publicitaria — abre VideoLoopControl
        val btnAdvertisingScreen = findViewById<LinearLayout>(R.id.btnAdvertisingScreen)
        btnAdvertisingScreen?.setOnClickListener {
            startActivity(Intent(this, VideoLoopControlActivity::class.java))
        }
    }

    private fun reiniciarDispositivo() {
        AlertDialog.Builder(this)
            .setTitle("Reiniciar Equipo")
            .setMessage("¿Está seguro de que desea reiniciar el dispositivo?")
            .setPositiveButton("Sí") { _, _ ->
                ejecutarReiniciodeEquipo()
            }
            .setNegativeButton("No", null)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun ejecutarReiniciodeEquipo() {
        var exito = false
        // Intento 1: PowerManager (si es app de sistema / firma)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.reboot(null)
            exito = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Intento 2: Shell / Root (`su -c reboot` o `reboot`)
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

        // Intento 3: Broadcast de REBOOT
        if (!exito) {
            try {
                val intent = Intent("android.intent.action.REBOOT")
                intent.putExtra("nowait", 1)
                intent.putExtra("interval", 1)
                intent.putExtra("window", 0)
                sendBroadcast(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "No se pudo reiniciar el dispositivo automáticamente", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cerrarAplicacion() {
        AlertDialog.Builder(this)
            .setTitle("Cerrar Aplicación")
            .setMessage("¿Está seguro de que desea cerrar la aplicación?")
            .setPositiveButton("Sí") { _, _ ->
                finishAffinity()
            }
            .setNegativeButton("No", null)
            .show()
    }
}