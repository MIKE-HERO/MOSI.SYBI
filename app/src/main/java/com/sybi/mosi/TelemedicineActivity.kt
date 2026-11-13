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
import com.sybi.mosi.integration.MosiTelemedicineSession
import com.sybi.mosi.network.ConnectivityStatusProvider
import com.sybi.mosi.network.NetworkSpeedHelper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var telemedicinaMonitorJob: Job? = null

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
    }

    override fun onDestroy() {
        detenerMonitoreoRedTeleconsulta()
        TelemedicineOptimizer.restaurarEstadoNormal()
        if (isFinishing && !isChangingConfigurations && reportedStart && !reportedEnd) {
            reportedEnd = true
            MosiTelemedicineSession.cancel(this, "La pantalla de telemedicina se cerró sin usar el botón Salir")
        }
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.restaurarSilencio(appContext, TAG)
        }
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
        TelemedicineOptimizer.optimizarParaTelemedicina()
        val uri = urlConsulta().toString()
        Log.d(TAG, "🌐 Abriendo consulta en CustomTabs: $uri")
        DailyReportManager.onTelemedicineStarted(this, idUsuarioWeb)

        iniciarMonitoreoRedTeleconsulta()

        // efimero=true: cada consulta pide permiso de cámara/mic de nuevo, sin reusar uno
        // guardado de una sesión anterior (ver CustomTabsHelper.openUrl).
        val exito = CustomTabsHelper.openUrl(this, uri, efimero = true)
        if (exito) {
            if (!reportedStart) {
                reportedStart = true
                MosiTelemedicineSession.start(this, sessionId)
            }
        } else {
            detenerMonitoreoRedTeleconsulta()
            MosiTelemedicineSession.error(this, sessionId, "No se encontró un navegador compatible")
            tvEstado.text = "No se pudo abrir la consulta en el navegador."
            Toast.makeText(this, "No se encontró un navegador compatible instalado", Toast.LENGTH_LONG).show()
        }
    }

    private fun iniciarMonitoreoRedTeleconsulta() {
        telemedicinaMonitorJob?.cancel()
        telemedicinaMonitorJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "🚀 [TELECONSULTA] Iniciando diagnóstico continuo de red (Cada 5s) durante la teleconsulta...")
            var iteracion = 0
            while (isActive) {
                try {
                    iteracion++
                    val connStatus = ConnectivityStatusProvider.get(applicationContext)
                    val latenciaServer = NetworkSpeedHelper.medirLatenciaSocket("www.sybiml.com", 443, 2000)
                    val latenciaDns = NetworkSpeedHelper.medirLatenciaSocket("8.8.8.8", 53, 2000)

                    val estadoCalidad = when {
                        !connStatus.internetAvailable || (latenciaServer < 0 && latenciaDns < 0) -> "CRÍTICO (SIN INTERNET / SERVIDOR INALCANZABLE)"
                        latenciaServer > 800 || latenciaDns > 800 -> "DEMASIADO LENTO (LATENCIA ALTA)"
                        latenciaServer > 400 || latenciaDns > 400 -> "REGULAR"
                        else -> "ESTABLE / EXCELENTE"
                    }

                    val transportInfo = buildString {
                        append(connStatus.transport.uppercase())
                        if (connStatus.wifiConnected && !connStatus.ssid.isNullOrBlank()) {
                            append(" (${connStatus.ssid}")
                            if (connStatus.signalDbm != null) append(", ${connStatus.signalDbm}dBm")
                            append(")")
                        }
                    }

                    val latenciaServerTexto = if (latenciaServer >= 0) "$latenciaServer ms" else "Sin respuesta"
                    val latenciaDnsTexto = if (latenciaDns >= 0) "$latenciaDns ms" else "Sin respuesta"

                    Log.d(
                        TAG,
                        "📊 [DIAGNÓSTICO RED TELECONSULTA #$iteracion (Cada 5s)] " +
                                "Latencia Servidor API: $latenciaServerTexto | Latencia DNS (8.8.8.8): $latenciaDnsTexto | " +
                                "Transporte: $transportInfo | " +
                                "Estado: $estadoCalidad"
                    )

                    if (!connStatus.internetAvailable || (latenciaServer < 0 && latenciaDns < 0)) {
                        Log.e(
                            TAG,
                            "❌ [DIAGNÓSTICO RED TELECONSULTA] 🚨 CONEXIÓN PERDIDA: Se ha perdido la conexión a internet o al servidor durante la teleconsulta! " +
                                    "(Latencia Servidor: $latenciaServerTexto, DNS: $latenciaDnsTexto, Transporte: $transportInfo)"
                        )
                    } else if (latenciaServer > 800 || latenciaDns > 800) {
                        Log.w(
                            TAG,
                            "⚠️ [DIAGNÓSTICO RED TELECONSULTA] ⚠️ CONEXIÓN DEMASIADO LENTA durante teleconsulta. " +
                                    "Latencia Servidor: $latenciaServerTexto, DNS: $latenciaDnsTexto. " +
                                    "Esto provocará congelamiento de imagen, desincronización de audio o desconexión en la videollamada."
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Error en diagnóstico de red de teleconsulta: ${e.message}")
                }

                delay(5000)
            }
        }
    }

    private fun detenerMonitoreoRedTeleconsulta() {
        if (telemedicinaMonitorJob != null) {
            Log.d(TAG, "🛑 [TELECONSULTA] Deteniendo diagnóstico continuo de red de teleconsulta...")
            telemedicinaMonitorJob?.cancel()
            telemedicinaMonitorJob = null
        }
    }

    private fun irAInicio() {
        detenerMonitoreoRedTeleconsulta()
        TelemedicineOptimizer.restaurarEstadoNormal()
        if (reportedStart && !reportedEnd) {
            reportedEnd = true
            MosiTelemedicineSession.finish(this)
        }
        DailyReportManager.onTelemedicineEnded(this, idUsuarioWeb)
        AutoLogHelper.enviarLogFinTeleconsulta(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
}