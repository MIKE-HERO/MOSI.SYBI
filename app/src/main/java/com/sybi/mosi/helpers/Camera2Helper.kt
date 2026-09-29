package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Helper de cámara basado en Camera2 nativo.
 * Diseñado para quioscos con cámaras USB externas (RK3288 y similares).
 *
 * Características:
 *  - Detecta primero cámaras con LENS_FACING_EXTERNAL (USB).
 *  - Hace fallback a cualquier cámara disponible si falla.
 *  - Elige un tamaño de preview soportado por el StreamConfigurationMap.
 *  - Reintenta abrir otras cámaras si la primera falla.
 *  - Evita dependencias de CameraX (CameraValidator / LensFacing).
 */
class Camera2Helper(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameAvailable: ((Bitmap) -> Unit)? = null
) {

    companion object {
        private const val TAG = "Camera2Helper"
        private const val DEFAULT_PREVIEW_WIDTH = 640
        private const val DEFAULT_PREVIEW_HEIGHT = 480
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isProcessingFrame = false
    private var isCameraRunning = false

    private var activeCameraId: String = "0"
    private var previewWidth: Int = DEFAULT_PREVIEW_WIDTH
    private var previewHeight: Int = DEFAULT_PREVIEW_HEIGHT

    // Lista de IDs de cámara ordenados por prioridad (EXTERNAL primero)
    private var cameraPriorityList: List<String> = emptyList()
    private var currentCameraIndex: Int = 0

    /**
     * Inicia la cámara. Si el TextureView ya está listo abre de inmediato,
     * de lo contrario espera al listener de la superficie.
     */
    fun startCamera() {
        if (isCameraRunning) return
        isCameraRunning = true

        startBackgroundThread()
        buildCameraPriorityList()

        if (textureView.isAvailable) {
            openNextCameraInternal()
        } else {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    if (isCameraRunning && cameraDevice == null) {
                        openNextCameraInternal()
                    }
                }
                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    /**
     * Construye la lista de cámaras priorizando las EXTERNAL (USB),
     * y luego el resto.
     */
    private fun buildCameraPriorityList() {
        try {
            val cameraIds = cameraManager.cameraIdList
            if (cameraIds.isEmpty()) {
                Log.e(TAG, "❌ No se encontraron cámaras en el dispositivo")
                cameraPriorityList = emptyList()
                return
            }

            val external = mutableListOf<String>()
            val others = mutableListOf<String>()

            for (id in cameraIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                    val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

                    val facingStr = when (lensFacing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN($lensFacing)"
                    }
                    Log.d(TAG, "📷 Cámara ID=$id, LENS_FACING=$facingStr, HW_LEVEL=$hwLevel")

                    if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                        external.add(id)
                    } else {
                        others.add(id)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Error leyendo características de cámara $id: ${e.message}")
                    others.add(id)
                }
            }

            cameraPriorityList = external + others
            currentCameraIndex = 0
            Log.d(TAG, "🎯 Orden de cámaras: $cameraPriorityList (EXTERNAL primero)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error construyendo lista de cámaras: ${e.message}")
            cameraPriorityList = emptyList()
        }
    }

    /**
     * Intenta abrir la siguiente cámara de la lista. Si falla, pasa a la siguiente.
     */
    private fun openNextCameraInternal() {
        if (cameraPriorityList.isEmpty()) {
            Log.e(TAG, "❌ Lista de cámaras vacía, no se puede abrir")
            return
        }
        if (currentCameraIndex >= cameraPriorityList.size) {
            Log.e(TAG, "❌ Todas las cámaras fallaron. Abortando.")
            return
        }

        activeCameraId = cameraPriorityList[currentCameraIndex]
        Log.d(TAG, "▶️ Intentando abrir cámara ID=$activeCameraId (${currentCameraIndex + 1}/${cameraPriorityList.size})")

        // Elegir tamaño de preview soportado
        choosePreviewSize()

        // Configurar ImageReader si hay callback de frames
        if (onFrameAvailable != null) {
            try {
                imageReader?.close()
                imageReader = ImageReader.newInstance(
                    previewWidth, previewHeight, ImageFormat.YUV_420_888, 2
                )
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        if (!isProcessingFrame) {
                            isProcessingFrame = true
                            val bitmap = imageToBitmap(image)
                            image.close()
                            if (bitmap != null) onFrameAvailable.invoke(bitmap)
                            isProcessingFrame = false
                        } else {
                            image.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error procesando cuadro: ${e.message}")
                        runCatching { image.close() }
                        isProcessingFrame = false
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ No se pudo crear ImageReader: ${e.message}")
                imageReader = null
            }
        }

        try {
            cameraManager.openCamera(activeCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    Log.d(TAG, "✅ Cámara $activeCameraId abierta correctamente")
                    cameraDevice = device
                    createCameraPreviewSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(TAG, "⚠️ Cámara $activeCameraId desconectada")
                    device.close()
                    cameraDevice = null
                    tryNextCamera()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "❌ Error en cámara $activeCameraId: $error")
                    device.close()
                    cameraDevice = null
                    tryNextCamera()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Error de permisos: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Excepción abriendo cámara $activeCameraId: ${e.message}")
            tryNextCamera()
        }
    }

    private fun tryNextCamera() {
        currentCameraIndex++
        if (isCameraRunning && currentCameraIndex < cameraPriorityList.size) {
            Log.d(TAG, "🔁 Reintentando con siguiente cámara...")
            openNextCameraInternal()
        } else {
            Log.e(TAG, "❌ No quedan más cámaras por probar")
        }
    }

    /**
     * Elige un tamaño de preview soportado por la cámara activa.
     */
    private fun choosePreviewSize() {
        try {
            val chars = cameraManager.getCameraCharacteristics(activeCameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            val sizes: Array<Size>? = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?: map?.getOutputSizes(SurfaceTexture::class.java)

            if (sizes.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ No hay tamaños soportados, usando ${DEFAULT_PREVIEW_WIDTH}x${DEFAULT_PREVIEW_HEIGHT}")
                previewWidth = DEFAULT_PREVIEW_WIDTH
                previewHeight = DEFAULT_PREVIEW_HEIGHT
                return
            }

            Log.d(TAG, "📐 Tamaños disponibles: ${sizes.joinToString { "${it.width}x${it.height}" }}")

            val target = sizes.minByOrNull {
                abs(it.width - DEFAULT_PREVIEW_WIDTH) + abs(it.height - DEFAULT_PREVIEW_HEIGHT)
            } ?: sizes[0]

            previewWidth = target.width
            previewHeight = target.height
            Log.d(TAG, "✅ Preview elegido: ${previewWidth}x${previewHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error eligiendo tamaño: ${e.message}, usando default")
            previewWidth = DEFAULT_PREVIEW_WIDTH
            previewHeight = DEFAULT_PREVIEW_HEIGHT
        }
    }

    private fun createCameraPreviewSession() {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: run {
            Log.e(TAG, "❌ surfaceTexture null al crear sesión")
            return
        }

        try {
            texture.setDefaultBufferSize(previewWidth, previewHeight)
            val previewSurface = Surface(texture)
            val readerSurface = imageReader?.surface

            val surfaces = mutableListOf<Surface>(previewSurface)
            if (readerSurface != null) surfaces.add(readerSurface)

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                if (readerSurface != null) addTarget(readerSurface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                        Log.d(TAG, "🎥 Sesión de vista previa iniciada (${previewWidth}x${previewHeight})")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error en repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "❌ Falló la configuración de la sesión de preview")
                    tryNextCamera()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creando sesión: ${e.message}")
            tryNextCamera()
        }
    }

    /**
     * Obtiene una captura del fotograma actual desde el TextureView.
     */
    fun takePhoto(): Bitmap? {
        return try {
            textureView.getBitmap(previewWidth, previewHeight)
        } catch (e: Exception) {
            Log.e(TAG, "Error capturando foto: ${e.message}")
            null
        }
    }

    /**
     * Detiene la cámara y libera recursos.
     */
    fun stopCamera() {
        isCameraRunning = false
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            stopBackgroundThread()
            Log.d(TAG, "🛑 Cámara $activeCameraId detenida")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando cámara: ${e.message}")
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2Background").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo hilo: ${e.message}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21, ImageFormat.NV21, image.width, image.height, null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            val bytes = out.toByteArray()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error YUV→Bitmap: ${e.message}")
            null
        }
    }
}