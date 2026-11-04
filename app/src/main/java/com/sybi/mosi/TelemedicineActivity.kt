package com.sybi.mosi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.sybi.mosi.integration.MosiApiClient
import com.sybi.mosi.integration.MosiSecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Lanzador de la consulta de telemedicina.
 *
 * La consulta en sí (buscar médico, lista de espera, video/audio) corre en la aplicación Angular
 * que ya usan los pacientes desde un navegador de escritorio (assets/telemedicina en el monorepo),
 * no en un WebView embebido: ese WebView tiene bugs propios de este equipo con getUserMedia — la
 * cámara externa y el micrófono se colgaban sin resolver ni rechazar nunca — mientras que un navegador
 * completo no los tiene. Esta pantalla abre esa URL en Custom Tabs (utilizando CustomTabsHelper,
 * igual que las pruebas de cámara/micrófono de calibración) y ofrece un botón para reintentar.
 */
class TelemedicineActivity : BaseActivity() {

    companion object {
        private const val TAG = "TelemedicineActivity"
        const val EXTRA_ID_USUARIO_WEB = "id_usuario_web"
        private const val REQUEST_PERMISSIONS_CODE = 200
        private const val STATE_SESSION_ID = "mosi_telemedicine_session_id"
        private const val STATE_REPORTED_START = "mosi_telemedicine_reported_start"
        private const val STATE_REPORTED_END = "mosi_telemedicine_reported_end"
    }

    private var idUsuarioWeb: Int = 0
    private lateinit var tvEstado: TextView
    private var onPermissionsGrantedAction: (() -> Unit)? = null
    private var sessionId: String = UUID.randomUUID().toString()
    private var reportedStart = false
    private var reportedEnd = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemedicine)

        sessionId = savedInstanceState?.getString(STATE_SESSION_ID).orEmpty().ifBlank { UUID.randomUUID().toString() }
        reportedStart = savedInstanceState?.getBoolean(STATE_REPORTED_START) ?: false
        reportedEnd = savedInstanceState?.getBoolean(STATE_REPORTED_END) ?: false

        idUsuarioWeb = intent.getIntExtra(EXTRA_ID_USUARIO_WEB, 0)
        Log.d(TAG, "📥 id_usuario_web recibido: $idUsuarioWeb")

        if (idUsuarioWeb <= 0) {
            Toast.makeText(this, "id_usuario_web inválido", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!TelemedicineSettingsActivity.isTelemedicineEnabled(this)) {
            Toast.makeText(this, "Telemedicina no está habilitada en la configuración", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Cola de impresión remota de recetas. Se arranca aquí, con la consulta, y se detiene
        // en onDestroy. Durante la teleconsulta esta Activity está detenida (la consulta corre
        // en Custom Tabs) pero el Service sigue sondeando, que es justo cuando el médico
        // imprime. Sin impresora configurada no se arranca: no hay a dónde mandar nada, y así
        // los trabajos quedan 'pendiente' en el servidor en vez de consumirse con un ack.
        val printerIp = getSharedPreferences("DevicePrefs", MODE_PRIVATE)
            .getString(ReportSettingsActivity.PREF_PRINTER_IP, "")
            ?.trim()
        if (!printerIp.isNullOrBlank()) {
            PrintJobService.iniciar(this)
        }

        tvEstado = findViewById(R.id.tvTelemedicineEstado)
        findViewById<Button>(R.id.btnReintentarConsulta).setOnClickListener {
            verificarPermisosYComenzar { abrirConsulta() }
        }
        findViewById<Button>(R.id.btnSalirConsulta).setOnClickListener { irAInicio() }

        verificarPermisosYComenzar { abrirConsulta() }
    }

    override fun onResume() {
        super.onResume()
        // Se silencia VideoLoop mientras dure esta pantalla, solo si el usuario lo tenía con
        // sonido (ver VideoLoopRemote.silenciarMientras). Se vuelve a llamar en cada onResume
        // (p. ej. al volver del navegador), pero esa función ya es segura de llamar varias veces.
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.silenciarMientras(appContext, TAG)
        }

        // SerialService lee los puertos de los sensores de la cabina en hilos propios de forma
        // continua (sondeo cada 20ms por puerto, no es solo espera pasiva) -- ese dato no se usa
        // durante la consulta, solo se manda después, así que se detiene mientras dure la llamada
        // para no competirle CPU a Firefox en una tablet de 2GB. onCreate() de SerialService
        // reabre todos los puertos solo con que se vuelva a arrancar el servicio, así que es
        // seguro parar/reiniciar en cada entrada/salida de esta pantalla.
        stopService(Intent(this, SerialService::class.java))
    }

    override fun onDestroy() {
        PrintJobService.detener(this)
        if (!isChangingConfigurations && reportedStart && !reportedEnd) {
            reportedEnd = true
            reportarEventoMosi("cancelacion", "La pantalla de telemedicina se cerró sin usar el botón Salir")
        }
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.restaurarSilencio(appContext, TAG)
        }
        startService(Intent(this, SerialService::class.java))
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SESSION_ID, sessionId)
        outState.putBoolean(STATE_REPORTED_START, reportedStart)
        outState.putBoolean(STATE_REPORTED_END, reportedEnd)
        super.onSaveInstanceState(outState)
    }

    private fun urlConsulta(): Uri {
        val base = TelemedicineSettingsActivity.getBaseUrl(this).trimEnd('/')
        val idCabina = TelemedicineSettingsActivity.getCabina(this).trim().ifEmpty { "1" }
        return "$base/?id_usuario=$idUsuarioWeb&idMachine=$idCabina".toUri()
    }

    private fun verificarPermisosYComenzar(onGranted: () -> Unit) {
        onPermissionsGrantedAction = onGranted
        val permisos = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGrantedAction?.invoke()
            } else {
                Toast.makeText(this, "Se necesitan permisos de cámara y micrófono para la consulta de telemedicina", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Asegura que CameraX libere completamente el dispositivo USB de cámara de manera secuencial,
     * y sólo cuando ya se desvinculó (con un margen de 250ms para el HAL), abre CustomTabs en Firefox.
     */
    private fun abrirConsulta() {
        try {
            val future = ProcessCameraProvider.getInstance(applicationContext)
            future.addListener({
                try {
                    val provider = future.get()
                    provider.unbindAll()
                    Log.d(TAG, "📸 CameraX unbindAll() ejecutado secuencialmente ANTES de CustomTabs")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error al desvincular CameraX: ${e.message}")
                }

                // Margen de 250ms para que el HAL/driver de la cámara USB libere el descriptor
                window.decorView.postDelayed({
                    lanzarCustomTabs()
                }, 250)
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error al acceder a ProcessCameraProvider: ${e.message}")
            lanzarCustomTabs()
        }
    }

    private fun lanzarCustomTabs() {
        val uri = urlConsulta().toString()
        Log.d(TAG, "🌐 Abriendo consulta en CustomTabs: $uri")
        DailyReportManager.onTelemedicineStarted(this, idUsuarioWeb)
        // efimero=true: cada consulta pide permiso de cámara/mic de nuevo, sin reusar uno
        // guardado de una sesión anterior (ver CustomTabsHelper.openUrl).
        val exito = CustomTabsHelper.openUrl(this, uri, efimero = true)
        if (exito) {
            if (!reportedStart) {
                reportedStart = true
                reportarEventoMosi("inicio")
            }
        } else {
            reportarEventoMosi("error", "No se encontró un navegador compatible")
            tvEstado.text = "No se pudo abrir la consulta en el navegador."
            Toast.makeText(this, "No se encontró un navegador compatible instalado", Toast.LENGTH_LONG).show()
        }
    }

    private fun irAInicio() {
        if (reportedStart && !reportedEnd) {
            reportedEnd = true
            reportarEventoMosi("fin")
        }
        DailyReportManager.onTelemedicineEnded(this, idUsuarioWeb)
        AutoLogHelper.enviarLogFinConsulta(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun reportarEventoMosi(evento: String, detalle: String? = null) {
        val appContext = applicationContext
        if (!MosiSecureStore.estaActivado(appContext)) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                MosiApiClient(appContext).reportarEventoTelemedicina(
                    event = evento,
                    sessionId = sessionId,
                    detail = detalle
                )
            }.onFailure { error ->
                Log.w(TAG, "No se pudo reportar evento MOSI de telemedicina '$evento': ${error.message}")
            }
        }
    }
}
