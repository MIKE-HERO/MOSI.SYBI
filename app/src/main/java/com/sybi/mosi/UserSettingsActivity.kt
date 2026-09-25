package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class UserSettingsActivity : BaseActivity() {

    companion object {
        const val PREF_FACE = "login_face"
        const val PREF_PHONE = "login_phone"
        const val PREF_IC = "login_ic"
        const val PREF_COMM_PHONE = "comm_phone"
        const val PREF_COMM_EMAIL = "comm_email"
        const val PREF_GUEST_ENABLE = "guest_enable"
    }

    private val allSwitches = mutableListOf<Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_settings)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.sideBarLayout)

        // --- RECEPTOR DE CAMBIO DE COLOR ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    sideBar.setBackgroundColor(Color.parseColor(newColor))
                    applySwitchTint(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82")
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        // --- BOTÓN REGRESAR ---
        findViewById<View>(R.id.btnBackUserSettings).setOnClickListener {
            finish()
        }

        // --- CONFIGURAR SWITCHES ---
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

        setupLoginSwitch(R.id.switchLoginFace, PREF_FACE, prefs)
        setupLoginSwitch(R.id.switchLoginPhone, PREF_PHONE, prefs)
        setupLoginSwitch(R.id.switchLoginIc, PREF_IC, prefs)
        setupSwitch(R.id.switchCommPhone, PREF_COMM_PHONE, prefs)
        setupSwitch(R.id.switchCommEmail, PREF_COMM_EMAIL, prefs)
        setupSwitch(R.id.switchGuestEnable, PREF_GUEST_ENABLE, prefs)

        // Aplicar el color inicial a los switches
        val initialColor = Color.parseColor(savedColor)
        applySwitchTint(initialColor)
    }

    private fun setupSwitch(switchId: Int, prefKey: String, prefs: android.content.SharedPreferences) {
        val switchView = findViewById<Switch>(switchId)
        allSwitches.add(switchView)
        switchView.isChecked = prefs.getBoolean(prefKey, true)
        switchView.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()

            if (prefKey == PREF_GUEST_ENABLE) {
                val updateIntent = Intent("UPDATE_GUEST_BUTTON")
                LocalBroadcastManager.getInstance(this).sendBroadcast(updateIntent)
            }
        }
    }

    private fun setupLoginSwitch(switchId: Int, prefKey: String, prefs: android.content.SharedPreferences) {
        val switchView = findViewById<Switch>(switchId)
        allSwitches.add(switchView)
        switchView.isChecked = prefs.getBoolean(prefKey, true)

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                val allLoginKeys = listOf(PREF_FACE, PREF_PHONE, PREF_IC)
                var activeCount = 0
                for (key in allLoginKeys) {
                    if (prefs.getBoolean(key, true)) {
                        activeCount++
                    }
                }
                if (activeCount <= 1) {
                    Toast.makeText(this, "Debe tener activo al menos un método de inicio de sesión", Toast.LENGTH_LONG).show()
                    switchView.isChecked = true
                    return@setOnCheckedChangeListener
                }
            }
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
    }

    // --- APLICAR TINTE ORIGINAL A TODOS LOS SWITCHES ---
    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            // Aplicar el color original al thumb y al track
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}