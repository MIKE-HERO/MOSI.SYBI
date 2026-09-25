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
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

class PatientTableActivity : BaseActivity() {

    companion object {
        private const val TAG = "PatientTableActivity"
        private const val CAMERA_PERMISSION_CODE_DIALOG = 300
    }

    private lateinit var patientsContainer: LinearLayout
    private lateinit var btnAddPatient: Button
    private lateinit var btnEditPatient: Button
    private lateinit var btnSyncSelected: Button
    private lateinit var btnDeleteSelected: Button
    private lateinit var topBar: View

    private val patientsList = mutableListOf<Paciente>()
    private val seleccionados = mutableSetOf<Long>()
    private var currentColor: String = "#0F3E82"

    // ✅ Para manejar la edición de foto
    private var idPacienteEditando: Long = 0L
    private var imgEditPreview: com.google.android.material.imageview.ShapeableImageView? = null
    private var nuevaFotoBase64: String? = null

    // ✅ Variables de cámara dentro del diálogo
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var dialogPreviewView: PreviewView? = null
    private var dialogImgPreview: ImageView? = null
    private var dialogTvStatus: TextView? = null
    private var dialogBtnCaptureOrRetake: Button? = null
    private var dialogBtnConfirmPhoto: Button? = null
    private var containerEditData: View? = null
    private var containerCamera: View? = null
    private var currentDialog: android.app.Dialog? = null
    private var isCameraActive = false

    // Ancho estándar de cada columna (en dp)
    private val CELL_WIDTH_DP = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_patient_table)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        patientsContainer = findViewById(R.id.patientsContainer)
        btnAddPatient = findViewById(R.id.btnAddPatient)
        btnEditPatient = findViewById(R.id.btnEditPatient)
        btnSyncSelected = findViewById(R.id.btnSyncSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)
        topBar = findViewById(R.id.topBar)

        findViewById<View>(R.id.btnBackTable).setOnClickListener { finish() }

        btnAddPatient.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        btnEditPatient.setOnClickListener {
            if (seleccionados.size == 1) {
                mostrarDialogoEdicion(seleccionados.first())
            }
        }

        btnSyncSelected.setOnClickListener { sincronizarSeleccionados() }
        btnDeleteSelected.setOnClickListener { confirmarEliminarSeleccionados() }

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        actualizarBotones()
        loadPatients()
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        topBar.setBackgroundColor(color)
        btnAddPatient.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        btnEditPatient.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        btnSyncSelected.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        btnDeleteSelected.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#E53935"))
        findViewById<View>(android.R.id.content).setBackgroundColor(Color.parseColor("#F5F5F5"))
    }

    private fun loadPatients() {
        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                patientsList.clear()
                patientsList.addAll(pacienteDao.obtenerTodosLosPacientes())
                Log.d(TAG, "📋 Pacientes cargados: ${patientsList.size}")

                runOnUiThread {
                    // Limpiar selección de ids que ya no existen
                    val idsExistentes = patientsList.map { it.id_local }.toSet()
                    seleccionados.retainAll(idsExistentes)
                    renderTable()
                    actualizarBotones()
                }
            }
        }.start()
    }

    private fun renderTable() {
        patientsContainer.removeAllViews()

        if (patientsList.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No hay pacientes registrados"
                textSize = 14f
                setTextColor(Color.parseColor("#757575"))
                setPadding(24, 24, 24, 24)
            }
            patientsContainer.addView(empty)
            return
        }

        for (paciente in patientsList) {
            patientsContainer.addView(createPatientRow(paciente))
        }
    }

    private fun createPatientRow(paciente: Paciente): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(8, 4, 8, 4)
            setBackgroundColor(
                if (patientsList.indexOf(paciente) % 2 == 0) Color.WHITE
                else Color.parseColor("#F5F5F5")
            )
        }

        // ✅ CheckBox de selección
        val check = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(currentColor))
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            isChecked = seleccionados.contains(paciente.id_local)
        }
        row.addView(check)

        // 👇 MISMO ORDEN QUE LOS ENCABEZADOS DEL XML SIMPLIFICADO
        row.addView(cell(paciente.id_local.toString()))
        row.addView(cell(paciente.id_usuario_web?.toString() ?: "—"))
        row.addView(cell(paciente.folio.ifEmpty { "—" }))
        row.addView(cell(paciente.nombre))
        row.addView(cell(paciente.apellido_paterno))
        row.addView(cell(paciente.apellido_materno))
        row.addView(cell(paciente.fecha_nacimiento))
        row.addView(cell(paciente.genero))
        row.addView(cell(paciente.curp.ifEmpty { "—" }))
        row.addView(cell(paciente.telefono))
        row.addView(cell(paciente.correo.ifEmpty { "—" }))
        row.addView(cell(paciente.direccion.ifEmpty { "—" }))
        row.addView(cell(paciente.fecha_registro.ifEmpty { "—" }))
        row.addView(cell(paciente.fecha_registro_global.ifEmpty { "—" }))

        row.setOnClickListener {
            toggleSeleccion(paciente.id_local)
            check.isChecked = seleccionados.contains(paciente.id_local)
        }

        return row
    }

    private fun toggleSeleccion(idLocal: Long) {
        if (seleccionados.contains(idLocal)) {
            seleccionados.remove(idLocal)
        } else {
            seleccionados.add(idLocal)
        }
        actualizarBotones()
    }

    private fun actualizarBotones() {
        val count = seleccionados.size
        btnAddPatient.isEnabled = count == 0
        btnEditPatient.isEnabled = count == 1
        btnSyncSelected.isEnabled = count > 0
        btnDeleteSelected.isEnabled = count > 0

        val alphaDisabled = 0.5f
        btnAddPatient.alpha = if (btnAddPatient.isEnabled) 1.0f else alphaDisabled
        btnEditPatient.alpha = if (btnEditPatient.isEnabled) 1.0f else alphaDisabled
        btnSyncSelected.alpha = if (btnSyncSelected.isEnabled) 1.0f else alphaDisabled
        btnDeleteSelected.alpha = if (btnDeleteSelected.isEnabled) 1.0f else alphaDisabled
    }

    private fun cell(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(CELL_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(4, 4, 4, 4)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    private fun dpToPx(dp: Int): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun sincronizarSeleccionados() {
        if (seleccionados.isEmpty()) return

        Toast.makeText(this, "🔄 Sincronizando pacientes...", Toast.LENGTH_SHORT).show()

        Thread {
            runBlocking {
                val db = AppDatabase.getInstance(this@PatientTableActivity)
                val repo = PacienteRemoteRepository(this@PatientTableActivity)
                var exitos = 0

                for (id in seleccionados) {
                    val p = db.pacienteDao().obtenerPacientePorIdLocal(id)
                    if (p != null) {
                        val ok = PacienteSyncHelper.sincronizarPaciente(
                            paciente = p,
                            pacienteDao = db.pacienteDao(),
                            resultadoDao = db.resultadoDao(),
                            repo = repo
                        )
                        if (ok) exitos++
                    }
                }

                runOnUiThread {
                    Toast.makeText(
                        this@PatientTableActivity,
                        "✅ Sincronizados $exitos de ${seleccionados.size}",
                        Toast.LENGTH_LONG
                    ).show()
                    loadPatients()
                }
            }
        }.start()
    }

    private fun confirmarEliminarSeleccionados() {
        if (seleccionados.isEmpty()) return

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirmar eliminación")
            .setMessage("¿Eliminar los ${seleccionados.size} pacientes seleccionados?")
            .setPositiveButton("Eliminar") { _, _ -> eliminarSeleccionados() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun eliminarSeleccionados() {
        Thread {
            runBlocking {
                val db = AppDatabase.getInstance(this@PatientTableActivity)
                try {
                    val count = seleccionados.size
                    for (id in seleccionados) {
                        db.pacienteDao().eliminarPaciente(id)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@PatientTableActivity,
                            "✅ $count paciente(s) eliminado(s)",
                            Toast.LENGTH_SHORT
                        ).show()
                        seleccionados.clear()
                        loadPatients()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this@PatientTableActivity, "❌ Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun mostrarDialogoEdicion(idLocal: Long) {
        val db = AppDatabase.getInstance(this)
        Thread {
            val p = runBlocking { db.pacienteDao().obtenerPacientePorIdLocal(idLocal) }
            if (p == null) return@Thread

            runOnUiThread {
                val dialog = android.app.Dialog(this@PatientTableActivity)
                dialog.setContentView(R.layout.dialog_edit_patient)
                dialog.window?.setLayout(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setupDialogKeyboardBehavior(dialog)
                currentDialog = dialog

                idPacienteEditando = idLocal
                nuevaFotoBase64 = p.foto

                // Contenedores
                containerEditData = dialog.findViewById(R.id.containerEditData)
                containerCamera = dialog.findViewById(R.id.containerCamera)

                // Prevenir autofoco inicial en el primer EditText
                containerEditData?.isFocusableInTouchMode = true
                containerEditData?.requestFocus()

                // Referencias de campos
                imgEditPreview = dialog.findViewById(R.id.imgEditProfile)
                val btnChange = dialog.findViewById<ImageView>(R.id.btnChangePhoto)
                val etNombre = dialog.findViewById<EditText>(R.id.etEditNombre)
                val etApPaterno = dialog.findViewById<EditText>(R.id.etEditApPaterno)
                val etApMaterno = dialog.findViewById<EditText>(R.id.etEditApMaterno)
                val etTel = dialog.findViewById<EditText>(R.id.etEditTelefono)
                val etCor = dialog.findViewById<EditText>(R.id.etEditCorreo)
                val etCurp = dialog.findViewById<EditText>(R.id.etEditCurp)
                val etDir = dialog.findViewById<EditText>(R.id.etEditDireccion)
                val btnSave = dialog.findViewById<Button>(R.id.btnSaveEdit)
                val btnCancel = dialog.findViewById<Button>(R.id.btnCancelEdit)

                // Referencias de cámara del diálogo
                dialogPreviewView = dialog.findViewById(R.id.dialogPreviewView)
                dialogImgPreview = dialog.findViewById(R.id.dialogImgPhotoPreview)
                dialogTvStatus = dialog.findViewById(R.id.tvCameraStatus)
                dialogBtnCaptureOrRetake = dialog.findViewById(R.id.btnCaptureOrRetake)
                dialogBtnConfirmPhoto = dialog.findViewById(R.id.btnConfirmPhoto)
                val btnCancelCamera = dialog.findViewById<Button>(R.id.btnCancelCamera)

                // Cargar datos
                etNombre.setText(p.nombre)
                etApPaterno.setText(p.apellido_paterno)
                etApMaterno.setText(p.apellido_materno)
                etTel.setText(p.telefono)
                etCor.setText(p.correo)
                etCurp.setText(p.curp)
                etDir.setText(p.direccion)

                val editTexts = listOf(etNombre, etApPaterno, etApMaterno, etTel, etCor, etCurp, etDir)
                editTexts.forEach {
                    it.clearFocus()
                }

                // Foto
                if (p.foto != null) {
                    try {
                        val bytes = android.util.Base64.decode(p.foto, android.util.Base64.DEFAULT)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        imgEditPreview?.setImageBitmap(bmp)
                    } catch (e: Exception) {
                        imgEditPreview?.setImageResource(R.drawable.ic_launcher_background)
                    }
                }
                imgEditPreview?.strokeColor =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(currentColor))
                btnChange.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(currentColor))
                btnSave.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor(currentColor))

                // Acciones
                btnCancel.setOnClickListener { dialog.dismiss() }

                // ✅ Al tocar "cambiar foto" → mostrar cámara inline
                btnChange.setOnClickListener {
                    mostrarCamaraEnDialogo()
                }

                // ✅ Cancelar cámara → volver a los datos
                btnCancelCamera.setOnClickListener {
                    ocultarCamaraEnDialogo()
                }

                // ✅ Botón que alterna entre "Tomar Foto" y "Reintentar"
                dialogBtnCaptureOrRetake?.setOnClickListener {
                    if (capturedBitmap == null) {
                        takePhotoInDialog()
                    } else {
                        retakePhotoInDialog()
                    }
                }

                // ✅ Confirmar foto → guardar en preview y volver a datos
                dialogBtnConfirmPhoto?.setOnClickListener {
                    capturedBitmap?.let { bmp ->
                        imgEditPreview?.setImageBitmap(bmp)
                        val stream = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        nuevaFotoBase64 = android.util.Base64.encodeToString(
                            stream.toByteArray(),
                            android.util.Base64.DEFAULT
                        )
                    }
                    ocultarCamaraEnDialogo()
                }

                btnSave.setOnClickListener {
                    val updated = p.copy(
                        nombre = etNombre.text.toString().trim().uppercase(),
                        apellido_paterno = etApPaterno.text.toString().trim().uppercase(),
                        apellido_materno = etApMaterno.text.toString().trim().uppercase(),
                        telefono = etTel.text.toString().trim(),
                        correo = etCor.text.toString().trim().lowercase(),
                        curp = etCurp.text.toString().trim().uppercase(),
                        direccion = etDir.text.toString().trim().uppercase(),
                        foto = nuevaFotoBase64
                    )

                    Thread {
                        runBlocking { db.pacienteDao().actualizarPaciente(updated) }
                        runOnUiThread {
                            Toast.makeText(
                                this@PatientTableActivity,
                                "✅ Paciente actualizado",
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                            loadPatients()
                        }
                    }.start()
                }

                // Al cerrar el diálogo, liberar cámara
                dialog.setOnDismissListener {
                    cameraProvider?.unbindAll()
                    cameraProvider = null
                    imageCapture = null
                    capturedBitmap = null
                    isCameraActive = false
                    currentDialog = null
                }

                dialog.show()
            }
        }.start()
    }

    // ==========================================
    // MÉTODOS DE CÁMARA EN EL DIÁLOGO
    // ==========================================
    private fun mostrarCamaraEnDialogo() {
        hideKeyboardAndClearFocus()
        containerEditData?.visibility = View.GONE
        containerCamera?.visibility = View.VISIBLE

        capturedBitmap = null
        dialogImgPreview?.visibility = View.GONE
        dialogPreviewView?.visibility = View.VISIBLE
        dialogBtnCaptureOrRetake?.text = "📸 Tomar Foto"
        dialogBtnConfirmPhoto?.visibility = View.GONE
        dialogTvStatus?.text = "Coloque su rostro frente a la cámara"
        dialogTvStatus?.setTextColor(Color.parseColor("#FF9800"))

        // Verificar permisos
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE_DIALOG
            )
            return
        }

        startCameraInDialog()
    }

    private fun startCameraInDialog() {
        val previewView = dialogPreviewView ?: return
        val future = ProcessCameraProvider.getInstance(this)

        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(previewView.display.rotation)
                    .build()

                val selector = getAvailableCameraSelector(provider)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    imageCapture
                )
                isCameraActive = true
            } catch (e: Exception) {
                Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getAvailableCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        return try {
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.Builder().build()
            }
        } catch (e: Exception) {
            CameraSelector.Builder().build()
        }
    }

    private fun takePhotoInDialog() {
        val capture = imageCapture ?: return

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val rotated = rotateBitmap(bitmap, rotation)
                    val resized = resizeBitmap(rotated, 480, 640)
                    capturedBitmap = resized

                    // Mostrar preview
                    dialogPreviewView?.visibility = View.GONE
                    dialogImgPreview?.visibility = View.VISIBLE
                    dialogImgPreview?.setImageBitmap(resized)

                    dialogBtnCaptureOrRetake?.text = "🔄 Reintentar"
                    dialogBtnConfirmPhoto?.visibility = View.VISIBLE
                    dialogTvStatus?.text = "¿Usar esta foto o reintentar?"
                    dialogTvStatus?.setTextColor(Color.parseColor("#4CAF50"))

                    // Detener cámara mientras se muestra la foto
                    cameraProvider?.unbindAll()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@PatientTableActivity,
                        "Error al tomar foto: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun retakePhotoInDialog() {
        capturedBitmap = null
        dialogImgPreview?.visibility = View.GONE
        dialogPreviewView?.visibility = View.VISIBLE
        dialogBtnCaptureOrRetake?.text = "📸 Tomar Foto"
        dialogBtnConfirmPhoto?.visibility = View.GONE
        dialogTvStatus?.text = "Coloque su rostro frente a la cámara"
        dialogTvStatus?.setTextColor(Color.parseColor("#FF9800"))

        startCameraInDialog()
    }

    private fun ocultarCamaraEnDialogo() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        capturedBitmap = null
        isCameraActive = false

        containerCamera?.visibility = View.GONE
        containerEditData?.visibility = View.VISIBLE
        dialogPreviewView?.visibility = View.VISIBLE
        dialogImgPreview?.visibility = View.GONE
    }

    // ==========================================
    // UTILIDADES DE IMAGEN
    // ==========================================
    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun resizeBitmap(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val scale = minOf(
            targetWidth.toFloat() / originalWidth,
            targetHeight.toFloat() / originalHeight
        )
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ==========================================
    // PERMISOS DE CÁMARA
    // ==========================================
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE_DIALOG) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCameraInDialog()
            } else {
                Toast.makeText(this, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show()
                ocultarCamaraEnDialogo()
            }
        }
    }

    // ==========================================
    // LIBERAR CÁMARA AL CERRAR LA ACTIVIDAD
    // ==========================================
    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
    }
}