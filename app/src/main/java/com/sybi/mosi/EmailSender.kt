package com.sybi.mosi

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Envío de correo por SMTP directo desde la app (JavaMail). La configuración (servidor,
 * puerto, usuario, contraseña, remitente) la ingresa el administrador en la pantalla de
 * ajustes y se guarda en DevicePrefs; NO va escrita en el código.
 *
 * Aviso de seguridad: al ir las credenciales en el dispositivo (DevicePrefs), quien tenga
 * acceso root al equipo podría leerlas. Si eso llegara a pasar, rota la contraseña del buzón
 * en el panel del correo y vuelve a configurarla aquí.
 *
 * [enviar] es bloqueante (hace red): llámalo siempre desde un hilo de fondo, nunca en el
 * hilo principal.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    const val PREF_HOST = "smtp_host"
    const val PREF_PORT = "smtp_port"
    const val PREF_USER = "smtp_user"
    const val PREF_PASS = "smtp_pass"
    const val PREF_FROM = "smtp_from"
    const val PREF_FROM_NAME = "smtp_from_name"
    const val PREF_SSL = "smtp_ssl"

    data class Config(
        val host: String,
        val port: Int,
        val user: String,
        val pass: String,
        val from: String,
        val fromName: String,
        val useSsl: Boolean
    ) {
        fun esValida(): Boolean =
            host.isNotBlank() && port in 1..65535 && user.isNotBlank() && pass.isNotBlank() && from.isNotBlank()
    }

    // Config no sensible (servidor, puerto, usuario, remitente) en prefs normales.
    private fun prefs(context: Context) =
        context.getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)

    // La contraseña SMTP va cifrada: la llave vive en el Android Keystore del equipo, no en
    // el APK ni en las prefs. Si el Keystore fallara, se degrada a prefs normales para no
    // dejar la app sin poder enviar (mejor que romper; el aviso de seguridad ya está dado).
    private fun securePrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "SmtpSecure",
                masterKeyAlias,
                context.applicationContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ No se pudo abrir el almacén cifrado, se usa prefs normales: ${e.message}")
            prefs(context)
        }
    }

    fun cargarConfig(context: Context): Config {
        val p = prefs(context)
        return Config(
            host = p.getString(PREF_HOST, "") ?: "",
            port = p.getInt(PREF_PORT, 465),
            user = p.getString(PREF_USER, "") ?: "",
            pass = securePrefs(context).getString(PREF_PASS, "") ?: "",
            from = p.getString(PREF_FROM, p.getString(PREF_USER, "") ?: "") ?: "",
            fromName = p.getString(PREF_FROM_NAME, "") ?: "",
            useSsl = p.getBoolean(PREF_SSL, true)
        )
    }

    fun guardarConfig(context: Context, config: Config) {
        prefs(context).edit()
            .putString(PREF_HOST, config.host.trim())
            .putInt(PREF_PORT, config.port)
            .putString(PREF_USER, config.user.trim())
            .putString(PREF_FROM, config.from.trim())
            .putString(PREF_FROM_NAME, config.fromName.trim())
            .putBoolean(PREF_SSL, config.useSsl)
            .apply()
        securePrefs(context).edit().putString(PREF_PASS, config.pass).apply()
    }

    fun estaConfigurado(context: Context): Boolean = cargarConfig(context).esValida()

    /**
     * Envía un correo con un adjunto opcional. Bloqueante: ejecutar en un hilo de fondo.
     * Devuelve Result.success si se entregó al servidor SMTP, o Result.failure con el error.
     */
    fun enviar(
        config: Config,
        destino: String,
        asunto: String,
        cuerpoHtml: String,
        adjunto: File? = null,
        nombreAdjunto: String = "informe.pdf"
    ): Result<Unit> {
        if (!config.esValida()) return Result.failure(IllegalStateException("Configuración SMTP incompleta"))
        if (destino.isBlank()) return Result.failure(IllegalStateException("Destinatario vacío"))

        return try {
            val props = Properties().apply {
                put("mail.smtp.host", config.host)
                put("mail.smtp.port", config.port.toString())
                put("mail.smtp.auth", "true")
                // Tiempos de espera para no colgar la app si el servidor no responde.
                put("mail.smtp.connectiontimeout", "15000")
                put("mail.smtp.timeout", "20000")
                put("mail.smtp.writetimeout", "20000")
                if (config.useSsl) {
                    // SMTPS (puerto 465): SSL/TLS desde el inicio.
                    put("mail.smtp.ssl.enable", "true")
                    put("mail.smtp.socketFactory.port", config.port.toString())
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.socketFactory.fallback", "false")
                } else {
                    // STARTTLS (p. ej. puerto 587).
                    put("mail.smtp.starttls.enable", "true")
                }
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(config.user, config.pass)
            })

            val message = MimeMessage(session).apply {
                setFrom(
                    if (config.fromName.isNotBlank()) InternetAddress(config.from, config.fromName)
                    else InternetAddress(config.from)
                )
                setRecipient(Message.RecipientType.TO, InternetAddress(destino))
                subject = asunto
            }

            val cuerpoParte = MimeBodyPart().apply { setContent(cuerpoHtml, "text/html; charset=utf-8") }
            val multipart = MimeMultipart().apply { addBodyPart(cuerpoParte) }

            if (adjunto != null && adjunto.exists()) {
                val adjParte = MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(adjunto))
                    fileName = nombreAdjunto
                }
                multipart.addBodyPart(adjParte)
            }

            message.setContent(multipart)
            Transport.send(message)
            Log.d(TAG, "✉️ Correo enviado a $destino")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error enviando correo: ${e.message}", e)
            Result.failure(e)
        }
    }
}
