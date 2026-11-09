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
import com.sybi.mosi.integration.MosiApiClient
import com.sybi.mosi.integration.MosiSecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class MosiRelease(
    val tagName: String,
    val name: String,
    val downloadUrl: String,
    val updateId: String = "",
    val versionCode: Long = 0,
    val sha256: String = "",
    val requestId: String = "",
    val source: String = "sybiml"
)

class UpdateManager(private val context: Context) {

    @Volatile
    var lastInstallError: String? = null
        private set

    companion object {
        private const val TAG = "UpdateManager"

        /**
         * Flag oculto de Android: INSTALL_ALLOW_DOWNGRADE.
         * Definido en PackageManager (android.content.pm.PackageManager).
         * Valor: 0x00000001
         */
        private const val INSTALL_ALLOW_DOWNGRADE = 0x00000001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Consulta las actualizaciones del canal SybiML por defecto. */
    suspend fun fetchReleases(): List<MosiRelease> = fetchSybimlReleases()

    /** Consulta el canal de actualizaciones SybiML (MOSI) autenticado para esta cabina. */
    suspend fun fetchSybimlReleases(): List<MosiRelease> = withContext(Dispatchers.IO) {
        try {
            if (!MosiSecureStore.estaActivado(context)) return@withContext emptyList()
            val update = MosiApiClient(context).consultarActualizacion() ?: return@withContext emptyList()
            listOf(MosiRelease(
                tagName = update.versionName,
                name = update.versionName,
                downloadUrl = update.apkUrl,
                updateId = update.id,
                versionCode = update.versionCode,
                sha256 = update.sha256,
                requestId = update.requestId,
                source = "sybiml"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando actualizaciones SybiML: ${e.message}")
            emptyList()
        }
    }

    /** Consulta el repositorio público de GitHub (MIKE-HERO/MOSI.SYBI). */
    suspend fun fetchGithubReleases(): List<MosiRelease> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/MIKE-HERO/MOSI.SYBI/releases")
                .header("User-Agent", "QuioscoApp")
                .header("Accept", "application/vnd.github+json")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error HTTP al consultar GitHub releases: ${response.code}")
                return@withContext emptyList()
            }
            val text = response.body?.string() ?: return@withContext emptyList()
            val jsonArray = JSONArray(text)
            val list = mutableListOf<MosiRelease>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val tagName = obj.optString("tag_name")
                val name = obj.optString("name", tagName)
                val assets = obj.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    val assetName = asset.optString("name")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
                if (apkUrl.isNotBlank()) {
                    list.add(
                        MosiRelease(
                            tagName = tagName,
                            name = name,
                            downloadUrl = apkUrl,
                            source = "github"
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando GitHub releases: ${e.message}")
            emptyList()
        }
    }

    /**
     * Descarga el APK reportando el progreso en porcentaje.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        expectedSha256: String = "",
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val isGithub = downloadUrl.contains("github.com", ignoreCase = true)

            if (!isGithub && expectedSha256.isNotBlank() && !expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                Log.e(TAG, "Descarga SybiML rechazada: falta un SHA-256 válido")
                return@withContext null
            }

            val requestBuilder = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "QuioscoApp")

            if (!isGithub) {
                val token = MosiSecureStore.token(context)
                if (token.isBlank()) {
                    Log.e(TAG, "Descarga rechazada: la cabina no está activada")
                    return@withContext null
                }
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Error HTTP al descargar APK (${response.code}) desde $downloadUrl")
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

            if (expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                val actualHash = sha256(apkFile)
                if (!actualHash.equals(expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 del APK no coincide; se descarta la descarga")
                    apkFile.delete()
                    return@withContext null
                }
            }

            Log.d(TAG, "APK descargado exitosamente en: ${apkFile.absolutePath}")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar APK: ${e.message}", e)
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
     * Revisa en el arranque si el canal MOSI asignó una nueva versión. Si la hay,
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

            Log.d(TAG, "Revisando canal MOSI. Versión instalada: v$currentVersionName")

            val releases = fetchReleases()
            if (releases.isEmpty()) {
                Log.d(TAG, "El canal MOSI no asignó una actualización.")
                return@withContext false
            }

            val latestRelease = releases[0]
            val latestTag = latestRelease.tagName

            if (isNewerVersion(latestTag, currentVersionName)) {
                Log.i(TAG, "Nueva versión MOSI: $latestTag (instalada: v$currentVersionName).")
                onStatusUpdate?.invoke("Actualización encontrada ($latestTag). Descargando...")

                val apkFile = downloadApk(latestRelease.downloadUrl, latestRelease.sha256) { percent, _, _ ->
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
     * Entrega siempre el APK a PackageInstaller. Android lo instala sin interacción cuando
     * MOSI es Device Owner o cuando autoriza la autoactualización de la propia aplicación.
     * Si el sistema exige consentimiento, el receiver abre la confirmación como respaldo.
     *
     * @param forceReinstall si es true, permite reinstalar la MISMA versión y también
     *        instalar versiones ANTERIORES (downgrade). Si es false, solo actualiza.
     */
    fun installApkAuto(apkFile: File, forceReinstall: Boolean = false): Boolean {
        lastInstallError = validateApk(apkFile)
        if (lastInstallError != null) {
            Log.e(TAG, "APK rechazado antes de instalar: $lastInstallError")
            return false
        }
        Log.i(
            TAG,
            "Enviando autoactualización a PackageInstaller " +
                "(deviceOwner=${PermisosAdmin.esPropietario(context)}, forceReinstall=$forceReinstall)"
        )
        return installApkSilently(apkFile, forceReinstall)
    }

    private fun validateApk(apkFile: File): String? {
        if (!apkFile.exists() || apkFile.length() <= 0L) return "El APK no existe o está vacío"
        return try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
            }
            val archive = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
                ?: return "Android no reconoce el archivo como APK"
            if (archive.packageName != context.packageName) {
                return "Package incompatible: ${archive.packageName} (esperado ${context.packageName})"
            }
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            val installedSignatures = signaturesOf(installed)
            val archiveSignatures = signaturesOf(archive)
            if (installedSignatures.isEmpty() || archiveSignatures.isEmpty()) {
                return "No se pudo verificar la firma del APK"
            }
            if (installedSignatures.none { installedCert -> archiveSignatures.any { it.contentEquals(installedCert) } }) {
                return "La firma del APK no coincide con la aplicación instalada"
            }
            null
        } catch (e: Exception) {
            "No se pudo validar el APK: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun signaturesOf(info: android.content.pm.PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION") info.signatures.orEmpty().map { it.toByteArray() }
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

            // Un Device Owner puede confirmar actualizaciones normales sin interacción.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                )
            }

            if (forceReinstall) {
                // ── Permitir reinstalar la misma versión y hacer downgrade ──
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
            lastInstallError = "PackageInstaller no pudo iniciar: ${e.message ?: e.javaClass.simpleName}"
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
