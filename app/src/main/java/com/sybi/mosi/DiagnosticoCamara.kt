package com.sybi.mosi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Informe para revisar, desde el propio equipo, si la cámara USB llega a la app:
 * puerto USB en modo anfitrión (OTG), dispositivos USB detectados y cámaras que Android
 * expone para las videollamadas.
 */
object DiagnosticoCamara {

    /** Ids de cámara que expone Android, para poder probar abrir cada una desde fuera. */
    fun idsCamaras(context: Context): List<String> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        return runCatching { cameraManager?.cameraIdList?.toList().orEmpty() }.getOrDefault(emptyList())
    }

    fun generar(context: Context): String {
        val lineas = mutableListOf<String>()
        val pm = context.packageManager

        lineas += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - ${Build.MANUFACTURER} ${Build.MODEL}"
        lineas += ""

        // 1. Puerto USB / OTG
        val usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        lineas += "PUERTO USB (OTG)"
        lineas += marca(usbHost, "El equipo soporta USB en modo anfitrión (OTG)")
        if (!usbHost) {
            lineas += "   Sin esto Android no ve ningún dispositivo USB. Suele ser un ajuste de firmware/placa (modo OTG o anfitrión)."
        }
        lineas += ""

        // 2. Dispositivos USB detectados
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val dispositivos = runCatching { usbManager?.deviceList?.values?.toList().orEmpty() }.getOrDefault(emptyList())
        lineas += "DISPOSITIVOS USB DETECTADOS: ${dispositivos.size}"
        if (dispositivos.isEmpty()) {
            lineas += "   Ninguno. Revisa el cable/adaptador OTG y que la cámara reciba energía."
        }
        var hayUsbVideo = false
        dispositivos.forEach { d ->
            val esVideo = esVideo(d)
            if (esVideo) hayUsbVideo = true
            val nombre = runCatching { d.productName }.getOrNull() ?: "(nombre no disponible)"
            val permiso = runCatching { usbManager?.hasPermission(d) == true }.getOrDefault(false)
            lineas += "   - %04X:%04X  %s".format(d.vendorId, d.productId, nombre)
            lineas += "     tipo: ${if (esVideo) "VIDEO (cámara)" else "clase ${d.deviceClass}"}, permiso de la app: ${if (permiso) "sí" else "no"}"
        }
        lineas += ""

        // 3. Cámaras que Android expone (lo que puede usar la videollamada)
        val permisoCamara = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        lineas += "CÁMARAS DISPONIBLES PARA LA VIDEOLLAMADA"
        lineas += marca(permisoCamara, "Permiso de cámara concedido a la app")
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        val ids = runCatching { cameraManager?.cameraIdList?.toList().orEmpty() }.getOrDefault(emptyList())
        if (ids.isEmpty()) {
            lineas += "   Android no expone ninguna cámara."
        }
        ids.forEach { id ->
            val posicion = runCatching {
                when (cameraManager?.getCameraCharacteristics(id)?.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
                    CameraCharacteristics.LENS_FACING_BACK -> "trasera"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "externa (USB)"
                    else -> "desconocida"
                }
            }.getOrDefault("desconocida")
            lineas += "   - cámara $id: $posicion"
        }
        lineas += marca(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL), "El sistema declara soporte de cámaras externas")
        lineas += ""

        // Conclusión
        lineas += "CONCLUSIÓN"
        lineas += when {
            ids.isNotEmpty() -> "OK: Android expone ${ids.size} cámara(s); la videollamada puede usarlas."
            !usbHost -> "El equipo no expone USB anfitrión (OTG): activar el modo OTG/anfitrión del equipo."
            dispositivos.isEmpty() -> "El puerto OTG está activo pero no se detecta ningún dispositivo USB: revisar cable, adaptador y alimentación."
            hayUsbVideo -> "La cámara USB se detecta, pero Android no la ofrece como cámara del sistema (falta soporte UVC en este equipo/versión de Android). La videollamada no podrá usarla."
            else -> "Se detectan dispositivos USB pero ninguno es una cámara (clase video): revisar que sea la cámara correcta."
        }
        return lineas.joinToString("\n")
    }

    /**
     * Intenta abrir de verdad la cámara [id] con la API nativa de Android (Camera2), directamente,
     * sin pasar por el WebView ni por apiRTC. Sirve para distinguir si un atasco al conectar la
     * videollamada (getUserMedia colgado) es del controlador/hardware de la cámara —también se
     * colgaría o fallaría aquí— o algo propio de Chromium/apiRTC —aquí abriría bien.
     *
     * Se ejecuta solo cuando el usuario pulsa el diagnóstico, nunca durante una consulta real, así
     * que no compite por la cámara con una llamada en curso.
     */
    suspend fun probarApertura(context: Context, id: String, timeoutMs: Long = 6000): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return "sin permiso de cámara"

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "sin acceso al servicio de cámara"
        val hilo = HandlerThread("DiagCamara-$id").apply { start() }
        val handler = Handler(hilo.looper)
        try {
            val inicio = System.currentTimeMillis()
            val resultado = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String> { cont ->
                    val callback = object : CameraDevice.StateCallback() {
                        override fun onOpened(device: CameraDevice) {
                            val ms = System.currentTimeMillis() - inicio
                            device.close()
                            if (cont.isActive) cont.resume("abrió en ${ms} ms")
                        }
                        override fun onDisconnected(device: CameraDevice) {
                            device.close()
                            if (cont.isActive) cont.resume("se desconectó al abrir")
                        }
                        override fun onError(device: CameraDevice, error: Int) {
                            device.close()
                            if (cont.isActive) cont.resume("error $error al abrir (ver CameraDevice.StateCallback)")
                        }
                    }
                    try {
                        manager.openCamera(id, callback, handler)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume("excepción al pedir apertura: ${e.message}")
                    }
                }
            }
            return resultado ?: "TIEMPO AGOTADO tras ${timeoutMs} ms (igual que el atasco de la videollamada: " +
                    "el controlador/hardware de la cámara no responde, no es un problema de la app)"
        } finally {
            hilo.quitSafely()
        }
    }

    private fun marca(ok: Boolean, texto: String) = (if (ok) "[OK] " else "[NO] ") + texto

    private fun esVideo(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
}
