package com.sybi.mosi

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

/**
 * Pantalla para configurar el envío de correo por SMTP directo (ver [EmailSender]). La
 * contraseña se guarda cifrada. Incluye un botón para enviar un correo de prueba y verificar
 * que los datos funcionan antes de usarlos en producción.
 */
class SmtpSettingsActivity : BaseActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etFrom: EditText
    private lateinit var etFromName: EditText
    private lateinit var swSsl: Switch
    private lateinit var tvEstado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_smtp_settings)

        val color = runCatching {
            Color.parseColor(getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
        }.getOrDefault(Color.parseColor("#0F3E82"))
        findViewById<View>(R.id.topSmtpBar).setBackgroundColor(color)

        etHost = findViewById(R.id.etSmtpHost)
        etPort = findViewById(R.id.etSmtpPort)
        etUser = findViewById(R.id.etSmtpUser)
        etPass = findViewById(R.id.etSmtpPass)
        etFrom = findViewById(R.id.etSmtpFrom)
        etFromName = findViewById(R.id.etSmtpFromName)
        swSsl = findViewById(R.id.swSmtpSsl)
        tvEstado = findViewById(R.id.tvSmtpEstado)

        cargarEnUi()

        findViewById<CheckBox>(R.id.cbVerPass).setOnCheckedChangeListener { _, ver ->
            etPass.inputType = if (ver)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPass.setSelection(etPass.text.length)
        }

        findViewById<Button>(R.id.btnBackSmtp).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGuardarSmtp).setOnClickListener { guardar() }
        findViewById<Button>(R.id.btnProbarSmtp).setOnClickListener { enviarPrueba() }
    }

    private fun cargarEnUi() {
        val c = EmailSender.cargarConfig(this)
        etHost.setText(c.host)
        etPort.setText(c.port.toString())
        etUser.setText(c.user)
        etPass.setText(c.pass)
        etFrom.setText(c.from)
        etFromName.setText(c.fromName)
        swSsl.isChecked = c.useSsl
        // Por defecto la prueba va al propio remitente; el admin puede cambiarlo para probar
        // envío a otros destinatarios.
        findViewById<EditText>(R.id.etSmtpTestTo).setText(c.from.ifBlank { c.user })
    }

    private fun leerDeUi(): EmailSender.Config = EmailSender.Config(
        host = etHost.text.toString().trim(),
        port = etPort.text.toString().trim().toIntOrNull() ?: 465,
        user = etUser.text.toString().trim(),
        pass = etPass.text.toString(),
        from = etFrom.text.toString().trim().ifBlank { etUser.text.toString().trim() },
        fromName = etFromName.text.toString().trim(),
        useSsl = swSsl.isChecked
    )

    private fun guardar(): EmailSender.Config? {
        val c = leerDeUi()
        if (!c.esValida()) {
            Toast.makeText(this, "Completa servidor, puerto, usuario y contraseña", Toast.LENGTH_LONG).show()
            return null
        }
        EmailSender.guardarConfig(this, c)
        Toast.makeText(this, "Configuración guardada", Toast.LENGTH_SHORT).show()
        return c
    }

    private fun enviarPrueba() {
        val c = guardar() ?: return
        val destino = findViewById<EditText>(R.id.etSmtpTestTo).text.toString().trim()
            .ifBlank { c.from.ifBlank { c.user } }
        tvEstado.setTextColor(Color.parseColor("#6B7280"))
        tvEstado.text = "Enviando correo de prueba a $destino…"

        lifecycleScope.launch {
            val resultado = withContext(Dispatchers.IO) {
                EmailSender.enviar(
                    config = c,
                    destino = destino,
                    asunto = "Prueba de configuración de correo",
                    cuerpoHtml = "<p>Este es un correo de prueba del módulo de salud. Si lo recibes, la configuración SMTP es correcta.</p>",
                    adjunto = null
                )
            }
            if (resultado.isSuccess) {
                tvEstado.setTextColor(Color.parseColor("#2E7D32"))
                tvEstado.text = "✅ Correo de prueba enviado a $destino. Revisa la bandeja."
            } else {
                val ex = resultado.exceptionOrNull()
                val detalle = ex?.message ?: "error desconocido"
                val causa = ex?.cause?.message
                tvEstado.setTextColor(Color.parseColor("#D32F2F"))
                tvEstado.text = "❌ No se pudo enviar a $destino:\n$detalle" +
                        if (!causa.isNullOrBlank() && causa != detalle) "\nCausa: $causa" else ""
            }
        }
    }
}
