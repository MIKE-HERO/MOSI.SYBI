package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.updater.GitHubRelease
import com.sybi.mosi.updater.UpdateManager
import kotlinx.coroutines.launch

class DeviceInfoActivity : BaseActivity() {

    companion object {
        private const val TAG = "DeviceInfoActivity"

        const val PREF_DEVICE_MODEL = "device_model_custom"
        const val PREF_DEVICE_SERIAL = "device_serial_custom"
        const val PREF_CABINA_NUMBER = "cabina_number"
        const val PREF_CLIENTE = "cliente_id"
        const val PREF_CLIENTE_NOMBRE = "cliente_nombre"
        const val PREF_SUCURSAL = "sucursal_id"
        const val PREF_SUCURSAL_NOMBRE = "sucursal_nombre"

        fun getModelo(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_DEVICE_MODEL, "") ?: ""
        }

        fun getSerie(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_DEVICE_SERIAL, "") ?: ""
        }

        fun getCabina(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_CABINA_NUMBER, "") ?: ""
        }

        fun getCliente(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_CLIENTE, "") ?: ""
        }

        fun getSucursal(context: Context): String {
            val prefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return prefs.getString(PREF_SUCURSAL, "") ?: ""
        }
    }

    private lateinit var etModelo: EditText
    private lateinit var etSerie: EditText

    private lateinit var sideBar: View

    // ── Actualizador ──
    private lateinit var tvSoftwareVersion: TextView
    private lateinit var btnCheckUpdates: Button
    private lateinit var spnVersions: Spinner
    private lateinit var btnInstallSelected: Button
    private lateinit var pbDownloadProgress: ProgressBar
    private lateinit var tvDownloadStatus: TextView

    private lateinit var updateManager: UpdateManager
    private var fetchedReleases: List<GitHubRelease> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        updateManager = UpdateManager(this)

        sideBar = findViewById(R.id.sideBarLayout)
        etModelo = findViewById(R.id.etDeviceModel)
        etSerie = findViewById(R.id.etDeviceSerial)

        // ── Vistas de actualización ──
        tvSoftwareVersion = findViewById(R.id.tvSoftwareVersion)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        spnVersions = findViewById(R.id.spnVersions)
        btnInstallSelected = findViewById(R.id.btnInstallSelected)
        pbDownloadProgress = findViewById(R.id.pbDownloadProgress)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)

        // ── Receptor de cambio de color ──
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    sideBar.setBackgroundColor(Color.parseColor(newColor))
                    btnInstallSelected.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val appPrefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = appPrefs.getString("BackgroundColor", "#0F3E82")!!
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnInstallSelected.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))

        // ── Botón regresar ──
        findViewById<View>(R.id.btnBackDeviceInfo).setOnClickListener {
            finish()
        }

        // ── Cargar valores guardados de hardware ──
        loadSavedValues()

        // ── Mostrar versión del software ──
        showCurrentSoftwareVersion()

        // ── Cargar releases del repositorio ──
        loadRepositoryReleases()



        // ── Botón Buscar Actualizaciones (Manual/Última) ──
        btnCheckUpdates.setOnClickListener {
            lifecycleScope.launch {
                var releases = fetchedReleases
                if (releases.isEmpty()) {
                    releases = updateManager.fetchReleases()
                    fetchedReleases = releases
                }

                if (releases.isNotEmpty()) {
                    val latestRelease = releases[0]
                    val pInfo = try {
                        packageManager.getPackageInfo(packageName, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error obteniendo versión de app: ${e.message}")
                        null
                    }
                    val installedVersion = pInfo?.versionName?.trim()?.removePrefix("v")?.removePrefix("V") ?: ""
                    val latestVersion = latestRelease.tagName.trim().removePrefix("v").removePrefix("V")

                    if (installedVersion.isNotEmpty() && latestVersion.isNotEmpty() && installedVersion == latestVersion) {
                        Toast.makeText(
                            this@DeviceInfoActivity,
                            "Ya tienes instalada la versión más reciente (${latestRelease.tagName})",
                            Toast.LENGTH_LONG
                        ).show()
                        tvDownloadStatus.visibility = View.VISIBLE
                        tvDownloadStatus.text = "✅ Ya tienes instalada la versión más reciente (${latestRelease.tagName})."
                        return@launch
                    }

                    startApkDownload(latestRelease.downloadUrl, "Última versión (${latestRelease.tagName})")
                } else {
                    startApkDownload(UpdateManager.LATEST_APK_URL, "Última versión disponible")
                }
            }
        }

        // ── Botón Instalar versión seleccionada ──
        btnInstallSelected.setOnClickListener {
            if (fetchedReleases.isEmpty()) {
                Toast.makeText(this, "No hay versiones disponibles para instalar", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val position = spnVersions.selectedItemPosition
            if (position in fetchedReleases.indices) {
                val selected = fetchedReleases[position]

                val pInfo = try {
                    packageManager.getPackageInfo(packageName, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "Error obteniendo versión de app: ${e.message}")
                    null
                }
                val installedVersion = pInfo?.versionName?.trim()?.removePrefix("v")?.removePrefix("V") ?: ""
                val selectedVersion = selected.tagName.trim().removePrefix("v").removePrefix("V")

                if (installedVersion.isNotEmpty() && selectedVersion.isNotEmpty() && installedVersion == selectedVersion) {
                    Toast.makeText(
                        this@DeviceInfoActivity,
                        "Ya tienes instalada la versión ${selected.tagName}",
                        Toast.LENGTH_LONG
                    ).show()
                    tvDownloadStatus.visibility = View.VISIBLE
                    tvDownloadStatus.text = "✅ Ya tienes instalada la versión ${selected.tagName}."
                    return@setOnClickListener
                }

                startApkDownload(selected.downloadUrl, "Versión ${selected.tagName}")
            } else {
                Toast.makeText(this, "Por favor seleccione una versión válida", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showCurrentSoftwareVersion() {
        val pInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo versión de app: ${e.message}")
            null
        }

        val versionName = pInfo?.versionName ?: "1.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo?.longVersionCode ?: 1
        } else {
            @Suppress("DEPRECATION")
            pInfo?.versionCode ?: 1
        }

        tvSoftwareVersion.text = "Versión del software: v$versionName (Build $versionCode)"
    }

    private fun loadRepositoryReleases() {
        lifecycleScope.launch {
            fetchedReleases = updateManager.fetchReleases()
            if (fetchedReleases.isNotEmpty()) {
                val tagList = fetchedReleases.map { "${it.tagName} (${it.name})" }
                val adapter = ArrayAdapter(this@DeviceInfoActivity, android.R.layout.simple_spinner_item, tagList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter

                spnVersions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selected = fetchedReleases[position]
                        tvSoftwareVersion.text = "Versión del software: ${selected.tagName}"
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                tvSoftwareVersion.text = "Versión del software: ${fetchedReleases[0].tagName}"
            } else {
                val defaultList = listOf("v1.0.0 (Sin conexión / Por defecto)")
                val adapter = ArrayAdapter(this@DeviceInfoActivity, android.R.layout.simple_spinner_item, defaultList)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter
                tvSoftwareVersion.text = "Versión del software: v1.0.0"
            }
        }
    }

    private fun startApkDownload(downloadUrl: String, versionLabel: String) {
        lifecycleScope.launch {
            setUiDownloading(true)
            tvDownloadStatus.text = "Iniciando descarga de $versionLabel..."

            val downloadedFile = updateManager.downloadApk(downloadUrl) { percent, bytesDownloaded, _ ->
                runOnUiThread {
                    if (percent >= 0) {
                        pbDownloadProgress.isIndeterminate = false
                        pbDownloadProgress.progress = percent
                        tvDownloadStatus.text = "Descargando $versionLabel: $percent%"
                    } else {
                        pbDownloadProgress.isIndeterminate = true
                        tvDownloadStatus.text = "Descargando... (${bytesDownloaded / 1024} KB)"
                    }
                }
            }

            setUiDownloading(false)

            if (downloadedFile != null && downloadedFile.exists()) {
                tvDownloadStatus.text = "✅ Descarga completada. Abriendo instalador..."
                val installed = updateManager.installApk(downloadedFile)
                if (!installed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(
                        this@DeviceInfoActivity,
                        "Por favor autorice la instalación de aplicaciones desconocidas en los ajustes e intente de nuevo.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                tvDownloadStatus.visibility = View.VISIBLE
                tvDownloadStatus.text = "❌ Error al descargar la versión especificada."
                Toast.makeText(
                    this@DeviceInfoActivity,
                    "No se pudo descargar el archivo APK desde el repositorio.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setUiDownloading(isDownloading: Boolean) {
        btnCheckUpdates.isEnabled = !isDownloading
        btnInstallSelected.isEnabled = !isDownloading
        spnVersions.isEnabled = !isDownloading

        if (isDownloading) {
            pbDownloadProgress.visibility = View.VISIBLE
            pbDownloadProgress.progress = 0
            tvDownloadStatus.visibility = View.VISIBLE
        }
    }

    private fun loadSavedValues() {
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        val modeloGuardado = prefs.getString(PREF_DEVICE_MODEL, "")
        val modeloDefault = "${Build.MANUFACTURER} ${Build.MODEL}".uppercase()
        etModelo.setText(if (modeloGuardado.isNullOrEmpty()) modeloDefault else modeloGuardado)

        val serieGuardada = prefs.getString(PREF_DEVICE_SERIAL, "")
        val serieDefault = getDeviceSerial()
        etSerie.setText(if (serieGuardada.isNullOrEmpty()) serieDefault else serieGuardada)
    }

    @Suppress("DEPRECATION")
    private fun getDeviceSerial(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.provider.Settings.Secure.getString(
                    contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )?.uppercase() ?: "DESCONOCIDO"
            } else {
                Build.SERIAL.uppercase()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo serie: ${e.message}")
            "DESCONOCIDO"
        }
    }

    // ==========================================
    // GUARDAR
    // ==========================================
    private fun saveDeviceInfo() {
        val modelo = etModelo.text.toString().trim()
        val serie = etSerie.text.toString().trim()

        if (modelo.isEmpty()) {
            Toast.makeText(this, "Ingrese el modelo del dispositivo", Toast.LENGTH_SHORT).show()
            etModelo.requestFocus()
            return
        }
        if (serie.isEmpty()) {
            Toast.makeText(this, "Ingrese el número de serie", Toast.LENGTH_SHORT).show()
            etSerie.requestFocus()
            return
        }

        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        prefs.edit()
            .putString(PREF_DEVICE_MODEL, modelo)
            .putString(PREF_DEVICE_SERIAL, serie)
            .apply()

        Log.d(TAG, "💾 Guardado: modelo=$modelo, serie=$serie")
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        saveDeviceInfoSilently()
    }

    private fun saveDeviceInfoSilently() {
        val modelo = etModelo.text.toString().trim()
        val serie = etSerie.text.toString().trim()

        if (modelo.isNotEmpty() && serie.isNotEmpty()) {
            val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_DEVICE_MODEL, modelo)
                .putString(PREF_DEVICE_SERIAL, serie)
                .apply()
            Log.d(TAG, "💾 Guardado automático: modelo=$modelo, serie=$serie")
        }
    }
}
