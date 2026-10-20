package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Parámetros de medición: hoy solo controla cuántas derivaciones de ECG usar
 * (6 o 12) al presionar "Comenzar medición" en la pantalla de mediciones, para
 * no tener que preguntarlo cada vez con un popup.
 */
class MeasurementParametersActivity : BaseActivity() {

    private lateinit var sideBar: View
    private lateinit var rgEcgDerivaciones: RadioGroup
    private lateinit var rbEcg6: RadioButton
    private lateinit var rbEcg12: RadioButton
    private lateinit var btnGuardar: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement_parameters)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.themeAccentBar)
        rgEcgDerivaciones = findViewById(R.id.rgEcgDerivaciones)
        rbEcg6 = findViewById(R.id.rbEcg6)
        rbEcg12 = findViewById(R.id.rbEcg12)
        btnGuardar = findViewById(R.id.btnGuardarParametros)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnGuardar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                btnGuardar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val derivacionesActuales = devicePrefs.getInt(TestElementsActivity.PREF_ECG_DERIVACIONES, 6)
        if (derivacionesActuales == 12) rbEcg12.isChecked = true else rbEcg6.isChecked = true

        findViewById<View>(R.id.btnBackMeasurementParameters).setOnClickListener { finish() }

        btnGuardar.setOnClickListener {
            val derivaciones = if (rbEcg12.isChecked) 12 else 6
            devicePrefs.edit().putInt(TestElementsActivity.PREF_ECG_DERIVACIONES, derivaciones).apply()
            Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
