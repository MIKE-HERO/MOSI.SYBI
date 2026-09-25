package com.sybi.mosi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Helper para abrir URLs en Custom Tabs, priorizando Firefox
 * y aplicando el color de tema configurado en LogoSettingsActivity.
 */
object CustomTabsHelper {

    private const val TAG = "CustomTabsHelper"

    val FIREFOX_PACKAGES = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus"
    )

    val CHROME_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev"
    )

    /**
     * Devuelve el nombre del paquete si está instalado en el dispositivo.
     */
    fun getInstalledPackage(context: Context, packages: List<String>): String? {
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // No instalado
            }
        }
        return null
    }

    /**
     * Verifica si Firefox está instalado en el dispositivo.
     */
    fun isFirefoxInstalled(context: Context): Boolean {
        return getInstalledPackage(context, FIREFOX_PACKAGES) != null
    }

    /**
     * Abre una URL en Custom Tabs, priorizando Firefox si está instalado
     * y configurando el color de la barra de URL según el tema guardado.
     */
    fun openUrl(context: Context, url: String, preferFirefox: Boolean = true): Boolean {
        val uri = Uri.parse(url)
        Log.d(TAG, "🌐 Abriendo URL en CustomTabs: $url (preferFirefox=$preferFirefox)")

        // Cargar el color guardado en AppPrefs ("BackgroundColor")
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColorHex = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"

        val builder = CustomTabsIntent.Builder()
        builder.setShowTitle(true)

        try {
            val colorInt = Color.parseColor(savedColorHex)
            val params = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(colorInt)
                .setNavigationBarColor(colorInt)
                .build()
            builder.setDefaultColorSchemeParams(params)
            Log.d(TAG, "🎨 Color de barra de URL configurado: $savedColorHex")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al aplicar color a la barra de URL: ${e.message}")
        }

        val customTabsIntent = builder.build()

        val firefoxPackage = getInstalledPackage(context, FIREFOX_PACKAGES)
        val chromePackage = getInstalledPackage(context, CHROME_PACKAGES)

        val selectedPackage = if (preferFirefox && firefoxPackage != null) {
            firefoxPackage
        } else if (chromePackage != null) {
            chromePackage
        } else {
            firefoxPackage
        }

        if (selectedPackage != null) {
            Log.d(TAG, "📦 Usando navegador: $selectedPackage")
            customTabsIntent.intent.setPackage(selectedPackage)
        } else {
            Log.d(TAG, "⚠️ No se encontró Firefox o Chrome específico, usando CustomTabs genérico.")
        }

        return try {
            customTabsIntent.launchUrl(context, uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al lanzar CustomTabsIntent con paquete $selectedPackage: ${e.message}")
            try {
                val fallbackTabsIntent = CustomTabsIntent.Builder().setShowTitle(true).build()
                fallbackTabsIntent.launchUrl(context, uri)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Error al lanzar CustomTabsIntent genérico: ${e2.message}")
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri)
                    if (selectedPackage != null) {
                        browserIntent.setPackage(selectedPackage)
                    }
                    context.startActivity(browserIntent)
                    true
                } catch (e3: Exception) {
                    Log.e(TAG, "❌ Falló el lanzamiento del Intent de navegador: ${e3.message}")
                    Toast.makeText(context, "No se encontró un navegador compatible instalado", Toast.LENGTH_LONG).show()
                    false
                }
            }
        }
    }
}
