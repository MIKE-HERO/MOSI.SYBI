package com.sybi.mosi

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Captura la cámara externa directamente con Camera2 y entrega los frames como JPEG, para
 * esquivar un bug conocido de Chromium/WebView: su `getUserMedia()` asume que los ids de cámara
 * son índices 0-based, pero el HAL de cámaras externas (USB/UVC) les da ids con offset, lo que
 * hace que la apertura desde el WebView truene o se quede colgada aunque Camera2 la abra bien.
 *
 * Se pide YUV_420_888 (el formato real de preview) en vez de pedirle JPEG directo al ImageReader:
 * en una cámara LEGACY, un ImageReader en formato JPEG hace que cada frame pase por el pipeline de
 * "tomar foto" del shim de Camera1 (lento, ~5-6 fps), mientras que YUV_420_888 usa el camino normal
 * de preview de la cámara (15-30 fps) y el JPEG se comprime aquí mismo, en software, por frame.
 *
 * Los frames se entregan por [onFrame] para que quien los reciba los dibuje en un <canvas> del
 * WebView y arme un MediaStream con `canvas.captureStream()`, en vez de pedirle la cámara al
 * propio WebView.
 */
class CamaraPuenteNativo(private val context: Context) {

    companion object {
        private const val TAG = "CamaraPuenteNativo"
        private const val CALIDAD_JPEG = 65
    }

    private var camaraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var hiloFondo: HandlerThread? = null
    private var handlerFondo: Handler? = null
    private var activo = false
    private var ultimoFrameMs = 0L
    private var intervaloMinimoMs = 0L

    fun iniciar(
        cameraId: String,
        anchoDeseado: Int,
        altoDeseado: Int,
        intervaloMinimoMs: Long = 0L,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (activo) return
        activo = true
        this.intervaloMinimoMs = intervaloMinimoMs
        ultimoFrameMs = 0L

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
            tamanoMasCercano(manager, cameraId, anchoDeseado, altoDeseado)
        }.getOrDefault(anchoDeseado to altoDeseado)

        imageReader = ImageReader.newInstance(ancho, alto, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val ahora = SystemClock.elapsedRealtime()
                val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (ahora - ultimoFrameMs < this@CamaraPuenteNativo.intervaloMinimoMs) return@setOnImageAvailableListener
                    ultimoFrameMs = ahora
                    val jpeg = comprimirJpeg(image)
                    onFrame(jpeg)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo procesar un frame: ${e.message}")
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

    /** Busca, entre los tamaños YUV que en verdad soporta la cámara, el más cercano al deseado. */
    private fun tamanoMasCercano(manager: CameraManager, cameraId: String, ancho: Int, alto: Int): Pair<Int, Int> {
        val mapa = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val tamanos = mapa?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val elegido = tamanos.minByOrNull { abs(it.width - ancho) + abs(it.height - alto) }
        return if (elegido != null) elegido.width to elegido.height else ancho to alto
    }

    /** YUV_420_888 -> NV21 -> JPEG. Más código que pedir JPEG directo, pero corre a la velocidad real de preview. */
    private fun comprimirJpeg(image: Image): ByteArray {
        val nv21 = yuv420888aNv21(image)
        val salida = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), CALIDAD_JPEG, salida)
        return salida.toByteArray()
    }

    private fun yuv420888aNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val nv21 = ByteArray(ySize + width * height / 2)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            var destPos = 0
            val fila = ByteArray(yRowStride)
            for (fila_i in 0 until height) {
                yBuffer.position(fila_i * yRowStride)
                yBuffer.get(fila, 0, width)
                System.arraycopy(fila, 0, nv21, destPos, width)
                destPos += width
            }
        }

        // NV21: después de Y van V y U intercalados, uno por cada bloque de 2x2 píxeles
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        var destPos = ySize
        for (fila in 0 until chromaHeight) {
            var vIndex = fila * vPlane.rowStride
            var uIndex = fila * uPlane.rowStride
            for (col in 0 until chromaWidth) {
                nv21[destPos++] = vBuffer.get(vIndex)
                nv21[destPos++] = uBuffer.get(uIndex)
                vIndex += vPlane.pixelStride
                uIndex += uPlane.pixelStride
            }
        }
        return nv21
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
