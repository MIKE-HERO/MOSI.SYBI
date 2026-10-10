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
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.helpers.Camera2Helper
import com.sybi.mosi.helpers.FaceBiometricsHelper
import com.sybi.mosi.helpers.MediaPipeFaceHelper
import com.sybi.mosi.repository.PacienteRemoteRepository
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class FaceRegistrationActivity : BaseActivity() {

    private lateinit var textureView: TextureView
    private lateinit var imgPhotoPreview: ImageView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSkipPhoto: Button
    private lateinit var btnRetakePhoto: Button
    private lateinit var btnSavePhoto: Button
    private lateinit var btnCancel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var topFaceBar: View

    private var camera2Helper: Camera2Helper? = null
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

        // Inicializar modelos (MediaPipe + FaceNet)
        FaceBiometricsHelper.init(this)

        // Inicializar vistas
        topFaceBar = findViewById(R.id.topFaceBar)
        textureView = findViewById(R.id.textureView)
        imgPhotoPreview = findViewById(R.id.imgPhotoPreview)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnSkipPhoto = findViewById(R.id.btnSkipPhoto)
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
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        currentColor = savedColor
        applyColorTheme(savedColor)

        idLocal = intent.getLongExtra("id_local", 0L)

        if (idLocal == 0L) {
            Toast.makeText(this, "Error: no se recibió el ID del paciente", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Configurar botones
        btnCancel.setOnClickListener { finish() }
        btnTakePhoto.setOnClickListener { takePhoto() }
        btnSkipPhoto.setOnClickListener { confirmSkipPhoto() }
        btnRetakePhoto.setOnClickListener { retakePhoto() }
        btnSavePhoto.setOnClickListener { savePatientWithPhoto() }

        // Verificar permisos
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        } else {
            startCamera()
        }

        // Estado inicial
        tvStatus.text = "Coloque su rostro frente a la cámara"
        tvStatus.setTextColor(Color.parseColor("#FF9800"))
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
        camera2Helper?.stopCamera()
        textureView.scaleX = -1f
        camera2Helper = Camera2Helper(this, textureView).also { it.startCamera() }

        textureView.visibility = View.VISIBLE
        imgPhotoPreview.visibility = View.GONE
        btnTakePhoto.visibility = View.VISIBLE
        btnSkipPhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.GONE
        btnSavePhoto.visibility = View.GONE

        tvStatus.text = "Coloque su rostro frente a la cámara"
        tvStatus.setTextColor(Color.parseColor("#FF9800"))
    }

    private fun takePhoto() {
        val bitmap = camera2Helper?.takePhoto() ?: run {
            Toast.makeText(this, "No se pudo capturar la foto", Toast.LENGTH_SHORT).show()
            return
        }

        val mirroredBitmap = mirrorBitmap(bitmap) // siempre espejamos (cámara frontal/USB)

        tvStatus.text = "Procesando rostro..."
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        Thread {
            try {
                val mpResult = MediaPipeFaceHelper.detect(mirroredBitmap)
                val hasFace = mpResult != null && mpResult.faceLandmarks().isNotEmpty()

                if (!hasFace || mpResult == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceRegistrationActivity,
                            "No se detectó un rostro claro. Intente de nuevo.",
                            Toast.LENGTH_LONG
                        ).show()
                        tvStatus.text = "No se detectó rostro, intente de nuevo"
                        tvStatus.setTextColor(Color.parseColor("#F44336"))
                    }
                    return@Thread
                }

                val biometrics = FaceBiometricsHelper.processFace(mirroredBitmap, mpResult)
                if (biometrics == null) {
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceRegistrationActivity,
                            "No se pudo procesar el rostro. Intente de nuevo.",
                            Toast.LENGTH_LONG
                        ).show()
                        tvStatus.text = "Rostro no válido, intente de nuevo"
                        tvStatus.setTextColor(Color.parseColor("#F44336"))
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
                    Toast.makeText(
                        this@FaceRegistrationActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    tvStatus.text = "Error al procesar la foto"
                    tvStatus.setTextColor(Color.parseColor("#F44336"))
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
        imgPhotoPreview.setImageBitmap(bitmap)

        btnTakePhoto.visibility = View.GONE
        btnSkipPhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.VISIBLE
        btnSavePhoto.visibility = View.VISIBLE

        tvStatus.text = "Foto capturada. ¿Desea guardarla, repetir u omitir?"
        tvStatus.setTextColor(Color.parseColor("#4CAF50"))

        camera2Helper?.stopCamera()
        camera2Helper = null
    }

    private fun retakePhoto() {
        capturedBitmap?.recycle()
        capturedBitmap = null

        imgPhotoPreview.visibility = View.GONE
        textureView.visibility = View.VISIBLE

        btnTakePhoto.visibility = View.VISIBLE
        btnSkipPhoto.visibility = View.VISIBLE
        btnRetakePhoto.visibility = View.GONE
        btnSavePhoto.visibility = View.GONE

        tvStatus.text = "Coloque su rostro frente a la cámara del quiosco"
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        startCamera()
    }

    private fun confirmSkipPhoto() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Omitir registro facial")
            .setMessage("Si omite la toma de foto, no podrá iniciar sesión mediante reconocimiento facial en el quiosco.\n\n¿Desea continuar sin foto?")
            .setPositiveButton("Sí, omitir") { _, _ ->
                skipPhoto()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun skipPhoto() {
        btnSkipPhoto.isEnabled = false
        btnTakePhoto.isEnabled = false
        btnRetakePhoto.isEnabled = false
        btnSavePhoto.isEnabled = false
        btnSkipPhoto.text = "Omitiendo..."

        tvStatus.text = "Guardando sin foto..."
        tvStatus.setTextColor(Color.parseColor("#FF9800"))

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                try {
                    val pacienteLocal = pacienteDao.obtenerPacientePorIdLocal(idLocal)
                    if (pacienteLocal == null) {
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "No se encontró el paciente en la BD local",
                                Toast.LENGTH_LONG
                            ).show()
                            btnSkipPhoto.isEnabled = true
                            btnTakePhoto.isEnabled = true
                            btnRetakePhoto.isEnabled = true
                            btnSavePhoto.isEnabled = true
                            btnSkipPhoto.text = "⏭️ Omitir"
                        }
                        return@runBlocking
                    }

                    runOnUiThread { tvStatus.text = "Buscando en sistema..." }

                    val repo = PacienteRemoteRepository(this@FaceRegistrationActivity)
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
                        Log.d(TAG, "🔄 Paciente actualizado con datos de la API (sin foto)")
                        pacienteDao.actualizarPaciente(pacienteActualizado)

                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Registro completado sin foto. Paciente vinculado (ID: ${resultado.idPaciente})",
                                Toast.LENGTH_LONG
                            ).show()
                            irALogin()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Registro completado sin foto. Paciente guardado localmente.",
                                Toast.LENGTH_LONG
                            ).show()
                            irALogin()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error omitiendo foto: ${e.message}", e)
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceRegistrationActivity,
                            "Error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        btnSkipPhoto.isEnabled = true
                        btnTakePhoto.isEnabled = true
                        btnRetakePhoto.isEnabled = true
                        btnSavePhoto.isEnabled = true
                        btnSkipPhoto.text = "⏭️ Omitir"
                    }
                }
            }
        }.start()
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

                    val pacienteConFoto = pacienteLocal.copy(foto = base64Image)
                    pacienteDao.actualizarPaciente(pacienteConFoto)

                    runOnUiThread { btnSavePhoto.text = "Buscando en sistema..." }

                    val repo = PacienteRemoteRepository(this@FaceRegistrationActivity)
                    val resultado = repo.buscarPacienteEnAPI(pacienteConFoto)

                    if (resultado != null) {
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
                        Log.d(TAG, "🔄 Paciente actualizado con datos de la API")
                        pacienteDao.actualizarPaciente(pacienteActualizado)

                        // Actualizar la caché de biometría en memoria con el nuevo paciente
                        com.sybi.mosi.helpers.FaceBiometricsCache.refresh(this@FaceRegistrationActivity)

                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Paciente vinculado con datos reales (ID: ${resultado.idPaciente})",
                                Toast.LENGTH_LONG
                            ).show()
                            irALogin()
                        }
                    } else {
                        // Actualizar la caché de biometría en memoria con el nuevo paciente
                        com.sybi.mosi.helpers.FaceBiometricsCache.refresh(this@FaceRegistrationActivity)

                        runOnUiThread {
                            Toast.makeText(
                                this@FaceRegistrationActivity,
                                "Paciente guardado localmente.",
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
            } catch (_: Exception) {}
        }
        return fechaAPI.ifBlank { "" }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val byteArray = stream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
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
        camera2Helper?.stopCamera()
        camera2Helper = null
        capturedBitmap?.recycle()
        capturedBitmap = null
    }
}