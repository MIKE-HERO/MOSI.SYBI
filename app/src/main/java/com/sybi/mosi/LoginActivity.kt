package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class LoginActivity : BaseActivity() {

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
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val returnToHome = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

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
            animateTabChange(true)
        }
        tabConsulta.setOnClickListener {
            isMedicionSelected = false
            animateTabChange(false)
        }

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)

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

        visibleOptions.forEach(::setupCardInteraction)

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
        btnGuestAccess.setOnClickListener {
            val intent = Intent(this, MeasurementActivity::class.java)
            startActivity(intent)
        }

        btnRegister.setOnClickListener {
            val faceEnabled = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                .getBoolean("login_face", true)
            val destination = if (faceEnabled) FaceRegistrationActivity::class.java else RegisterActivity::class.java
            startActivity(Intent(this, destination).apply {
                putExtra("capture_before_form", faceEnabled)
                putExtra("photo_step_completed", !faceEnabled)
            })
        }
    }

    // ✅ RECEPTOR PARA ACTUALIZAR LA VISIBILIDAD DEL BOTÓN DE INVITADO
    override fun onResume() {
        super.onResume()
        updateGuestButtonVisibility()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        inactivityHandler.removeCallbacks(returnToHome)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(returnToHome)
        inactivityHandler.postDelayed(returnToHome, 60_000L)
    }

    private fun updateGuestButtonVisibility() {
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val guestEnabled = userPrefs.getBoolean("guest_enable", true)

        // ✅ Solo se muestra en la pestaña de Medición si está activado
        if (isMedicionSelected && guestEnabled) {
            btnGuestAccess.visibility = View.VISIBLE
            (btnGuestAccess.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                marginEnd = 10.dp
            }.also { btnGuestAccess.layoutParams = it }
            (btnRegister.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                width = 0
                marginStart = 10.dp
            }.also { btnRegister.layoutParams = it }
        } else {
            btnGuestAccess.visibility = View.GONE
            (btnRegister.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 0f
                width = 390.dp
                marginStart = 0
            }.also { btnRegister.layoutParams = it }
        }
    }

    private fun selectTab(isMedicion: Boolean) {
        val theme = Color.parseColor(currentColor)
        if (isMedicion) {
            tabMedicion.background = roundedBackground(theme, 24f)
            tvMedicion.setTextColor(Color.WHITE)
            tabConsulta.background = null
            tvConsulta.setTextColor(Color.parseColor("#757575"))
        } else {
            tabConsulta.background = roundedBackground(theme, 24f)
            tvConsulta.setTextColor(Color.WHITE)
            tabMedicion.background = null
            tvMedicion.setTextColor(Color.parseColor("#757575"))
        }
        updateGuestButtonVisibility()
    }

    private fun animateTabChange(isMedicion: Boolean) {
        optionContainer.animate().alpha(0f).scaleX(0.975f).scaleY(0.975f).setDuration(110L)
            .withEndAction {
                selectTab(isMedicion)
                optionContainer.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(190L).start()
            }.start()
    }

    private fun setupCardInteraction(card: View) {
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.97f).scaleY(0.97f).translationZ(2.dp.toFloat())
                    .setDuration(90L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).translationZ(0f)
                    .setDuration(150L).start()
            }
            false
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        val cardBorder = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
        listOf(optionPhone, optionFace, optionIc).forEach { card ->
            card.background = GradientDrawable().apply {
                cornerRadius = 24.dp.toFloat()
                setColor(Color.WHITE)
                setStroke(2.dp, cardBorder)
            }
        }

        btnGuestAccess.background = roundedBackground(color, 18f)
        btnRegister.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, ColorUtils.getTintedColor(color, 0.18f))
        ).apply { cornerRadius = 18.dp.toFloat() }

        // Las ilustraciones 3D conservan sus materiales y color original.
        ivPhone.imageTintList = null
        ivFace.imageTintList = null
        ivIc.imageTintList = null

        selectTab(isMedicionSelected)
    }

    private fun roundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun lightenColor(color: String, factor: Float = 0.8f): String {
        val c = Color.parseColor(color)
        val r = (Color.red(c) + (255 - Color.red(c)) * factor).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * factor).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * factor).toInt()
        return String.format("#%02X%02X%02X", r, g, b)
    }
}
