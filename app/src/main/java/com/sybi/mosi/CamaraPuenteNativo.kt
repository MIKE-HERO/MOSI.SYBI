package com.sybi.mosi

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.abs

/**
 * Captura la cámara externa directamente con Camera2 y entrega los frames como JPEG, para
 * esquivar un bug conocido de Chromium/WebView: su `getUserMedia()` asume que los ids de cámara
 * son índices 0-based, pero el HAL de cámaras externas (USB/UVC) les da ids con offset, lo que
 * hace que la apertura desde el WebView truene o se quede colgada aunque Camera2 la abra bien.
 *
 * Los frames se entregan por [onFrame] para que quien los reciba los dibuje en un <canvas> del
 * WebView y arme un MediaStream con `canvas.captureStream()`, en vez de pedirle la cámara al
 * propio WebView.
 */
class CamaraPuenteNativo(private val context: Context) {

    companion object {
        private const val TAG = "CamaraPuenteNativo"
    }

    private var camaraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var hiloFondo: HandlerThread? = null
    private var handlerFondo: Handler? = null
    private var activo = false

    fun iniciar(
        cameraId: String,
        anchoDeseado: Int,
        altoDeseado: Int,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (activo) return
        activo = true

        val hilo = HandlerThread("CamaraPuenteNativo-$cameraId").apply { start() }
        hiloFondo = hilo
        val handler = Handler(hilo.looper)
        handlerFondo = handler

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) {
            activo = false
            onError("Sin acceso al servicio de cámara")
            return
        }

        val (ancho, alto) = runCatching {
            tamanoJpegMasCercano(manager, cameraId, anchoDeseado, altoDeseado)
        }.getOrDefault(anchoDeseado to altoDeseado)

        imageReader = ImageReader.newInstance(ancho, alto, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    onFrame(bytes)
                } finally {
                    image.close()
                }
            }, handler)
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!activo) {
                        device.close()
                        return
                    }
                    camaraDevice = device
                    iniciarSesion(device, onError)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (activo) onError("La cámara se desconectó")
                    detener()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (activo) onError("Error de cámara (código $error)")
                    detener()
                }
            }, handler)
        } catch (e: Exception) {
            activo = false
            onError("No se pudo abrir la cámara: ${e.message}")
        }
    }

    private fun iniciarSesion(device: CameraDevice, onError: (String) -> Unit) {
        val reader = imageReader ?: return
        val handler = handlerFondo
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configuredSession: CameraCaptureSession) {
                    if (!activo) {
                        configuredSession.close()
                        return
                    }
                    session = configuredSession
                    runCatching {
                        configuredSession.setRepeatingRequest(builder.build(), null, handler)
                    }.onFailure {
                        Log.e(TAG, "No se pudo iniciar la captura repetida: ${it.message}")
                        onError("No se pudo iniciar la captura de video")
                    }
                }

                override fun onConfigureFailed(configuredSession: CameraCaptureSession) {
                    Log.e(TAG, "Configuración de sesión de captura fallida")
                    onError("No se pudo configurar la cámara")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando la sesión de captura: ${e.message}")
            onError("Error preparando la cámara: ${e.message}")
        }
    }

    /** Busca, entre los tamaños JPEG que en verdad soporta la cámara, el más cercano al deseado. */
    private fun tamanoJpegMasCercano(manager: CameraManager, cameraId: String, ancho: Int, alto: Int): Pair<Int, Int> {
        val mapa = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val tamanos = mapa?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val elegido = tamanos.minByOrNull { abs(it.width - ancho) + abs(it.height - alto) }
        return if (elegido != null) elegido.width to elegido.height else ancho to alto
    }

    fun detener() {
        if (!activo) return
        activo = false
        runCatching { session?.close() }
        runCatching { camaraDevice?.close() }
        runCatching { imageReader?.close() }
        session = null
        camaraDevice = null
        imageReader = null
        hiloFondo?.quitSafely()
        hiloFondo = null
        handlerFondo = null
    }
}
