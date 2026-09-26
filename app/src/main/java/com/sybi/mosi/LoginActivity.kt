package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LoginActivity : BaseActivity() {

    private lateinit var sideBar: View
    private lateinit var btnGuestAccess: Button
    private lateinit var btnRegister: Button
    private lateinit var tabMedicion: LinearLayout
    private lateinit var tabConsulta: LinearLayout
    private lateinit var tvMedicion: TextView
    private lateinit var tvConsulta: TextView
    private lateinit var optionContainer: LinearLayout
    private lateinit var optionPhone: LinearLayout
    private lateinit var optionFace: LinearLayout
    private lateinit var optionIc: LinearLayout

    private lateinit var ivPhone: android.widget.ImageView
    private lateinit var ivFace: android.widget.ImageView
    private lateinit var ivIc: android.widget.ImageView

    private var currentColor: String = "#0F3E82"
    private var isMedicionSelected: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.sideBarLayout)
        btnGuestAccess = findViewById(R.id.btnGuestAccess)
        btnRegister = findViewById(R.id.btnRegister)
        tabMedicion = findViewById(R.id.tabMedicion)
        tabConsulta = findViewById(R.id.tabConsulta)
        tvMedicion = findViewById(R.id.tvMedicion)
        tvConsulta = findViewById(R.id.tvConsulta)
        optionContainer = findViewById(R.id.optionContainer)
        optionPhone = findViewById(R.id.optionPhone)
        optionFace = findViewById(R.id.optionFace)
        optionIc = findViewById(R.id.optionIc)
        ivPhone = findViewById(R.id.ivPhone)
        ivFace  = findViewById(R.id.ivFace)
        ivIc    = findViewById(R.id.ivIc)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        selectTab(true)

        tabMedicion.setOnClickListener {
            isMedicionSelected = true
            selectTab(true)
        }
        tabConsulta.setOnClickListener {
            isMedicionSelected = false
            selectTab(false)
        }

        findViewById<View>(R.id.btnBackLogin).setOnClickListener { finish() }

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

        // ✅ OCULTAR BOTÓN DE INVITADO SI ESTÁ DESACTIVADO
        val guestEnabled = userPrefs.getBoolean("guest_enable", true)
        if (!guestEnabled) {
            btnGuestAccess.visibility = View.GONE
        }

        fun isEnabled(key: String) = userPrefs.getBoolean(key, true)

        if (!isEnabled("login_phone")) optionPhone.visibility = View.GONE
        if (!isEnabled("login_face")) optionFace.visibility = View.GONE
        if (!isEnabled("login_ic")) optionIc.visibility = View.GONE

        val visibleOptions = mutableListOf<View>()
        if (optionPhone.visibility == View.VISIBLE) visibleOptions.add(optionPhone)
        if (optionFace.visibility == View.VISIBLE) visibleOptions.add(optionFace)
        if (optionIc.visibility == View.VISIBLE) visibleOptions.add(optionIc)

        val count = visibleOptions.size
        if (count == 0) {
            Toast.makeText(this, "No hay métodos de acceso activos", Toast.LENGTH_SHORT).show()
            return
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val buttonSize = (screenWidth * 0.25).toInt()

        for (option in visibleOptions) {
            val params = option.layoutParams
            params.width = buttonSize
            params.height = buttonSize
            option.layoutParams = params
        }

        optionPhone.setOnClickListener {
            val intent = Intent(this, PhoneLoginActivity::class.java)
            val sessionType = if (isMedicionSelected) "measurement" else "telemedicine"
            intent.putExtra("session_type", sessionType)
            startActivity(intent)
        }

        optionFace.setOnClickListener {
            val intent = Intent(this, FaceLoginActivity::class.java)
            val sessionType = if (isMedicionSelected) "measurement" else "telemedicine"
            intent.putExtra("session_type", sessionType)
            startActivity(intent)
        }

        optionIc.setOnClickListener {
            val intent = Intent(this, IcCardLoginActivity::class.java)
            val sessionType = if (isMedicionSelected) "measurement" else "telemedicine"
            intent.putExtra("session_type", sessionType)
            startActivity(intent)
        }

        // ✅ Botón de invitado: va directo a mediciones sin login ni perfil
        if (guestEnabled) {
            btnGuestAccess.setOnClickListener {
                val intent = Intent(this, MeasurementActivity::class.java)
                startActivity(intent)
            }
        }

        btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    // ✅ RECEPTOR PARA ACTUALIZAR LA VISIBILIDAD DEL BOTÓN DE INVITADO
    override fun onResume() {
        super.onResume()
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val guestEnabled = userPrefs.getBoolean("guest_enable", true)

        if (guestEnabled) {
            btnGuestAccess.visibility = View.VISIBLE
        } else {
            btnGuestAccess.visibility = View.GONE
        }
    }

    private fun selectTab(isMedicion: Boolean) {
        if (isMedicion) {
            tabMedicion.setBackgroundColor(Color.WHITE)
            tvMedicion.setTextColor(Color.parseColor(currentColor))

            tabConsulta.setBackgroundColor(Color.parseColor("#E0E0E0"))
            tvConsulta.setTextColor(Color.parseColor("#757575"))
        } else {
            tabConsulta.setBackgroundColor(Color.WHITE)
            tvConsulta.setTextColor(Color.parseColor(currentColor))

            tabMedicion.setBackgroundColor(Color.parseColor("#E0E0E0"))
            tvMedicion.setTextColor(Color.parseColor("#757575"))
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        val lightColor = Color.parseColor(lightenColor(colorHex, 0.7f))
        val tint = android.content.res.ColorStateList.valueOf(color)

        sideBar.setBackgroundColor(color)
        btnGuestAccess.backgroundTintList = tint
        btnRegister.backgroundTintList = tint

        optionContainer.setBackgroundColor(lightColor)

        optionPhone.setBackgroundColor(lightColor)
        optionFace.setBackgroundColor(lightColor)
        optionIc.setBackgroundColor(lightColor)

        // ✅ Cambiar el color de los íconos al del tema
        ivPhone.imageTintList = tint
        ivFace.imageTintList = tint
        ivIc.imageTintList = tint

        selectTab(isMedicionSelected)
    }

    private fun lightenColor(color: String, factor: Float = 0.8f): String {
        val c = Color.parseColor(color)
        val r = (Color.red(c) + (255 - Color.red(c)) * factor).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * factor).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * factor).toInt()
        return String.format("#%02X%02X%02X", r, g, b)
    }
}