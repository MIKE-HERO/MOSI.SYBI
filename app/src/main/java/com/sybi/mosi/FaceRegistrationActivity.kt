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
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class FaceRegistrationActivity : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var imgPhotoPreview: ImageView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnRetakePhoto: Button
    private lateinit var btnSavePhoto: Button
    private lateinit var btnCancel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var topFaceBar: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var currentColor: String = "#0F3E82"

    private var idLocal: Long = 0

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "FaceRegistrationActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_registration)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Inicializar vistas
        topFaceBar = findViewById(R.id.topFaceBar)
        previewView = findViewById(R.id.previewView)
        imgPhotoPreview = findViewById(R.id.imgPhotoPreview)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnRetakePhoto = findViewById(R.id.btnRetakePhoto)
        btnSavePhoto = findViewById(R.id.btnSavePhoto)
        btnCancel = findViewById(R.id.btnCancelFaceRegistration)
        tvStatus = findViewById(R.id.tvFaceStatus)

        // Receptor de cambio de color
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

        // Aplicar color guardado
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        // Solo recibimos id_local
        idLocal = intent.getLongExtra("id_local", 0L)

        if (idLocal == 0L) {
            Toast.makeText(this, "Error: no se recibió el ID del paciente", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Configurar botones
        btnCancel.setOnClickListener { finish() }
        btnTakePhoto.setOnClickListener { takePhoto() }
        btnRetakePhoto.setOnClickListener { retakePhoto() }
        btnSavePhoto.setOnClickListener { savePatientWithPhoto() }

        // Inicializar cámara
        startCamera()

        // Verificar permisos
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        topFaceBar.setBackgroundColor(color)
        btnTakePhoto.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        btnRetakePhoto.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        btnSavePhoto.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Toast.makeText(this, "Error al iniciar cámara: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder()
            .build()
            .also { preview ->
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(previewView.display.rotation)
            .build()

        val selector = getAvailableCameraSelector(provider)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Error al enlazar cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getAvailableCameraSelector(provider: ProcessCameraProvider): CameraSelector {
        return try {
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.Builder().build()
            }
        } catch (_: Exception) {
            CameraSelector.Builder().build()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()

                    val rotatedBitmap = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)
                    val resizedBitmap = resizeBitmap(rotatedBitmap, 480, 640)

                    capturedBitmap = resizedBitmap
                    showPhotoPreview(resizedBitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@FaceRegistrationActivity,
                        "Error al tomar foto: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showPhotoPreview(bitmap: Bitmap) {
        previewView.visibility = View.GONE
        imgPhotoPreview.visibility = View.VISIBLE
        imgPhotoPreview.setImageBitmap(bitmap)

        btnTakePhoto.visibility = View.GONE
        btnRetakePhoto.visibility = View.VISIBLE
        btnSavePhoto.visibility = View.VISIBLE

        tvStatus.text = "Foto capturada. ¿Desea guardarla o repetir?"
        tvStatus.setTextColor(Color.parseColor("#4CAF50"))

        cameraProvider?.unbindAll()
    }

    private fun retakePhoto() {
        capturedBitmap = null
        imgPhotoPreview.visibility = View.GONE
        previewView.visibility = View.VISIBLE

        btnTakePhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.GONE
        btnSavePhoto.visibility = View.GONE

        tvStatus.text = "Coloque su rostro frente a la cámara del quiosco"
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        startCamera()
    }

    // ============================================================
    // GUARDAR FOTO + BUSCAR EN API + SOBRESCRIBIR CON DATOS REALES
    // ============================================================
    private fun savePatientWithPhoto() {
        val bitmap = capturedBitmap ?: run {
            Toast.makeText(this, "Primero tome una foto", Toast.LENGTH_SHORT).show()
            return
        }

        btnSavePhoto.isEnabled = false
        btnSavePhoto.text = "Guardando..."

        val base64Image = bitmapToBase64(bitmap)

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                try {
                    // 1. Obtener el paciente
                    val pacienteLocal = pacienteDao.obtenerPacientePorIdLocal(idLocal)
                    if (pacienteLocal == null) {
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "No se encontró el paciente en la BD local",
                                Toast.LENGTH_LONG
                            ).show()
                            btnSavePhoto.isEnabled = true
                            btnSavePhoto.text = "Guardar"
                        }
                        return@runBlocking
                    }

                    // 2. Actualizar con la foto
                    val pacienteConFoto = pacienteLocal.copy(foto = base64Image)
                    pacienteDao.actualizarPaciente(pacienteConFoto)

                    // 3. Buscar en API
                    runOnUiThread { btnSavePhoto.text = "Buscando en sistema..." }

                    val repo = PacienteRemoteRepository(this@FaceRegistrationActivity)
                    val resultado = repo.buscarPacienteEnAPI(pacienteConFoto)

                    if (resultado != null) {
                        // 🔥 Sobrescribir con datos REALES de la API
                        val u = resultado.usuarioWeb
                        val pacienteActualizado = pacienteConFoto.copy(
                            id_usuario_web = resultado.idPaciente,
                            nombre = u.nombre?.ifBlank { null }?.uppercase() ?: pacienteConFoto.nombre,
                            apellido_paterno = u.apellidoPaterno?.ifBlank { null }?.uppercase() ?: pacienteConFoto.apellido_paterno,
                            apellido_materno = u.apellidoMaterno?.ifBlank { null }?.uppercase() ?: pacienteConFoto.apellido_materno,
                            fecha_nacimiento = parsearFechaNacimiento(u.fechaNacimiento) ?: pacienteConFoto.fecha_nacimiento,
                            curp = u.curp?.ifBlank { null }?.uppercase() ?: pacienteConFoto.curp,
                            telefono = u.celular ?: u.telefonoCasa ?: pacienteConFoto.telefono,
                            correo = u.correo?.trim()?.ifBlank { null }?.lowercase() ?: pacienteConFoto.correo,
                            folio = u.folio?.ifBlank { null } ?: pacienteConFoto.folio,
                            direccion = u.direccion?.uppercase() ?: pacienteConFoto.direccion,
                            fecha_registro_global = parsearFechaRegistroAPI(u.fechaRegistro),
                            sincronizado = true
                        )
                        Log.d(TAG, "🔄 Paciente actualizado con datos de la API:")
                        Log.d(TAG, "   pacienteLocal antes: nombre='${pacienteLocal.nombre}', apPat='${pacienteLocal.apellido_paterno}', curp='${pacienteLocal.curp}', tel='${pacienteLocal.telefono}'")
                        Log.d(TAG, "   pacienteActualizado: nombre='${pacienteActualizado.nombre}', apPat='${pacienteActualizado.apellido_paterno}', curp='${pacienteActualizado.curp}', tel='${pacienteActualizado.telefono}'")
                        pacienteDao.actualizarPaciente(pacienteActualizado)
                        Log.d(TAG, "✅ actualizarPaciente ejecutado")

                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Paciente vinculado con datos reales (ID: ${resultado.idPaciente})",
                                Toast.LENGTH_LONG
                            ).show()
                            irALogin()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Paciente guardado localmente. Aún no está dado de alta en el sistema del doctor.",
                                Toast.LENGTH_LONG
                            ).show()
                            irALogin()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceRegistrationActivity,
                            "Error al guardar: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        btnSavePhoto.isEnabled = true
                        btnSavePhoto.text = "Guardar"
                    }
                }
            }
        }.start()
    }

    private fun irALogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Convierte la fecha de la API ("Oct 1 1958 12:00AM") a formato dd/MM/yyyy.
     */
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

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}