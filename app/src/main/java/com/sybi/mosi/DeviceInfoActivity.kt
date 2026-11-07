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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.updater.GitHubRelease
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
    private lateinit var btnCheckUpdates: Button
    private lateinit var spnVersions: Spinner
    private lateinit var btnInstallSelected: Button

    private lateinit var updateManager: UpdateManager
    private var fetchedReleases: List<GitHubRelease> = emptyList()

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
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        spnVersions = findViewById(R.id.spnVersions)
        btnInstallSelected = findViewById(R.id.btnInstallSelected)

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

        // ── Cargar releases del repositorio ──
        loadRepositoryReleases()

        // ── Botón Buscar Actualizaciones (Manual/Última) ──
        // Solo instala si la versión del repositorio es MÁS NUEVA que la instalada.
        btnCheckUpdates.setOnClickListener {
            lifecycleScope.launch {
                btnCheckUpdates.isEnabled = false
                Toast.makeText(this@DeviceInfoActivity, "Buscando actualizaciones...", Toast.LENGTH_SHORT).show()

                var releases = fetchedReleases
                if (releases.isEmpty()) {
                    releases = updateManager.fetchReleases()
                    fetchedReleases = releases
                }

                btnCheckUpdates.isEnabled = true

                if (releases.isNotEmpty()) {
                    val tagList = fetchedReleases.map { "${it.tagName} (${it.name})" }
                    val adapter = ArrayAdapter(
                        this@DeviceInfoActivity,
                        android.R.layout.simple_spinner_item,
                        tagList
                    )
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spnVersions.adapter = adapter
                    tvSoftwareVersion.text = "Versión en repositorio: ${fetchedReleases[0].tagName}"

                    val latestRelease = releases[0]
                    val latestTag = latestRelease.tagName

                    if (!updateManager.isNewerVersion(latestTag, installedVersionName)) {
                        Toast.makeText(
                            this@DeviceInfoActivity,
                            "Ya tienes instalada la versión más reciente ($latestTag)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    // Lanzar pantalla de actualización ininterrumpida.
                    // forceReinstall = false → solo se instala si es más nueva.
                    UpdateActivity.start(
                        this@DeviceInfoActivity,
                        latestRelease.downloadUrl,
                        latestTag,
                        forceReinstall = false
                    )
                } else {
                    Toast.makeText(
                        this@DeviceInfoActivity,
                        "No se pudieron consultar las versiones en GitHub",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // ── Botón Instalar versión seleccionada ──
        // Permite instalar CUALQUIER versión elegida en el spinner:
        //   - la misma versión instalada (reinstalar)
        //   - una versión anterior (downgrade)
        //   - una versión más nueva (actualizar)
        // forceReinstall = true → se salta la validación de isNewerVersion y
        // se configuran los flags de PackageInstaller para permitir
        // misma versión y downgrade.
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
                    "🔧 Instalando versión seleccionada: ${selected.tagName} " +
                            "(instalada actualmente: $installedVersionName). forceReinstall=true"
                )

                // forceReinstall = true → permite reinstalar misma versión y downgrade.
                UpdateActivity.start(
                    this@DeviceInfoActivity,
                    selected.downloadUrl,
                    selected.tagName,
                    forceReinstall = true
                )
            } else {
                Toast.makeText(this, "Por favor seleccione una versión válida", Toast.LENGTH_SHORT).show()
            }
        }
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
     * Carga las releases del repositorio de GitHub y las muestra en el Spinner.
     * NO modifica tvInstalledVersion.
     */
    private fun loadRepositoryReleases() {
        lifecycleScope.launch {
            fetchedReleases = updateManager.fetchReleases()
            if (fetchedReleases.isNotEmpty()) {
                val tagList = fetchedReleases.map { "${it.tagName} (${it.name})" }
                val adapter = ArrayAdapter(
                    this@DeviceInfoActivity,
                    android.R.layout.simple_spinner_item,
                    tagList
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter

                spnVersions.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (position in fetchedReleases.indices) {
                            val selected = fetchedReleases[position]
                            tvSoftwareVersion.text = "Versión en repositorio: ${selected.tagName}"
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                tvSoftwareVersion.text = "Versión en repositorio: ${fetchedReleases[0].tagName}"
            } else {
                val defaultList = listOf("Sin conexión / No disponible")
                val adapter = ArrayAdapter(
                    this@DeviceInfoActivity,
                    android.R.layout.simple_spinner_item,
                    defaultList
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spnVersions.adapter = adapter
                tvSoftwareVersion.text = "Versión en repositorio: —"
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