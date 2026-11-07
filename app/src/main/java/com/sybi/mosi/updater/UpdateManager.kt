package com.sybi.mosi.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.sybi.mosi.admin.PermisosAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class GitHubRelease(
    val tagName: String,
    val name: String,
    val downloadUrl: String
)

class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"
        private const val DEFAULT_REPO_OWNER = "MIKE-HERO"
        private const val DEFAULT_REPO_NAME = "MOSI.SYBI"

        /**
         * Flag oculto de Android: INSTALL_ALLOW_DOWNGRADE.
         * Definido en PackageManager (android.content.pm.PackageManager).
         * Valor: 0x00000001
         */
        private const val INSTALL_ALLOW_DOWNGRADE = 0x00000001

        fun getRepoOwner(context: Context): String {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            return prefs.getString("GithubRepoOwner", DEFAULT_REPO_OWNER) ?: DEFAULT_REPO_OWNER
        }

        fun getRepoName(context: Context): String {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            return prefs.getString("GithubRepoName", DEFAULT_REPO_NAME) ?: DEFAULT_REPO_NAME
        }

        val LATEST_APK_URL: String
            get() = "https://github.com/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME/releases/latest/download/app-release.apk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val repoOwner: String
        get() = getRepoOwner(context)

    private val repoName: String
        get() = getRepoName(context)

    private val releasesApiUrl: String
        get() = "https://api.github.com/repos/$repoOwner/$repoName/releases"

    /**
     * Obtiene la lista de releases/versiones disponibles desde la API de GitHub.
     */
    suspend fun fetchReleases(): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val releasesList = mutableListOf<GitHubRelease>()
        try {
            val requestBuilder = Request.Builder()
                .url(releasesApiUrl)
                .header("User-Agent", "QuioscoApp")
                .header("Accept", "application/vnd.github.v3+json")

            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val token = prefs.getString("GithubToken", "") ?: ""
            if (token.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                if (!bodyString.isNullOrEmpty()) {
                    val jsonArray = JSONArray(bodyString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val tagName = obj.optString("tag_name", "")
                        val name = obj.optString("name", tagName)

                        var apkDownloadUrl = "https://github.com/$repoOwner/$repoName/releases/download/$tagName/app-release.apk"
                        val assets = obj.optJSONArray("assets")
                        if (assets != null) {
                            for (j in 0 until assets.length()) {
                                val asset = assets.getJSONObject(j)
                                val assetName = asset.optString("name", "")
                                if (assetName.endsWith(".apk", ignoreCase = true)) {
                                    apkDownloadUrl = asset.optString("browser_download_url", apkDownloadUrl)
                                    break
                                }
                            }
                        }

                        if (tagName.isNotEmpty()) {
                            releasesList.add(GitHubRelease(tagName, name, apkDownloadUrl))
                        }
                    }
                }
            } else {
                Log.e(TAG, "Error al consultar releases de GitHub: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción consultando releases de GitHub: ${e.message}", e)
        }
        releasesList
    }

    /**
     * Descarga el APK reportando el progreso en porcentaje.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "QuioscoApp")

            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val token = prefs.getString("GithubToken", "") ?: ""
            if (token.isNotBlank() && downloadUrl.contains("api.github.com")) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error HTTP al descargar APK: ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val contentLength = body.contentLength()

            val destinationDir = context.getExternalFilesDir(null) ?: context.cacheDir
            val apkFile = File(destinationDir, "app-release-update.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (contentLength > 0) {
                    val percent = ((totalRead * 100) / contentLength).toInt()
                    onProgress(percent, totalRead, contentLength)
                } else {
                    onProgress(-1, totalRead, -1)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            Log.d(TAG, "APK descargado exitosamente en: ${apkFile.absolutePath}")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar APK: ${e.message}", e)
            null
        }
    }

    /**
     * Compara semánticamente dos cadenas de versión (ej. "v1.0.20" vs "1.0.19").
     * Retorna true si latestTag es mayor que currentVersion.
     */
    fun isNewerVersion(latestTag: String, currentVersionName: String): Boolean {
        val cleanLatest = latestTag.trim().removePrefix("v").removePrefix("V")
        val cleanCurrent = currentVersionName.trim().removePrefix("v").removePrefix("V")

        if (cleanLatest.isEmpty() || cleanCurrent.isEmpty()) return false
        if (cleanLatest == cleanCurrent) return false

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        return false
    }

    /**
     * Revisa en el arranque si hay una nueva versión en GitHub. Si la hay,
     * la descarga e instala de forma silenciosa e ininterrumpida.
     */
    suspend fun checkAndAutoUpdateOnStartup(onStatusUpdate: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val pInfo = try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (e: Exception) {
                null
            }
            val currentVersionName = pInfo?.versionName ?: "1.0.0"

            Log.d(TAG, "🔍 Revisando actualizaciones automáticas en arranque. Versión instalada: v$currentVersionName (Repo: $repoOwner/$repoName)")

            val releases = fetchReleases()
            if (releases.isEmpty()) {
                Log.d(TAG, "No se encontraron releases en GitHub.")
                return@withContext false
            }

            val latestRelease = releases[0]
            val latestTag = latestRelease.tagName

            if (isNewerVersion(latestTag, currentVersionName)) {
                Log.i(TAG, "🚀 Nueva versión detectada en GitHub: $latestTag (instalada: v$currentVersionName). Iniciando actualización silenciosa...")
                onStatusUpdate?.invoke("Actualización encontrada ($latestTag). Descargando...")

                val apkFile = downloadApk(latestRelease.downloadUrl) { percent, _, _ ->
                    if (percent >= 0) {
                        onStatusUpdate?.invoke("Descargando actualización $latestTag: $percent%")
                    }
                }

                if (apkFile != null && apkFile.exists()) {
                    Log.i(TAG, "✅ APK descargado correctamente. Aplicando instalación silenciosa...")
                    onStatusUpdate?.invoke("Instalando versión $latestTag...")
                    return@withContext installApkAuto(apkFile, forceReinstall = false)
                } else {
                    Log.e(TAG, "❌ No se pudo descargar el APK de la actualización.")
                }
            } else {
                Log.d(TAG, "✅ La app está actualizada en la última versión (v$currentVersionName).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la comprobación de actualización en arranque: ${e.message}", e)
        }
        false
    }

    /**
     * Método unificado de instalación:
     * Si la app es Device Owner (Administrador del Quiosco), instala silenciosamente sin intervención.
     * Si no, abre el instalador de Android mediante Intent.
     *
     * @param forceReinstall si es true, permite reinstalar la MISMA versión y también
     *        instalar versiones ANTERIORES (downgrade). Si es false, solo actualiza.
     */
    fun installApkAuto(apkFile: File, forceReinstall: Boolean = false): Boolean {
        return if (PermisosAdmin.esPropietario(context)) {
            Log.i(TAG, "La app es Device Owner: ejecutando instalación silenciosa (forceReinstall=$forceReinstall)...")
            installApkSilently(apkFile, forceReinstall)
        } else {
            Log.w(TAG, "La app no es Device Owner: abriendo instalador con confirmación del usuario...")
            installApk(apkFile)
        }
    }

    /**
     * Realiza una instalación SILENCIOSA usando la API de PackageInstaller (para Device Owner).
     *
     * @param forceReinstall si es true:
     *   - Permite reinstalar la misma versión (mismo versionCode).
     *   - Permite instalar versiones anteriores (downgrade).
     *   - En Android 12+ no pide confirmación al usuario.
     */
    fun installApkSilently(apkFile: File, forceReinstall: Boolean = false): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            Log.e(TAG, "El archivo APK no existe o está vacío: ${apkFile.absolutePath}")
            return false
        }

        val packageInstaller = context.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        return try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(context.packageName)

            if (forceReinstall) {
                // ── Permitir reinstalar la misma versión y hacer downgrade ──
                try {
                    // Android 12+ (API 31): no pedir confirmación al usuario
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        params.setRequireUserAction(
                            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo configurar USER_ACTION_NOT_REQUIRED: ${e.message}")
                }

                // Android 9+ (API 28): permitir downgrade / misma versión
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    allowDowngrade(params)
                }
            }

            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            session.openWrite("package_apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }

            val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_COMPLETE
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            Log.i(TAG, "📦 Sesión de instalación enviada exitosamente (sessionId: $sessionId, forceReinstall=$forceReinstall)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al iniciar instalación silenciosa: ${e.message}", e)
            try { session?.abandon() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Habilita la instalación de versiones anteriores (downgrade) o de la misma versión.
     *
     * `setAllowDowngrade(boolean)` es un método @SystemApi / @hide en
     * PackageInstaller.SessionParams, por lo que no se puede invocar directamente
     * desde el SDK público. Se usa reflexión y, como respaldo, se establece
     * directamente el flag INSTALL_ALLOW_DOWNGRADE en el campo installFlags.
     */
    private fun allowDowngrade(params: PackageInstaller.SessionParams) {
        // ── Intento 1: reflexión sobre setAllowDowngrade(boolean) ──
        try {
            val method = params.javaClass.getMethod(
                "setAllowDowngrade",
                Boolean::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(params, true)
            Log.d(TAG, "✅ setAllowDowngrade(true) aplicado mediante reflexión")
            return
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setAllowDowngrade no existe en esta versión de Android: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Reflexión sobre setAllowDowngrade falló: ${e.message}")
        }

        // ── Intento 2: respaldo — modificar directamente installFlags ──
        try {
            val installFlagsField = params.javaClass.getField("installFlags")
            installFlagsField.isAccessible = true
            val currentFlags = installFlagsField.getInt(params)
            installFlagsField.setInt(params, currentFlags or INSTALL_ALLOW_DOWNGRADE)
            Log.d(TAG, "✅ INSTALL_ALLOW_DOWNGRADE aplicado en installFlags (flags=$currentFlags -> ${currentFlags or INSTALL_ALLOW_DOWNGRADE})")
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo aplicar INSTALL_ALLOW_DOWNGRADE por ningún método: ${e.message}")
        }
    }

    /**
     * Método fallback mediante Intent para mostrar la pantalla de instalación del sistema.
     * Nota: si el usuario no es Device Owner, Android mostrará su propia confirmación
     * y puede rechazar el downgrade según la versión del sistema.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            if (!apkFile.exists()) {
                Log.e(TAG, "El archivo APK no existe: ${apkFile.absolutePath}")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return false
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al lanzar instalador de APK: ${e.message}", e)
            false
        }
    }
}