package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.sybi.mosi.admin.PermisosAdmin
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.network.Cliente
import com.sybi.mosi.network.RetrofitClient
import com.sybi.mosi.network.Sucursal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TelemedicineSettingsActivity : BaseActivity() {

    companion object {
        private const val TAG = "TelemedicineSettings"

        const val PREF_TELEMEDICINE_ENABLED = "telemedicine_enabled"
        const val PREF_TELEMEDICINE_BASE_URL = "telemedicine_base_url"
        const val DEFAULT_BASE_URL = "https://www.sybiml.com/telemedicina/"

        const val PREF_CUSTOM_TAB_BROWSER = "custom_tab_browser"

        const val PREF_CABINA_NUMBER = "cabina_number"
        const val PREF_CLIENTE = "cliente_id"
        const val PREF_CLIENTE_NOMBRE = "cliente_nombre"
        const val PREF_SUCURSAL = "sucursal_id"
        const val PREF_SUCURSAL_NOMBRE = "sucursal_nombre"

        fun isTelemedicineEnabled(context: Context): Boolean {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            val enabled = devicePrefs.getBoolean(PREF_TELEMEDICINE_ENABLED, false)
            Log.d(TAG, "🔍 Telemedicina habilitada: $enabled")
            return enabled
        }

        fun getPreferredBrowser(context: Context): String {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return devicePrefs.getString(PREF_CUSTOM_TAB_BROWSER, CustomTabsHelper.BROWSER_FIREFOX)
                ?: CustomTabsHelper.BROWSER_FIREFOX
        }

        const val PREF_FALLBACK_ENABLED = "telemedicine_fallback_enabled"

        /** Respaldo WebRTC del paciente; apagado por defecto hasta validarlo contra el servidor real. */
        fun isFallbackEnabled(context: Context): Boolean =
            context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                .getBoolean(PREF_FALLBACK_ENABLED, false)

        fun getBaseUrl(context: Context): String {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            val savedUrl = devicePrefs.getString(PREF_TELEMEDICINE_BASE_URL, DEFAULT_BASE_URL)
            return if (!savedUrl.isNullOrBlank()) savedUrl.trim() else DEFAULT_BASE_URL
        }

        fun getCabina(context: Context): String {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return devicePrefs.getString(PREF_CABINA_NUMBER, "") ?: ""
        }

        fun getCliente(context: Context): String {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return devicePrefs.getString(PREF_CLIENTE, "") ?: ""
        }

        fun getSucursal(context: Context): String {
            val devicePrefs = context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
            return devicePrefs.getString(PREF_SUCURSAL, "") ?: ""
        }
    }

    private lateinit var sideBar: View
    private lateinit var switchTelemedicine: Switch
    private lateinit var switchFallback: Switch
    private lateinit var etBaseUrl: EditText
    private lateinit var spinnerBrowser: Spinner
    private lateinit var etCabina: EditText
    private lateinit var etCliente: AutoCompleteTextView
    private lateinit var etSucursal: AutoCompleteTextView
    private lateinit var btnGuardar: Button

    private var listaClientes: List<Cliente> = emptyList()
    private var listaSucursales: List<Sucursal> = emptyList()

    private var idClienteSeleccionado: String = ""
    private var nombreClienteSeleccionado: String = ""
    private var idSucursalSeleccionada: String = ""
    private var nombreSucursalSeleccionada: String = ""

    // Lista dinámica de navegadores instalados (llena en runtime)
    private var navegadoresInstalados: List<CustomTabsHelper.InstalledBrowser> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_telemedicine_settings)

        initViews()
        setupSpinnerBrowser()
        setupListeners()

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    sideBar.setBackgroundColor(Color.parseColor(newColor))
                    btnGuardar.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
                    applySwitchTint(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnGuardar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))
        applySwitchTint(Color.parseColor(savedColor))

        loadSavedValues()
        cargarClientes()
    }

    private fun initViews() {
        sideBar = findViewById(R.id.sideBarLayout)
        switchTelemedicine = findViewById(R.id.switchTelemedicine)
        switchFallback = findViewById(R.id.switchFallback)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        spinnerBrowser = findViewById(R.id.spinnerBrowser)
        etCabina = findViewById(R.id.etCabinaNumber)
        etCliente = findViewById(R.id.etCliente)
        etSucursal = findViewById(R.id.etSucursal)
        btnGuardar = findViewById(R.id.btnGuardarTelemedicine)
    }

    private fun setupSpinnerBrowser() {
        // Escanear navegadores instalados
        navegadoresInstalados = CustomTabsHelper.listInstalledBrowsers(this)

        if (navegadoresInstalados.isEmpty()) {
            Log.e(TAG, "❌ No hay navegadores instalados en el dispositivo")
            // Fallback: mostrar solo Firefox con etiqueta de error
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                listOf("⚠️ No se detectaron navegadores instalados")
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerBrowser.adapter = adapter
            spinnerBrowser.isEnabled = false
            return
        }

        val items = navegadoresInstalados.map { it.displayName }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBrowser.adapter = adapter
        spinnerBrowser.isEnabled = true
    }

    private fun browserKeyToPosition(key: String): Int {
        val idx = navegadoresInstalados.indexOfFirst { it.key == key }
        if (idx >= 0) return idx

        // Si la preferencia no está instalada, buscar Firefox
        val firefoxIdx = navegadoresInstalados.indexOfFirst { it.key == CustomTabsHelper.BROWSER_FIREFOX }
        if (firefoxIdx >= 0) return firefoxIdx

        // Si Firefox tampoco está, primero disponible
        return 0
    }

    private fun positionToBrowserKey(position: Int): String {
        return navegadoresInstalados.getOrNull(position)?.key ?: CustomTabsHelper.BROWSER_FIREFOX
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBackTelemedicine).setOnClickListener {
            saveTelemedicineSettings()
            finish()
        }

        btnGuardar.setOnClickListener {
            saveTelemedicineSettings()
        }

        findViewById<Button>(R.id.btnDiagCamara).setOnClickListener { mostrarDiagnosticoCamara() }
        findViewById<Button>(R.id.btnDiagWebView).setOnClickListener { mostrarDiagnosticoWebView() }

        findViewById<Button>(R.id.btnConcederPermisos).setOnClickListener {
            val estado = PermisosAdmin.aplicar(this)
            if (!estado.esPropietario && estado.pendientes.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, estado.pendientes.toTypedArray(), 200)
            }
            actualizarEstadoAdmin()
        }
        findViewById<Button>(R.id.btnRenunciarAdmin).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Renunciar a administrador")
                .setMessage("La app dejará de ser propietaria del dispositivo y volverá a pedir los permisos. ¿Continuar?")
                .setPositiveButton("Renunciar") { _, _ ->
                    val ok = PermisosAdmin.renunciar(this)
                    Toast.makeText(this, if (ok) "Administrador desactivado" else "La app no es administradora", Toast.LENGTH_SHORT).show()
                    actualizarEstadoAdmin()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        actualizarEstadoAdmin()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        actualizarEstadoAdmin()
    }

    private fun actualizarEstadoAdmin() {
        val estado = PermisosAdmin.estado(this)
        findViewById<android.widget.TextView>(R.id.tvEstadoAdmin).text =
            (if (estado.esPropietario) "Administrador del dispositivo: SÍ (los permisos se conceden solos)"
            else "Administrador del dispositivo: NO (Android pedirá confirmar cada permiso)") +
                    "\nPermisos concedidos: ${estado.concedidos} de ${estado.total}" +
                    (if (estado.pendientes.isNotEmpty()) "\nPendientes: " +
                            estado.pendientes.joinToString { it.substringAfterLast('.') } else "")
        findViewById<Button>(R.id.btnRenunciarAdmin).isEnabled = estado.esPropietario
    }

    private fun mostrarDiagnosticoCamara() {
        val informe = DiagnosticoCamara.generar(this)
        Log.i(TAG, "🔎 Diagnóstico de cámara:\n$informe")
        val texto = android.widget.TextView(this).apply {
            text = "$informe\n\nProbando abrir cada cámara…"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(texto) }
        val dialogo = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnóstico de cámara y USB")
            .setView(scroll)
            .setPositiveButton("Actualizar") { _, _ -> mostrarDiagnosticoCamara() }
            .setNegativeButton("Cerrar", null)
            .show()

        lifecycleScope.launch {
            val aperturas = StringBuilder("\n\nAPERTURA REAL DE CADA CÁMARA (Camera2, sin WebView)\n")
            for (id in DiagnosticoCamara.idsCamaras(this@TelemedicineSettingsActivity)) {
                if (!dialogo.isShowing) return@launch
                val resultado = DiagnosticoCamara.probarApertura(this@TelemedicineSettingsActivity, id)
                aperturas.append("   - cámara $id: $resultado\n")
                if (dialogo.isShowing) texto.text = "$informe$aperturas"
            }
            Log.i(TAG, "🔎 Apertura real de cámaras:$aperturas")
        }
    }

    private fun mostrarDiagnosticoWebView() {
        val informe = DiagnosticoWebView.generar(this)
        Log.i(TAG, "🔎 Diagnóstico de WebView:\n$informe")
        val texto = android.widget.TextView(this).apply {
            text = informe
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setPadding(48, 24, 48, 24)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(texto) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnóstico de WebView")
            .setView(scroll)
            .setPositiveButton("Copiar") { _, _ ->
                val portapapeles = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                portapapeles.setPrimaryClip(android.content.ClipData.newPlainText("Diagnóstico de WebView", informe))
                Toast.makeText(this, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun loadSavedValues() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        switchTelemedicine.isChecked = devicePrefs.getBoolean(PREF_TELEMEDICINE_ENABLED, false)
        switchFallback.isChecked = devicePrefs.getBoolean(PREF_FALLBACK_ENABLED, false)

        val urlGuardada = devicePrefs.getString(PREF_TELEMEDICINE_BASE_URL, DEFAULT_BASE_URL)
        etBaseUrl.setText(if (!urlGuardada.isNullOrBlank()) urlGuardada else DEFAULT_BASE_URL)

        val savedBrowser = getPreferredBrowser(this)
        spinnerBrowser.setSelection(browserKeyToPosition(savedBrowser))

        etCabina.setText(devicePrefs.getString(PREF_CABINA_NUMBER, ""))

        idClienteSeleccionado = devicePrefs.getString(PREF_CLIENTE, "") ?: ""
        nombreClienteSeleccionado = devicePrefs.getString(PREF_CLIENTE_NOMBRE, "") ?: ""
        if (idClienteSeleccionado.isNotEmpty()) {
            etCliente.setText("$nombreClienteSeleccionado ($idClienteSeleccionado)")
        }

        idSucursalSeleccionada = devicePrefs.getString(PREF_SUCURSAL, "") ?: ""
        nombreSucursalSeleccionada = devicePrefs.getString(PREF_SUCURSAL_NOMBRE, "") ?: ""
        if (idSucursalSeleccionada.isNotEmpty()) {
            etSucursal.setText("$nombreSucursalSeleccionada ($idSucursalSeleccionada)")
        }

        if (idClienteSeleccionado.isNotEmpty()) {
            cargarSucursales(idClienteSeleccionado)
        }
    }

    // ==========================================
    // CLIENTES
    // ==========================================
    private fun cargarClientes() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.obtenerClientes()
                }
                if (response.isSuccessful) {
                    val body = response.body()
                    listaClientes = body?.clientes ?: emptyList()
                    Log.d(TAG, "✅ Clientes cargados: ${listaClientes.size}")
                    configurarAutoCompleteClientes()
                } else {
                    Log.e(TAG, "❌ Error HTTP clientes: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🔥 Excepción cargando clientes: ${e.message}", e)
            }
        }
    }

    private fun configurarAutoCompleteClientes() {
        val items = listaClientes.map {
            "${it.nombreCliente ?: "Sin nombre"} (${it.idCliente ?: "-"})"
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            items
        )
        etCliente.setAdapter(adapter)

        etCliente.setOnClickListener {
            if (listaClientes.isNotEmpty()) etCliente.showDropDown()
        }
        etCliente.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && listaClientes.isNotEmpty()) etCliente.showDropDown()
        }

        etCliente.setOnItemClickListener { _, _, _, _ ->
            val textoSeleccionado = etCliente.text.toString()
            val cliente = listaClientes.firstOrNull {
                "${it.nombreCliente ?: "Sin nombre"} (${it.idCliente ?: "-"})" == textoSeleccionado
            }

            if (cliente != null) {
                idClienteSeleccionado = cliente.idCliente ?: ""
                nombreClienteSeleccionado = cliente.nombreCliente ?: ""
                etCliente.setText("$nombreClienteSeleccionado ($idClienteSeleccionado)")
                etCliente.setSelection(etCliente.text.length)

                idSucursalSeleccionada = ""
                nombreSucursalSeleccionada = ""
                etSucursal.setText("")
                listaSucursales = emptyList()
                etSucursal.setAdapter(null)

                cargarSucursales(idClienteSeleccionado)

                Log.d(TAG, "✅ Cliente seleccionado: $idClienteSeleccionado - $nombreClienteSeleccionado")
            }
        }
    }

    // ==========================================
    // SUCURSALES
    // ==========================================
    private fun cargarSucursales(idCliente: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.obtenerSucursales(idCliente)
                }
                if (response.isSuccessful) {
                    val body = response.body()
                    listaSucursales = body?.sucursales ?: emptyList()
                    Log.d(TAG, "✅ Sucursales cargadas: ${listaSucursales.size}")
                    configurarAutoCompleteSucursales()
                } else {
                    Log.e(TAG, "❌ Error HTTP sucursales: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🔥 Excepción cargando sucursales: ${e.message}", e)
            }
        }
    }

    private fun configurarAutoCompleteSucursales() {
        val items = listaSucursales.map {
            "${it.nombreSucursal ?: "Sin nombre"} (${it.idSucursal ?: "-"})"
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            items
        )
        etSucursal.setAdapter(adapter)

        etSucursal.setOnClickListener {
            if (listaSucursales.isNotEmpty()) etSucursal.showDropDown()
        }

        etSucursal.setOnItemClickListener { _, _, _, _ ->
            val textoSeleccionado = etSucursal.text.toString()
            val sucursal = listaSucursales.firstOrNull {
                "${it.nombreSucursal ?: "Sin nombre"} (${it.idSucursal ?: "-"})" == textoSeleccionado
            }

            if (sucursal != null) {
                idSucursalSeleccionada = sucursal.idSucursal ?: ""
                nombreSucursalSeleccionada = sucursal.nombreSucursal ?: ""
                etSucursal.setText("$nombreSucursalSeleccionada ($idSucursalSeleccionada)")
                etSucursal.setSelection(etSucursal.text.length)

                Log.d(TAG, "✅ Sucursal seleccionada: $idSucursalSeleccionada - $nombreSucursalSeleccionada")
            }
        }
    }

    // ==========================================
    // GUARDAR CONFIGURACIÓN
    // ==========================================
    private fun saveTelemedicineSettings() {
        val enabled = switchTelemedicine.isChecked
        val rawBaseUrl = etBaseUrl.text.toString().trim().ifEmpty { DEFAULT_BASE_URL }
        val selectedBrowserKey = positionToBrowserKey(spinnerBrowser.selectedItemPosition)
        val cabina = etCabina.text.toString().trim()

        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        devicePrefs.edit()
            .putBoolean(PREF_TELEMEDICINE_ENABLED, enabled)
            .putBoolean(PREF_FALLBACK_ENABLED, switchFallback.isChecked)
            .putString(PREF_TELEMEDICINE_BASE_URL, rawBaseUrl)
            .putString(PREF_CUSTOM_TAB_BROWSER, selectedBrowserKey)
            .putString(PREF_CABINA_NUMBER, cabina)
            .putString(PREF_CLIENTE, idClienteSeleccionado)
            .putString(PREF_CLIENTE_NOMBRE, nombreClienteSeleccionado)
            .putString(PREF_SUCURSAL, idSucursalSeleccionada)
            .putString(PREF_SUCURSAL_NOMBRE, nombreSucursalSeleccionada)
            .apply()

        Log.d(
            TAG,
            "💾 Guardado: enabled=$enabled, baseUrl=$rawBaseUrl, browser=$selectedBrowserKey, cabina=$cabina, " +
                    "cliente=$idClienteSeleccionado ($nombreClienteSeleccionado), " +
                    "sucursal=$idSucursalSeleccionada ($nombreSucursalSeleccionada)"
        )
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        saveTelemedicineSettings()
    }

    private fun applySwitchTint(originalColor: Int) {
        switchTelemedicine.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        switchTelemedicine.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        switchFallback.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        switchFallback.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
    }
}
