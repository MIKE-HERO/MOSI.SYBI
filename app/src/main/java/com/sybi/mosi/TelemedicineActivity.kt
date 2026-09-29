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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    }

    private var idUsuarioWeb: Int = 0
    private lateinit var tvEstado: TextView
    private var onPermissionsGrantedAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemedicine)

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
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.restaurarSilencio(appContext, TAG)
        }
        super.onDestroy()
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
        val exito = CustomTabsHelper.openUrl(this, uri)
        if (!exito) {
            tvEstado.text = "No se pudo abrir la consulta en el navegador."
            Toast.makeText(this, "No se encontró un navegador compatible instalado", Toast.LENGTH_LONG).show()
        }
    }

    private fun irAInicio() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
