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
import android.view.View
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

class FaceLoginActivity : BaseActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnBackFaceLogin: View
    private lateinit var tvStatus: TextView
    private lateinit var sideBar: View

    private var currentColor: String = "#0F3E82"
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var isProcessing = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // Inicializar vistas
        previewView = findViewById(R.id.previewView)
        btnBackFaceLogin = findViewById(R.id.btnBackFaceLogin)
        tvStatus = findViewById(R.id.tvFaceLoginStatus)
        sideBar = findViewById(R.id.sideBarLayout)

        // ✅ Configurar botón de regresar
        btnBackFaceLogin.setOnClickListener {
            finish()
        }

        // Configurar receptor de color
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Aplicar color guardado
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        // Iniciar cámara
        if (hasCameraPermission()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE
            )
        }
    }

    // ✅ Función para aplicar color del tema
    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        sideBar.setBackgroundColor(color)
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

        // ✅ Usar ImageAnalysis para procesamiento en tiempo real
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .build()

        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            processImageProxy(imageProxy)
        }

        val selector = getAvailableCameraSelector(provider)

        try {
            provider.unbindAll()

            provider.bindToLifecycle(
                this,
                selector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Error al enlazar cámara: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        } catch (_: Exception) {
            CameraSelector.Builder().build()
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            // Configurar detector de rostros con LANDMARK_MODE_ALL para biometría geométrica
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .build()

            val detector = FaceDetection.getClient(options)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        // ✅ Rostro detectado, procesar reconocimiento
                        isProcessing = true
                        tvStatus.text = "Rostro detectado, reconociendo..."
                        tvStatus.setTextColor(Color.parseColor("#FF9800"))

                        val bitmap = imageProxy.toBitmap()
                        val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

                        val face = faces[0]
                        val liveSignature = extraerFirma(face)
                        val bounds = face.boundingBox
                        val left = maxOf(0, bounds.left)
                        val top = maxOf(0, bounds.top)
                        val right = minOf(rotatedBitmap.width, bounds.right)
                        val bottom = minOf(rotatedBitmap.height, bounds.bottom)

                        val croppedFace = if (right > left && bottom > top) {
                            try {
                                Bitmap.createBitmap(rotatedBitmap, left, top, right - left, bottom - top)
                            } catch (e: Exception) {
                                rotatedBitmap
                            }
                        } else {
                            rotatedBitmap
                        }

                        // Procesar reconocimiento con el rostro recortado y su firma geométrica
                        processFaceLogin(croppedFace, liveSignature)
                    } else {
                        tvStatus.text = "Coloque su rostro frente a la cámara"
                        tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                    }
                }
                .addOnFailureListener { e ->
                    // Error al procesar
                }
                .addOnCompleteListener {
                    imageProxy.close()
                    detector.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun processFaceLogin(bitmap: Bitmap, liveSignature: Triple<Float, Float, Float>?) {
        // Redimensionar a tamaño estándar
        val resizedBitmap = resizeBitmap(bitmap, 300, 300)

        // Convertir a Base64
        val base64Image = bitmapToBase64(resizedBitmap)

        // Buscar en base de datos por foto
        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                val pacientes = pacienteDao.obtenerTodosLosPacientes()

                var mejorPaciente: com.sybi.mosi.database.Paciente? = null
                var mayorSimilitud = 0f
                val umbral = 0.78f // Umbral estricto para evitar falsos positivos

                for (paciente in pacientes) {
                    val fotoGuardada = paciente.foto
                    if (!fotoGuardada.isNullOrEmpty()) {
                        val similitud = calcularSimilitudEntreBase64(base64Image, fotoGuardada, liveSignature)
                        android.util.Log.d("FaceLoginActivity", "Paciente ${paciente.nombre} (ID: ${paciente.id_local}) - Similitud final: $similitud")
                        if (similitud > mayorSimilitud) {
                            mayorSimilitud = similitud
                            mejorPaciente = paciente
                        }
                    }
                }

                val pacienteEncontrado = if (mejorPaciente != null && mayorSimilitud >= umbral) mejorPaciente else null

                if (pacienteEncontrado != null) {
                    val paciente = pacienteEncontrado
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceLoginActivity,
                            "¡Reconocimiento exitoso! Bienvenido ${paciente.nombre}",
                            Toast.LENGTH_LONG
                        ).show()

                        val intent = Intent(this@FaceLoginActivity, ProfileActivity::class.java)

                        // ✅ Solo id_local como identificador
                        intent.putExtra("id_local", paciente.id_local)

                        // Datos personales
                        intent.putExtra("nombre", paciente.nombre)
                        intent.putExtra("apellido_paterno", paciente.apellido_paterno)
                        intent.putExtra("apellido_materno", paciente.apellido_materno)
                        intent.putExtra("fecha_nacimiento", paciente.fecha_nacimiento)
                        intent.putExtra("genero", paciente.genero)
                        intent.putExtra("curp", paciente.curp)

                        // Contacto y Dirección
                        intent.putExtra("telefono", paciente.telefono)
                        intent.putExtra("correo", paciente.correo)
                        intent.putExtra("direccion", paciente.direccion)

                        // Sesión
                        val sessionType = this@FaceLoginActivity.intent
                            .getStringExtra("session_type") ?: "measurement"
                        intent.putExtra("session_type", sessionType)

                        startActivity(intent)
                        finish()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@FaceLoginActivity,
                            "No se encontró ningún paciente con este rostro",
                            Toast.LENGTH_LONG
                        ).show()
                        // Permitir reintentar
                        isProcessing = false
                        tvStatus.text = "Intente nuevamente"
                        tvStatus.setTextColor(Color.parseColor("#F44336"))
                    }
                }
            }
        }.start()
    }

    private fun extraerFirma(face: com.google.mlkit.vision.face.Face): Triple<Float, Float, Float>? {
        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
        val mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val bounds = face.boundingBox

        if (leftEye == null || rightEye == null || nose == null || mouth == null || bounds.width() == 0 || bounds.height() == 0) {
            return null
        }

        val eyeDist = Math.hypot((leftEye.x - rightEye.x).toDouble(), (leftEye.y - rightEye.y).toDouble()).toFloat()
        val r1 = eyeDist / bounds.width().toFloat()

        val eyeMidX = (leftEye.x + rightEye.x) / 2f
        val eyeMidY = (leftEye.y + rightEye.y) / 2f
        val eyeToNose = Math.hypot((eyeMidX - nose.x).toDouble(), (eyeMidY - nose.y).toDouble()).toFloat()
        val r2 = eyeToNose / bounds.height().toFloat()

        val noseToMouth = Math.hypot((nose.x - mouth.x).toDouble(), (nose.y - mouth.y).toDouble()).toFloat()
        val r3 = noseToMouth / bounds.height().toFloat()

        return Triple(r1, r2, r3)
    }

    data class FaceData(
        val bitmap: Bitmap,
        val signature: Triple<Float, Float, Float>?
    )

    private fun extraerDatosRostroDeBitmap(bitmap: Bitmap): FaceData? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()

        val detector = FaceDetection.getClient(options)
        try {
            val task = detector.process(image)
            val faces = com.google.android.gms.tasks.Tasks.await(task)
            if (faces.isNotEmpty()) {
                val face = faces[0]
                val signature = extraerFirma(face)
                val bounds = face.boundingBox
                val left = maxOf(0, bounds.left)
                val top = maxOf(0, bounds.top)
                val right = minOf(bitmap.width, bounds.right)
                val bottom = minOf(bitmap.height, bounds.bottom)

                if (right > left && bottom > top) {
                    val cropped = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    detector.close()
                    return FaceData(cropped, signature)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FaceLoginActivity", "Error extrayendo rostro de foto guardada: ${e.message}")
        } finally {
            detector.close()
        }
        return null
    }

    private fun calcularSimilitudEntreBase64(foto1: String, foto2: String, liveFaceSignature: Triple<Float, Float, Float>?): Float {
        try {
            val bytes1 = Base64.decode(foto1, Base64.DEFAULT)
            val bytes2 = Base64.decode(foto2, Base64.DEFAULT)

            val bmp1 = android.graphics.BitmapFactory.decodeByteArray(bytes1, 0, bytes1.size)
            val bmp2 = android.graphics.BitmapFactory.decodeByteArray(bytes2, 0, bytes2.size)

            if (bmp1 != null && bmp2 != null) {
                val faceData2 = extraerDatosRostroDeBitmap(bmp2)
                val bmp2Rostro = faceData2?.bitmap ?: bmp2
                val storedSignature = faceData2?.signature

                val pixelSimilarity = calcularSimilitudCoseno(bmp1, bmp2Rostro)

                // Validación geométrica de biometría facial (landmarks)
                if (liveFaceSignature != null && storedSignature != null) {
                    val diff1 = Math.abs(liveFaceSignature.first - storedSignature.first)
                    val diff2 = Math.abs(liveFaceSignature.second - storedSignature.second)
                    val diff3 = Math.abs(liveFaceSignature.third - storedSignature.third)

                    android.util.Log.d("FaceLoginActivity", "Diferencias geométricas: $diff1, $diff2, $diff3")

                    // Si las proporciones faciales difieren en más del 18%, es otra persona
                    if (diff1 > 0.18f || diff2 > 0.18f || diff3 > 0.18f) {
                        android.util.Log.d("FaceLoginActivity", "❌ Rechazado por proporciones faciales distintas (diferente persona)")
                        return 0.40f // Forzar puntaje bajo para rechazar
                    }
                }

                return pixelSimilarity
            }
        } catch (e: Exception) {
            android.util.Log.e("FaceLoginActivity", "Error calculando similitud: ${e.message}")
        }
        return 0f
    }

    private fun calcularSimilitudCoseno(bmp1: Bitmap, bmp2: Bitmap): Float {
        val size = 64
        val b1 = Bitmap.createScaledBitmap(bmp1, size, size, true)
        val b2 = Bitmap.createScaledBitmap(bmp2, size, size, true)

        val pixels1 = IntArray(size * size)
        val pixels2 = IntArray(size * size)
        b1.getPixels(pixels1, 0, size, 0, 0, size, size)
        b2.getPixels(pixels2, 0, size, 0, 0, size, size)

        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for (i in pixels1.indices) {
            val p1 = pixels1[i]
            val p2 = pixels2[i]

            val r1 = Color.red(p1); val g1 = Color.green(p1); val b1_ch = Color.blue(p1)
            val r2 = Color.red(p2); val g2 = Color.green(p2); val b2_ch = Color.blue(p2)

            val gray1 = 0.299 * r1 + 0.587 * g1 + 0.114 * b1_ch
            val gray2 = 0.299 * r2 + 0.587 * g2 + 0.114 * b2_ch

            dotProduct += gray1 * gray2
            norm1 += gray1 * gray1
            norm2 += gray2 * gray2
        }

        if (norm1 == 0.0 || norm2 == 0.0) return 0f
        return (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2))).toFloat()
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

        // Calcular proporción
        val scale = minOf(
            targetWidth.toFloat() / originalWidth,
            targetHeight.toFloat() / originalHeight
        )

        // Redimensionar manteniendo proporción
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
                Toast.makeText(this, "Se requiere permiso de cámara para el reconocimiento facial", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
    }
}