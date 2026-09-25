package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DeviceInfoActivity : BaseActivity() {

    companion object {
        private const val TAG = "DeviceInfoActivity"

        const val PREF_DEVICE_MODEL = "device_model_custom"
        const val PREF_DEVICE_SERIAL = "device_serial_custom"
        const val PREF_CABINA_NUMBER = "cabina_number"
        const val PREF_CLIENTE = "cliente_id"
        const val PREF_CLIENTE_NOMBRE = "cliente_nombre"
        const val PREF_SUCURSAL = "sucursal_id"
        const val PREF_SUCURSAL_NOMBRE = "sucursal_nombre"

        fun getModelo(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_DEVICE_MODEL, "") ?: ""
        }

        fun getSerie(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_DEVICE_SERIAL, "") ?: ""
        }

        fun getCabina(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_CABINA_NUMBER, "") ?: ""
        }

        fun getCliente(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_CLIENTE, "") ?: ""
        }

        fun getSucursal(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_SUCURSAL, "") ?: ""
        }
    }

    private lateinit var etModelo: EditText
    private lateinit var etSerie: EditText
    private lateinit var btnGuardar: Button
    private lateinit var sideBar: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.sideBarLayout)
        etModelo = findViewById(R.id.etDeviceModel)
        etSerie = findViewById(R.id.etDeviceSerial)
        btnGuardar = findViewById(R.id.btnGuardarDeviceInfo)

        // ── Receptor de cambio de color ──
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    sideBar.setBackgroundColor(Color.parseColor(newColor))
                    btnGuardar.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82")!!
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnGuardar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))

        // ── Botón regresar ──
        findViewById<View>(R.id.btnBackDeviceInfo).setOnClickListener {
            saveDeviceInfo()
            finish()
        }

        // ── Cargar valores guardados ──
        loadSavedValues()

        // ── Botón guardar ──
        btnGuardar.setOnClickListener {
            saveDeviceInfo()
        }
    }

    private fun loadSavedValues() {
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        val modeloGuardado = prefs.getString(PREF_DEVICE_MODEL, "")
        val modeloDefault = "${Build.MANUFACTURER} ${Build.MODEL}".uppercase()
        etModelo.setText(if (modeloGuardado.isNullOrEmpty()) modeloDefault else modeloGuardado)

        val serieGuardada = prefs.getString(PREF_DEVICE_SERIAL, "")
        val serieDefault = getDeviceSerial()
        etSerie.setText(if (serieGuardada.isNullOrEmpty()) serieDefault else serieGuardada)
    }

    @Suppress("DEPRECATION")
    private fun getDeviceSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )?.uppercase() ?: "DESCONOCIDO"
            } else {
                Build.SERIAL.uppercase()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo serie: ${e.message}")
            "DESCONOCIDO"
        }
    }

    // ==========================================
    // GUARDAR
    // ==========================================
    private fun saveDeviceInfo() {
        val modelo = etModelo.text.toString().trim()
        val serie = etSerie.text.toString().trim()

        if (modelo.isEmpty()) {
            Toast.makeText(this, "Ingrese el modelo del dispositivo", Toast.LENGTH_SHORT).show()
            etModelo.requestFocus()
            return
        }
        if (serie.isEmpty()) {
            Toast.makeText(this, "Ingrese el número de serie", Toast.LENGTH_SHORT).show()
            etSerie.requestFocus()
            return
        }

        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        prefs.edit()
            .putString(PREF_DEVICE_MODEL, modelo)
            .putString(PREF_DEVICE_SERIAL, serie)
            .apply()

        Log.d(TAG, "💾 Guardado: modelo=$modelo, serie=$serie")
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()
    }
}