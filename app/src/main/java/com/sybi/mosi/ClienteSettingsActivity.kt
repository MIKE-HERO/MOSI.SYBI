package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.sybi.mosi.network.Cliente
import com.sybi.mosi.network.RetrofitClient
import com.sybi.mosi.network.Sucursal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Selección de cliente/sucursal, antes mezclada dentro de los ajustes de Telemedicina.
 * Lee y escribe las mismas claves de DevicePrefs (PREF_CLIENTE/PREF_SUCURSAL, definidas en
 * TelemedicineSettingsActivity) para no romper a quien ya las consulta (p. ej. ResultsActivity
 * al armar el PDF), aunque la UI para editarlas ahora vive aquí.
 */
class ClienteSettingsActivity : BaseActivity() {

    companion object {
        private const val TAG = "ClienteSettings"
    }

    private lateinit var sideBar: View
    private lateinit var etCliente: AutoCompleteTextView
    private lateinit var etSucursal: AutoCompleteTextView
    private lateinit var btnGuardar: Button

    private lateinit var tvImagenesHint: TextView
    private lateinit var cardSlotIzq: View
    private lateinit var cardSlotDer: View
    private lateinit var cardSlotFirma: View
    private lateinit var imgSlotIzq: ImageView
    private lateinit var imgSlotDer: ImageView
    private lateinit var imgSlotFirma: ImageView
    private lateinit var tvSlotSeleccionado: TextView
    private lateinit var imgPreviewGrande: ImageView
    private lateinit var btnSeleccionarImagen: Button

    private var listaClientes: List<Cliente> = emptyList()
    private var listaSucursales: List<Sucursal> = emptyList()

    private var idClienteSeleccionado: String = ""
    private var nombreClienteSeleccionado: String = ""
    private var idSucursalSeleccionada: String = ""
    private var nombreSucursalSeleccionada: String = ""

    /** Slot actualmente previsualizado en grande ("izq"/"der"/"firma"), o null si ninguno. */
    private var slotSeleccionado: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_cliente_settings)

        sideBar = findViewById(R.id.themeAccentBar)
        etCliente = findViewById(R.id.etCliente)
        etSucursal = findViewById(R.id.etSucursal)
        btnGuardar = findViewById(R.id.btnGuardarCliente)

        tvImagenesHint = findViewById(R.id.tvImagenesHint)
        cardSlotIzq = findViewById(R.id.cardSlotIzq)
        cardSlotDer = findViewById(R.id.cardSlotDer)
        cardSlotFirma = findViewById(R.id.cardSlotFirma)
        imgSlotIzq = findViewById(R.id.imgSlotIzq)
        imgSlotDer = findViewById(R.id.imgSlotDer)
        imgSlotFirma = findViewById(R.id.imgSlotFirma)
        tvSlotSeleccionado = findViewById(R.id.tvSlotSeleccionado)
        imgPreviewGrande = findViewById(R.id.imgPreviewGrande)
        btnSeleccionarImagen = findViewById(R.id.btnSeleccionarImagen)

        findViewById<View>(R.id.btnBackCliente).setOnClickListener {
            saveClienteSettings()
            finish()
        }
        btnGuardar.setOnClickListener { saveClienteSettings() }

        cardSlotIzq.setOnClickListener { mostrarPreviewGrande("izq") }
        cardSlotDer.setOnClickListener { mostrarPreviewGrande("der") }
        cardSlotFirma.setOnClickListener { mostrarPreviewGrande("firma") }
        btnSeleccionarImagen.setOnClickListener { abrirSelectorDeImagen() }

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                btnGuardar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnGuardar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))

        loadSavedValues()
        cargarClientes()
    }

    private fun loadSavedValues() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

        idClienteSeleccionado = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE, "") ?: ""
        nombreClienteSeleccionado = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, "") ?: ""
        if (idClienteSeleccionado.isNotEmpty()) {
            // setText(texto, false): precarga el campo SIN disparar el filtro de autocompletado.
            etCliente.setText("$nombreClienteSeleccionado ($idClienteSeleccionado)", false)
        }

        idSucursalSeleccionada = devicePrefs.getString(TelemedicineSettingsActivity.PREF_SUCURSAL, "") ?: ""
        nombreSucursalSeleccionada = devicePrefs.getString(TelemedicineSettingsActivity.PREF_SUCURSAL_NOMBRE, "") ?: ""
        if (idSucursalSeleccionada.isNotEmpty()) {
            etSucursal.setText("$nombreSucursalSeleccionada ($idSucursalSeleccionada)", false)
        }

        if (idClienteSeleccionado.isNotEmpty()) {
            cargarSucursales(idClienteSeleccionado)
            actualizarSlotsImagenes()
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
                    listaClientes = response.body()?.clientes ?: emptyList()
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
        val items = listaClientes.map { "${it.nombreCliente ?: "Sin nombre"} (${it.idCliente ?: "-"})" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
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
                etCliente.setText("$nombreClienteSeleccionado ($idClienteSeleccionado)", false)
                etCliente.setSelection(etCliente.text.length)

                idSucursalSeleccionada = ""
                nombreSucursalSeleccionada = ""
                etSucursal.setText("")
                listaSucursales = emptyList()
                etSucursal.setAdapter(null)

                cargarSucursales(idClienteSeleccionado)
                actualizarSlotsImagenes()

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
                    listaSucursales = response.body()?.sucursales ?: emptyList()
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
        val items = listaSucursales.map { "${it.nombreSucursal ?: "Sin nombre"} (${it.idSucursal ?: "-"})" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        etSucursal.setAdapter(adapter)

        etSucursal.setOnClickListener {
            if (listaSucursales.isNotEmpty()) etSucursal.showDropDown()
        }
        etSucursal.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && listaSucursales.isNotEmpty()) etSucursal.showDropDown()
        }

        etSucursal.setOnItemClickListener { _, _, _, _ ->
            val textoSeleccionado = etSucursal.text.toString()
            val sucursal = listaSucursales.firstOrNull {
                "${it.nombreSucursal ?: "Sin nombre"} (${it.idSucursal ?: "-"})" == textoSeleccionado
            }

            if (sucursal != null) {
                idSucursalSeleccionada = sucursal.idSucursal ?: ""
                nombreSucursalSeleccionada = sucursal.nombreSucursal ?: ""
                etSucursal.setText("$nombreSucursalSeleccionada ($idSucursalSeleccionada)", false)
                etSucursal.setSelection(etSucursal.text.length)

                Log.d(TAG, "✅ Sucursal seleccionada: $idSucursalSeleccionada - $nombreSucursalSeleccionada")
            }
        }
    }

    // ==========================================
    // IMÁGENES DEL CLIENTE (logos izq/der y firma)
    // ==========================================

    private fun vistaDelSlot(slot: String): ImageView = when (slot) {
        "izq" -> imgSlotIzq
        "der" -> imgSlotDer
        else -> imgSlotFirma
    }

    private fun tarjetaDelSlot(slot: String): View = when (slot) {
        "izq" -> cardSlotIzq
        "der" -> cardSlotDer
        else -> cardSlotFirma
    }

    /** Vuelve a cargar las miniaturas de los 3 slots para el cliente actualmente seleccionado. */
    private fun actualizarSlotsImagenes() {
        val idCliente = idClienteSeleccionado
        if (idCliente.isBlank()) {
            tvImagenesHint.text = "Selecciona un cliente para ver sus imágenes"
            ClienteLogoCache.SLOTS.forEach { (slot, _) -> vistaDelSlot(slot).setImageDrawable(null) }
            return
        }
        tvImagenesHint.text = "Toca una imagen para previsualizarla o reemplazarla"

        lifecycleScope.launch {
            val archivos = withContext(Dispatchers.IO) {
                ClienteLogoCache.SLOTS.associate { (slot, _) ->
                    slot to ClienteLogoCache.rutaImagenSlotActual(this@ClienteSettingsActivity, idCliente, slot)
                }
            }
            archivos.forEach { (slot, archivo) ->
                cargarImagenEnVista(vistaDelSlot(slot), archivo)
            }
            // Si había un slot en previsualización grande, refrescar también esa vista.
            slotSeleccionado?.let { mostrarPreviewGrande(it, recargarSlots = false) }
        }
    }

    private fun mostrarPreviewGrande(slot: String, recargarSlots: Boolean = true) {
        slotSeleccionado = slot
        val etiqueta = ClienteLogoCache.SLOTS.firstOrNull { it.first == slot }?.second ?: slot

        if (idClienteSeleccionado.isBlank()) {
            tvSlotSeleccionado.text = "Selecciona primero un cliente"
            imgPreviewGrande.setImageDrawable(null)
            btnSeleccionarImagen.isEnabled = false
            return
        }

        tvSlotSeleccionado.text = etiqueta
        btnSeleccionarImagen.isEnabled = true

        // Resaltar la tarjeta del slot activo para que quede claro cuál se está previsualizando.
        ClienteLogoCache.SLOTS.forEach { (s, _) -> tarjetaDelSlot(s).alpha = if (s == slot) 1f else 0.55f }

        val archivo = ClienteLogoCache.archivoOverride(this, idClienteSeleccionado, slot)
            ?: ClienteLogoCache.rutaImagenSlotActual(this, idClienteSeleccionado, slot)
        cargarImagenEnVista(imgPreviewGrande, archivo)
    }

    private fun cargarImagenEnVista(imageView: ImageView, archivo: File?) {
        if (archivo == null) {
            imageView.setImageResource(R.drawable.ic_info)
            return
        }
        try {
            val imageLoader = ImageLoader.Builder(imageView.context)
                .components { add(SvgDecoder.Factory()) }
                .build()
            val request = ImageRequest.Builder(imageView.context)
                .data(archivo)
                .target(imageView)
                .error(R.drawable.ic_info)
                .build()
            imageLoader.enqueue(request)
        } catch (e: Exception) {
            imageView.setImageResource(R.drawable.ic_info)
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val slot = slotSeleccionado
        val idCliente = idClienteSeleccionado
        if (result.resultCode != RESULT_OK || slot == null || idCliente.isBlank()) return@registerForActivityResult
        val uri: Uri = result.data?.data ?: return@registerForActivityResult

        lifecycleScope.launch {
            val guardado = withContext(Dispatchers.IO) {
                ClienteLogoCache.guardarOverrideDesdeUri(this@ClienteSettingsActivity, idCliente, slot, uri)
            }
            if (guardado != null) {
                cargarImagenEnVista(vistaDelSlot(slot), guardado)
                cargarImagenEnVista(imgPreviewGrande, guardado)
                Toast.makeText(this@ClienteSettingsActivity, "Imagen actualizada para $idCliente", Toast.LENGTH_SHORT).show()

                // Si el override es de un logotipo (izquierdo o derecho; la firma no cuenta
                // como logotipo de marca) del cliente ACTIVO del kiosco, refleja el cambio de
                // inmediato en la pantalla principal de la app, sin esperar a que el usuario
                // presione "Guardar Configuración" por separado. El slot elegido aquí queda
                // como preferido para este cliente en sincronizaciones futuras.
                if ((slot == "izq" || slot == "der") &&
                    idCliente == TelemedicineSettingsActivity.getCliente(this@ClienteSettingsActivity)
                ) {
                    withContext(Dispatchers.IO) {
                        LogoSyncHelper.aplicarLogoSeleccionado(
                            this@ClienteSettingsActivity,
                            idCliente,
                            slot,
                            guardado.absolutePath
                        )
                    }
                }
            } else {
                Toast.makeText(this@ClienteSettingsActivity, "No se pudo guardar la imagen", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirSelectorDeImagen() {
        if (idClienteSeleccionado.isBlank() || slotSeleccionado == null) {
            Toast.makeText(this, "Selecciona primero un cliente y una imagen", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "image/svg+xml"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            pickImageLauncher.launch(Intent(Intent.ACTION_PICK).apply { type = "image/*" })
        }
    }

    // ==========================================
    // GUARDAR
    // ==========================================
    private fun saveClienteSettings() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        devicePrefs.edit()
            .putString(TelemedicineSettingsActivity.PREF_CLIENTE, idClienteSeleccionado)
            .putString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, nombreClienteSeleccionado)
            .putString(TelemedicineSettingsActivity.PREF_SUCURSAL, idSucursalSeleccionada)
            .putString(TelemedicineSettingsActivity.PREF_SUCURSAL_NOMBRE, nombreSucursalSeleccionada)
            .apply()

        Log.d(
            TAG,
            "💾 Guardado: cliente=$idClienteSeleccionado ($nombreClienteSeleccionado), " +
                "sucursal=$idSucursalSeleccionada ($nombreSucursalSeleccionada)"
        )
        Toast.makeText(this, "✅ Configuración guardada", Toast.LENGTH_SHORT).show()

        // Al guardar el cliente/sucursal, sincroniza el logo de marca de la app
        // (pantalla principal, PDF, etc.) con el logo del cliente activo.
        if (idClienteSeleccionado.isNotBlank() || idSucursalSeleccionada.isNotBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                LogoSyncHelper.sincronizarLogoDeCliente(
                    this@ClienteSettingsActivity,
                    idSucursalSeleccionada,
                    idClienteSeleccionado
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveClienteSettings()
    }
}
