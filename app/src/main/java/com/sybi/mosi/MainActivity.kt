package com.sybi.mosi

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import java.io.File

class MainActivity : BaseActivity() {

    // La pantalla de inicio es la única que cicla el modo multicolor (con transición suave);
    // las demás pantallas se quedan en el color que ésta tenía al salir (ver BaseActivity).
    override fun debeAnimarMulticolor(): Boolean = true

    private lateinit var gestureDetector: GestureDetector
    private var currentColor: String = "#0F3E82"

    // Efectos de "pantalla viva" en reposo
    private lateinit var shineOverlay: ShineSweepView
    private lateinit var imgLogo: ImageView
    private lateinit var rootLayout: View
    private lateinit var settingsSurface: View
    private lateinit var bottomBarContainer: View
    private lateinit var medicalBackground: ImageView
    private var breathingAnimator: ObjectAnimator? = null

    // 🔥 Variables para los receivers (para poder desregistrarlos)
    private var deviceUpdateReceiver: BroadcastReceiver? = null
    private var colorReceiver: BroadcastReceiver? = null
    private var logoReceiver: BroadcastReceiver? = null
    private var titleReceiver: BroadcastReceiver? = null
    private var connectionReceiver: BroadcastReceiver? = null
    private var scaleReceiver: BroadcastReceiver? = null
    private var backgroundReceiver: BroadcastReceiver? = null

    // Control para evitar abrir LoginActivity múltiples veces por la misma pisada de báscula
    @Volatile private var isLaunchingLogin = false

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ PRECARGAR BIOMETRÍA DE PACIENTES Y CAMERAX EN SEGUNDO PLANO
        com.sybi.mosi.helpers.FaceBiometricsCache.preload(this)
        precargarCameraX()

        // Solicitar permisos al iniciar la app
        solicitarPermisos()

        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        rootLayout = findViewById(R.id.mainRootLayout)
        settingsSurface = findViewById(R.id.settingsSurface)
        bottomBarContainer = findViewById(R.id.bottomBarContainer)
        imgLogo = findViewById(R.id.imgLogo)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnSettings = findViewById<View>(R.id.btnSettings)
        val touchInterceptor = findViewById<View>(R.id.touchInterceptor)
        shineOverlay = findViewById(R.id.shineOverlay)
        medicalBackground = findViewById(R.id.medicalBackground)

        // --- INICIAR EL SERVICIO SERIAL AL ABRIR LA APP ---
        startService(Intent(this, SerialService::class.java))

        // --- RECEPTOR PARA ACTUALIZAR DISPOSITIVOS ---
        deviceUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateDeviceButtonsVisibility()
                val updateIntent = Intent("UPDATE_SERIAL_PORTS")
                LocalBroadcastManager.getInstance(this@MainActivity).sendBroadcast(updateIntent)
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            deviceUpdateReceiver!!,
            IntentFilter("ACTION_UPDATE_DEVICES")
        )

        // --- RECEPTOR PARA CAMBIO DE COLOR ---
        colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyThemeAccents(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            colorReceiver!!,
            IntentFilter("ACTION_UPDATE_THEME")
        )

        // --- RECEPTOR PARA CAMBIO DE LOGO ---
        logoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val logoPath = intent.getStringExtra("logo_path")
                if (logoPath != null) {
                    loadLogo(imgLogo, logoPath)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            logoReceiver!!,
            IntentFilter("ACTION_UPDATE_LOGO")
        )

        // --- RECEPTOR PARA CAMBIO DE TÍTULO ---
        titleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newTitle = intent.getStringExtra("main_title")
                if (newTitle != null) {
                    tvTitle?.text = newTitle
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            titleReceiver!!,
            IntentFilter("ACTION_UPDATE_TITLE")
        )

        applyMedicalBackground(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("MedicalBackground", "azul"))
        backgroundReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyMedicalBackground(intent.getStringExtra("background"))
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            backgroundReceiver!!,
            IntentFilter("ACTION_UPDATE_BACKGROUND")
        )

        // --- RECEPTOR PARA ESTADO DE CONEXIÓN ---
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val deviceKey = intent.getStringExtra("device_key") ?: return
                val status = intent.getStringExtra("status") ?: return
                val port = intent.getStringExtra("port") ?: ""
                Log.d(TAG, "📡 Dispositivo $deviceKey: $status (puerto $port)")
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            connectionReceiver!!,
            IntentFilter("DEVICE_CONNECTION_STATUS")
        )

        // --- RECEPTOR PARA DETECTAR PISADA DE BÁSCULA Y CAMBIAR A LOGIN ---
        scaleReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isLaunchingLogin) return

                val action = intent.action
                if (action == "ACTION_SCALE_STEP") {
                    Log.d(TAG, "⚖️ Pisada detectada en báscula (ACTION_SCALE_STEP), abriendo LoginActivity")
                    openLoginActivityFromScale()
                } else if (action == "SERIAL_RAW_DATA") {
                    val port = intent.getStringExtra("port")
                    val type = intent.getStringExtra("type")
                    val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                    val scalePort = devicePrefs.getString("device_altura_peso_port", "/dev/ttyS5") ?: "/dev/ttyS5"

                    if (type == "RX" && port == scalePort) {
                        Log.d(TAG, "⚖️ Trama serial de pisada recibida en $port, abriendo LoginActivity")
                        openLoginActivityFromScale()
                    }
                } else if (action == "DEVICE_DATA_RECEIVED") {
                    val type = intent.getStringExtra("type")
                    if (type == "ALTURA_PESO" || type == "ALTURA_PESO_STEP") {
                        Log.d(TAG, "⚖️ Datos de pisada de báscula recibidos ($type), abriendo LoginActivity")
                        openLoginActivityFromScale()
                    }
                }
            }
        }
        val scaleFilter = IntentFilter().apply {
            addAction("ACTION_SCALE_STEP")
            addAction("SERIAL_RAW_DATA")
            addAction("DEVICE_DATA_RECEIVED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scaleReceiver!!, scaleFilter)

        // Nota: el ciclo del modo multicolor ahora lo maneja BaseActivity para toda la app;
        // esta pantalla solo necesita su colorReceiver (arriba) para repintar rootLayout.

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val defaultColor = "#0F3E82"
        currentColor = appPrefs.getString("BackgroundColor", defaultColor) ?: defaultColor
        rootLayout.setBackgroundColor(Color.parseColor("#07182B"))
        applyThemeAccents(Color.parseColor(currentColor))

        // Cargar título guardado
        val defaultTitle = "MÓDULO DE SALUD INTEGRAL"
        val savedTitle = appPrefs.getString("AppTitle", defaultTitle) ?: defaultTitle
        tvTitle.text = savedTitle

        // Cargar logo guardado
        val logoPath = appPrefs.getString("LogoPath", null)
        if (logoPath != null) {
            loadLogo(imgLogo, logoPath)
        } else {
            // Logo por defecto si no hay ninguno guardado
            imgLogo.setImageResource(R.drawable.sybi_logo_blanco)
        }

        btnSettings.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val passwordEnabled = prefs.getBoolean("PasswordEnabled", false)

            if (passwordEnabled) {
                mostrarDialogoPassword()
            } else {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }

        updateDeviceButtonsVisibility()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                return true
            }
        })

        touchInterceptor.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        DeviceDefaults.ensureDefaultPorts(devicePrefs)
    }

    private fun applyMedicalBackground(key: String?) {
        val drawable = when (key) {
            "morado" -> R.drawable.bg_medical_morado
            "turquesa" -> R.drawable.bg_medical_turquesa
            else -> R.drawable.bg_medical_azul
        }
        medicalBackground.setImageResource(drawable)
    }

    // ==========================================
    // SOLICITAR PERMISOS (incluye almacenamiento)
    // ==========================================
    private fun solicitarPermisos() {
        // Si la app es propietaria del dispositivo, los permisos se conceden solos (sin diálogos)
        com.sybi.mosi.admin.PermisosAdmin.aplicar(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permisosNecesarios = mutableListOf<String>()

            // Cámara
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                permisosNecesarios.add(Manifest.permission.CAMERA)
            }

            // Micrófono
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                permisosNecesarios.add(Manifest.permission.RECORD_AUDIO)
            }

            // Almacenamiento (necesario para Android 7.1.2)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permisosNecesarios.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permisosNecesarios.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (permisosNecesarios.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permisosNecesarios.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "✅ Todos los permisos concedidos")
            }
        }
    }

    // ==========================================
    // PRECARGAR CAMERAX (usando addListener)
    // ==========================================
    /**
     * Antes de invocar CameraX directamente, se verifica con Camera2 si el hardware expone un
     * "lens facing" válido. En equipos donde no lo expone (algunos rk3288 con HAL de cámara
     * incompleto), CameraX internamente reintenta la validación 5 veces y se rinde, pero sin
     * lanzar excepción — así que no hay forma de detectarlo "atrapando el error" después. Esta
     * verificación es barata (una sola consulta de características, sin reintentos) y evita
     * repetir en cada arranque ~2.8s de reintentos garantizados a fallar.
     */
    private fun esCamaraUtilizable(): Boolean {
        return try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cameraManager.cameraIdList.any { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verificando disponibilidad de cámara: ${e.message}")
            false
        }
    }

    private fun precargarCameraX() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("camera_incompatible", false)) {
            Log.d(TAG, "⏭️ CameraX precarga omitida: cámara ya detectada como no utilizable en este equipo")
            return
        }
        if (!esCamaraUtilizable()) {
            Log.w(TAG, "⚠️ Cámara sin 'lens facing' válido: se omite la precarga de CameraX (hardware incompatible)")
            prefs.edit().putBoolean("camera_incompatible", true).apply()
            return
        }
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    future.get()
                    Log.d(TAG, "✅ CameraX precargado correctamente")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error al precargar CameraX: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando CameraX: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            var todosPermisosConcedidos = true
            for (resultado in grantResults) {
                if (resultado != PackageManager.PERMISSION_GRANTED) {
                    todosPermisosConcedidos = false
                    break
                }
            }

            if (todosPermisosConcedidos) {
                Log.d(TAG, "✅ Permisos concedidos")
            } else {
                Log.w(TAG, "⚠️ Algunos permisos denegados")
                Toast.makeText(
                    this,
                    "Se requieren permisos para el funcionamiento correcto",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ==========================================
    // CARGAR LOGO (PNG, JPG, WEBP, SVG)
    // ==========================================
    private fun loadLogo(imageView: ImageView, path: String) {
        try {
            val imageLoader = ImageLoader.Builder(imageView.context)
                .components {
                    add(SvgDecoder.Factory())
                }
                .build()

            val file = File(path)
            val data: Any = if (file.exists()) file else Uri.parse(path)

            val request = ImageRequest.Builder(imageView.context)
                .data(data)
                .target(imageView)
                .error(R.drawable.sybi_logo_blanco)
                .placeholder(R.drawable.sybi_logo_blanco)
                .build()

            imageLoader.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando logo: ${e.message}")
            imageView.setImageResource(R.drawable.sybi_logo_blanco)
        }
    }

    // ==========================================
    // DIÁLOGO DE CONTRASEÑA
    // ==========================================
    private fun mostrarDialogoPassword() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedPassword = prefs.getString("ConfigPassword", "111111") ?: "111111"

        // Obtener el color del tema según la preferencia de LogoSettings
        val isMulticolor = prefs.getBoolean("MulticolorEnabled", false)
        val themeColorHex = if (isMulticolor) {
            prefs.getString("MulticolorColorVivo", null)
                ?: prefs.getString("BackgroundColor", "#0F3E82")
                ?: "#0F3E82"
        } else {
            prefs.getString("BackgroundColor", currentColor)
                ?: currentColor
        }

        val themeColorInt = try {
            Color.parseColor(themeColorHex)
        } catch (_: Exception) {
            try { Color.parseColor(currentColor) } catch (_: Exception) { Color.parseColor("#0F3E82") }
        }

        var enteredPassword = ""

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_password_keypad)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // Aplicar color del tema al encabezado (esquinas superiores redondeadas)
        val headerBar = dialog.findViewById<View>(R.id.headerPasswordBar)
        if (headerBar != null) {
            val r16 = 16.dp.toFloat()
            val headerBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(themeColorInt)
                cornerRadii = floatArrayOf(
                    r16, r16, // top-left
                    r16, r16, // top-right
                    0f, 0f,   // bottom-right
                    0f, 0f    // bottom-left
                )
            }
            headerBar.background = headerBg
        }

        // Crear drawable para los botones con el color del tema y efecto ripple
        fun createButtonDrawable(): Drawable {
            val r8 = 8.dp.toFloat()
            val content = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(themeColorInt)
                cornerRadius = r8
            }
            val mask = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = r8
            }
            val rippleColor = ColorStateList.valueOf(Color.argb(51, 255, 255, 255))
            return RippleDrawable(rippleColor, content, mask)
        }

        // Aplicar fondo del tema a todos los botones del teclado
        val keyButtonIds = listOf(
            R.id.btnKey0, R.id.btnKey1, R.id.btnKey2, R.id.btnKey3, R.id.btnKey4,
            R.id.btnKey5, R.id.btnKey6, R.id.btnKey7, R.id.btnKey8, R.id.btnKey9,
            R.id.btnKeyConfirm
        )
        for (id in keyButtonIds) {
            dialog.findViewById<View>(id)?.background = createButtonDrawable()
        }

        val tvDisplay = dialog.findViewById<TextView>(R.id.tvPasswordDisplay)
        val btnBackspace = dialog.findViewById<ImageButton>(R.id.btnBackspacePassword)
        val btnClose = dialog.findViewById<ImageView>(R.id.btnClosePasswordDialog)

        fun updateDisplay() {
            tvDisplay.text = "● ".repeat(enteredPassword.length).trim()
        }

        val numericButtons = mapOf(
            R.id.btnKey0 to "0", R.id.btnKey1 to "1", R.id.btnKey2 to "2",
            R.id.btnKey3 to "3", R.id.btnKey4 to "4", R.id.btnKey5 to "5",
            R.id.btnKey6 to "6", R.id.btnKey7 to "7", R.id.btnKey8 to "8",
            R.id.btnKey9 to "9"
        )

        for ((id, digit) in numericButtons) {
            dialog.findViewById<View>(id)?.setOnClickListener {
                if (enteredPassword.length < 12) {
                    enteredPassword += digit
                    updateDisplay()
                }
            }
        }

        btnBackspace.setOnClickListener {
            if (enteredPassword.isNotEmpty()) {
                enteredPassword = enteredPassword.dropLast(1)
                updateDisplay()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.btnKeyConfirm)?.setOnClickListener {
            if (enteredPassword == savedPassword) {
                dialog.dismiss()
                startActivity(Intent(this, SettingsActivity::class.java))
            } else {
                Toast.makeText(this, "❌ Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                enteredPassword = ""
                updateDisplay()
            }
        }

        setupDialogKeyboardBehavior(dialog)
        if (!isFinishing && !isDestroyed) {
            dialog.show()
        }
    }

    private fun openLoginActivityFromScale() {
        if (isLaunchingLogin) return
        isLaunchingLogin = true

        Log.d(TAG, "⚖️ Abriendo LoginActivity desde pisada de báscula...")
        val intent = Intent(this@MainActivity, LoginActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ==========================================
    // EFECTOS DE "PANTALLA VIVA" (respiración del logo + brillo diagonal)
    // ==========================================
    override fun onResume() {
        super.onResume()
        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("MulticolorEnabled", false)) {
            val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
            currentColor = savedColor
            applyThemeAccents(Color.parseColor(savedColor))
        }
        if (::medicalBackground.isInitialized) {
            applyMedicalBackground(
                appPrefs.getString("MedicalBackground", "azul")
            )
        }
        shineOverlay.startSweeping()
        startBreathingEffect()

        // Restablecer flag para estar listo INMEDIATAMENTE para recibir pisadas
        isLaunchingLogin = false
    }

    override fun onPause() {
        super.onPause()
        shineOverlay.stopSweeping()
        stopBreathingEffect()

        // Deshabilitar detecciones en segundo plano mientras no estemos en primer plano
        isLaunchingLogin = true
    }

    /** Pulso suave y lento de escala en el logo, para dar sensación de "respiración". */
    private fun startBreathingEffect() {
        if (breathingAnimator != null) return
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f)
        breathingAnimator = ObjectAnimator.ofPropertyValuesHolder(imgLogo, scaleX, scaleY).apply {
            duration = 2600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreathingEffect() {
        breathingAnimator?.cancel()
        breathingAnimator = null
        imgLogo.scaleX = 1f
        imgLogo.scaleY = 1f
    }

    // ==========================================
    // ON DESTROY (desregistrar receivers)
    // ==========================================
    override fun onDestroy() {
        super.onDestroy()

        // 🔥 Desregistrar todos los receivers
        try {
            deviceUpdateReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            colorReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            logoReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            titleReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            connectionReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            scaleReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
            backgroundReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error desregistrando receivers: ${e.message}")
        }

        // Detener servicio serial
        val intent = Intent("RELEASE_SERIAL_PORTS")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        stopService(Intent(this, SerialService::class.java))
    }

    // ==========================================
    // ACTUALIZAR VISIBILIDAD DE BOTONES
    // ==========================================
    private fun updateDeviceButtonsVisibility() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        fun isDeviceActive(prefKey: String): Boolean {
            return devicePrefs.getBoolean(prefKey, true)
        }

        val navAlturaPeso = findViewById<LinearLayout>(R.id.navAlturaPeso)
        val navComposicion = findViewById<LinearLayout>(R.id.navComposicion)
        val navPresion = findViewById<LinearLayout>(R.id.navPresion)
        val navTemperatura = findViewById<LinearLayout>(R.id.navTemperatura)
        val navOxigeno = findViewById<LinearLayout>(R.id.navOxigeno)
        val navEcg = findViewById<LinearLayout>(R.id.navEcg)
        val navAzucar = findViewById<LinearLayout>(R.id.navAzucar)
        val navAcidoUrico = findViewById<LinearLayout>(R.id.navAcidoUrico)
        val navColesterol = findViewById<LinearLayout>(R.id.navColesterol)

        navAlturaPeso.visibility = if (isDeviceActive("device_altura_peso")) View.VISIBLE else View.GONE
        navComposicion.visibility = if (isDeviceActive("device_composicion")) View.VISIBLE else View.GONE
        navPresion.visibility = if (isDeviceActive("device_presion")) View.VISIBLE else View.GONE
        navTemperatura.visibility = if (isDeviceActive("device_temperatura")) View.VISIBLE else View.GONE
        navOxigeno.visibility = if (isDeviceActive("device_oxigeno")) View.VISIBLE else View.GONE
        navEcg.visibility = if (isDeviceActive("device_ecg")) View.VISIBLE else View.GONE
        navAzucar.visibility = if (isDeviceActive("device_azucar")) View.VISIBLE else View.GONE
        navAcidoUrico.visibility = if (isDeviceActive("device_acido_urico")) View.VISIBLE else View.GONE
        navColesterol.visibility = if (isDeviceActive("device_colesterol")) View.VISIBLE else View.GONE
    }

    /** Mantiene intacto el fondo medico y aplica el tema solo a detalles luminosos. */
    private fun applyThemeAccents(themeColor: Int) {
        val softColor = Color.argb(115, Color.red(themeColor), Color.green(themeColor), Color.blue(themeColor))

        settingsSurface.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(45, 255, 255, 255))
            setStroke(2.dp, softColor)
        }
        bottomBarContainer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(230, 12, 35, 61), Color.argb(242, 4, 17, 34))
        ).apply {
            cornerRadius = 24.dp.toFloat()
            setStroke(1.dp, Color.parseColor("#668BC8DC"))
        }
        val nav = bottomBarContainer as? LinearLayout ?: return
        for (index in 0 until nav.childCount) {
            val item = nav.getChildAt(index) as? LinearLayout ?: continue
            (item.getChildAt(0) as? ImageView)?.setColorFilter(
                ColorUtils.getTintedColor(themeColor, 0.55f),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
            // El color personalizado vive solo en la figura; cada celda permanece limpia.
            item.foreground = null
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

}
