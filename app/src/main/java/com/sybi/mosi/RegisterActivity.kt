package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.network.UsuarioWeb
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RegisterActivity : BaseActivity() {

    private var currentColor: String = "#0F3E82"
    private var fechaNacimiento: String = "" // dd/MM/yyyy

    private lateinit var btnGuardar: Button
    private lateinit var btnCancelar: Button
    private lateinit var etSearchQuery: EditText
    private lateinit var btnSearch: Button

    // Datos personales
    private lateinit var etRegNombre: EditText
    private lateinit var etRegApellidoPaterno: EditText
    private lateinit var etRegApellidoMaterno: EditText
    private lateinit var etRegFolio: EditText
    private lateinit var etRegCurp: EditText
    private lateinit var etRegTelefono: EditText
    private lateinit var etRegCorreo: EditText
    private lateinit var etRegDireccion: EditText
    private lateinit var etRegTarjetaIc: EditText
    private lateinit var layoutTarjetaIcContainer: LinearLayout

    private lateinit var icCardReceiver: BroadcastReceiver

    // ✅ Fecha con spinners
    private lateinit var spDia: Spinner
    private lateinit var spMes: Spinner
    private lateinit var spAnio: Spinner

    // ✅ Género con RadioButtons
    private lateinit var rgGenero: RadioGroup
    private lateinit var rbMasculino: RadioButton
    private lateinit var rbFemenino: RadioButton

    // ✅ Lista de resultados (búsqueda por folio)
    private lateinit var resultadosContainer: LinearLayout
    private lateinit var listaResultados: LinearLayout

    companion object {
        private const val TAG = "RegisterActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // ── Referencias ──
        val sideBar = findViewById<View>(R.id.sideBarLayout)
        btnGuardar = findViewById(R.id.btnGuardarPaciente)
        btnCancelar = findViewById(R.id.btnCancelar)
        etSearchQuery = findViewById(R.id.etSearchQuery)
        btnSearch = findViewById(R.id.btnSearchPaciente)

        etRegNombre = findViewById(R.id.etRegNombre)
        etRegApellidoPaterno = findViewById(R.id.etRegApellidoPaterno)
        etRegApellidoMaterno = findViewById(R.id.etRegApellidoMaterno)
        etRegFolio = findViewById(R.id.etRegFolio)
        etRegCurp = findViewById(R.id.etRegCurp)
        etRegTelefono = findViewById(R.id.etRegTelefono)
        etRegCorreo = findViewById(R.id.etRegCorreo)
        etRegDireccion = findViewById(R.id.etRegDireccion)
        etRegTarjetaIc = findViewById(R.id.etRegTarjetaIc)
        layoutTarjetaIcContainer = findViewById(R.id.layoutTarjetaIcContainer)

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val icLoginEnabled = userPrefs.getBoolean("login_ic", true)
        if (!icLoginEnabled) {
            layoutTarjetaIcContainer.visibility = View.GONE
        } else {
            layoutTarjetaIcContainer.visibility = View.VISIBLE
        }

        // ✅ Registrar receptor para lector de tarjeta IC
        icCardReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "DEVICE_DATA_RECEIVED" && intent.getStringExtra("type") == "IC_CARD") {
                    val cardNumber = intent.getStringExtra("card_number") ?: return
                    etRegTarjetaIc.setText(cardNumber)
                    Toast.makeText(this@RegisterActivity, "Tarjeta IC leída: $cardNumber", Toast.LENGTH_SHORT).show()
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(icCardReceiver, IntentFilter("DEVICE_DATA_RECEIVED"))

        spDia = findViewById(R.id.spDia)
        spMes = findViewById(R.id.spMes)
        spAnio = findViewById(R.id.spAnio)

        rgGenero = findViewById(R.id.rgGenero)
        rbMasculino = findViewById(R.id.rbMasculino)
        rbFemenino = findViewById(R.id.rbFemenino)

        resultadosContainer = findViewById(R.id.resultadosContainer)
        listaResultados = findViewById(R.id.listaResultados)

        // ── Tema ──
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")!!
        currentColor = savedColor
        sideBar.setBackgroundColor(Color.parseColor(savedColor))
        btnGuardar.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))
        btnSearch.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(savedColor))

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                currentColor = newColor
                sideBar.setBackgroundColor(Color.parseColor(newColor))
                btnGuardar.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
                btnSearch.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // ── Botones ──
        findViewById<View>(R.id.btnBackRegister).setOnClickListener { finish() }
        btnCancelar.setOnClickListener { finish() }
        btnGuardar.setOnClickListener { guardarPaciente() }
        btnSearch.setOnClickListener { buscarPaciente() }

        // ── Configurar spinners y radio buttons ──
        setupFechaSpinners()
    }

    // ==========================================
    // BÚSQUEDA (teléfono, CURP y folio)
    // ==========================================
    private fun buscarPaciente() {
        val query = etSearchQuery.text.toString().trim()
        if (query.isEmpty()) {
            toast("Ingrese teléfono, CURP o folio para buscar")
            return
        }

        // Ocultar lista anterior
        resultadosContainer.visibility = View.GONE
        listaResultados.removeAllViews()

        btnSearch.isEnabled = false
        btnSearch.text = "Buscando..."

        val repo = PacienteRemoteRepository(this)

        lifecycleScope.launch {
            try {
                val resultado = withTimeout(8000) {
                    withContext(Dispatchers.IO) {
                        when {
                            // CURP: 18 caracteres alfanuméricos
                            query.length == 18 -> repo.buscarPorCurp(query)
                            // Folio: solo dígitos, 1-8 caracteres
                            query.all { it.isDigit() } && query.length in 1..8 -> repo.buscarPorFolio(query)
                            // Teléfono: solo dígitos, 10+ caracteres
                            query.all { it.isDigit() } && query.length >= 10 -> repo.buscarPorCelular(query)
                            else -> repo.buscarPorCurp(query) // fallback
                        }
                    }
                }

                btnSearch.isEnabled = true
                btnSearch.text = "Buscar"

                when {
                    resultado == null -> toast("No se encontró el paciente")

                    // Búsqueda por folio: puede devolver múltiples usuarios
                    resultado is PacienteRemoteRepository.ResultadoMultiple -> {
                        if (resultado.usuarios.size == 1) {
                            toast("Paciente encontrado")
                            autocompletarCampos(resultado.usuarios.first())
                        } else {
                            mostrarListaResultados(resultado.usuarios)
                        }
                    }

                    // Búsqueda por teléfono o CURP: un solo resultado
                    resultado is PacienteRemoteRepository.ResultadoBusqueda -> {
                        toast("Paciente encontrado")
                        autocompletarCampos(resultado.usuarioWeb)
                    }

                    else -> toast("No se encontró el paciente")
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "⏰ Tiempo de búsqueda agotado")
                btnSearch.isEnabled = true
                btnSearch.text = "Buscar"
                toast("La búsqueda tardó demasiado. Verifique su conexión.")
            } catch (e: Exception) {
                Log.e(TAG, "Error en la búsqueda: ${e.message}", e)
                btnSearch.isEnabled = true
                btnSearch.text = "Buscar"
                toast("Error al buscar: ${e.message}")
            }
        }
    }

    /**
     * Muestra la lista de usuarios encontrados por folio.
     */
    private fun mostrarListaResultados(usuarios: List<UsuarioWeb>) {
        listaResultados.removeAllViews()
        resultadosContainer.visibility = View.VISIBLE

        usuarios.forEach { usuario ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundResource(android.R.drawable.list_selector_background)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val nombreCompleto = listOfNotNull(
                usuario.nombre,
                usuario.apellidoPaterno,
                usuario.apellidoMaterno
            ).joinToString(" ").ifBlank { "Sin nombre" }

            val tvNombre = TextView(this).apply {
                text = nombreCompleto
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#333333"))
            }

            val tvDetalle = TextView(this).apply {
                val partes = mutableListOf<String>()
                usuario.folio?.let { partes.add("Folio: $it") }
                usuario.curp?.let { partes.add("CURP: $it") }
                usuario.celular?.let { partes.add("Tel: $it") }
                text = partes.joinToString(" · ").ifBlank { "Sin datos adicionales" }
                textSize = 11f
                setTextColor(Color.parseColor("#757575"))
                setPadding(0, 4, 0, 0)
            }

            row.addView(tvNombre)
            row.addView(tvDetalle)

            row.setOnClickListener {
                autocompletarCampos(usuario)
                resultadosContainer.visibility = View.GONE
                toast("Paciente seleccionado")
            }

            listaResultados.addView(row)

            // Divisor
            listaResultados.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            })
        }
    }

    /**
     * Autocompleta todos los campos a partir de un UsuarioWeb.
     */
    private fun autocompletarCampos(u: UsuarioWeb) {
        etRegNombre.setText(u.nombre?.uppercase() ?: "")
        etRegApellidoPaterno.setText(u.apellidoPaterno?.uppercase() ?: "")
        etRegApellidoMaterno.setText(u.apellidoMaterno?.uppercase() ?: "")

        etRegFolio.setText(u.folio ?: "")
        etRegCurp.setText(u.curp?.uppercase() ?: "")
        etRegTelefono.setText(u.celular ?: u.telefonoCasa ?: "")
        etRegCorreo.setText(u.correo?.lowercase() ?: "")
        etRegDireccion.setText(u.direccion?.uppercase() ?: "")

        // Parsear fecha y setear spinners
        val fechaParseada = parsearFechaNacimiento(u.fechaNacimiento)
        if (fechaParseada != null) {
            fechaNacimiento = fechaParseada
            setFechaEnSpinners(fechaParseada)
        }

        // El género NO se autocompleta
        rgGenero.clearCheck()
    }

    // ==========================================
    // SPINNERS DE FECHA
    // ==========================================
    private fun setupFechaSpinners() {
        // Día: 1..31
        val dias = (1..31).map { it.toString().padStart(2, '0') }
        val adapterDia = ArrayAdapter(this, android.R.layout.simple_spinner_item, dias)
        adapterDia.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spDia.adapter = adapterDia
        spDia.setSelection(0)

        // Mes: 01..12
        val meses = (1..12).map { it.toString().padStart(2, '0') }
        val adapterMes = ArrayAdapter(this, android.R.layout.simple_spinner_item, meses)
        adapterMes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMes.adapter = adapterMes
        spMes.setSelection(0)

        // Año: desde 1900 hasta el año actual (orden descendente)
        val anioActual = Calendar.getInstance().get(Calendar.YEAR)
        val anios = (anioActual downTo 1900).map { it.toString() }
        val adapterAnio = ArrayAdapter(this, android.R.layout.simple_spinner_item, anios)
        adapterAnio.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAnio.adapter = adapterAnio
        spAnio.setSelection(30) // ~hace 30 años por defecto
    }

    /**
     * Setea los spinners de día, mes y año a partir de una fecha dd/MM/yyyy.
     */
    private fun setFechaEnSpinners(fecha: String) {
        try {
            val partes = fecha.split("/")
            if (partes.size != 3) return
            val dia = partes[0].toInt()
            val mes = partes[1].toInt()
            val anio = partes[2].toInt()

            val dias = (1..31).map { it.toString().padStart(2, '0') }
            val meses = (1..12).map { it.toString().padStart(2, '0') }
            val anioActual = Calendar.getInstance().get(Calendar.YEAR)
            val anios = (anioActual downTo 1900).map { it.toString() }

            spDia.setSelection(dias.indexOf(dia.toString().padStart(2, '0')))
            spMes.setSelection(meses.indexOf(mes.toString().padStart(2, '0')))
            spAnio.setSelection(anios.indexOf(anio.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Error seteando fecha en spinners: $fecha", e)
        }
    }

    /**
     * Valida que la fecha seleccionada exista y construye fechaNacimiento.
     */
    private fun fechaEsValida(): Boolean {
        return try {
            val dia = spDia.selectedItem.toString().toInt()
            val mes = spMes.selectedItem.toString().toInt()
            val anio = spAnio.selectedItem.toString().toInt()

            val cal = Calendar.getInstance()
            cal.setLenient(false)
            cal.set(anio, mes - 1, dia)
            cal.time // lanza excepción si la fecha no existe

            if (cal.timeInMillis > System.currentTimeMillis()) return false

            fechaNacimiento = String.format("%02d/%02d/%d", dia, mes, anio)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // GÉNERO
    // ==========================================
    private fun getValorGenero(): String {
        return when {
            rbMasculino.isChecked -> "M"
            rbFemenino.isChecked -> "F"
            else -> ""
        }
    }

    private fun setGenero(valor: String) {
        when (valor.uppercase()) {
            "M", "MASCULINO" -> {
                rbMasculino.isChecked = true
                rbFemenino.isChecked = false
            }
            "F", "FEMENINO" -> {
                rbMasculino.isChecked = false
                rbFemenino.isChecked = true
            }
            else -> rgGenero.clearCheck()
        }
    }

    // ==========================================
    // PARSEO DE FECHA DE LA API
    // ==========================================
    private fun parsearFechaNacimiento(fechaAPI: String?): String? {
        if (fechaAPI.isNullOrBlank()) return null
        return try {
            val formatoEntrada = SimpleDateFormat("MMM d yyyy hh:mma", Locale.US)
            val fecha = formatoEntrada.parse(fechaAPI)
            val formatoSalida = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            fecha?.let { formatoSalida.format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando fecha: $fechaAPI", e)
            null
        }
    }

    // ==========================================
    // VALIDACIONES
    // ==========================================
    private fun validarFormulario(): Boolean {
        val nombre = etRegNombre.text.toString().trim()
        val apPat = etRegApellidoPaterno.text.toString().trim()
        val apMat = etRegApellidoMaterno.text.toString().trim()
        val genero = getValorGenero()

        if (TextUtils.isEmpty(nombre)) { toast("Ingrese nombre"); return false }
        if (TextUtils.isEmpty(apPat)) { toast("Ingrese apellido paterno"); return false }
        if (TextUtils.isEmpty(apMat)) { toast("Ingrese apellido materno"); return false }

        if (!fechaEsValida()) {
            toast("Seleccione una fecha de nacimiento válida")
            return false
        }

        if (TextUtils.isEmpty(genero)) { toast("Seleccione el género"); return false }

        val tel = etRegTelefono.text.toString().trim()
        if (TextUtils.isEmpty(tel)) { toast("Ingrese el número de teléfono"); return false }

        val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val icLoginEnabled = userPrefs.getBoolean("login_ic", true)
        if (icLoginEnabled) {
            val tarjetaIc = etRegTarjetaIc.text.toString().trim()
            if (TextUtils.isEmpty(tarjetaIc)) {
                toast("Ingrese o escanee el número de tarjeta IC")
                return false
            }
        }

        return true
    }

    // ==========================================
    // GUARDAR PACIENTE
    // ==========================================
    private fun guardarPaciente() {
        if (!validarFormulario()) return

        val folio = etRegFolio.text.toString().trim()
        val nombre = etRegNombre.text.toString().trim().uppercase()
        val apPat = etRegApellidoPaterno.text.toString().trim().uppercase()
        val apMat = etRegApellidoMaterno.text.toString().trim().uppercase()
        val tel = etRegTelefono.text.toString().trim()
        val correo = etRegCorreo.text.toString().trim().lowercase()
        val curp = etRegCurp.text.toString().trim().uppercase()
        val direccion = etRegDireccion.text.toString().trim().uppercase()
        val tarjetaIc = etRegTarjetaIc.text.toString().trim()
        val genero = getValorGenero()

        val fechaRegistro = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val nuevoPaciente = Paciente(
            folio = folio,
            nombre = nombre,
            apellido_paterno = apPat,
            apellido_materno = apMat,
            fecha_nacimiento = fechaNacimiento,
            genero = genero,
            curp = curp,
            telefono = tel,
            correo = correo,
            direccion = direccion,
            tarjetaIc = tarjetaIc,
            fecha_registro = fechaRegistro
        )

        btnGuardar.isEnabled = false
        btnGuardar.text = "Guardando..."

        val db = AppDatabase.getInstance(this)
        val dao = db.pacienteDao()

        Thread {
            runBlocking {
                try {
                    // Verificar duplicado por teléfono (si hay)
                    if (tel.isNotEmpty()) {
                        val existenteTel = dao.obtenerPacientePorTelefono(tel)
                        if (existenteTel != null) {
                            runOnUiThread {
                                toast("El teléfono $tel ya está registrado")
                                btnGuardar.isEnabled = true
                                btnGuardar.text = "Registrar paciente"
                            }
                            return@runBlocking
                        }
                    }

                    val idLocal = dao.insertarPaciente(nuevoPaciente)
                    Log.d(TAG, "✅ Paciente insertado: id_local=$idLocal")

                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    val faceLoginEnabled = userPrefs.getBoolean("login_face", true)

                    if (faceLoginEnabled) {
                        runOnUiThread {
                            val intent = Intent(this@RegisterActivity, FaceRegistrationActivity::class.java)
                            intent.putExtra("id_local", idLocal)
                            startActivity(intent)
                            finish()
                        }
                    } else {
                        runOnUiThread { btnGuardar.text = "Buscando en sistema..." }

                        val pacienteLocal = dao.obtenerPacientePorIdLocal(idLocal)
                        if (pacienteLocal == null) {
                            runOnUiThread {
                                toast("Error al recuperar el paciente")
                                btnGuardar.isEnabled = true
                                btnGuardar.text = "Registrar paciente"
                            }
                            return@runBlocking
                        }

                        val repo = PacienteRemoteRepository(this@RegisterActivity)
                        val resultado = repo.buscarPacienteEnAPI(pacienteLocal)

                        if (resultado != null) {
                            val u = resultado.usuarioWeb
                            val pacienteActualizado = pacienteLocal.copy(
                                id_usuario_web = resultado.idPaciente,
                                nombre = u.nombre?.ifBlank { null }?.uppercase() ?: pacienteLocal.nombre,
                                apellido_paterno = u.apellidoPaterno?.ifBlank { null }?.uppercase() ?: pacienteLocal.apellido_paterno,
                                apellido_materno = u.apellidoMaterno?.ifBlank { null }?.uppercase() ?: pacienteLocal.apellido_materno,
                                fecha_nacimiento = parsearFechaNacimiento(u.fechaNacimiento) ?: pacienteLocal.fecha_nacimiento,
                                curp = u.curp?.ifBlank { null }?.uppercase() ?: pacienteLocal.curp,
                                telefono = u.celular ?: u.telefonoCasa ?: pacienteLocal.telefono,
                                correo = u.correo?.trim()?.ifBlank { null }?.lowercase() ?: pacienteLocal.correo,
                                folio = u.folio?.ifBlank { null } ?: pacienteLocal.folio,
                                direccion = u.direccion?.uppercase() ?: pacienteLocal.direccion,
                                fecha_registro_global = parsearFechaRegistroAPI(u.fechaRegistro),
                                sincronizado = true
                            )
                            dao.actualizarPaciente(pacienteActualizado)

                            runOnUiThread {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Paciente vinculado con datos reales (ID: ${resultado.idPaciente})",
                                    Toast.LENGTH_LONG
                                ).show()
                                irALogin()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Paciente guardado localmente. Aún no está en el sistema del doctor.",
                                    Toast.LENGTH_LONG
                                ).show()
                                irALogin()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                    runOnUiThread {
                        toast("Error: ${e.message}")
                        btnGuardar.isEnabled = true
                        btnGuardar.text = "Registrar paciente"
                    }
                }
            }
        }.start()
    }

    private fun parsearFechaRegistroAPI(fechaAPI: String?): String {
        if (fechaAPI.isNullOrBlank()) return ""
        val formatos = listOf(
            SimpleDateFormat("MMM d yyyy hh:mma", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US)
        )
        val formatoSalida = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        for (f in formatos) {
            try {
                f.isLenient = true
                val fecha = f.parse(fechaAPI)
                if (fecha != null) {
                    return formatoSalida.format(fecha)
                }
            } catch (_: Exception) {
                // continuar
            }
        }
        return fechaAPI.ifBlank { "" }
    }

    private fun irALogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(icCardReceiver)
        } catch (_: Exception) {}
    }
}