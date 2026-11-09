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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.updater.MosiRelease
import com.sybi.mosi.updater.UpdateActivity
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
    private lateinit var tvInstalledVersion: TextView
    private lateinit var tvSoftwareVersion: TextView
    private lateinit var tvGithubVersion: TextView
    private lateinit var btnCheckUpdates: Button
    private lateinit var btnUpdateGithub: Button
    private lateinit var spnVersions: Spinner
    private lateinit var btnInstallSelected: Button
    private lateinit var tvMosiIntegrationStatus: TextView
    private lateinit var btnMosiActivation: Button

    private lateinit var updateManager: UpdateManager
    private var sybimlReleases: List<MosiRelease> = emptyList()
    private var githubReleases: List<MosiRelease> = emptyList()
    private var fetchedReleases: List<MosiRelease> = emptyList()

    // ✅ Versión realmente instalada (leída de packageManager)
    private var installedVersionName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        updateManager = UpdateManager(this)

        sideBar = findViewById(R.id.themeAccentBar)
        etModelo = findViewById(R.id.etDeviceModel)
        etSerie = findViewById(R.id.etDeviceSerial)

        // ── Vistas de actualización ──
        tvInstalledVersion = findViewById(R.id.tvInstalledVersion)
        tvSoftwareVersion = findViewById(R.id.tvSoftwareVersion)
        tvGithubVersion = findViewById(R.id.tvGithubVersion)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        btnUpdateGithub = findViewById(R.id.btnUpdateGithub)
        spnVersions = findViewById(R.id.spnVersions)
        btnInstallSelected = findViewById(R.id.btnInstallSelected)
        tvMosiIntegrationStatus = findViewById(R.id.tvMosiIntegrationStatus)
        btnMosiActivation = findViewById(R.id.btnMosiActivation)
        refreshMosiStatus()
        btnMosiActivation.setOnClickListener {
            startActivity(Intent(this, com.sybi.mosi.integration.MosiActivationActivity::class.java))
        }

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

        // ── Mostrar versión del software INSTALADA ──
        showCurrentSoftwareVersion()

        // ── Cargar releases de los repositorios ──
        loadRepositoryReleases()

        // ── Botón Actualizar desde SybiML (Manual) ──
        btnCheckUpdates.setOnClickListener {
            lifecycleScope.launch {
                btnCheckUpdates.isEnabled = false
                Toast.makeText(this@DeviceInfoActivity, "Buscando actualizaciones en SybiML...", Toast.LENGTH_SHORT).show()

                var releases = sybimlReleases
                if (releases.isEmpty()) {
                    releases = updateManager.fetchSybimlReleases()
                    sybimlReleases = releases
                }

                btnCheckUpdates.isEnabled = true

                if (releases.isNotEmpty()) {
                    val latestRelease = releases[0]
                    val latestTag = latestRelease.tagName

                    if (!updateManager.isNewerVersion(latestTag, installedVersionName)) {
                        Toast.makeText(
                            this@DeviceInfoActivity,
                            "Ya tienes instalada la versión más reciente de SybiML ($latestTag)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    UpdateActivity.start(
                        this@DeviceInfoActivity,
                        latestRelease.downloadUrl,
                        latestTag,
                        forceReinstall = false,
                        sha256 = latestRelease.sha256,
                        updateId = latestRelease.updateId,
                        requestId = latestRelease.requestId
                    )
                } else {
                    Toast.makeText(
                        this@DeviceInfoActivity,
                        "No se pudo consultar el canal de actualizaciones SybiML",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // ── Botón Actualizar desde GitHub (Manual) ──
        btnUpdateGithub.setOnClickListener {
            lifecycleScope.launch {
                btnUpdateGithub.isEnabled = false
                Toast.makeText(this@DeviceInfoActivity, "Buscando actualizaciones en GitHub...", Toast.LENGTH_SHORT).show()

                var releases = githubReleases
                if (releases.isEmpty()) {
                    releases = updateManager.fetchGithubReleases()
                    githubReleases = releases
                }

                btnUpdateGithub.isEnabled = true

                if (releases.isNotEmpty()) {
                    val latestRelease = releases[0]
                    val latestTag = latestRelease.tagName

                    if (!updateManager.isNewerVersion(latestTag, installedVersionName)) {
                        Toast.makeText(
                            this@DeviceInfoActivity,
                            "Ya tienes instalada la versión más reciente de GitHub ($latestTag)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    UpdateActivity.start(
                        this@DeviceInfoActivity,
                        latestRelease.downloadUrl,
                        latestTag,
                        forceReinstall = false,
                        sha256 = "",
                        updateId = "",
                        requestId = ""
                    )
                } else {
                    Toast.makeText(
                        this@DeviceInfoActivity,
                        "No se pudo consultar el repositorio de GitHub",
                        Toast.LENGTH_SHORT
                    ).show()
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

                Log.d(
                    TAG,
                    "🔧 Instalando versión seleccionada: ${selected.tagName} (${selected.source}) " +
                            "(instalada actualmente: $installedVersionName). forceReinstall=true"
                )

                UpdateActivity.start(
                    this@DeviceInfoActivity,
                    selected.downloadUrl,
                    selected.tagName,
                    forceReinstall = true,
                    sha256 = selected.sha256,
                    updateId = selected.updateId,
                    requestId = selected.requestId
                )
            } else {
                Toast.makeText(this, "Por favor seleccione una versión válida", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::tvMosiIntegrationStatus.isInitialized) refreshMosiStatus()
    }

    private fun refreshMosiStatus() {
        val activated = com.sybi.mosi.integration.MosiSecureStore.estaActivado(this)
        val owner = com.sybi.mosi.admin.PermisosAdmin.esPropietario(this)
        tvMosiIntegrationStatus.text = buildString {
            append(if (activated) "Administrador MOSI: activado" else "Administrador MOSI: sin activar")
            append(" · Instalación silenciosa: ")
            append(if (owner) "disponible" else "NO disponible (falta Device Owner)")
        }
        btnMosiActivation.text = if (activated) "Reactivar" else "Activar"
    }

    /**
     * Muestra la versión del software REALMENTE INSTALADA en el dispositivo.
     * Este texto NO se debe sobrescribir con datos del repositorio.
     */
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

        installedVersionName = versionName

        tvInstalledVersion.text = "Versión instalada: v$versionName (Build $versionCode)"
        Log.d(TAG, "📱 Versión instalada detectada: v$versionName (Build $versionCode)")
    }

    /**
     * Carga las versiones disponibles tanto de SybiML como de GitHub.
     */
    private fun loadRepositoryReleases() {
        lifecycleScope.launch {
            sybimlReleases = updateManager.fetchSybimlReleases()
            githubReleases = updateManager.fetchGithubReleases()

            val latestSybiml = sybimlReleases.firstOrNull()?.tagName
            tvSoftwareVersion.text = if (latestSybiml != null) {
                "Versión disponible en SybiML: $latestSybiml"
            } else {
                "Versión disponible en SybiML: —"
            }

            val latestGithub = githubReleases.firstOrNull()?.tagName
            tvGithubVersion.text = if (latestGithub != null) {
                "Versión disponible en GitHub: $latestGithub"
            } else {
                "Versión disponible en GitHub: —"
            }

            val combined = mutableListOf<MosiRelease>()
            sybimlReleases.forEach { rel ->
                combined.add(rel.copy(name = "[SybiML] ${rel.tagName}"))
            }
            githubReleases.forEach { rel ->
                combined.add(rel.copy(name = "[GitHub] ${rel.tagName}"))
            }
            fetchedReleases = combined

            if (fetchedReleases.isNotEmpty()) {
                val tagList = fetchedReleases.map { it.name }
                val adapter = ArrayAdapter(
                    this@DeviceInfoActivity,
                    android.R.layout.simple_spinner_item,
                    tagList
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter
            } else {
                val defaultList = listOf("Sin conexión / No disponible")
                val adapter = ArrayAdapter(
                    this@DeviceInfoActivity,
                    android.R.layout.simple_spinner_item,
                    defaultList
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter
            }
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