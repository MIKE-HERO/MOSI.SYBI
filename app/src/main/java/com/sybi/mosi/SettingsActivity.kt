package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toBitmap
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class SettingsActivity : BaseActivity() {

    // Las 12 tarjetas del menú: su icono debe tomar el color de marca (los iconos blancos de
    // la barra lateral se quedan como están).
    private val botonesMenu = listOf(
        R.id.btnBasicSettings, R.id.btnElementsTest, R.id.btnReportSettings, R.id.btnMicTest,
        R.id.btnPatientTable, R.id.btnLogoSettings, R.id.btnUserSettings, R.id.btnAdvertisingScreen,
        R.id.btnResultTable, R.id.btnPasswordSettings, R.id.btnTelemedicineSettings, R.id.btnDeviceInfo
    )

    private fun tenirIconosMenu(color: Int) {
        // Los iconos del menú son imágenes 3D con transparencia y color propio.
        // Aplicarles un filtro plano elimina sus luces, volumen y acabado original.
        botonesMenu.forEach { id ->
            val boton = findViewById<LinearLayout>(id) ?: return@forEach
            for (i in 0 until boton.childCount) {
                (boton.getChildAt(i) as? ImageView)?.clearColorFilter()
            }
        }
    }

    /**
     * Aplica un contorno oscuro a un icono blanco (efecto "outline" robusto).
     * Dibuja la silueta en negro desplazada alrededor y el icono blanco encima.
     * Así se lee bien aunque el fondo de la barra sea amarillo, cian, etc.
     */
    private fun aplicarContornoIcono(iconId: Int, outline: Int = Color.BLACK, grosor: Int = 2) {
        val iv = findViewById<ImageView>(iconId) ?: return

        // ✅ 1) Limpiar el tint del XML ANTES de leer el drawable
        iv.imageTintList = null
        iv.colorFilter = null

        // 2) Obtener el drawable ya sin tint
        val original: Drawable = iv.drawable ?: return

        // 3) Bitmap base
        val w = original.intrinsicWidth.coerceAtLeast(48)
        val h = original.intrinsicHeight.coerceAtLeast(48)
        val bmp = original.toBitmap(w, h).copy(Bitmap.Config.ARGB_8888, true)

        // 4) Canvas con margen para el contorno
        val margen = grosor * 2
        val out = Bitmap.createBitmap(
            bmp.width + margen * 2,
            bmp.height + margen * 2,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 5) Contorno oscuro: silueta desplazada en 8 direcciones
        paint.colorFilter = PorterDuffColorFilter(outline, PorterDuff.Mode.SRC_IN)
        for (dx in -grosor..grosor) {
            for (dy in -grosor..grosor) {
                if (dx == 0 && dy == 0) continue
                canvas.drawBitmap(bmp, (margen + dx).toFloat(), (margen + dy).toFloat(), paint)
            }
        }

        // 6) Icono blanco encima
        paint.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bmp, margen.toFloat(), margen.toFloat(), paint)

        // 7) Asignar bitmap final y asegurar que no se re-tinte
        iv.setImageBitmap(out)
        iv.imageTintList = null
        iv.colorFilter = null
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** Aplica contorno a los 3 iconos de la barra lateral. */
    private fun aplicarContornosBarra() {
        aplicarContornoIcono(R.id.iconBackSettings)
        aplicarContornoIcono(R.id.iconRebootDevice)
        aplicarContornoIcono(R.id.iconCloseApp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ocultar SOLO la barra de estado (Hora, batería, señal)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_settings)

        // --- RECEPTOR DE CAMBIO DE COLOR ---
        val rootView = findViewById<View>(android.R.id.content)
        val sideBar = findViewById<View>(R.id.sideBarLayout)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                rootView.setBackgroundColor(Color.parseColor("#EDF4F8"))
                val colorInt = try {
                    Color.parseColor(newColor)
                } catch (_: Exception) {
                    Color.parseColor("#0F3E82")
                }
                sideBar?.setBackgroundColor(colorInt)
                tenirIconosMenu(colorInt)
                // Los iconos de la barra son blancos con contorno: no dependen del color.
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val savedColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", "#0F3E82")
                ?: "#0F3E82"
        } else {
            prefs.getString("BackgroundColor", "#0F3E82")
                ?: "#0F3E82"
        }
        val themeColorInt = try {
            Color.parseColor(savedColorHex)
        } catch (_: Exception) {
            Color.parseColor("#0F3E82")
        }

        rootView.setBackgroundColor(Color.parseColor("#EDF4F8"))
        sideBar?.setBackgroundColor(themeColorInt)
        tenirIconosMenu(themeColorInt)
        // ------------------------------------------------

        // Iconos de la barra con contorno oscuro (funciona sobre cualquier color de fondo)
        aplicarContornosBarra()

        // 0.1 Botón: Reiniciar Equipo
        findViewById<View>(R.id.btnRebootDevice)?.setOnClickListener {
            reiniciarDispositivo()
        }

        // 0.2 Botón: Cerrar Aplicación
        findViewById<View>(R.id.btnCloseApp)?.setOnClickListener {
            cerrarAplicacion()
        }

        // 1. Botón Regresar (Barra azul izquierda)
        findViewById<View>(R.id.btnBackSettings)?.setOnClickListener {
            finish()
        }

        // 1.1 Botón: Ajustes básicos
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

        // 6. Botón: Base de datos de pacientes
        findViewById<LinearLayout>(R.id.btnPatientTable)?.setOnClickListener {
            startActivity(Intent(this, PatientTableActivity::class.java))
        }

        // 7. Botón: Base de datos de Resultados
        findViewById<LinearLayout>(R.id.btnResultTable)?.setOnClickListener {
            startActivity(Intent(this, ResultTableActivity::class.java))
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
        val sideBar = findViewById<View>(R.id.sideBarLayout)
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val savedColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", "#0F3E82")
                ?: "#0F3E82"
        } else {
            prefs.getString("BackgroundColor", "#0F3E82")
                ?: "#0F3E82"
        }
        val themeColorInt = try {
            Color.parseColor(savedColorHex)
        } catch (_: Exception) {
            Color.parseColor("#0F3E82")
        }
        sideBar?.setBackgroundColor(themeColorInt)
        tenirIconosMenu(themeColorInt)

        // Reaplicar contornos (por si el tema cambió el color de fondo)
        aplicarContornosBarra()
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