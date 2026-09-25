package com.sybi.mosi

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Control remoto de VideoLoop: SOLO envío de videos (agregar o reemplazar todos).
 * El volumen y el mute se controlan desde Ajustes Básicos.
 * Usa la IP y la clave guardadas en "Telemedicina" (sección Pantalla de video).
 */
class VideoLoopControlActivity : BaseActivity() {

    private lateinit var tvConnection: TextView
    private lateinit var tvVideos: TextView
    private lateinit var btnSendVideo: Button
    private lateinit var btnRefresh: Button
    private lateinit var pbUpload: ProgressBar
    private lateinit var tvUpload: TextView
    private lateinit var etVideoIp: EditText
    private lateinit var etVideoKey: EditText
    private lateinit var btnFindVideo: Button

    private var uploading = false

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) askMode(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videoloop_control)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        tvConnection = findViewById(R.id.tvConnection)
        tvVideos = findViewById(R.id.tvVideos)
        btnSendVideo = findViewById(R.id.btnSendVideo)
        btnRefresh = findViewById(R.id.btnRefresh)
        pbUpload = findViewById(R.id.pbUpload)
        tvUpload = findViewById(R.id.tvUpload)
        etVideoIp = findViewById(R.id.etVideoIp)
        etVideoKey = findViewById(R.id.etVideoKey)
        btnFindVideo = findViewById(R.id.btnFindVideo)

        // Cargar valores guardados
        etVideoIp.setText(VideoLoopRemote.getSavedHostPort(this))
        etVideoKey.setText(VideoLoopRemote.getSavedKey(this))

        btnFindVideo.setOnClickListener { buscarVideoLoop() }

        // Aplicar color del tema
        val savedColor = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("BackgroundColor", "#0F3E82")!!
        findViewById<View>(R.id.sideBarLayout).setBackgroundColor(Color.parseColor(savedColor))

        val colorInt = Color.parseColor(savedColor)
        val colorStateList = android.content.res.ColorStateList.valueOf(colorInt)

        btnFindVideo.backgroundTintList = colorStateList
        btnSendVideo.backgroundTintList = colorStateList
        btnRefresh.backgroundTintList = colorStateList

        findViewById<View>(R.id.btnBackVideoLoop).setOnClickListener {
            if (uploading) {
                Toast.makeText(this, "Espere a que termine el envío del video", Toast.LENGTH_SHORT).show()
            } else {
                guardarConexionVideo()
                finish()
            }
        }

        btnSendVideo.setOnClickListener { pickVideo.launch("video/*") }
        btnRefresh.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        // Sincronizar los campos con los valores guardados (pudieron cambiar por descubrimiento)
        if (::etVideoIp.isInitialized) {
            etVideoIp.setText(VideoLoopRemote.getSavedHostPort(this))
            etVideoKey.setText(VideoLoopRemote.getSavedKey(this))
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        guardarConexionVideo()
    }

    private fun refresh() {
        tvConnection.setTextColor(Color.parseColor("#757575"))
        tvConnection.text = "Conectando con VideoLoop (${VideoLoopRemote.getSavedHostPort(this)})…"
        lifecycleScope.launch { show(VideoLoopRemote.getStatus(this@VideoLoopControlActivity)) }
    }

    /** Muestra el resultado de una orden: el estado de VideoLoop, o el error. */
    private fun show(result: VideoLoopRemote.Result) {
        val ip = VideoLoopRemote.getSavedHostPort(this)
        val status = result.status
        if (!result.ok || status == null) {
            tvConnection.setTextColor(Color.parseColor("#C62828"))
            tvConnection.text = "❌ VideoLoop ($ip): ${result.error ?: "respuesta no válida"}"
            return
        }
        tvConnection.setTextColor(Color.parseColor("#2E7D32"))
        tvConnection.text = "✅ Conectado con VideoLoop ($ip)"

        tvVideos.text = if (status.videos.isEmpty()) {
            "(sin videos)"
        } else {
            status.videos.joinToString("\n") { "• ${it.name}  (${"%.1f".format(it.size / 1_048_576.0)} MB)" }
        }
    }

    private fun askMode(uri: Uri) {
        val options = arrayOf(
            "Agregar a la lista de reproducción",
            "Reemplazar todos (quedará solo este video)",
        )
        AlertDialog.Builder(this)
            .setTitle("¿Cómo enviar el video a VideoLoop?")
            .setItems(options) { _, which ->
                if (which == 0) upload(uri, replace = false) else confirmReplace(uri)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmReplace(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Reemplazar todos los videos")
            .setMessage("Se borrarán todos los videos actuales de VideoLoop y quedará solo el nuevo. ¿Continuar?")
            .setPositiveButton("Reemplazar") { _, _ -> upload(uri, replace = true) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun upload(uri: Uri, replace: Boolean) {
        uploading = true
        btnSendVideo.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pbUpload.progress = 0
        pbUpload.visibility = View.VISIBLE
        tvUpload.visibility = View.VISIBLE
        tvUpload.text = "Enviando… 0%"

        lifecycleScope.launch {
            val result = VideoLoopRemote.uploadVideo(this@VideoLoopControlActivity, uri, replace) { percent ->
                pbUpload.progress = percent
                tvUpload.text = "Enviando… $percent%"
            }
            uploading = false
            btnSendVideo.isEnabled = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            pbUpload.visibility = View.GONE
            if (result.ok) {
                tvUpload.text = if (replace) "✅ Video enviado. Ahora es el único en VideoLoop." else "✅ Video agregado a la lista de VideoLoop."
                show(result)
            } else {
                tvUpload.text = "❌ No se pudo enviar el video: ${result.error}"
            }
        }
    }

    /** Guarda la IP:puerto y la clave de VideoLoop escritas en pantalla. */
    private fun guardarConexionVideo() {
        val videoIpVal = etVideoIp.text.toString().trim()
        if (videoIpVal.isNotBlank()) {
            VideoLoopRemote.saveHostPort(this, videoIpVal)
        }
        VideoLoopRemote.saveKey(this, etVideoKey.text.toString())
    }

    /** Busca en la red el VideoLoop que tiene la clave escrita y rellena su IP. */
    private fun buscarVideoLoop() {
        val key = etVideoKey.text.toString().trim()
        if (key.isEmpty()) {
            Toast.makeText(this, "Escribe primero la clave que muestra VideoLoop", Toast.LENGTH_SHORT).show()
            return
        }
        btnFindVideo.isEnabled = false
        btnFindVideo.text = "Buscando…"
        lifecycleScope.launch {
            val found = VideoLoopRemote.discover(this@VideoLoopControlActivity, key)
            btnFindVideo.isEnabled = true
            btnFindVideo.text = "🔎  Buscar automáticamente"
            if (found != null) {
                etVideoIp.setText(found)
                guardarConexionVideo()
                Toast.makeText(this@VideoLoopControlActivity, "✅ VideoLoop encontrado en $found", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(
                    this@VideoLoopControlActivity,
                    "❌ No se encontró VideoLoop. Verifica que esté encendido, en la misma red y con la misma clave.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}