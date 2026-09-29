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
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class ReportSettingsActivity : BaseActivity() {

    companion object {
        const val PREF_ALLOW_PRINT = "allow_print_results"
        const val PREF_ALLOW_EMAIL = "allow_email_results"
    }

    private val allSwitches = mutableListOf<Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report_settings)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.sideBarLayout)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                applySwitchTint(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        findViewById<View>(R.id.btnBackReportSettings).setOnClickListener { finish() }

        // Switches ligados a DevicePrefs (o los que uses para resultados)
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        setupSwitch(R.id.switchEnablePrint, PREF_ALLOW_PRINT, prefs)
        setupSwitch(R.id.switchEnableEmail, PREF_ALLOW_EMAIL, prefs)

        applySwitchTint(Color.parseColor(savedColor))
    }

    private fun setupSwitch(switchId: Int, prefKey: String, prefs: android.content.SharedPreferences) {
        val sw = findViewById<Switch>(switchId)
        allSwitches.add(sw)
        sw.isChecked = prefs.getBoolean(prefKey, true) // por defecto activado
        sw.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefKey, isChecked).apply()
        }
    }

    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}