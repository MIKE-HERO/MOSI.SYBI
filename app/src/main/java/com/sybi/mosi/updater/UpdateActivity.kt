package com.sybi.mosi.updater

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
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
import com.sybi.mosi.admin.PermisosAdmin
import com.sybi.mosi.integration.MosiApiClient
import com.sybi.mosi.integration.MosiSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pantalla protegida que acompaña la descarga, validacion e instalacion del APK. */
class UpdateActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UpdateActivity"
        const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        const val EXTRA_VERSION_TAG = "extra_version_tag"
        const val EXTRA_FORCE_REINSTALL = "extra_force_reinstall"
        const val EXTRA_SHA256 = "extra_sha256"
        const val EXTRA_UPDATE_ID = "extra_update_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"

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
    private lateinit var tvDetail: TextView
    private lateinit var tvStepDownload: TextView
    private lateinit var tvStepVerify: TextView
    private lateinit var tvStepInstall: TextView
    private lateinit var pbRing: ProgressBar
    private lateinit var pbLinear: ProgressBar
    private lateinit var updateManager: UpdateManager

    private var downloadUrl = ""
    private var versionTag = ""
    private var forceReinstall = false
    private var sha256 = ""
    private var updateId = ""
    private var requestId = ""
    private var retryCount = 0
    private var updateRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        setContentView(R.layout.activity_update)

        updateManager = UpdateManager(this)
        readIntent(intent)
        bindViews()
        showInitialState()
        applyThemeColor()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Navegacion bloqueada durante la actualizacion")
            }
        })
        iniciarProcesoActualizacion()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!updateRunning) {
            readIntent(intent)
            showInitialState()
            iniciarProcesoActualizacion()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun readIntent(intent: Intent) {
        downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL).orEmpty()
        versionTag = intent.getStringExtra(EXTRA_VERSION_TAG) ?: "1.0"
        forceReinstall = intent.getBooleanExtra(EXTRA_FORCE_REINSTALL, false)
        sha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty()
        updateId = intent.getStringExtra(EXTRA_UPDATE_ID).orEmpty()
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
    }

    private fun bindViews() {
        tvPercent = findViewById(R.id.tvUpdatePercent)
        tvStatus = findViewById(R.id.tvUpdateStatus)
        tvVersion = findViewById(R.id.tvUpdateVersion)
        tvWarning = findViewById(R.id.tvUpdateWarning)
        tvDetail = findViewById(R.id.tvUpdateDetail)
        tvStepDownload = findViewById(R.id.tvStepDownload)
        tvStepVerify = findViewById(R.id.tvStepVerify)
        tvStepInstall = findViewById(R.id.tvStepInstall)
        pbRing = findViewById(R.id.pbUpdateRing)
        pbLinear = findViewById(R.id.pbUpdateLinear)
    }

    private fun showInitialState() {
        tvVersion.text = "Versión $versionTag"
        tvStatus.text = "Preparando actualización"
        tvDetail.text = "Validando el paquete asignado a esta cabina."
        tvWarning.text = "Mantenga el equipo encendido y conectado a internet."
        tvWarning.setTextColor(Color.parseColor("#334E68"))
        tvPercent.text = "0%"
        pbLinear.progress = 0
        pbRing.visibility = View.VISIBLE
    }

    private fun applyThemeColor() {
        val saved = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("BackgroundColor", "#176FA3") ?: "#176FA3"
        val color = runCatching { Color.parseColor(saved) }.getOrDefault(Color.parseColor("#176FA3"))
        pbRing.indeterminateTintList = ColorStateList.valueOf(color)
    }

    private fun iniciarProcesoActualizacion() {
        if (updateRunning) return
        updateRunning = true
        lifecycleScope.launch(Dispatchers.IO) {
            if (downloadUrl.isBlank()) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Buscando actualización"
                    tvDetail.text = "Consultando el canal seguro de MOSI."
                }
                val release = updateManager.fetchReleases().firstOrNull()
                if (release == null) {
                    withContext(Dispatchers.Main) {
                        showFailure("No se encontró una actualización disponible.")
                    }
                    updateRunning = false
                    return@launch
                }
                downloadUrl = release.downloadUrl
                versionTag = release.tagName
                sha256 = release.sha256
                updateId = release.updateId
                requestId = release.requestId
                withContext(Dispatchers.Main) { tvVersion.text = "Versión $versionTag" }
            }

            withContext(Dispatchers.Main) { showDownloading() }
            val downloadedFile = updateManager.downloadApk(downloadUrl, sha256) { percent, _, _ ->
                runOnUiThread {
                    if (percent >= 0) {
                        pbLinear.isIndeterminate = false
                        pbLinear.progress = percent
                        tvPercent.text = "$percent%"
                    } else {
                        pbLinear.isIndeterminate = true
                        tvPercent.text = "--"
                    }
                }
            }

            if (downloadedFile == null || !downloadedFile.exists()) {
                handleDownloadFailure()
                return@launch
            }

            reportEvent("descarga")
            withContext(Dispatchers.Main) { showVerifying() }
            delay(650)
            withContext(Dispatchers.Main) { showInstalling() }

            MosiSecureStore.guardarActualizacionPendiente(
                this@UpdateActivity, updateId, versionTag, requestId
            )
            val started = updateManager.installApkAuto(downloadedFile, forceReinstall)
            if (!started) {
                val detail = updateManager.lastInstallError
                    ?: "No se pudo iniciar la instalación de Android"
                reportEvent("error", "$detail; device_owner=${PermisosAdmin.esPropietario(this@UpdateActivity)}")
                withContext(Dispatchers.Main) { showFailure(detail) }
                updateRunning = false
            } else {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Aplicando actualización"
                    tvDetail.text = "Android está validando e instalando la nueva versión."
                    tvWarning.text = "MOSI se reiniciará automáticamente al finalizar."
                }
            }
        }
    }

    private suspend fun handleDownloadFailure() {
        reportEvent("error", "La descarga fallo o el SHA-256 no coincide")
        retryCount++
        if (retryCount < 3) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "Reconectando con el servidor"
                tvDetail.text = "La descarga se reintentará automáticamente."
                tvWarning.text = "Intento ${retryCount + 1} de 3. Mantenga la conexión activa."
            }
            delay(5000)
            updateRunning = false
            iniciarProcesoActualizacion()
        } else {
            withContext(Dispatchers.Main) {
                showFailure("La descarga falló o el archivo no superó la validación de seguridad.")
            }
            updateRunning = false
        }
    }

    private fun showDownloading() {
        tvStatus.text = "Descargando actualización"
        tvDetail.text = "Recibiendo la versión $versionTag desde el canal seguro."
        tvWarning.text = "Mantenga el equipo encendido y conectado a internet."
        setSteps(download = "#176FA3", verify = "#9FB3C8", install = "#9FB3C8")
        pbLinear.progress = 0
    }

    private fun showVerifying() {
        tvStatus.text = "Verificando integridad"
        tvDetail.text = "Validando firma, versión y huella SHA-256."
        tvWarning.text = "El paquete fue descargado. No apague el equipo."
        setSteps(download = "#16794A", verify = "#176FA3", install = "#9FB3C8")
        pbLinear.isIndeterminate = false
        pbLinear.progress = 100
        tvPercent.text = "100%"
    }

    private fun showInstalling() {
        tvStatus.text = "Instalando actualización"
        tvDetail.text = "Android está reemplazando la aplicación de forma segura."
        tvWarning.text = "MOSI se reiniciará automáticamente al finalizar."
        setSteps(download = "#16794A", verify = "#16794A", install = "#176FA3")
    }

    private fun showFailure(detail: String) {
        pbRing.visibility = View.INVISIBLE
        pbLinear.isIndeterminate = false
        pbLinear.progress = 0
        tvStatus.text = "No fue posible actualizar"
        tvDetail.text = detail
        tvWarning.text = "Revise la firma del APK, la conexión y el modo Device Owner."
        tvWarning.setTextColor(Color.parseColor("#B42318"))
        tvPercent.text = "ERROR"
    }

    private fun setSteps(download: String, verify: String, install: String) {
        tvStepDownload.setTextColor(Color.parseColor(download))
        tvStepVerify.setTextColor(Color.parseColor(verify))
        tvStepInstall.setTextColor(Color.parseColor(install))
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
