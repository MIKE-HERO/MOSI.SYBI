package com.sybi.mosi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.webkit.WebViewAssetLoader
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.sybi.mosi.telemedicina.TelemedicinaApi
import com.sybi.mosi.telemedicina.TelemedicinaException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Consulta de telemedicina integrada en la app.
 *
 * Todo el flujo (clave de apiRTC, búsqueda de médico, lista de espera, notificación al médico,
 * cierre y liberación de recursos) y toda la interfaz son nativos. El audio/video corre en un
 * WebView interno con apiRTC (assets/telemedicina/call.html), ya que el SDK nativo de apiRTC
 * para Android está descontinuado. El paciente nunca sale de la app.
 */
class TelemedicineActivity : BaseActivity() {

    private enum class Estado {
        BUSCANDO, CONECTANDO, EN_LLAMADA, MEDICO_DESCONECTADO,
        FINALIZANDO, TERMINADA, SERVIDOR_LLENO, LISTA_ESPERA, ERROR
    }

    companion object {
        private const val TAG = "TelemedicineActivity"
        const val EXTRA_ID_USUARIO_WEB = "id_usuario_web"

        private const val REQUEST_PERMISSIONS_CODE = 100
        private const val CALL_PAGE_URL =
            "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/telemedicina/call.html"

        // Igual que la web: si el médico no regresa en 40 min se cierra la consulta
        private const val AUSENCIA_MEDICO_MS = 40 * 60 * 1000L
        private const val ESPERA_SALIDA_JS_MS = 5000L
        private const val REGRESO_AUTOMATICO_MS = 10_000L
    }

    private lateinit var topBar: View
    private lateinit var tvDoctorName: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var tvCallBanner: TextView
    private lateinit var btnEndCall: ImageView
    private lateinit var webView: WebView
    private lateinit var callControls: LinearLayout
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnHangUp: ImageButton
    private lateinit var statusOverlay: View
    private lateinit var progressStatus: ProgressBar
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusMessage: TextView
    private lateinit var btnStatusAction: Button

    private lateinit var api: TelemedicinaApi
    private var colorReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())

    private var idUsuarioWeb: Int = 0
    private var idCabina: String = "1"
    private var estado = Estado.BUSCANDO

    // Recursos del servidor que hay que liberar al terminar
    private var idApikeyMedico: JsonElement? = null
    private var idNotificacion: JsonElement? = null
    private var idCola: JsonElement? = null
    private var recursosLiberados = false
    private var saliendo = false

    // Estado de la videollamada
    private var apikeyLlamada: String = ""
    private var codigoConversacion: String = ""
    private var nombreMedico: String = ""
    private var inicioLlamadaMs = 0L
    private var paginaLista = false
    private var sdkDisponible = false
    private var inicioPendiente = false
    private var salidaJs: CompletableDeferred<Unit>? = null

    private val cronometro = object : Runnable {
        override fun run() {
            val segundos = (SystemClock.elapsedRealtime() - inicioLlamadaMs) / 1000
            tvCallTimer.text = String.format("%02d:%02d", segundos / 60, segundos % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private val ausenciaMedico = Runnable {
        Log.w(TAG, "⏱️ El médico no regresó en ${AUSENCIA_MEDICO_MS / 60000} min, finalizando")
        colgar()
    }

    private val regresoAutomatico = Runnable { irAInicio() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        idCabina = TelemedicineSettingsActivity.getCabina(this).trim().ifEmpty { "1" }
        api = TelemedicinaApi(TelemedicineSettingsActivity.getBaseUrl(this))

        initViews()
        aplicarTemaGuardado()
        configurarWebView()

        btnEndCall.setOnClickListener { confirmarSalida() }
        btnHangUp.setOnClickListener { confirmarSalida() }
        btnSwitchCamera.setOnClickListener { ejecutarJs("MosiCall.switchCamera()") }

        mostrarEstado(Estado.BUSCANDO)
        verificarPermisosYComenzar()
    }

    private fun initViews() {
        topBar = findViewById(R.id.topBar)
        tvDoctorName = findViewById(R.id.tvDoctorName)
        tvCallTimer = findViewById(R.id.tvCallTimer)
        tvCallBanner = findViewById(R.id.tvCallBanner)
        btnEndCall = findViewById(R.id.btnEndCall)
        webView = findViewById(R.id.callWebView)
        callControls = findViewById(R.id.callControls)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnHangUp = findViewById(R.id.btnHangUp)
        statusOverlay = findViewById(R.id.statusOverlay)
        progressStatus = findViewById(R.id.progressStatus)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        btnStatusAction = findViewById(R.id.btnStatusAction)
    }

    // ==========================================
    // TEMA
    // ==========================================
    private fun aplicarTemaGuardado() {
        colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("new_color")?.let { aplicarColorTema(it) }
            }
        }.also {
            LocalBroadcastManager.getInstance(this).registerReceiver(it, IntentFilter("ACTION_UPDATE_THEME"))
        }

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        aplicarColorTema(prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82")
    }

    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            topBar.setBackgroundColor(colorInt)
            btnStatusAction.backgroundTintList = android.content.res.ColorStateList.valueOf(colorInt)
            tvStatusTitle.setTextColor(colorInt)
            ivStatusIcon.setColorFilter(colorInt)
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color de tema: ${e.message}")
        }
    }

    // ==========================================
    // WEBVIEW (solo audio/video)
    // ==========================================
    @SuppressLint("SetJavaScriptEnabled")
    private fun configurarWebView() {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.setBackgroundColor(Color.BLACK)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            // La página de video nunca debe navegar a otro sitio
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.origin.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                        val permitidos = request.resources.filter {
                            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }
                        request.grant(permitidos.toTypedArray())
                    } else {
                        request.deny()
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(TAG, "🌐 JS: ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})")
                return true
            }
        }

        webView.addJavascriptInterface(PuenteJs(), "MosiBridge")
        webView.loadUrl(CALL_PAGE_URL)
    }

    private inner class PuenteJs {
        @JavascriptInterface
        fun onEvent(tipo: String, datos: String) {
            val json = runCatching { JSONObject(datos) }.getOrDefault(JSONObject())
            runOnUiThread { manejarEventoJs(tipo, json) }
        }
    }

    private fun ejecutarJs(script: String) {
        if (!isDestroyed) webView.evaluateJavascript(script, null)
    }

    private fun manejarEventoJs(tipo: String, datos: JSONObject) {
        Log.d(TAG, "📨 Evento de video: $tipo $datos")
        when (tipo) {
            "ready" -> {
                paginaLista = true
                sdkDisponible = datos.optBoolean("sdk")
                if (inicioPendiente) iniciarVideo()
            }
            "joined" -> {
                btnSwitchCamera.visibility = if (datos.optInt("camaras") > 1) View.VISIBLE else View.GONE
                if (estado == Estado.CONECTANDO) {
                    tvStatusMessage.text = "Esperando a que $nombreMedico se una a la llamada."
                }
            }
            "remoteAdded" -> {
                handler.removeCallbacks(ausenciaMedico)
                if (estado == Estado.CONECTANDO || estado == Estado.MEDICO_DESCONECTADO) {
                    mostrarEstado(Estado.EN_LLAMADA)
                    actualizarNombreMedico()
                }
            }
            "remoteRemoved" -> {
                if (datos.optInt("restantes") == 0 && estado == Estado.EN_LLAMADA) {
                    mostrarEstado(Estado.MEDICO_DESCONECTADO)
                    handler.postDelayed(ausenciaMedico, AUSENCIA_MEDICO_MS)
                }
            }
            "left" -> salidaJs?.complete(Unit)
            "warning" -> Log.w(TAG, "⚠️ Aviso de video: $datos")
            "error" -> {
                Log.e(TAG, "❌ Error de video: $datos")
                if (estado == Estado.CONECTANDO || estado == Estado.EN_LLAMADA ||
                    estado == Estado.MEDICO_DESCONECTADO
                ) {
                    mostrarEstado(Estado.ERROR, mensajeErrorVideo(datos))
                }
            }
        }
    }

    private fun mensajeErrorVideo(datos: JSONObject): String = when (datos.optString("nombre")) {
        "NotAllowedError" -> "Permiso denegado. Asegúrate de que la cámara y el micrófono estén habilitados."
        "NotFoundError" -> "No se encontró la cámara o el micrófono del equipo."
        // El detalle técnico queda en el log ("Error de video"); al paciente solo un texto claro
        else -> when (datos.optString("etapa")) {
            "sdk" -> "No se pudo cargar el servicio de video. Revisa la conexión a internet."
            "registro" -> "No se pudo conectar con el servicio de video. Intenta de nuevo en unos minutos."
            else -> "Ocurrió un error en la videollamada. Intenta de nuevo en unos minutos."
        }
    }

    // ==========================================
    // PERMISOS
    // ==========================================
    private fun verificarPermisosYComenzar() {
        val permisos = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (faltantes.isNotEmpty()) {
            Log.d(TAG, "⚠️ Permisos faltantes: $faltantes. Solicitando...")
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            iniciarConsulta()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                iniciarConsulta()
            } else {
                mostrarEstado(
                    Estado.ERROR,
                    "Se requieren permisos de cámara y micrófono para la consulta."
                )
            }
        }
    }

    // ==========================================
    // FLUJO DE LA CONSULTA
    // ==========================================
    private fun iniciarConsulta() {
        lifecycleScope.launch {
            try {
                val clave = api.obtenerApikey(idUsuarioWeb, idCabina)
                if (clave is TelemedicinaApi.ApiKeyResultado.ServidorLleno) {
                    Log.d(TAG, "🚫 Servidor lleno: ${clave.mensaje}")
                    mostrarEstado(Estado.SERVIDOR_LLENO)
                    return@launch
                }
                clave as TelemedicinaApi.ApiKeyResultado.Asignada
                idApikeyMedico = clave.idApikeyMedico

                val medico = api.obtenerMedicoDisponible(idCabina)
                if (medico == null) {
                    Log.d(TAG, "⏳ Sin médicos disponibles, entrando a lista de espera")
                    idCola = api.entrarListaEspera(idUsuarioWeb, idCabina, clave.idApikeyMedico)
                    mostrarEstado(Estado.LISTA_ESPERA)
                    return@launch
                }

                nombreMedico = medico.nombre.ifBlank { "el médico" }
                codigoConversacion = generarCodigo(6)
                apikeyLlamada = clave.apikey

                val campos = JsonObject().apply {
                    add("id_medico", medico.id)
                    addProperty("id_usuarioWeb", idUsuarioWeb.toString())
                    add("id_sucursal", medico.idSucursal)
                    addProperty("st_mensaje", codigoConversacion)
                    addProperty("st_apikey", clave.apikey)
                    addProperty("id_cabinaMedica", idCabina)
                }
                val notificacion = api.registrarNotificacion(campos)
                idNotificacion = notificacion.idNotificacion
                Log.d(TAG, "🔔 Notificación registrada: ${notificacion.idNotificacion}")

                campos.add("st_sucursal", notificacion.stSucursal)
                try {
                    api.notificarMedicoFirestore(campos)
                } catch (e: TelemedicinaException) {
                    // La web también continúa si falla Firestore; el médico podría no enterarse
                    Log.e(TAG, "❌ No se pudo notificar al médico en Firestore: ${e.message}", e)
                }

                inicioLlamadaMs = SystemClock.elapsedRealtime()
                mostrarEstado(Estado.CONECTANDO)
                iniciarVideo()
            } catch (e: TelemedicinaException) {
                Log.e(TAG, "❌ Error iniciando la consulta: ${e.message}", e)
                mostrarEstado(Estado.ERROR, "${e.message}. Intenta de nuevo en unos minutos.")
            }
        }
    }

    private fun iniciarVideo() {
        if (!paginaLista) {
            inicioPendiente = true
            return
        }
        inicioPendiente = false
        if (!sdkDisponible) {
            mostrarEstado(Estado.ERROR, "No se pudo cargar el servicio de video. Revisa la conexión a internet.")
            return
        }
        ejecutarJs("MosiCall.start(${JSONObject.quote(apikeyLlamada)}, ${JSONObject.quote(codigoConversacion)})")
    }

    private fun actualizarNombreMedico() {
        lifecycleScope.launch {
            try {
                api.obtenerNombreMedicoActual(idUsuarioWeb, codigoConversacion)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { nombreMedico = it }
            } catch (e: TelemedicinaException) {
                Log.w(TAG, "⚠️ No se pudo obtener el médico actual: ${e.message}")
            }
            tvDoctorName.text = "Atiende: $nombreMedico"
            tvDoctorName.visibility = View.VISIBLE
        }
    }

    private fun generarCodigo(longitud: Int): String {
        val caracteres = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..longitud).map { caracteres.random() }.joinToString("")
    }

    // ==========================================
    // FINALIZAR
    // ==========================================
    private fun llamadaActiva() = estado == Estado.CONECTANDO || estado == Estado.EN_LLAMADA ||
            estado == Estado.MEDICO_DESCONECTADO

    private fun confirmarSalida() {
        if (!llamadaActiva()) {
            salir()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Finalizar consulta")
            .setMessage("¿Deseas terminar la consulta con el médico?")
            .setPositiveButton("Finalizar") { _, _ -> colgar() }
            .setNegativeButton("Continuar", null)
            .show()
    }

    /** Cuelga la videollamada, libera los recursos y muestra el cierre. */
    private fun colgar() {
        if (saliendo) return
        saliendo = true
        mostrarEstado(Estado.FINALIZANDO)
        lifecycleScope.launch {
            liberarRecursos()
            mostrarEstado(Estado.TERMINADA)
            handler.postDelayed(regresoAutomatico, REGRESO_AUTOMATICO_MS)
        }
    }

    /** Sale de la pantalla (lista de espera, error, cancelación) liberando lo que se haya reservado. */
    private fun salir() {
        if (saliendo) return
        saliendo = true
        mostrarEstado(Estado.FINALIZANDO)
        lifecycleScope.launch {
            liberarRecursos()
            irAInicio()
        }
    }

    private suspend fun liberarRecursos() {
        if (recursosLiberados) return
        recursosLiberados = true
        handler.removeCallbacks(cronometro)
        handler.removeCallbacks(ausenciaMedico)

        val notificacion = idNotificacion
        val apikey = idApikeyMedico
        if (notificacion != null) {
            if (apikey != null) {
                val minutos = ((SystemClock.elapsedRealtime() - inicioLlamadaMs) / 60_000).toInt()
                runCatching { api.liberarApikey(apikey, minutos) }
                    .onFailure { Log.e(TAG, "❌ Error liberando apikey: ${it.message}") }
            }

            salidaJs = CompletableDeferred()
            ejecutarJs("MosiCall.hangup()")
            withTimeoutOrNull(ESPERA_SALIDA_JS_MS) { salidaJs?.await() }

            runCatching { api.cancelarNotificacion(notificacion) }
                .onFailure { Log.e(TAG, "❌ Error cancelando notificación: ${it.message}") }
        }

        idCola?.let { cola ->
            runCatching { api.salirListaEspera(cola) }
                .onFailure { Log.e(TAG, "❌ Error saliendo de lista de espera: ${it.message}") }
        }
    }

    /** Si la pantalla se destruye sin colgar (p. ej. el sistema la cierra), libera en segundo plano. */
    private fun liberarRecursosEnSegundoPlano() {
        if (recursosLiberados) return
        recursosLiberados = true
        val apikey = idApikeyMedico
        val notificacion = idNotificacion
        val cola = idCola
        val minutos = if (inicioLlamadaMs > 0) ((SystemClock.elapsedRealtime() - inicioLlamadaMs) / 60_000).toInt() else 0
        val api = api
        CoroutineScope(Dispatchers.IO).launch {
            if (notificacion != null) {
                apikey?.let { runCatching { api.liberarApikey(it, minutos) } }
                runCatching { api.cancelarNotificacion(notificacion) }
            }
            cola?.let { runCatching { api.salirListaEspera(it) } }
        }
    }

    private fun irAInicio() {
        handler.removeCallbacks(regresoAutomatico)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    // ==========================================
    // UI DE ESTADOS
    // ==========================================
    private fun mostrarEstado(nuevo: Estado, mensaje: String? = null) {
        estado = nuevo
        Log.d(TAG, "🔄 Estado: $nuevo")

        val enLlamada = nuevo == Estado.EN_LLAMADA || nuevo == Estado.MEDICO_DESCONECTADO
        statusOverlay.visibility = if (enLlamada) View.GONE else View.VISIBLE
        callControls.visibility = if (enLlamada) View.VISIBLE else View.GONE
        tvCallBanner.visibility = if (nuevo == Estado.MEDICO_DESCONECTADO) View.VISIBLE else View.GONE
        tvCallBanner.text = "El médico se desconectó. Esperando a que regrese…"

        val cronometroVisible = nuevo == Estado.CONECTANDO || enLlamada
        tvCallTimer.visibility = if (cronometroVisible) View.VISIBLE else View.GONE
        handler.removeCallbacks(cronometro)
        if (cronometroVisible) handler.post(cronometro)

        when (nuevo) {
            Estado.BUSCANDO -> panel(
                cargando = true,
                titulo = "Buscando médico disponible",
                texto = "Estamos localizando a un médico para tu consulta. Esto puede tardar unos segundos.",
                accion = "Cancelar"
            ) { salir() }

            Estado.CONECTANDO -> panel(
                cargando = true,
                titulo = "Conectando con $nombreMedico",
                texto = "Preparando la cámara y el micrófono…",
                accion = "Cancelar consulta"
            ) { colgar() }

            Estado.SERVIDOR_LLENO -> panel(
                cargando = false,
                titulo = "Servidor lleno",
                texto = "En este momento todos los consultorios virtuales están ocupados. Intenta de nuevo en unos minutos.",
                accion = "Aceptar"
            ) { salir() }

            Estado.LISTA_ESPERA -> panel(
                cargando = false,
                titulo = "Entraste a la lista de espera",
                texto = "No hay médicos disponibles en este momento. Tu solicitud quedó registrada.",
                accion = "Aceptar"
            ) { salir() }

            Estado.FINALIZANDO -> panel(
                cargando = true,
                titulo = "Finalizando consulta",
                texto = "Cerrando la videollamada…",
                accion = null
            ) {}

            Estado.TERMINADA -> panel(
                cargando = false,
                titulo = "Consulta terminada",
                texto = "Gracias por usar el servicio de telemedicina.",
                accion = "Aceptar"
            ) { irAInicio() }

            Estado.ERROR -> panel(
                cargando = false,
                titulo = "No se pudo completar la consulta",
                texto = mensaje ?: "Ocurrió un error en el servidor.",
                accion = "Salir"
            ) { salir() }

            Estado.EN_LLAMADA, Estado.MEDICO_DESCONECTADO -> Unit
        }
    }

    private fun panel(cargando: Boolean, titulo: String, texto: String, accion: String?, alPulsar: () -> Unit) {
        progressStatus.visibility = if (cargando) View.VISIBLE else View.GONE
        ivStatusIcon.visibility = if (cargando) View.GONE else View.VISIBLE
        tvStatusTitle.text = titulo
        tvStatusMessage.text = texto
        btnStatusAction.visibility = if (accion != null) View.VISIBLE else View.GONE
        btnStatusAction.text = accion.orEmpty()
        btnStatusAction.setOnClickListener { alPulsar() }
    }

    // ==========================================
    // CICLO DE VIDA
    // ==========================================
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val ok = VideoLoopRemote.setMuted(this@TelemedicineActivity, true)
            if (!ok) {
                Log.w(TAG, "No se pudo silenciar el video en " +
                        VideoLoopRemote.getSavedHostPort(this@TelemedicineActivity))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmarSalida()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        colorReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        if (::api.isInitialized) liberarRecursosEnSegundoPlano()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("MosiBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()

        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.setMuted(appContext, false)
        }
    }
}
