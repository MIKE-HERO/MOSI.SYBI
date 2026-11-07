package com.sybi.mosi.updater

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sybi.mosi.R
import com.sybi.mosi.integration.MosiApiClient
import com.sybi.mosi.integration.MosiSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de actualización ininterrumpida sin botones.
 * Muestra el progreso de descarga de la versión y la barra/anillo de carga.
 * Bloquea la salida o retroceso hasta que la actualización finaliza.
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdateActivity"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_VERSION_TAG = "extra_version_tag"
        const val EXTRA_FORCE_REINSTALL = "extra_force_reinstall"
        const val EXTRA_SHA256 = "extra_sha256"
        const val EXTRA_UPDATE_ID = "extra_update_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        /**
         * @param forceReinstall si es true, permite reinstalar la MISMA versión
         *        y también instalar versiones ANTERIORES (downgrade).
         *        Si es false, solo se permite actualizar a una versión más nueva.
         */
        fun start(
            context: Context,
            downloadUrl: String,
            versionTag: String,
            forceReinstall: Boolean = false,
            sha256: String = "",
            updateId: String = "",
            requestId: String = ""
        ) {
            val intent = Intent(context, UpdateActivity::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_VERSION_TAG, versionTag)
                putExtra(EXTRA_FORCE_REINSTALL, forceReinstall)
                putExtra(EXTRA_SHA256, sha256)
                putExtra(EXTRA_UPDATE_ID, updateId)
                putExtra(EXTRA_REQUEST_ID, requestId)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        }
    }

    private lateinit var tvPercent: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvWarning: TextView
    private lateinit var pbRing: ProgressBar
    private lateinit var updateManager: UpdateManager

    private var downloadUrl: String = ""
    private var versionTag: String = ""
    private var forceReinstall: Boolean = false
    private var sha256: String = ""
    private var updateId: String = ""
    private var requestId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantener la pantalla encendida y sin barras del sistema
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_update)

        updateManager = UpdateManager(this)

        downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: ""
        versionTag = intent.getStringExtra(EXTRA_VERSION_TAG) ?: "v1.0"
        forceReinstall = intent.getBooleanExtra(EXTRA_FORCE_REINSTALL, false)
        sha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty()
        updateId = intent.getStringExtra(EXTRA_UPDATE_ID).orEmpty()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()

        tvPercent = findViewById(R.id.tvUpdatePercent)
        tvStatus = findViewById(R.id.tvUpdateStatus)
        tvVersion = findViewById(R.id.tvUpdateVersion)
        tvWarning = findViewById(R.id.tvUpdateWarning)
        pbRing = findViewById(R.id.pbUpdateRing)

        tvVersion.text = "Versión $versionTag"
        tvStatus.text = "Iniciando descarga de actualización..."
        tvPercent.text = "0%"

        applyThemeColor()

        // Bloquear completamente la tecla o gesto Atrás
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Intento de navegación hacia atrás bloqueado durante la actualización")
            }
        })

        iniciarProcesoActualizacion()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyThemeColor() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColorHex = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        val themeColor = try {
            Color.parseColor(savedColorHex)
        } catch (_: Exception) {
            Color.parseColor("#0F3E82")
        }

        pbRing.indeterminateTintList = android.content.res.ColorStateList.valueOf(themeColor)
        tvPercent.setTextColor(themeColor)
    }

    private fun iniciarProcesoActualizacion() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (downloadUrl.isBlank()) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Consultando canal de actualizaciones MOSI..."
                }
                val releases = updateManager.fetchReleases()
                if (releases.isNotEmpty()) {
                    downloadUrl = releases[0].downloadUrl
                    versionTag = releases[0].tagName
                    sha256 = releases[0].sha256
                    updateId = releases[0].updateId
                    requestId = releases[0].requestId
                    withContext(Dispatchers.Main) {
                        tvVersion.text = "Versión $versionTag"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "No hay actualización asignada o no hay conexión"
                        tvWarning.text = "No se pudo conectar con el servidor. Reintentando en breve..."
                    }
                    delay(5000)
                    iniciarProcesoActualizacion()
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                tvStatus.text = "Descargando actualización..."
                tvWarning.text = "⚠️ Descargando archivos. Por favor no cierre la app ni apague el equipo."
            }

            val downloadedFile = updateManager.downloadApk(downloadUrl, sha256) { percent, _, _ ->
                runOnUiThread {
                    if (percent >= 0) {
                        tvPercent.visibility = View.VISIBLE
                        tvPercent.text = "$percent%"
                    } else {
                        tvPercent.visibility = View.GONE
                    }
                }
            }

            if (downloadedFile != null && downloadedFile.exists()) {
                reportEvent("descarga")
                withContext(Dispatchers.Main) {
                    tvPercent.visibility = View.GONE
                    tvStatus.text = "Instalando actualización..."
                    tvWarning.text = "⚙️ Instalando actualización. No cierre la app ni apague el equipo."
                }

                delay(1000)

                // ✅ Se pasa forceReinstall para permitir reinstalar misma versión y downgrade
                MosiSecureStore.guardarActualizacionPendiente(this@UpdateActivity, updateId, versionTag, requestId)
                val exito = updateManager.installApkAuto(downloadedFile, forceReinstall)
                if (!exito) {
                    reportEvent(
                        "error",
                        updateManager.lastInstallError
                            ?: "No se pudo iniciar PackageInstaller; device_owner=${com.sybi.mosi.admin.PermisosAdmin.esPropietario(this@UpdateActivity)}"
                    )
                    Log.e(TAG, "Error al iniciar el proceso de instalación")
                }
            } else {
                reportEvent("error", "La descarga falló o el SHA-256 no coincide")
                withContext(Dispatchers.Main) {
                    tvStatus.text = "❌ Error al descargar el archivo"
                    tvWarning.text = "Falló la descarga. Reintentando en 5 segundos..."
                }
                delay(5000)
                iniciarProcesoActualizacion()
            }
        }
    }

    private fun reportEvent(event: String, detail: String? = null) {
        if (updateId.isBlank() || !MosiSecureStore.estaActivado(this)) return
        runCatching {
            MosiApiClient(this).reportarEventoActualizacion(
                event = event,
                updateId = updateId,
                version = versionTag,
                requestId = requestId,
                detail = detail
            )
        }.onFailure { Log.w(TAG, "No se pudo reportar evento '$event': ${it.message}") }
    }
}