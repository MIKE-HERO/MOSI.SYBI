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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class PasswordSettingsActivity : BaseActivity() {

    companion object {
        private const val TAG = "PasswordSettings"
    }

    private lateinit var switchEnablePassword: Switch
    private lateinit var layoutPasswordFields: LinearLayout
    private lateinit var editPassword: EditText
    private lateinit var editConfirmPassword: EditText
    private lateinit var btnSavePassword: Button

    private lateinit var sideBarLayout: View

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("new_color")?.let { aplicarColorTema(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_password_settings)

        // Vistas
        sideBarLayout = findViewById(R.id.sideBarLayout)
        switchEnablePassword = findViewById(R.id.switchEnablePassword)
        layoutPasswordFields = findViewById(R.id.layoutPasswordFields)
        editPassword = findViewById(R.id.editPassword)
        editConfirmPassword = findViewById(R.id.editConfirmPassword)
        btnSavePassword = findViewById(R.id.btnSavePassword)

        // Registrar receiver de tema
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Aplicar color guardado
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        aplicarColorTema(savedColor)

        // Botón regresar
        findViewById<View>(R.id.btnBackPasswordSettings).setOnClickListener { finish() }

        // --- CARGAR ESTADO GUARDADO ---
        val passwordEnabled = prefs.getBoolean("PasswordEnabled", false)
        switchEnablePassword.isChecked = passwordEnabled
        layoutPasswordFields.visibility = if (passwordEnabled) View.VISIBLE else View.GONE

        // --- SWITCH ---
        switchEnablePassword.setOnCheckedChangeListener { _, isChecked ->
            layoutPasswordFields.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (!isChecked) {
                prefs.edit().putBoolean("PasswordEnabled", false).apply()
                Toast.makeText(this, "Contraseña desactivada", Toast.LENGTH_SHORT).show()
            }
        }

        // --- BOTÓN GUARDAR ---
        btnSavePassword.setOnClickListener {
            val pass = editPassword.text.toString()
            val confirm = editConfirmPassword.text.toString()

            if (pass.length < 6) {
                Toast.makeText(this, "La contraseña debe tener mínimo 6 dígitos", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (!pass.matches(Regex("^[0-9]+$"))) {
                Toast.makeText(this, "La contraseña solo puede contener números", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (pass != confirm) {
                Toast.makeText(this, "Las contraseñas no coinciden", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putBoolean("PasswordEnabled", true)
                .putString("ConfigPassword", pass)
                .apply()

            Toast.makeText(this, "✅ Contraseña guardada correctamente", Toast.LENGTH_SHORT).show()

            editPassword.text.clear()
            editConfirmPassword.text.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver)
        } catch (_: Exception) { }
    }

    // ============================================================
    // TEMA (barra lateral + botón + switch)
    // ============================================================
    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            val colorStateList = ColorStateList.valueOf(colorInt)

            // Barra lateral
            sideBarLayout.setBackgroundColor(colorInt)

            // Botón guardar
            btnSavePassword.backgroundTintList = colorStateList

            // ✅ Switch: thumb (bolita) y track (riel) siempre del color del tema
            switchEnablePassword.thumbTintList = colorStateList
            switchEnablePassword.trackTintList = colorStateList

        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color: ${e.message}")
        }
    }
}