package com.sybi.mosi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Informe de qué motor de video (WebView/Chromium) usa este equipo, sin depender de
 * `adb shell dumpsys webviewupdate` — en algunos quioscos ese comando falla desde la
 * computadora conectada (según el equipo, el build de Android restringe qué puede
 * pedir un `adb shell` normal). Todo aquí se obtiene con permisos normales de la app.
 */
object DiagnosticoWebView {

    /** Paquetes que Android puede usar como proveedor de WebView (el orden es el de prioridad habitual). */
    private val CANDIDATOS = listOf(
        "com.google.android.webview" to "WebView de Google (el que se actualiza a mano)",
        "com.android.webview" to "WebView del sistema (de fábrica en muchos equipos sin Play Store)",
        "com.android.chrome" to "Chrome",
        "com.chrome.beta" to "Chrome Beta",
        "com.chrome.dev" to "Chrome Dev",
        "com.chrome.canary" to "Chrome Canary",
    )

    fun generar(context: Context): String {
        val lineas = mutableListOf<String>()
        val pm = context.packageManager

        lineas += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) - ${Build.MANUFACTURER} ${Build.MODEL}"
        lineas += ""

        // 1. Motor realmente cargado ahora mismo: el user-agent lo delata sin tener que abrir
        //    una pantalla de video. WebSettings.getDefaultUserAgent existe desde API 17, no
        //    hace falta crear un WebView en pantalla.
        lineas += "MOTOR DE VIDEO CARGADO AHORA (user-agent)"
        val userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        if (userAgent.isNullOrBlank()) {
            lineas += "   No se pudo leer (WebView no disponible en este equipo)."
        } else {
            val version = Regex("""Chrome/(\d+\.\d+\.\d+\.\d+)""").find(userAgent)?.groupValues?.get(1)
            lineas += if (version != null) "   Chrome/WebView $version" else "   $userAgent"
            lineas += "   Completo: $userAgent"
        }
        lineas += ""

        // 2. Qué candidatos a proveedor de WebView hay instalados y en qué versión.
        lineas += "PAQUETES DE WEBVIEW/CHROME INSTALADOS"
        var alguno = false
        for ((paquete, descripcion) in CANDIDATOS) {
            val info = runCatching { pm.getPackageInfo(paquete, 0) }.getOrNull() ?: continue
            alguno = true
            val actualizado = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(info.lastUpdateTime))
            }.getOrDefault("?")
            lineas += "   - $paquete ($descripcion)"
            lineas += "     versión ${info.versionName}, actualizado $actualizado"
        }
        if (!alguno) {
            lineas += "   Ninguno de los paquetes esperados está visible para la app."
            lineas += "   (En Android 11+ podría hacer falta declarar el paquete en <queries> del manifiesto; ya está hecho aquí)."
        }
        lineas += ""

        val hayPlayStore = runCatching { pm.getPackageInfo("com.android.vending", 0) }.isSuccess
        lineas += "Google Play Store instalado: " + if (hayPlayStore) "sí" else
            "no (WebView no se actualiza solo; hay que instalar el APK a mano)"

        return lineas.joinToString("\n")
    }
}
