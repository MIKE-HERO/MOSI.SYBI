package com.sybi.mosi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.text.InputFilter
import android.text.TextUtils
import android.util.Log
import android.util.Base64
import android.view.TextureView
import android.view.View
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.helpers.Camera2Helper
import com.sybi.mosi.helpers.FaceBiometricsHelper
import com.sybi.mosi.helpers.MediaPipeFaceHelper
import com.sybi.mosi.network.UsuarioWeb
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RegisterActivity : BaseActivity() {

    private var currentColor: String = "#0F3E82"
    private var fechaNacimiento: String = "" // dd/MM/yyyy
    private var datosAutocompletados = false
    private var idUsuarioWebAutocompletado: Int? = null

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

    // ✅ Lista de resultados (búsqueda por folio o teléfono)
    private lateinit var resultadosContainer: LinearLayout
    private lateinit var listaResultados: LinearLayout
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    // Paso 2: registro facial
    private lateinit var tvRegisterStep: TextView
    private lateinit var registerContent: View
    private lateinit var stepFotoContent: View
    private lateinit var textureView: TextureView
    private lateinit var imgPhotoPreview: ImageView
    private lateinit var faceRegistrationGuide: View
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSkipPhoto: Button
    private lateinit var btnRetakePhoto: Button
    private lateinit var btnSavePhoto: Button
    private lateinit var tvFaceStatus: TextView

    private var camera2Helper: Camera2Helper? = null
    private var capturedBitmap: Bitmap? = null
    private var idLocalPendienteFoto: Long = 0

    companion object {
        private const val TAG = "RegisterActivity"
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Precarga de modelos de biometría facial (MediaPipe + FaceNet)
        FaceBiometricsHelper.init(this)

        // ── Referencias ──
        val sideBar = findViewById<View>(R.id.sideBarLayout)
        btnGuardar = findViewById(R.id.btnGuardarPaciente)
        btnCancelar = findViewById(R.id.btnCancelar)
        etSearchQuery = findViewById(R.id.etSearchQuery)

        // Forzar mayúsculas en el campo de búsqueda
        etSearchQuery.filters = arrayOf(InputFilter.AllCaps())
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

        // Desactivar autocompletar, sugerencias y predicciones en todos los campos de texto
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        val allEditTexts = listOf(
            etSearchQuery, etRegNombre, etRegApellidoPaterno, etRegApellidoMaterno,
            etRegTelefono, etRegCorreo, etRegTarjetaIc, etRegFolio, etRegCurp, etRegDireccion
        )
        for (et in allEditTexts) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                et.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }

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

        // Paso 2: registro facial
        tvRegisterStep = findViewById(R.id.tvRegisterStep)
        registerContent = findViewById(R.id.registerContent)
        stepFotoContent = findViewById(R.id.stepFotoContent)
        textureView = findViewById(R.id.textureView)
        imgPhotoPreview = findViewById(R.id.imgPhotoPreview)
        faceRegistrationGuide = findViewById(R.id.faceRegistrationGuide)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnSkipPhoto = findViewById(R.id.btnSkipPhoto)
        btnRetakePhoto = findViewById(R.id.btnRetakePhoto)
        btnSavePhoto = findViewById(R.id.btnSavePhoto)
        tvFaceStatus = findViewById(R.id.tvFaceStatus)

        btnTakePhoto.setOnClickListener { takePhoto() }
        btnSkipPhoto.setOnClickListener { confirmSkipPhoto() }
        btnRetakePhoto.setOnClickListener { retakePhoto() }
        btnSavePhoto.setOnClickListener { savePatientWithPhoto() }

        // ── Tema ──
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")!!
        currentColor = savedColor
        applyElegantTheme(Color.parseColor(savedColor), sideBar)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                currentColor = newColor
                applyElegantTheme(Color.parseColor(newColor), sideBar)
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // ── Botones ──
        findViewById<View>(R.id.btnBackRegister).setOnClickListener {
            if (stepFotoContent.visibility == View.VISIBLE) volverAPaso1() else finish()
        }
        btnCancelar.setOnClickListener { finish() }
        btnGuardar.setOnClickListener { guardarPaciente() }
        btnSearch.setOnClickListener { buscarPaciente() }

        // Listener para autocompletar género cuando se escribe o modifica la CURP
        etRegCurp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                autocompletarGeneroDesdeCurp(s?.toString())
            }
        })

        // ── Configurar spinners y radio buttons ──
        setupFechaSpinners()
    }

    private fun applyElegantTheme(accent: Int, sideBar: View) {
        sideBar.setBackgroundColor(accent)
        btnGuardar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#163F5B"))
        btnSearch.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#294F67"))
        btnTakePhoto.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(accent)
        findViewById<TextView>(R.id.tvRegisterTitle).setTextColor(Color.parseColor("#102A43"))

        val fields = listOf(
            etSearchQuery, etRegNombre, etRegApellidoPaterno, etRegApellidoMaterno,
            etRegTelefono, etRegCorreo, etRegTarjetaIc, etRegFolio, etRegCurp, etRegDireccion
        )
        fields.forEach {
            it.setBackgroundResource(R.drawable.bg_input_modern)
            it.setPadding(16.dp, 8.dp, 16.dp, 8.dp)
            it.minHeight = 52.dp
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        event?.let {
            when (it.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = it.rawX
                    swipeStartY = it.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val dx = it.rawX - swipeStartX
                    val dy = it.rawY - swipeStartY
                    if (dx < -180.dp && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.6f) {
                        returnToHome()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun returnToHome() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
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

                    // Búsqueda por folio o teléfono: puede devolver múltiples beneficiarios
                    resultado is PacienteRemoteRepository.ResultadoMultiple -> {
                        when (resultado.usuarios.size) {
                            0 -> toast("No se encontró el paciente")
                            1 -> {
                                toast("Paciente encontrado")
                                autocompletarCampos(resultado.usuarios.first())
                            }
                            else -> mostrarListaResultados(resultado.usuarios)
                        }
                    }

                    // Búsqueda por CURP: un solo resultado
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
     * Muestra la lista de usuarios encontrados por folio o teléfono.
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
        datosAutocompletados = true
        idUsuarioWebAutocompletado = u.idUsuarioWeb?.toIntOrNull()

        etRegNombre.setText(u.nombre?.uppercase() ?: "")
        etRegApellidoPaterno.setText(u.apellidoPaterno?.uppercase() ?: "")
        etRegApellidoMaterno.setText(u.apellidoMaterno?.uppercase() ?: "")

        etRegFolio.setText(u.folio ?: "")
        val curp = u.curp?.uppercase() ?: ""
        etRegCurp.setText(curp)
        etRegTelefono.setText(u.celular ?: u.telefonoCasa ?: "")
        etRegCorreo.setText(u.correo?.lowercase() ?: "")
        etRegDireccion.setText(u.direccion?.uppercase() ?: "")

        // Parsear fecha y setear spinners
        val fechaParseada = parsearFechaNacimiento(u.fechaNacimiento)
        if (fechaParseada != null) {
            fechaNacimiento = fechaParseada
            setFechaEnSpinners(fechaParseada)
        }

        // Autocompletar género desde la CURP ('H' -> Masculino/Hombre, 'M' -> Femenino/Mujer)
        autocompletarGeneroDesdeCurp(curp)
    }

    /**
     * Autocompleta la selección de género a partir del carácter de género en la CURP (posición 10, base 0):
     * 'H' -> Hombre (Masculino)
     * 'M' -> Mujer (Femenino)
     */
    private fun autocompletarGeneroDesdeCurp(curp: String?) {
        if (curp.isNullOrBlank()) return
        val curpLimpia = curp.trim().uppercase()
        if (curpLimpia.length >= 11) {
            when (curpLimpia[10]) {
                'H' -> setGenero("MASCULINO")
                'M' -> setGenero("FEMENINO")
            }
        }
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
            "M", "MASCULINO", "H", "HOMBRE" -> {
                rbMasculino.isChecked = true
                rbFemenino.isChecked = false
            }
            "F", "FEMENINO", "MUJER" -> {
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

        if (datosAutocompletados) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Aviso importante")
                .setMessage("Los cambios se guardarán localmente en el dispositivo. Recuerde que debe ponerse en contacto con el equipo de soporte para corregir esos datos en el sistema.")
                .setPositiveButton("Aceptar") { _, _ -> ejecutarGuardadoPaciente() }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            ejecutarGuardadoPaciente()
        }
    }

    private fun ejecutarGuardadoPaciente() {

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
            fecha_registro = fechaRegistro,
            id_usuario_web = idUsuarioWebAutocompletado,
            sincronizado = datosAutocompletados
        )

        btnGuardar.isEnabled = false
        btnGuardar.text = "Guardando..."

        val db = AppDatabase.getInstance(this)
        val dao = db.pacienteDao()

        Thread {
            runBlocking {
                try {
                    // Si el paciente ya existe localmente (por teléfono, tarjeta IC o CURP),
                    // NO bloqueamos ni creamos un duplicado: sobrescribimos ese registro
                    // con los datos nuevos. La verdad viene del servidor, así que lo que
                    // esté guardado localmente se reemplaza.
                    val existente: Paciente? =
                        (if (tel.isNotEmpty()) dao.obtenerPacientePorTelefono(tel) else null)
                            ?: (if (tarjetaIc.isNotEmpty()) dao.obtenerPacientePorTarjetaIc(tarjetaIc) else null)
                            ?: (if (curp.isNotEmpty()) dao.obtenerPacientePorCurp(curp) else null)

                    val idLocal: Long = if (existente != null) {
                        // Reusamos su id_local para no acumular filas viejas.
                        // Conservamos id_usuario_web y foto para no perderlos.
                        val sobrescrito = nuevoPaciente.copy(
                            id_local = existente.id_local,
                            id_usuario_web = if (datosAutocompletados) (idUsuarioWebAutocompletado ?: existente.id_usuario_web) else existente.id_usuario_web,
                            foto = existente.foto,
                            sincronizado = if (datosAutocompletados) true else false
                        )
                        dao.actualizarPaciente(sobrescrito)
                        Log.d(TAG, "♻️ Paciente existente sobrescrito: id_local=${existente.id_local}")
                        existente.id_local
                    } else {
                        val nuevoId = dao.insertarPaciente(nuevoPaciente)
                        Log.d(TAG, "✅ Paciente insertado: id_local=$nuevoId")
                        nuevoId
                    }

                    val userPrefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
                    val faceLoginEnabled = userPrefs.getBoolean("login_face", true)

                    if (faceLoginEnabled) {
                        idLocalPendienteFoto = idLocal
                        runOnUiThread { irAPasoFoto() }
                    } else {
                        // Ya NO se hace una segunda consulta a la API al guardar.
                        // El paciente queda tal como se capturó/autocompletó localmente.
                        // La sincronización con el servidor se delega a PacienteSyncHelper
                        // en segundo plano, que usa id_usuario_web (no teléfono) y por lo
                        // tanto no sobrescribe con otro paciente que comparte número.
                        runOnUiThread {
                            if (datosAutocompletados) {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Paciente guardado con datos autocompletados",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@RegisterActivity,
                                    "Paciente guardado localmente.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            irALogin()
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

    // ==========================================
    // PASO 2: REGISTRO FACIAL (antes FaceRegistrationActivity)
    // ==========================================
    private fun irAPasoFoto() {
        registerContent.visibility = View.GONE
        stepFotoContent.visibility = View.VISIBLE
        tvRegisterStep.text = "PASO 2 DE 2  •  REGISTRO FACIAL"
        findViewById<TextView>(R.id.tvRegisterTitle).text = "Prepara tu identificación facial"

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        } else {
            startCamera()
        }
    }

    private fun volverAPaso1() {
        camera2Helper?.stopCamera()
        camera2Helper = null
        capturedBitmap?.recycle()
        capturedBitmap = null

        stepFotoContent.visibility = View.GONE
        registerContent.visibility = View.VISIBLE
        tvRegisterStep.text = "PASO 1 DE 2  •  DATOS DEL PACIENTE"
        findViewById<TextView>(R.id.tvRegisterTitle).text = "Completa tu registro"

        btnGuardar.isEnabled = true
        btnGuardar.text = "Completar registro"
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        camera2Helper?.stopCamera()
        textureView.scaleX = -1f
        camera2Helper = Camera2Helper(this, textureView).also { it.startCamera() }

        textureView.visibility = View.VISIBLE
        imgPhotoPreview.visibility = View.GONE
        faceRegistrationGuide.visibility = View.VISIBLE
        btnTakePhoto.visibility = View.VISIBLE
        btnSkipPhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.GONE
        btnSavePhoto.visibility = View.GONE

        tvFaceStatus.text = "Coloque su rostro frente a la cámara"
        tvFaceStatus.setTextColor(Color.parseColor("#FF9800"))
    }

    private fun takePhoto() {
        val bitmap = camera2Helper?.takePhoto() ?: run {
            toast("No se pudo capturar la foto")
            return
        }

        val mirroredBitmap = mirrorBitmap(bitmap) // siempre espejamos (cámara frontal/USB)

        tvFaceStatus.text = "Procesando rostro..."
        tvFaceStatus.setTextColor(Color.parseColor("#FF9800"))

        Thread {
            try {
                val mpResult = MediaPipeFaceHelper.detect(mirroredBitmap)
                val hasFace = mpResult != null && mpResult.faceLandmarks().isNotEmpty()

                if (!hasFace || mpResult == null) {
                    runOnUiThread {
                        toast("No se detectó un rostro claro. Intente de nuevo.")
                        tvFaceStatus.text = "No se detectó rostro, intente de nuevo"
                        tvFaceStatus.setTextColor(Color.parseColor("#F44336"))
                    }
                    return@Thread
                }

                val biometrics = FaceBiometricsHelper.processFace(mirroredBitmap, mpResult)
                if (biometrics == null) {
                    runOnUiThread {
                        toast("No se pudo procesar el rostro. Intente de nuevo.")
                        tvFaceStatus.text = "Rostro no válido, intente de nuevo"
                        tvFaceStatus.setTextColor(Color.parseColor("#F44336"))
                    }
                    return@Thread
                }

                capturedBitmap = mirroredBitmap

                runOnUiThread {
                    showPhotoPreview(mirroredBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando rostro: ${e.message}", e)
                runOnUiThread {
                    toast("Error: ${e.message}")
                    tvFaceStatus.text = "Error al procesar la foto"
                    tvFaceStatus.setTextColor(Color.parseColor("#F44336"))
                }
            }
        }.start()
    }

    private fun mirrorBitmap(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1.0f, 1.0f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun showPhotoPreview(bitmap: Bitmap) {
        textureView.visibility = View.GONE
        imgPhotoPreview.visibility = View.VISIBLE
        faceRegistrationGuide.visibility = View.GONE
        imgPhotoPreview.setImageBitmap(bitmap)

        btnTakePhoto.visibility = View.GONE
        btnSkipPhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.VISIBLE
        btnSavePhoto.visibility = View.VISIBLE

        tvFaceStatus.text = "Revisa tu fotografía. Si se ve clara, continúa con el registro."
        tvFaceStatus.setTextColor(Color.parseColor("#4CAF50"))

        camera2Helper?.stopCamera()
        camera2Helper = null
    }

    private fun retakePhoto() {
        capturedBitmap?.recycle()
        capturedBitmap = null
        startCamera()
    }

    private fun confirmSkipPhoto() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Omitir registro facial")
            .setMessage("Si omite la toma de foto, no podrá iniciar sesión mediante reconocimiento facial en el quiosco.\n\n¿Desea continuar sin foto?")
            .setPositiveButton("Sí, omitir") { _, _ -> skipPhoto() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun skipPhoto() {
        btnSkipPhoto.isEnabled = false
        btnTakePhoto.isEnabled = false
        btnRetakePhoto.isEnabled = false
        btnSavePhoto.isEnabled = false
        btnSkipPhoto.text = "Omitiendo..."

        tvFaceStatus.text = "Guardando sin foto..."
        tvFaceStatus.setTextColor(Color.parseColor("#FF9800"))

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()
        val idLocal = idLocalPendienteFoto

        Thread {
            runBlocking {
                try {
                    val pacienteLocal = pacienteDao.obtenerPacientePorIdLocal(idLocal)
                    if (pacienteLocal == null) {
                        runOnUiThread {
                            toast("No se encontró el paciente en la BD local")
                            btnSkipPhoto.isEnabled = true
                            btnTakePhoto.isEnabled = true
                            btnRetakePhoto.isEnabled = true
                            btnSavePhoto.isEnabled = true
                            btnSkipPhoto.text = "Continuar sin fotografía"
                        }
                        return@runBlocking
                    }

                    // Ya no se consulta la API: el paciente queda como está localmente
                    // (autocompletado o capturado). Evita sobrescribir con otro paciente
                    // que comparte el mismo número de teléfono.
                    runOnUiThread {
                        toast("Registro completado sin foto.")
                        irALogin()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error omitiendo foto: ${e.message}", e)
                    runOnUiThread {
                        toast("Error: ${e.message}")
                        btnSkipPhoto.isEnabled = true
                        btnTakePhoto.isEnabled = true
                        btnRetakePhoto.isEnabled = true
                        btnSavePhoto.isEnabled = true
                        btnSkipPhoto.text = "Continuar sin fotografía"
                    }
                }
            }
        }.start()
    }

    private fun savePatientWithPhoto() {
        val bitmap = capturedBitmap ?: run {
            toast("Primero tome una foto")
            return
        }

        btnSavePhoto.isEnabled = false
        btnSavePhoto.text = "Guardando..."

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()
        val idLocal = idLocalPendienteFoto

        Thread {
            runBlocking {
                try {
                    // La compresión y codificación de una foto grande es costosa: nunca
                    // debe ejecutarse en el hilo principal porque congela el botón y la UI.
                    val base64Image = bitmapToBase64(bitmap)
                    val pacienteLocal = pacienteDao.obtenerPacientePorIdLocal(idLocal)
                    if (pacienteLocal == null) {
                        runOnUiThread {
                            toast("No se encontró el paciente en la BD local")
                            btnSavePhoto.isEnabled = true
                            btnSavePhoto.text = "Guardar y finalizar"
                        }
                        return@runBlocking
                    }

                    // Solo guardamos la foto sobre el registro local ya creado en el Paso 1.
                    // NO se vuelve a consultar la API: los datos del paciente ya son los
                    // correctos (autocompletados por búsqueda o capturados manualmente) y
                    // buscar por teléfono sobrescribía con otro paciente que comparte número.
                    val pacienteConFoto = pacienteLocal.copy(foto = base64Image)
                    pacienteDao.actualizarPaciente(pacienteConFoto)

                    // Refrescamos la caché de biometría en memoria con el nuevo paciente
                    com.sybi.mosi.helpers.FaceBiometricsCache.refresh(this@RegisterActivity)

                    runOnUiThread {
                        toast("Paciente guardado con fotografía.")
                        irALogin()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                    runOnUiThread {
                        toast("Error al guardar: ${e.message}")
                        btnSavePhoto.isEnabled = true
                        btnSavePhoto.text = "Guardar y finalizar"
                    }
                }
            }
        }.start()
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                toast("Se requiere permiso de cámara")
                volverAPaso1()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        camera2Helper?.stopCamera()
        camera2Helper = null
        capturedBitmap?.recycle()
        capturedBitmap = null
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(icCardReceiver)
        } catch (_: Exception) {}
    }
}