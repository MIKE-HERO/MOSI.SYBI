package com.sybi.mosi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/**
 * Helper para abrir URLs en Custom Tabs y para detectar dinámicamente
 * qué navegadores están instalados en el dispositivo.
 */
object CustomTabsHelper {

    private const val TAG = "CustomTabsHelper"

    // Claves de preferencia (se guardan en DevicePrefs)
    const val BROWSER_AUTO = "auto"
    const val BROWSER_FIREFOX = "firefox"
    const val BROWSER_CHROME = "chrome"
    const val BROWSER_FOCUS = "focus"
    const val BROWSER_CUSTOM = "custom"   // para navegadores detectados no listados

    // Paquetes conocidos de Firefox
    val FIREFOX_STANDARD_PACKAGES = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
    )

    val FIREFOX_PACKAGES = listOf(
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
    )

    // Paquetes conocidos de Chrome
    val CHROME_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
    )

    /**
     * Navegadores candidatos que escaneamos. Si añades uno nuevo, agrégalo aquí.
     * El orden importa: los primeros se consideran "más recomendados".
     */
    private val KNOWN_BROWSERS: List<BrowserEntry> = listOf(
        BrowserEntry(
            key = BROWSER_FIREFOX,
            displayName = "Firefox",
            packages = FIREFOX_STANDARD_PACKAGES
        ),
        BrowserEntry(
            key = BROWSER_FOCUS,
            displayName = "Firefox Focus",
            packages = listOf("org.mozilla.focus")
        ),
        BrowserEntry(
            key = BROWSER_CHROME,
            displayName = "Google Chrome",
            packages = CHROME_PACKAGES
        ),
        BrowserEntry(
            key = "edge",
            displayName = "Microsoft Edge",
            packages = listOf("com.microsoft.emmx")
        ),
        BrowserEntry(
            key = "brave",
            displayName = "Brave",
            packages = listOf("com.brave.browser")
        ),
        BrowserEntry(
            key = "opera",
            displayName = "Opera",
            packages = listOf("com.opera.browser", "com.opera.mini.native")
        ),
        BrowserEntry(
            key = "samsung",
            displayName = "Samsung Internet",
            packages = listOf("com.sec.android.app.sbrowser")
        ),
        BrowserEntry(
            key = "duckduckgo",
            displayName = "DuckDuckGo",
            packages = listOf("com.duckduckgo.mobile.android")
        ),
        BrowserEntry(
            key = "vivaldi",
            displayName = "Vivaldi",
            packages = listOf("com.vivaldi.browser")
        ),
        BrowserEntry(
            key = "uc",
            displayName = "UC Browser",
            packages = listOf("com.UCMobile.intl", "com.uc.browser.en")
        ),
    )

    data class BrowserEntry(
        val key: String,
        val displayName: String,
        val packages: List<String>
    )

    data class InstalledBrowser(
        val key: String,
        val displayName: String,
        val packageName: String
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
            } catch (_: PackageManager.NameNotFoundException) {
                // No instalado
            }
        }
        return null
    }

    fun isFirefoxInstalled(context: Context): Boolean =
        getInstalledPackage(context, FIREFOX_PACKAGES) != null

    /**
     * Escanea el dispositivo y devuelve la lista de navegadores conocidos
     * que están realmente instalados, en el orden de KNOWN_BROWSERS.
     */
    fun listInstalledBrowsers(context: Context): List<InstalledBrowser> {
        val resultado = mutableListOf<InstalledBrowser>()
        for (entry in KNOWN_BROWSERS) {
            val pkg = getInstalledPackage(context, entry.packages)
            if (pkg != null) {
                resultado.add(
                    InstalledBrowser(
                        key = entry.key,
                        displayName = entry.displayName,
                        packageName = pkg
                    )
                )
            }
        }
        Log.d(TAG, "🔎 Navegadores instalados: ${resultado.map { it.displayName }}")
        return resultado
    }

    /**
     * Selecciona el paquete del navegador de acuerdo a la preferencia guardada.
     * Si la preferencia no está instalada, intenta con Firefox > Chrome > primero disponible.
     */
    fun getSelectedPackage(
        context: Context,
        preferredBrowser: String = BROWSER_FIREFOX
    ): String? {
        val instalados = listInstalledBrowsers(context)
        if (instalados.isEmpty()) {
            Log.w(TAG, "⚠️ No hay navegadores conocidos instalados")
            return null
        }

        // 1. Intentar la preferencia exacta
        val matchPreferido = instalados.firstOrNull { it.key == preferredBrowser }
        if (matchPreferido != null) return matchPreferido.packageName

        // 2. Si la preferencia era "auto", intentar Firefox
        if (preferredBrowser == BROWSER_AUTO) {
            val firefox = instalados.firstOrNull { it.key == BROWSER_FIREFOX }
            if (firefox != null) return firefox.packageName
        }

        // 3. Fallback: Firefox > Chrome > primero disponible
        val firefox = instalados.firstOrNull { it.key == BROWSER_FIREFOX }
        if (firefox != null) return firefox.packageName

        val chrome = instalados.firstOrNull { it.key == BROWSER_CHROME }
        if (chrome != null) return chrome.packageName

        // 4. Cualquiera instalado
        return instalados.first().packageName
    }

    /**
     * Abre una URL en Custom Tabs aplicando la preferencia de navegador elegida
     * y el color de tema de la barra.
     */
    fun openUrl(
        context: Context,
        url: String,
        preferredBrowser: String = TelemedicineSettingsActivity.getPreferredBrowser(context)
    ): Boolean {
        val uri = url.toUri()
        Log.d(TAG, "🌐 Abriendo URL en CustomTabs: $url (prefBrowser=$preferredBrowser)")

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
        val selectedPackage = getSelectedPackage(context, preferredBrowser)

        if (selectedPackage != null) {
            Log.d(TAG, "📦 Usando navegador: $selectedPackage")
            customTabsIntent.intent.setPackage(selectedPackage)
        } else {
            Log.d(TAG, "⚠️ No se encontró paquete específico, usando CustomTabs genérico")
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

    /**
     * Sobrecarga de compatibilidad.
     */
    fun openUrl(
        context: Context,
        url: String,
        preferFirefox: Boolean
    ): Boolean {
        val pref = if (preferFirefox) BROWSER_FIREFOX else BROWSER_AUTO
        return openUrl(context, url, preferredBrowser = pref)
    }
}