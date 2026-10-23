package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton

class LoginActivity : BaseActivity() {

    private lateinit var btnGuestAccess: MaterialButton
    private lateinit var btnRegister: MaterialButton
    private lateinit var btnBackLogin: ImageButton
    private lateinit var tabMedicion: LinearLayout
    private lateinit var tabConsulta: LinearLayout
    private lateinit var tvMedicion: TextView
    private lateinit var tvConsulta: TextView
    private lateinit var optionContainer: LinearLayout
    private lateinit var optionPhone: LinearLayout
    private lateinit var optionFace: LinearLayout
    private lateinit var optionIc: LinearLayout

    private lateinit var ivPhone: ImageView
    private lateinit var ivFace: ImageView
    private lateinit var ivIc: ImageView

    private var currentColor: String = "#0F3E82"
    private var isMedicionSelected: Boolean = true

    // --- TEMPORIZADOR DE INACTIVIDAD (10 SEGUNDOS) ---
    private val inactivityHandler = Handler(Looper.getMainLooper())
    private val inactivityRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            android.util.Log.d(TAG, "⏰ 10 segundos de inactividad en LoginActivity. Regresando a MainActivity.")
            finish()
        }
    }

    companion object {
        private const val TAG = "LoginActivity"
        private const val INACTIVITY_TIMEOUT_MS = 10_000L
        private const val ID_USUARIO_WEB_INVITADO = 4721085
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        inactivityHandler.postDelayed(inactivityRunnable, INACTIVITY_TIMEOUT_MS)
    }

    private fun stopInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // --- Inicializar TODAS las vistas antes de usarlas en receivers/lambdas ---
        btnGuestAccess = findViewById(R.id.btnGuestAccess)
        btnRegister = findViewById(R.id.btnRegister)
        btnBackLogin = findViewById(R.id.btnBackLogin)
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

        // --- RECEPTOR DE CAMBIO DE COLOR ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                currentColor = newColor
                applyColorTheme(newColor)
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        currentColor = savedColor
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

        // ✅ Botón de invitado: si es Medición solicita edad/género, si es Consulta abre Telemedicina
        btnGuestAccess.setOnClickListener {
            if (isMedicionSelected) {
                mostrarDialogoDatosInvitado()
            } else {
                getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("guest_mode_enabled", true)
                    .apply()

                val intent = Intent(this, TelemedicineActivity::class.java).apply {
                    putExtra(TelemedicineActivity.EXTRA_ID_USUARIO_WEB, ID_USUARIO_WEB_INVITADO)
                }
                startActivity(intent)
            }
        }

        btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // ✅ Botón Regresar (esquina superior izquierda)
        btnBackLogin.setOnClickListener {
            finish()
        }
    }

    // ✅ RECEPTOR PARA ACTUALIZAR LA VISIBILIDAD DEL BOTÓN DE INVITADO E INICIAR TEMPORIZADOR
    override fun onResume() {
        super.onResume()
        updateGuestButtonVisibility()
        resetInactivityTimer()
        // Reaplicar el color por si cambió mientras estábamos fuera
        applyColorTheme(currentColor)
    }

    override fun onPause() {
        super.onPause()
        stopInactivityTimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetInactivityTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    private fun updateGuestButtonVisibility() {
        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val guestEnabled = userPrefs.getBoolean("guest_enable", true)

        // ✅ Se muestra tanto en la pestaña de Medición como en Consulta si está activado
        if (guestEnabled) {
            btnGuestAccess.visibility = View.VISIBLE
            (btnGuestAccess.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f
                width = 0
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

        // Reaplicar el color después de ajustar layouts (por si acaso)
        applyBottomButtonsColor(Color.parseColor(currentColor))
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

    /**
     * Aplica el color del tema a TODO el login:
     *  - borde de las tarjetas
     *  - tabs (medición / consulta)
     *  - botones inferiores (invitado / registrar) + sus iconos
     *  - botón regresar (tint)
     *  - iconos 3D se mantienen sin tint
     */
    private fun applyColorTheme(colorHex: String) {
        val color = try {
            Color.parseColor(colorHex)
        } catch (_: Exception) {
            Color.parseColor("#0F3E82")
        }

        // 1) Tarjetas con borde del color del tema
        val cardBorder = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
        listOf(optionPhone, optionFace, optionIc).forEach { card ->
            card.background = GradientDrawable().apply {
                cornerRadius = 24.dp.toFloat()
                setColor(Color.WHITE)
                setStroke(2.dp, cardBorder)
            }
        }

        // 2) Botones inferiores (fondo + iconos)
        applyBottomButtonsColor(color)

        // 3) Botón regresar: tint del color del tema
        btnBackLogin.setColorFilter(color)

        // 4) Los iconos 3D conservan sus materiales y color original
        ivPhone.imageTintList = null
        ivFace.imageTintList = null
        ivIc.imageTintList = null

        // 5) Tabs (por si el cambio de color ocurre estando en la pestaña actual)
        selectTab(isMedicionSelected)
    }

    /**
     * Aplica el color al fondo de los botones inferiores y blanco a sus iconos.
     * Usa backgroundTintList = null + un GradientDrawable concreto para que el color
     * SIEMPRE se refleje (MaterialButton ignora backgroundTintList si no hay drawable).
     */
    private fun applyBottomButtonsColor(color: Int) {
        // --- Botón invitado: color sólido del tema ---
        val solid = GradientDrawable().apply {
            cornerRadius = 18.dp.toFloat()
            setColor(color)
        }
        btnGuestAccess.backgroundTintList = null
        btnGuestAccess.background = solid
        btnGuestAccess.setTextColor(Color.WHITE)
        btnGuestAccess.iconTint = ColorStateList.valueOf(Color.WHITE)

        // --- Botón registrar: gradiente color -> color+18% ---
        val lighter = ColorUtils.getTintedColor(color, 0.18f)
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(color, lighter)
        ).apply { cornerRadius = 18.dp.toFloat() }
        btnRegister.backgroundTintList = null
        btnRegister.background = gradient
        btnRegister.setTextColor(Color.WHITE)
        btnRegister.iconTint = ColorStateList.valueOf(Color.WHITE)
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

    private fun getThemeColorInt(): Int {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val themeColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", currentColor)
                ?: currentColor
        } else {
            prefs.getString("BackgroundColor", currentColor)
                ?: currentColor
        }

        return try {
            Color.parseColor(themeColorHex)
        } catch (_: Exception) {
            try { Color.parseColor(currentColor) } catch (_: Exception) { Color.parseColor("#0F3E82") }
        }
    }

    private fun mostrarDialogoDatosInvitado() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_datos_invitado)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val themeColorInt = getThemeColorInt()

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val width = (screenWidth * 0.50).toInt()
        val height = (screenHeight * 0.80).toInt()

        dialog.window?.setLayout(width, height)
        dialog.window?.setGravity(android.view.Gravity.CENTER)

        val headerBar = dialog.findViewById<View>(R.id.headerConsentBar)
        if (headerBar != null) {
            val r22 = 22.dp.toFloat()
            val headerBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(themeColorInt)
                cornerRadii = floatArrayOf(
                    r22, r22,
                    r22, r22,
                    0f, 0f,
                    0f, 0f
                )
            }
            headerBar.background = headerBg
        }

        val btnConfirm = dialog.findViewById<Button>(R.id.btnConfirmDatos)
        btnConfirm.backgroundTintList = ColorStateList.valueOf(themeColorInt)

        val rbMasculino = dialog.findViewById<RadioButton>(R.id.rbMasculinoInvitado)
        val rbFemenino = dialog.findViewById<RadioButton>(R.id.rbFemeninoInvitado)
        val etEdad = dialog.findViewById<EditText>(R.id.etEdadInvitado)

        rbMasculino.buttonTintList = ColorStateList.valueOf(themeColorInt)
        rbFemenino.buttonTintList = ColorStateList.valueOf(themeColorInt)

        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDatos)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val edadText = etEdad.text.toString().trim()
            val edad = edadText.toIntOrNull()
            if (edad == null || edad !in 1..120) {
                Toast.makeText(this, "Por favor, ingrese una edad válida (1 - 120)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val genero = if (rbMasculino.isChecked) "M" else "F"

            dialog.dismiss()

            // ✅ Marcar modo invitado para que ResultsActivity lo detecte
            getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("guest_mode_enabled", true)
                .apply()

            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val birthYear = currentYear - edad
            val fechaNacimiento = "01/01/$birthYear"

            val intent = Intent(this, MeasurementActivity::class.java).apply {
                putExtra("id_usuario_web", ID_USUARIO_WEB_INVITADO)
                putExtra("genero", genero)
                putExtra("fecha_nacimiento", fechaNacimiento)
                putExtra("nombre", "Invitado")
            }
            startActivity(intent)
        }

        dialog.show()
        dialog.window?.setLayout(width, height)
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }
}