package com.sybi.mosi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Actividad que gestiona el inicio de la consulta de Telemedicina
 * usando Custom Tabs en Firefox (o navegador por defecto), garantizando
 * el correcto funcionamiento del micrófono y videollamada WebRTC.
 */
class TelemedicineActivity : BaseActivity() {

    private lateinit var topBar: View
    private lateinit var btnEndCall: ImageView
    private lateinit var btnOpenCallFirefox: Button
    private lateinit var btnOpenCallDefault: Button
    private lateinit var btnExitCall: Button
    private lateinit var tvTelemedicineStatus: TextView

    private var idUsuarioWeb: Int = 0
    private var autoLaunched = false
    private var customTabLaunched = false
    private var hasBeenStopped = false

    companion object {
        private const val TAG = "TelemedicineActivity"
        const val EXTRA_ID_USUARIO_WEB = "id_usuario_web"

        private const val TELEMEDICINE_BASE_URL = "https://www.sybiml.com/telemedicina/"
        private const val REQUEST_PERMISSIONS_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_telemedicine)

        // ✅ Recibir id_usuario_web
        idUsuarioWeb = intent.getIntExtra(EXTRA_ID_USUARIO_WEB, 0)
        Log.d(TAG, "📥 id_usuario_web recibido: $idUsuarioWeb")

        if (idUsuarioWeb <= 0) {
            Toast.makeText(this, "id_usuario_web inválido", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // ✅ Verificar que telemedicina está habilitada
        if (!TelemedicineSettingsActivity.isTelemedicineEnabled(this)) {
            Toast.makeText(this, "Telemedicina no está habilitada en la configuración", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        topBar = findViewById(R.id.topBar)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnOpenCallFirefox = findViewById(R.id.btnOpenCallFirefox)
        btnOpenCallDefault = findViewById(R.id.btnOpenCallDefault)
        btnExitCall = findViewById(R.id.btnExitCall)
        tvTelemedicineStatus = findViewById(R.id.tvTelemedicineStatus)

        // --- RECEPTOR DE CAMBIO DE TEMA ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    aplicarColorTema(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        aplicarColorTema(savedColor)

        btnEndCall.setOnClickListener { finishCall() }
        btnExitCall.setOnClickListener { finishCall() }

        btnOpenCallFirefox.setOnClickListener {
            abrirConsulta(preferFirefox = true)
        }

        btnOpenCallDefault.setOnClickListener {
            abrirConsulta(preferFirefox = false)
        }

        actualizarEstadoNavegador()
        verificarPermisosYComenzar()
    }

    override fun onStop() {
        super.onStop()
        if (customTabLaunched) {
            hasBeenStopped = true
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val ok = VideoLoopRemote.setMuted(this@TelemedicineActivity, true)
            if (!ok) {
                Log.w(TAG, "No se pudo silenciar el video en " +
                        VideoLoopRemote.getSavedHostPort(this@TelemedicineActivity))
            }
        }
        if (customTabLaunched && hasBeenStopped) {
            Log.d(TAG, "🔚 Regresando de CustomTabs (llamada finalizada / 'X' pulsada) -> Redirigiendo a MainActivity")
            finishCall()
        }
    }

    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            topBar.setBackgroundColor(colorInt)
            btnOpenCallFirefox.backgroundTintList = android.content.res.ColorStateList.valueOf(colorInt)
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color de tema: ${e.message}")
        }
    }

    private fun actualizarEstadoNavegador() {
        val firefoxInstalled = CustomTabsHelper.isFirefoxInstalled(this)
        if (firefoxInstalled) {
            tvTelemedicineStatus.text = "✅ Firefox instalado. Se usará para la consulta de telemedicina."
            tvTelemedicineStatus.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvTelemedicineStatus.text = "⚠️ Firefox no está instalado. Se usará el navegador por defecto."
            tvTelemedicineStatus.setTextColor(Color.parseColor("#E65100"))
        }
    }

    private fun verificarPermisosYComenzar() {
        val permisos = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (faltantes.isNotEmpty()) {
            Log.d(TAG, "⚠️ Permisos faltantes: $faltantes. Solicitando...")
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            lanzarConsultaInicial()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                lanzarConsultaInicial()
            } else {
                Toast.makeText(this, "Se requieren permisos de cámara y micrófono para la consulta", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun lanzarConsultaInicial() {
        if (!autoLaunched) {
            autoLaunched = true
            abrirConsulta(preferFirefox = true)
        }
    }

    private fun abrirConsulta(preferFirefox: Boolean) {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val idMachine = devicePrefs.getString("cabina_number", "1")
            ?.trim()
            .orEmpty()
            .ifEmpty { "1" }

        val baseUrl = TelemedicineSettingsActivity.getBaseUrl(this)
        val cleanBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val separator = if (cleanBaseUrl.contains("?")) "&" else "?"

        val url = "${cleanBaseUrl}${separator}id_usuario=$idUsuarioWeb&idMachine=$idMachine"
        Log.d(TAG, "🚀 Abriendo videollamada en CustomTabs: $url (preferFirefox=$preferFirefox)")

        customTabLaunched = true
        CustomTabsHelper.openUrl(this, url, preferFirefox = preferFirefox)
    }

    private fun finishCall() {
        Log.d(TAG, "🔚 Finalizando consulta de telemedicina")
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val ok = VideoLoopRemote.setMuted(appContext, false)
            if (!ok) {
                Log.w(TAG, "No se pudo des-silenciar el video en " +
                        VideoLoopRemote.getSavedHostPort(appContext))
            }
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.setMuted(appContext, false)
        }
    }
}
