package com.sybi.mosi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lanzador de la consulta de telemedicina.
 *
 * La consulta en sí (buscar médico, lista de espera, video/audio) corre en la aplicación Angular
 * que ya usan los pacientes desde un navegador de escritorio (assets/telemedicina en el monorepo),
 * no en un WebView embebido: ese WebView tiene bugs propios de este equipo con getUserMedia — la
 * cámara externa y el micrófono se colgaban sin resolver ni rechazar nunca (ver notas del
 * proyecto) — mientras que un navegador completo no los tiene. Esta pantalla solo abre esa URL en
 * Firefox Focus y ofrece un botón para reintentar si el paciente vuelve a la app.
 */
class TelemedicineActivity : BaseActivity() {

    companion object {
        private const val TAG = "TelemedicineActivity"
        const val EXTRA_ID_USUARIO_WEB = "id_usuario_web"
        private const val PAQUETE_FIREFOX_FOCUS = "org.mozilla.focus"
    }

    private var idUsuarioWeb: Int = 0
    private lateinit var tvEstado: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telemedicine)

        idUsuarioWeb = intent.getIntExtra(EXTRA_ID_USUARIO_WEB, 0)
        Log.d(TAG, "📥 id_usuario_web recibido: $idUsuarioWeb")

        if (idUsuarioWeb <= 0) {
            Toast.makeText(this, "id_usuario_web inválido", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!TelemedicineSettingsActivity.isTelemedicineEnabled(this)) {
            Toast.makeText(this, "Telemedicina no está habilitada en la configuración", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tvEstado = findViewById(R.id.tvTelemedicineEstado)
        findViewById<Button>(R.id.btnReintentarConsulta).setOnClickListener { abrirConsulta() }
        findViewById<Button>(R.id.btnSalirConsulta).setOnClickListener { irAInicio() }

        abrirConsulta()
    }

    override fun onResume() {
        super.onResume()
        // Se silencia VideoLoop mientras dure esta pantalla, solo si el usuario lo tenía con
        // sonido (ver VideoLoopRemote.silenciarMientras). Se vuelve a llamar en cada onResume
        // (p. ej. al volver de Focus), pero esa función ya es segura de llamar varias veces.
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.silenciarMientras(appContext, TAG)
        }
    }

    override fun onDestroy() {
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.restaurarSilencio(appContext, TAG)
        }
        super.onDestroy()
    }

    private fun urlConsulta(): Uri {
        val base = TelemedicineSettingsActivity.getBaseUrl(this).trimEnd('/')
        val idCabina = TelemedicineSettingsActivity.getCabina(this).trim().ifEmpty { "1" }
        return "$base/?id_usuario=$idUsuarioWeb&idMachine=$idCabina".toUri()
    }

    /**
     * CustomTabsIntent minimiza la barra de navegación; si Firefox Focus no implementa el
     * protocolo de Custom Tabs simplemente abre como su navegador normal, que tampoco expone una
     * barra de direcciones editable.
     */
    private fun abrirConsulta() {
        val uri = urlConsulta()
        Log.d(TAG, "🌐 Abriendo consulta en Firefox Focus: $uri")
        val customTabs = CustomTabsIntent.Builder().build()
        customTabs.intent.setPackage(PAQUETE_FIREFOX_FOCUS)
        try {
            customTabs.launchUrl(this, uri)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "❌ Firefox Focus no está instalado: ${e.message}")
            tvEstado.text = "Firefox Focus no está instalado en este equipo."
            Toast.makeText(this, "Firefox Focus no está instalado en este equipo", Toast.LENGTH_LONG).show()
        }
    }

    private fun irAInicio() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
