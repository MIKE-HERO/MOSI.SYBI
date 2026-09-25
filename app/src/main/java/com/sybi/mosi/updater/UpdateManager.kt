package com.sybi.mosi.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
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
        private const val REPO_OWNER = "MIKE-HERO"
        private const val REPO_NAME = "MOSI.SYBI"
        const val LATEST_APK_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest/download/app-release.apk"
        private const val RELEASES_API_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Obtiene la lista de releases/versiones disponibles desde la API de GitHub.
     */
    suspend fun fetchReleases(): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val releasesList = mutableListOf<GitHubRelease>()
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("User-Agent", "QuioscoApp")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyString = response.body?.string()
                if (!bodyString.isNullOrEmpty()) {
                    val jsonArray = JSONArray(bodyString)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val tagName = obj.optString("tag_name", "")
                        val name = obj.optString("name", tagName)

                        var apkDownloadUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tagName/app-release.apk"
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
                Log.e(TAG, "Error al consultar releases: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción consultando releases de GitHub: ${e.message}", e)
        }
        releasesList
    }

    /**
     * Construye la URL de descarga para un tag específico.
     */
    fun getDownloadUrlForTag(tag: String): String {
        val cleanTag = tag.trim()
        return "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$cleanTag/app-release.apk"
    }

    /**
     * Descarga el APK reportando el progreso en porcentaje.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (percent: Int, bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "QuioscoApp")
                .build()

            val response = client.newCall(request).execute()
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
     * Verifica permisos e instala el APK guardado.
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
