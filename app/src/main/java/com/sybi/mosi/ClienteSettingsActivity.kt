package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.Filterable
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var imgSlotIzq: ImageView
    private lateinit var imgSlotDer: ImageView
    private lateinit var tvSlotSeleccionado: TextView

    /**
     * Esta pantalla solo deja previsualizar/aplicar izq y der (la firma no es un logotipo de
     * marca — ver aplicarComoLogoPorDefecto). Se usa en vez de ClienteLogoCache.SLOTS (que sí
     * incluye "firma", necesario en LogoSettingsActivity para subir firmas al servidor).
     */
    private val SLOTS_PREVIEW = ClienteLogoCache.SLOTS.filter { it.first != "firma" }
    private lateinit var imgPreviewGrande: ImageView
    private lateinit var btnSeleccionarImagen: Button
    private lateinit var btnBuscarImagenesCliente: Button

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
        imgSlotIzq = findViewById(R.id.imgSlotIzq)
        imgSlotDer = findViewById(R.id.imgSlotDer)
        tvSlotSeleccionado = findViewById(R.id.tvSlotSeleccionado)
        imgPreviewGrande = findViewById(R.id.imgPreviewGrande)
        btnSeleccionarImagen = findViewById(R.id.btnSeleccionarImagen)
        btnBuscarImagenesCliente = findViewById(R.id.btnBuscarImagenesCliente)

        findViewById<View>(R.id.btnBackCliente).setOnClickListener {
            saveClienteSettings()
            finish()
        }
        btnGuardar.setOnClickListener { saveClienteSettings() }

        cardSlotIzq.setOnClickListener { mostrarPreviewGrande("izq") }
        cardSlotDer.setOnClickListener { mostrarPreviewGrande("der") }
        btnSeleccionarImagen.setOnClickListener { aplicarComoLogoPorDefecto() }
        btnBuscarImagenesCliente.setOnClickListener { buscarImagenesDeCliente() }

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

    /**
     * ArrayAdapter con un Filter propio que busca por NOMBRE o ID como substring en
     * cualquier parte (no solo prefijo de palabra, que es lo que hace el Filter por
     * defecto de ArrayAdapter — por eso buscar por ID nunca encontraba nada: el id
     * siempre aparece envuelto en paréntesis "(136)", así que ningún "word" de la
     * cadena empieza nunca con el id tal cual lo escribe el usuario).
     */
    private fun crearAdapterFiltrable(itemsConId: List<Pair<String, String>>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this, android.R.layout.simple_dropdown_item_1line, itemsConId.map { it.first }
        ) {
            override fun getFilter(): android.widget.Filter {
                return object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val query = constraint?.toString()?.trim()?.lowercase() ?: ""
                        val resultados = if (query.isBlank()) {
                            itemsConId.map { it.first }
                        } else {
                            itemsConId.filter { (texto, id) ->
                                texto.lowercase().contains(query) || id.lowercase().contains(query)
                            }.map { it.first }
                        }
                        return FilterResults().apply { values = resultados; count = resultados.size }
                    }

                    @Suppress("UNCHECKED_CAST")
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                        clear()
                        (results?.values as? List<String>)?.let { addAll(it) }
                        notifyDataSetChanged()
                    }
                }
            }
        }
    }

    /**
     * Prepara un AutoCompleteTextView para que, al tocarlo, SIEMPRE muestre el
     * desplegable completo (seleccionando el texto actual para que escribir lo
     * reemplace de inmediato) — en vez de depender de que el usuario borre el valor
     * previamente elegido a mano. Sin esto, tocar el campo con un cliente/sucursal ya
     * seleccionado abría un desplegable VACÍO: el Filter de Android solo corre cuando
     * el texto cambia, y `setText(valor, false)` (usado al seleccionar un item) se
     * salta deliberadamente ese disparo, así que nunca había resultados que mostrar.
     */
    private fun habilitarDropdownCompleto(campo: AutoCompleteTextView, hayItems: () -> Boolean) {
        val abrirConTodo = {
            if (hayItems()) {
                campo.selectAll()
                (campo.adapter as? Filterable)?.filter?.filter("") { campo.showDropDown() }
            }
        }
        campo.setOnClickListener { abrirConTodo() }
        campo.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) abrirConTodo() }
    }

    private fun configurarAutoCompleteClientes() {
        val itemsConId = listaClientes.map {
            "${it.nombreCliente ?: "Sin nombre"} (${it.idCliente ?: "-"})" to (it.idCliente ?: "")
        }

        etCliente.setAdapter(crearAdapterFiltrable(itemsConId))
        habilitarDropdownCompleto(etCliente) { listaClientes.isNotEmpty() }

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
        val itemsConId = listaSucursales.map {
            "${it.nombreSucursal ?: "Sin nombre"} (${it.idSucursal ?: "-"})" to (it.idSucursal ?: "")
        }

        etSucursal.setAdapter(crearAdapterFiltrable(itemsConId))
        habilitarDropdownCompleto(etSucursal) { listaSucursales.isNotEmpty() }

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
        else -> imgSlotDer
    }

    private fun tarjetaDelSlot(slot: String): View = when (slot) {
        "izq" -> cardSlotIzq
        else -> cardSlotDer
    }

    /** Vuelve a cargar las miniaturas de los 3 slots para el cliente actualmente seleccionado. */
    private fun actualizarSlotsImagenes() {
        val idCliente = idClienteSeleccionado
        if (idCliente.isBlank()) {
            tvImagenesHint.text = "Selecciona un cliente para ver sus imágenes"
            btnBuscarImagenesCliente.isEnabled = false
            SLOTS_PREVIEW.forEach { (slot, _) -> vistaDelSlot(slot).setImageDrawable(null) }
            return
        }
        tvImagenesHint.text = "Toca una imagen para previsualizarla"
        btnBuscarImagenesCliente.isEnabled = true

        lifecycleScope.launch {
            val archivos = withContext(Dispatchers.IO) {
                SLOTS_PREVIEW.associate { (slot, _) ->
                    slot to ClienteLogoCache.rutaImagenSlotActual(this@ClienteSettingsActivity, idCliente, slot)
                }
            }
            archivos.forEach { (slot, archivo) ->
                cargarImagenEnVista(vistaDelSlot(slot), archivo, anchoObjetivoDp = 150)
            }
            // Si había un slot en previsualización grande, refrescar también esa vista.
            slotSeleccionado?.let { mostrarPreviewGrande(it, recargarSlots = false) }
        }
    }

    private fun mostrarPreviewGrande(slot: String, recargarSlots: Boolean = true) {
        slotSeleccionado = slot
        val etiqueta = SLOTS_PREVIEW.firstOrNull { it.first == slot }?.second ?: slot

        if (idClienteSeleccionado.isBlank()) {
            tvSlotSeleccionado.text = "Selecciona primero un cliente"
            imgPreviewGrande.setImageDrawable(null)
            btnSeleccionarImagen.isEnabled = false
            return
        }

        tvSlotSeleccionado.text = etiqueta
        btnSeleccionarImagen.isEnabled = true

        // Resaltar la tarjeta del slot activo para que quede claro cuál se está previsualizando.
        SLOTS_PREVIEW.forEach { (s, _) -> tarjetaDelSlot(s).alpha = if (s == slot) 1f else 0.55f }

        val archivo = ClienteLogoCache.archivoOverride(this, idClienteSeleccionado, slot)
            ?: ClienteLogoCache.rutaImagenSlotActual(this, idClienteSeleccionado, slot)
        cargarImagenEnVista(imgPreviewGrande, archivo, anchoObjetivoDp = 500)
    }

    /**
     * [anchoObjetivoDp]: ancho aproximado al que se va a mostrar la imagen (miniatura chica vs.
     * previsualización grande), usado SOLO para decodificar en resolución razonable los SVG que
     * en realidad envuelven una imagen rasterizada (ver [ClienteLogoCache.decodificarSvgEnvueltoComoImagen]
     * — si no se hace así, logos de varios MB/miles de px agotan memoria en tablets de gama baja
     * y el logo termina sin mostrarse). Los SVG vectoriales reales y los PNG/JPG normales siguen
     * su pipeline habitual con Coil, que ya resuelve bien esos casos.
     */
    private fun cargarImagenEnVista(imageView: ImageView, archivo: File?, anchoObjetivoDp: Int) {
        if (archivo == null) {
            imageView.setImageResource(R.drawable.ic_info)
            return
        }
        val anchoObjetivoPx = (anchoObjetivoDp * resources.displayMetrics.density).toInt()

        lifecycleScope.launch {
            val bitmapLiviano = withContext(Dispatchers.IO) {
                ClienteLogoCache.decodificarSvgEnvueltoComoImagen(archivo, anchoObjetivoPx)
            }
            if (bitmapLiviano != null) {
                imageView.setImageBitmap(bitmapLiviano)
                return@launch
            }

            // No era un SVG "envuelto": o es un SVG vectorial real, o no es SVG. Pipeline normal.
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
    }

    /**
     * "Seleccionar imagen" no abre ningún selector de archivos: su única función es tomar la
     * imagen que ya se está previsualizando (izq/der del cliente elegido, ya sea la descargada
     * del servidor o un override previo) y aplicarla como logo por defecto de la app (pantalla
     * principal, PDF, etc.), sin importar si este cliente es el "activo" del dispositivo. La
     * firma no cuenta como logotipo de marca, así que el botón no aplica para ese slot.
     */
    private fun aplicarComoLogoPorDefecto() {
        val slot = slotSeleccionado
        val idCliente = idClienteSeleccionado
        if (idCliente.isBlank() || slot == null) {
            Toast.makeText(this, "Selecciona primero un cliente y una imagen", Toast.LENGTH_SHORT).show()
            return
        }
        if (slot != "izq" && slot != "der") {
            Toast.makeText(this, "La firma no se puede usar como logo de la app", Toast.LENGTH_SHORT).show()
            return
        }
        val archivo = ClienteLogoCache.archivoOverride(this, idCliente, slot)
            ?: ClienteLogoCache.rutaImagenSlotActual(this, idCliente, slot)
        if (archivo == null) {
            Toast.makeText(this, "Este cliente no tiene imagen en ese slot. Usa \"Buscar imágenes de cliente\" primero.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LogoSyncHelper.aplicarLogoSeleccionado(this@ClienteSettingsActivity, idCliente, slot, archivo.absolutePath)
            }
            Toast.makeText(this@ClienteSettingsActivity, "✅ Logo de la app actualizado", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Descarga/actualiza explícitamente desde el servidor los logos y la firma del cliente
     * seleccionado (usuariosWeb/logosCliente), y refresca las previsualizaciones. Un override
     * local (imagen elegida a mano) sigue ganando siempre sobre lo que traiga el servidor para
     * ese mismo slot — este botón no lo reemplaza, solo refresca los slots sin override.
     */
    private fun buscarImagenesDeCliente() {
        val idCliente = idClienteSeleccionado
        if (idCliente.isBlank()) {
            Toast.makeText(this, "Selecciona primero un cliente", Toast.LENGTH_SHORT).show()
            return
        }
        btnBuscarImagenesCliente.isEnabled = false
        btnBuscarImagenesCliente.text = "Buscando imágenes..."

        lifecycleScope.launch {
            val logos = withContext(Dispatchers.IO) {
                runCatching { ClienteLogoCache.obtener(this@ClienteSettingsActivity, idCliente) }.getOrNull()
            }
            actualizarSlotsImagenes()
            btnBuscarImagenesCliente.isEnabled = true
            btnBuscarImagenesCliente.text = "Buscar imágenes de cliente"

            if (logos != null) {
                Toast.makeText(this@ClienteSettingsActivity, "✅ Imágenes de $idCliente actualizadas", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ClienteSettingsActivity, "No se encontraron imágenes para este cliente en el servidor", Toast.LENGTH_LONG).show()
            }
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
