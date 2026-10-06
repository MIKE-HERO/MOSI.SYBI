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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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

    private lateinit var gestureDetector: GestureDetector
    private var currentColor: String = "#0F3E82"

    // Efectos de "pantalla viva" en reposo
    private lateinit var shineOverlay: ShineSweepView
    private lateinit var imgLogo: ImageView
    private lateinit var rootLayout: View
    private var breathingAnimator: ObjectAnimator? = null

    // 🔥 Variables para los receivers (para poder desregistrarlos)
    private var deviceUpdateReceiver: BroadcastReceiver? = null
    private var colorReceiver: BroadcastReceiver? = null
    private var logoReceiver: BroadcastReceiver? = null
    private var titleReceiver: BroadcastReceiver? = null
    private var connectionReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ PRECARGAR CAMERAX EN SEGUNDO PLANO
        precargarCameraX()

        // Solicitar permisos al iniciar la app
        solicitarPermisos()

        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        rootLayout = findViewById(R.id.mainRootLayout)
        imgLogo = findViewById(R.id.imgLogo)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnSettings = findViewById<View>(R.id.btnSettings)
        val touchInterceptor = findViewById<View>(R.id.touchInterceptor)
        shineOverlay = findViewById(R.id.shineOverlay)

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
                    rootLayout.setBackgroundColor(Color.parseColor(newColor))
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

        // Nota: el ciclo del modo multicolor ahora lo maneja BaseActivity para toda la app;
        // esta pantalla solo necesita su colorReceiver (arriba) para repintar rootLayout.

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val defaultColor = "#0F3E82"
        currentColor = appPrefs.getString("BackgroundColor", defaultColor) ?: defaultColor
        rootLayout.setBackgroundColor(Color.parseColor(currentColor))

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
    private fun precargarCameraX() {
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

        // EditText con estilo
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Ingrese la contraseña"
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
            setPadding(50, 40, 50, 40)
            setBackgroundResource(R.drawable.bg_input_rounded) // fondo redondeado
            textSize = 18f
            setTextColor(android.graphics.Color.BLACK)
        }

        // Contenedor con padding para que no se pegue a los bordes
        val container = android.widget.FrameLayout(this).apply {
            setPadding(40, 20, 40, 0)
            addView(input, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🔒 Contraseña requerida")
            .setMessage("Ingrese la contraseña para acceder a la configuración")
            .setView(container)
            .setPositiveButton("Aceptar") { dialog, _ ->
                val entered = input.text.toString()
                if (entered == savedPassword) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else {
                    Toast.makeText(this, "❌ Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        // Botones con color personalizado (opcional)
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(android.graphics.Color.parseColor("#0F3E82"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(android.graphics.Color.GRAY)
        }

        setupDialogKeyboardBehavior(dialog)
        dialog.show()
    }

    // ==========================================
    // EFECTOS DE "PANTALLA VIVA" (respiración del logo + brillo diagonal)
    // ==========================================
    override fun onResume() {
        super.onResume()
        shineOverlay.startSweeping()
        startBreathingEffect()
        // El ciclo del modo multicolor ya lo arranca BaseActivity.onResume(); esta pantalla
        // solo necesita su colorReceiver (registrado arriba) para pintarse con cada color.
    }

    override fun onPause() {
        super.onPause()
        shineOverlay.stopSweeping()
        stopBreathingEffect()
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

}