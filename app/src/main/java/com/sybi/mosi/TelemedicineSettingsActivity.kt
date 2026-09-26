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
import android.widget.Switch
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_telemedicine_settings)

        initViews()
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
        etCabina = findViewById(R.id.etCabinaNumber)
        etCliente = findViewById(R.id.etCliente)
        etSucursal = findViewById(R.id.etSucursal)
        btnGuardar = findViewById(R.id.btnGuardarTelemedicine)
    }

    private fun setupListeners() {
        findViewById<View>(R.id.btnBackTelemedicine).setOnClickListener {
            saveTelemedicineSettings()
            finish()
        }

        btnGuardar.setOnClickListener {
            saveTelemedicineSettings()
        }
    }

    private fun loadSavedValues() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        switchTelemedicine.isChecked = devicePrefs.getBoolean(PREF_TELEMEDICINE_ENABLED, false)
        switchFallback.isChecked = devicePrefs.getBoolean(PREF_FALLBACK_ENABLED, false)

        val urlGuardada = devicePrefs.getString(PREF_TELEMEDICINE_BASE_URL, DEFAULT_BASE_URL)
        etBaseUrl.setText(if (!urlGuardada.isNullOrBlank()) urlGuardada else DEFAULT_BASE_URL)

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
        val cabina = etCabina.text.toString().trim()

        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        devicePrefs.edit()
            .putBoolean(PREF_TELEMEDICINE_ENABLED, enabled)
            .putBoolean(PREF_FALLBACK_ENABLED, switchFallback.isChecked)
            .putString(PREF_TELEMEDICINE_BASE_URL, rawBaseUrl)
            .putString(PREF_CABINA_NUMBER, cabina)
            .putString(PREF_CLIENTE, idClienteSeleccionado)
            .putString(PREF_CLIENTE_NOMBRE, nombreClienteSeleccionado)
            .putString(PREF_SUCURSAL, idSucursalSeleccionada)
            .putString(PREF_SUCURSAL_NOMBRE, nombreSucursalSeleccionada)
            .apply()

        Log.d(
            TAG,
            "💾 Guardado: enabled=$enabled, baseUrl=$rawBaseUrl, cabina=$cabina, " +
                    "cliente=$idClienteSeleccionado ($nombreClienteSeleccionado), " +
                    "sucursal=$idSucursalSeleccionada ($nombreSucursalSeleccionada), " +
                    "videoIp=${VideoLoopRemote.getSavedHostPort(this)}"
        )
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
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