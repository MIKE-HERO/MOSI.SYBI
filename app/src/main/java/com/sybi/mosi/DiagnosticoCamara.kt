package com.sybi.mosi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Informe para revisar, desde el propio equipo, si la cámara USB llega a la app:
 * puerto USB en modo anfitrión (OTG), dispositivos USB detectados y cámaras que Android
 * expone para las videollamadas.
 */
object DiagnosticoCamara {

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

    private fun marca(ok: Boolean, texto: String) = (if (ok) "[OK] " else "[NO] ") + texto

    private fun esVideo(d: UsbDevice): Boolean =
        d.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
}
