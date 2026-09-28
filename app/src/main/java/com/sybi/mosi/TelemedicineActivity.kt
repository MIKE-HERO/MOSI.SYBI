package com.sybi.mosi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
        private const val CALL_PAGE_PATH = "/assets/telemedicina/call.html"
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        // Igual que la web: si el médico no regresa en 40 min se cierra la consulta
        private const val AUSENCIA_MEDICO_MS = 40 * 60 * 1000L
        private const val ESPERA_SALIDA_JS_MS = 5000L
        private const val REGRESO_AUTOMATICO_MS = 10_000L

        // Si no se logra entrar a la conversación en este tiempo se ofrece reconectar. Debe superar la
        // suma de los márgenes que call.html da a cámara (45 s) + micrófono (20 s) + entregar el
        // stream a apiRTC (10 s), para no ofrecer "Reconectar" mientras esos pasos todavía podrían
        // terminar bien.
        private const val TIMEOUT_CONEXION_MS = 90_000L

        // Espera máxima a que cargue la página de video (SDK) antes de iniciar la consulta
        private const val ESPERA_MOTOR_VIDEO_MS = 10_000L

        // Cada cuánto se revisa en Firestore si el médico pidió el respaldo WebRTC. Con la llamada
        // ya establecida hay menos urgencia, así que se espacía para no gastar lecturas/batería
        // de más durante minutos de consulta en los que apiRTC funciona bien.
        private const val INTERVALO_RESPALDO_MS = 3_000L
        private const val INTERVALO_RESPALDO_ESTABLE_MS = 12_000L

        // Puente Camera2 -> <canvas> del WebView, solo para cámaras externas (ver camaraQueRequierePuente).
        // Resolución y fps moderados: el cuello de botella es el bridge JS, no la cámara.
        private const val PUENTE_CAMARA_ANCHO = 640
        private const val PUENTE_CAMARA_ALTO = 480
        private const val PUENTE_CAMARA_INTERVALO_MS = 80L // ~12 fps
    }

    private lateinit var topBar: View
    private lateinit var tvDoctorName: TextView
    private lateinit var tvCallTimer: TextView
    private lateinit var tvCallBanner: TextView
    private lateinit var btnEndCall: ImageView
    private lateinit var webView: WebView
    private lateinit var callControls: LinearLayout
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnReconnect: ImageButton
    private lateinit var btnHangUp: ImageButton
    private lateinit var statusOverlay: View
    private lateinit var progressStatus: ProgressBar
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusMessage: TextView
    private lateinit var btnStatusAction: Button
    private lateinit var btnStatusSecondary: Button

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
    private var authApiRtc: JSONObject? = null
    private var nombreMedico: String = ""
    private var inicioLlamadaMs = 0L
    private var paginaLista = false
    private var sdkDisponible = false
    private var webViewChrome = 0
    private var errorSdk = ""
    private var inicioPendiente = false
    private var salidaJs: CompletableDeferred<Unit>? = null
    private var reconectando = false

    // Respaldo WebRTC (apagado por defecto en Ajustes)
    private var vigilanciaRespaldo: Job? = null
    private var respaldoIniciado = false
    private var respaldoConectado = false
    private var apiRtcLiberado = false

    // Puente de cámara externa (ver camaraQueRequierePuente / iniciarPuenteCamara)
    private var puenteCamara: CamaraPuenteNativo? = null
    private var ultimoFramePuenteMs = 0L
    private var idCamaraExternaActual: String? = null
    private var previewPendiente = false
    private var previewIniciada = false

    // Enrutamiento de audio de la llamada (ver activarAudioLlamada / restaurarAudioLlamada)
    private var audioManager: AudioManager? = null
    private var modoAudioPrevio = AudioManager.MODE_NORMAL
    private var speakerPrevio = false
    private var audioLlamadaActivado = false

    private val tiempoConexion = Runnable {
        if (estado == Estado.CONECTANDO && !saliendo) {
            Log.w(TAG, "⏱️ No se logró entrar a la conversación en ${TIMEOUT_CONEXION_MS / 1000} s")
            mostrarEstado(Estado.ERROR, "La conexión está tardando más de lo normal. Puedes intentar reconectar.")
        }
    }

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        initViews()
        aplicarTemaGuardado()
        configurarWebView()

        btnEndCall.setOnClickListener { confirmarSalida() }
        btnHangUp.setOnClickListener { confirmarSalida() }
        btnSwitchCamera.setOnClickListener { ejecutarJs("MosiCall.switchCamera()") }
        btnReconnect.setOnClickListener { reconectar() }

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
        btnReconnect = findViewById(R.id.btnReconnect)
        btnHangUp = findViewById(R.id.btnHangUp)
        statusOverlay = findViewById(R.id.statusOverlay)
        progressStatus = findViewById(R.id.progressStatus)
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        btnStatusAction = findViewById(R.id.btnStatusAction)
        btnStatusSecondary = findViewById(R.id.btnStatusSecondary)
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
        // apiRTC autoriza las claves por dominio de la página. Sirviendo call.html (desde los assets
        // de la app, sin red) con el dominio de la telemedicina, apiRTC la acepta igual que a la web;
        // con el dominio genérico del WebView responde "applicationUUID is not authorized".
        val hostTelemedicina = Uri.parse(TelemedicineSettingsActivity.getBaseUrl(this)).let {
            it.host.takeIf { host -> it.scheme == "https" && host != null && '.' in host && !IPV4.matches(host) }
        }
        val dominioVideo = hostTelemedicina ?: WebViewAssetLoader.DEFAULT_DOMAIN
        Log.d(TAG, "🌐 Página de video servida como $dominioVideo")
        // Queda en el mismo log de cada intento de consulta, sin tener que ir a Ajustes a pedirlo aparte
        Log.i(TAG, "🔎 Diagnóstico de WebView:\n${DiagnosticoWebView.generar(this)}")

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(dominioVideo)
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
                request.url.host != dominioVideo
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    Log.d(TAG, "🔑 Permiso solicitado por WebView: origin=${request.origin}, recursos=${request.resources.joinToString()}")
                    val permitidos = request.resources.filter {
                        it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (permitidos.isNotEmpty()) {
                        request.grant(permitidos.toTypedArray())
                        Log.d(TAG, "✅ Permisos concedidos a WebView: ${permitidos.joinToString()}")
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
        webView.loadUrl("https://$dominioVideo$CALL_PAGE_PATH")
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
                webViewChrome = datos.optInt("chrome")
                errorSdk = datos.optString("errorSdk")
                if (previewPendiente) iniciarPreview()
                if (inicioPendiente) iniciarVideo()
            }
            // Avance del video en pantalla: así se ve en qué paso se detiene sin revisar el log
            "etapa" -> if (estado == Estado.CONECTANDO) {
                tvStatusMessage.text = when (datos.optString("nombre")) {
                    "registro" -> "Conectado al servicio de video. Abriendo la cámara…"
                    "camara" -> "Cámara abierta. Abriendo el micrófono…"
                    "microfono", "enumerar" -> "Cámara y micrófono listos. Entrando a la sala con $nombreMedico…"
                    "stream-local" -> "Entrando a la sala con $nombreMedico…"
                    "join" -> "En la sala. Enviando tu video…"
                    else -> tvStatusMessage.text.toString()
                }
            }
            "joined" -> {
                handler.removeCallbacks(tiempoConexion)
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
            "previewListo" -> Log.d(TAG, "🎥 Vista previa de cámara lista")
            "previewError" -> Log.w(TAG, "⚠️ No se pudo preparar la vista previa de cámara: $datos")
            "audioDevices" -> {
                val dispositivos = datos.optJSONArray("dispositivos")
                val lista = (0 until (dispositivos?.length() ?: 0)).joinToString("\n") {
                    val d = dispositivos!!.getJSONObject(it)
                    "   - id=${d.optString("deviceId")} label=\"${d.optString("label")}\""
                }
                Log.i(TAG, "🎤 Micrófonos que ve el WebView:\n${lista.ifEmpty { "   (ninguno)" }}")
            }
            "warning" -> Log.w(TAG, "⚠️ Aviso de video: $datos")
            "fallback" -> manejarEventoRespaldo(datos.optString("estado"))
            "error" -> {
                Log.e(TAG, "❌ Error de video: $datos")
                if (datos.optString("etapa") == "fallback") {
                    // El respaldo es un canal alterno: su falla no debe tumbar la consulta principal
                    detenerRespaldo()
                    respaldoFallo()
                    return
                }
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
        "NotReadableError" -> "La cámara está siendo usada por otra aplicación o no responde. Desconéctala, vuelve a conectarla y presiona Reconectar."
        // El detalle técnico queda en el log ("Error de video"); al paciente solo un texto claro
        else -> if (datos.optString("mensaje").contains("\"camara\"")) {
            // Se agotó el tiempo abriendo la cámara (por separado del micrófono): casi siempre es la
            // cámara (USB) desconectada, o este equipo tarda de verdad más de lo normal en abrirla
            "No se pudo abrir la cámara. Revisa que la cámara USB esté conectada y presiona Reconectar."
        } else if (datos.optString("mensaje").contains("\"microfono\"")) {
            "No se pudo abrir el micrófono. Revisa la conexión y presiona Reconectar."
        } else when (datos.optString("etapa")) {
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
            iniciarPreview()
            iniciarConsulta()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                iniciarPreview()
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
    /**
     * Antes de reservar clave, registrar la consulta y avisar al médico se comprueba que este
     * equipo puede hacer video (el SDK de apiRTC cargó). Si no, no se molesta al médico ni se
     * gasta una clave en una consulta que no podría conectarse.
     */
    private suspend fun motorDeVideoListo(): Boolean {
        withTimeoutOrNull(ESPERA_MOTOR_VIDEO_MS) {
            while (!paginaLista) delay(100)
        }
        if (paginaLista && sdkDisponible) return true
        Log.w(TAG, "🚫 Motor de video no disponible (página=$paginaLista, sdk=$sdkDisponible, chrome=$webViewChrome, $errorSdk)")
        mostrarEstado(Estado.ERROR, mensajeSdkNoDisponible())
        return false
    }

    private fun mensajeSdkNoDisponible(): String =
        if (webViewChrome in 1..79 || errorSdk.contains("SyntaxError", ignoreCase = true)) {
            val version = if (webViewChrome > 0) " (Chrome $webViewChrome)" else ""
            "El WebView de este equipo$version es demasiado antiguo para la videollamada. " +
                    "Actualiza \"Android System WebView\" o el sistema del equipo."
        } else {
            "No se pudo cargar el servicio de video. Revisa la conexión a internet."
        }

    /**
     * Sin cámara no hay videollamada: antes de reservar clave y avisar al médico se comprueba que
     * Android ve una cámara (incluida una USB/externa). Si la cámara USB está conectada pero el
     * equipo no la expone como cámara, se avisa aparte porque no es un problema de conexión.
     */
    private fun camaraDisponible(): Boolean {
        val hay = runCatching {
            (getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.isNotEmpty()
        }.getOrDefault(true)
        if (hay) return true

        val usbVideo = runCatching {
            (getSystemService(Context.USB_SERVICE) as UsbManager).deviceList.values.any { dispositivo ->
                dispositivo.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                        (0 until dispositivo.interfaceCount).any {
                            dispositivo.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                        }
            }
        }.getOrDefault(false)
        Log.w(TAG, "🚫 Sin cámara para la videollamada (usbVideoConectado=$usbVideo)")
        mostrarEstado(
            Estado.ERROR,
            if (usbVideo) {
                "Hay una cámara USB conectada, pero este equipo no la reconoce como cámara para videollamadas."
            } else {
                "No se detectó ninguna cámara. Conecta la cámara USB y presiona Reintentar."
            }
        )
        return false
    }

    private fun iniciarConsulta() {
        lifecycleScope.launch {
            logCaracteristicasCamaras()
            if (!camaraDisponible()) return@launch
            if (!motorDeVideoListo()) return@launch
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
                authApiRtc = clave.apirtcToken?.let {
                    JSONObject().put("id", clave.apirtcId ?: "paciente-$idUsuarioWeb").put("token", it)
                }

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
                vigilarRespaldo()
            } catch (e: TelemedicinaException) {
                Log.e(TAG, "❌ Error iniciando la consulta: ${e.message}", e)
                mostrarEstado(Estado.ERROR, "${e.message}. Intenta de nuevo en unos minutos.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // la pantalla se está cerrando; no es un error que mostrar
            } catch (e: Exception) {
                // Cualquier fallo no previsto (JSON inesperado del servidor, etc.) no debe tumbar la
                // app: sin este resguardo, una excepción distinta a TelemedicinaException se habría
                // propagado sin capturar dentro de la corrutina.
                Log.e(TAG, "❌ Error inesperado iniciando la consulta: ${e.message}", e)
                mostrarEstado(Estado.ERROR, "Ocurrió un error inesperado. Intenta de nuevo en unos minutos.")
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
            mostrarEstado(Estado.ERROR, mensajeSdkNoDisponible())
            return
        }
        if (!previewIniciada) iniciarPreview()
        ejecutarJs(
            "MosiCall.start(${JSONObject.quote(apikeyLlamada)}, ${JSONObject.quote(codigoConversacion)}, " +
                    "${authApiRtc ?: "null"}, ${idCamaraExternaActual != null})"
        )
        handler.removeCallbacks(tiempoConexion)
        handler.postDelayed(tiempoConexion, TIMEOUT_CONEXION_MS)
    }

    /**
     * Abre cámara y micrófono en cuanto se conceden los permisos, mucho antes de que se encuentre
     * médico o se registre la consulta: así el paciente ve su propio recuadro de video mientras
     * espera (ver #local en call.html, ya no tapado por el statusOverlay), y cuando por fin arranca
     * la llamada de verdad, MosiCall.start() reutiliza esta cámara/micrófono ya abiertos en vez de
     * volver a pedirlos, ahorrando tiempo y evitando abrir la cámara dos veces.
     */
    private fun iniciarPreview() {
        if (!paginaLista) {
            previewPendiente = true
            return
        }
        previewPendiente = false
        if (previewIniciada) return
        previewIniciada = true
        activarAudioLlamada()
        idCamaraExternaActual = camaraQueRequierePuente()
        idCamaraExternaActual?.let { iniciarPuenteCamara(it) }
        ejecutarJs("MosiCall.prepararPreview(${idCamaraExternaActual != null})")
    }

    // ==========================================
    // AUDIO DE LA LLAMADA
    // ==========================================
    /**
     * El volumen que controla Ajustes (STREAM_MUSIC) es independiente del volumen con el que
     * Android reproduce el audio de una llamada WebRTC (STREAM_VOICE_CALL, activo solo en modo
     * MODE_IN_COMMUNICATION): sin esto, el equipo puede tener "todo el volumen" al tope y aun así
     * no escucharse la consulta porque STREAM_VOICE_CALL nunca se tocó ni se subió a un nivel
     * audible, y sin forzar el altavoz el audio podría salir por una salida casi inaudible.
     */
    private fun activarAudioLlamada() {
        if (audioLlamadaActivado) return
        val am = audioManager ?: return
        audioLlamadaActivado = true
        modoAudioPrevio = am.mode
        speakerPrevio = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0)
        }.onFailure { Log.w(TAG, "⚠️ No se pudo ajustar el volumen de llamada: ${it.message}") }
    }

    private fun restaurarAudioLlamada() {
        if (!audioLlamadaActivado) return
        audioLlamadaActivado = false
        val am = audioManager ?: return
        am.isSpeakerphoneOn = speakerPrevio
        am.mode = modoAudioPrevio
    }

    // ==========================================
    // PUENTE DE CÁMARA EXTERNA (Camera2 -> <canvas> del WebView)
    // ==========================================
    /**
     * En este kiosco (RK3288 + webcam USB) el HAL reporta la cámara USB como LENS_FACING_BACK
     * con HW_LEVEL_LEGACY, así que no se puede distinguir por CameraCharacteristics. Como el
     * equipo solo tiene esa cámara, y el getUserMedia del WebView 109 se cuelga con ella,
     * se usa siempre el puente nativo.
     */
    private fun camaraQueRequierePuente(): String? {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        if (ids.isEmpty()) return null

        for (id in ids) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue

            // 1) Marcada explícitamente como externa
            if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                Log.i(TAG, "🎥 Cámara $id → puente (LENS_FACING_EXTERNAL)")
                return id
            }

            // 2) HW level EXTERNAL
            if (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) {
                Log.i(TAG, "🎥 Cámara $id → puente (HW_LEVEL_EXTERNAL)")
                return id
            }

            // 3) HAL LEGACY con un solo sensor → típico de webcam USB en SoCs chinos
            val hwLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val tieneAutoFoco = afModes.any { it != android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF }

            if (ids.size == 1 && hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                && !flash && !tieneAutoFoco) {
                Log.i(TAG, "🎥 Cámara $id → puente (única cámara LEGACY sin AF ni flash, típica USB)")
                return id
            }

            // 4) Cámara con id != 0, sin flash, sin AF → sospechosa
            if (id != "0" && !flash && !tieneAutoFoco) {
                Log.i(TAG, "🎥 Cámara $id → puente (heurística USB)")
                return id
            }
        }

        Log.d(TAG, "🎥 Ninguna cámara requiere puente; se usará getUserMedia del WebView")
        return null
    }

    private fun iniciarPuenteCamara(cameraId: String) {
        Log.i(TAG, "🎥 Cámara externa detectada (id=$cameraId): usando puente nativo en vez de getUserMedia del WebView")
        puenteCamara?.detener()
        ultimoFramePuenteMs = 0L
        puenteCamara = CamaraPuenteNativo(this).apply {
            iniciar(
                cameraId = cameraId,
                anchoDeseado = PUENTE_CAMARA_ANCHO,
                altoDeseado = PUENTE_CAMARA_ALTO,
                onFrame = { jpeg -> enviarFramePuente(jpeg) },
                onError = { msg -> Log.e(TAG, "❌ Puente de cámara: $msg") }
            )
        }
    }

    /** Llega en el hilo de fondo de la cámara; se limita la tasa antes de cruzar al WebView. */
    private fun enviarFramePuente(jpeg: ByteArray) {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimoFramePuenteMs < PUENTE_CAMARA_INTERVALO_MS) return
        ultimoFramePuenteMs = ahora
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        handler.post { if (!isDestroyed) ejecutarJs("MosiCall.pushFrame(${JSONObject.quote(b64)})") }
    }

    private fun detenerPuenteCamara() {
        puenteCamara?.detener()
        puenteCamara = null
    }

    // ==========================================
    // RESPALDO WebRTC (lado paciente)
    // ==========================================
    /**
     * Igual que la web: el médico decide cuándo (escribe fallback_status='iniciando' en el
     * documento de la consulta) y el paciente crea la sala. apiRTC sigue de fondo.
     */
    private fun vigilarRespaldo() {
        if (!TelemedicineSettingsActivity.isFallbackEnabled(this)) return
        vigilanciaRespaldo?.cancel()
        vigilanciaRespaldo = lifecycleScope.launch {
            while (isActive && !saliendo) {
                val intervalo = if (estado == Estado.EN_LLAMADA) INTERVALO_RESPALDO_ESTABLE_MS else INTERVALO_RESPALDO_MS
                delay(intervalo)
                val fb = api.leerEstadoFallback() ?: continue
                when {
                    fb.eliminado || fb.status == "cancelado" -> {
                        val huboRespaldo = respaldoIniciado
                        respaldoIniciado = false
                        detenerRespaldo()
                        if (huboRespaldo && !respaldoConectado) respaldoFallo()
                    }
                    fb.status == "iniciando" && !respaldoIniciado -> iniciarRespaldo()
                    // 'terminado' lo escribe el médico en TODO cierre; solo importa si usamos el respaldo
                    fb.status == "terminado" && respaldoIniciado -> {
                        detenerRespaldo()
                        colgar()
                    }
                }
            }
        }
    }

    private suspend fun iniciarRespaldo() {
        respaldoIniciado = true
        Log.d(TAG, "🛟 El médico pidió el respaldo WebRTC")
        try {
            val credenciales = api.obtenerCredencialesFallback(idUsuarioWeb)
            val roomId = api.crearSalaFallback(credenciales)
            api.publicarSalaFallback(roomId)

            // Decisión de diseño (distinta a la web): apiRTC se libera antes de abrir el respaldo
            // para no capturar cámara y micrófono dos veces ni mantener dos conexiones activas.
            apiRtcLiberado = true
            salidaJs = CompletableDeferred()
            ejecutarJs("MosiCall.hangup()")
            withTimeoutOrNull(ESPERA_SALIDA_JS_MS) { salidaJs?.await() }

            val config = JSONObject().apply {
                put("roomId", roomId)
                put("socketUrl", credenciales.socketUrl)
                put("jwt", credenciales.jwt)
                put("iceServers", JSONArray(credenciales.iceServers.toString()))
            }
            ejecutarJs("MosiFallback.start($config)")
            handler.removeCallbacks(tiempoConexion)
            handler.postDelayed(tiempoConexion, TIMEOUT_CONEXION_MS)
        } catch (e: TelemedicinaException) {
            // La web solo registra la falla; aquí, si apiRTC ya se liberó, se ofrece reconectar
            Log.e(TAG, "❌ No se pudo iniciar el respaldo: ${e.message}", e)
            respaldoFallo()
        }
    }

    /** El respaldo no logró conectar: si apiRTC ya se había liberado no queda canal, así que se ofrece reconectar. */
    private fun respaldoFallo() {
        if (apiRtcLiberado && !saliendo && llamadaActiva()) {
            mostrarEstado(Estado.ERROR, "No se pudo establecer el canal de respaldo. Puedes intentar reconectar.")
        }
    }

    private fun manejarEventoRespaldo(estadoRtc: String) {
        Log.d(TAG, "🛟 Respaldo: $estadoRtc")
        when (estadoRtc) {
            "connected" -> {
                respaldoConectado = true
                btnReconnect.visibility = View.GONE
                btnSwitchCamera.visibility = View.GONE
                handler.removeCallbacks(tiempoConexion)
                handler.removeCallbacks(ausenciaMedico)
                if (!saliendo && (estado == Estado.CONECTANDO || estado == Estado.MEDICO_DESCONECTADO ||
                            estado == Estado.ERROR)
                ) {
                    mostrarEstado(Estado.EN_LLAMADA)
                    actualizarNombreMedico()
                }
            }
            // El médico salió del canal de respaldo: la consulta terminó
            "terminado" -> {
                detenerRespaldo()
                colgar()
            }
        }
    }

    private fun detenerRespaldo() {
        respaldoConectado = false
        btnReconnect.visibility = View.VISIBLE
        ejecutarJs("MosiFallback.stop()")
    }

    // ==========================================
    // RECONECTAR / REINTENTAR
    // ==========================================
    /** Hay una sala ya creada y notificada al médico, así que se puede volver a entrar a ella. */
    private fun puedeReconectar() = !saliendo && idNotificacion != null &&
            apikeyLlamada.isNotEmpty() && codigoConversacion.isNotEmpty()

    /**
     * Cierra la sesión de video y vuelve a entrar a la MISMA conversación (misma clave y código),
     * sin crear otra notificación: el médico sigue viendo la misma consulta.
     */
    private fun reconectar() {
        if (reconectando || !puedeReconectar()) return
        reconectando = true
        lifecycleScope.launch {
            // Misma comprobación que al iniciar la consulta: si la cámara ya no está (p. ej. se
            // desconectó el USB entre intentos) se avisa aquí, en vez de fallar dentro del JS.
            if (!camaraDisponible()) {
                reconectando = false
                return@launch
            }
            handler.removeCallbacks(tiempoConexion)
            handler.removeCallbacks(ausenciaMedico)
            tvDoctorName.visibility = View.GONE
            mostrarEstado(Estado.CONECTANDO)

            // Reconectar a apiRTC reemplaza al respaldo, si estaba abierto; se reinicia por completo
            // para que si el médico vuelve a pedirlo más adelante en la misma consulta, se atienda
            ejecutarJs("MosiFallback.stop()")
            respaldoConectado = false
            respaldoIniciado = false
            btnReconnect.visibility = View.VISIBLE
            salidaJs = CompletableDeferred()
            ejecutarJs("MosiCall.hangup()")
            withTimeoutOrNull(ESPERA_SALIDA_JS_MS) { salidaJs?.await() }
            apiRtcLiberado = false
            reconectando = false
            if (!saliendo && !isDestroyed) iniciarVideo()
        }
    }

    /** Sin sala creada: libera lo reservado y arranca el flujo completo desde cero. */
    private fun reintentar() {
        if (saliendo) return
        saliendo = true
        mostrarEstado(Estado.FINALIZANDO)
        lifecycleScope.launch {
            liberarRecursos()
            idApikeyMedico = null
            idNotificacion = null
            idCola = null
            apikeyLlamada = ""
            codigoConversacion = ""
            authApiRtc = null
            inicioLlamadaMs = 0L
            recursosLiberados = false
            respaldoIniciado = false
            respaldoConectado = false
            apiRtcLiberado = false
            previewIniciada = false
            idCamaraExternaActual = null
            saliendo = false
            if (!sdkDisponible) {
                // El SDK no cargó (p. ej. falló la red): se vuelve a cargar la página de video
                paginaLista = false
                webView.reload()
            }
            mostrarEstado(Estado.BUSCANDO)
            verificarPermisosYComenzar()
        }
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
        vigilanciaRespaldo?.cancel()
        detenerPuenteCamara()
        restaurarAudioLlamada()
        if (respaldoIniciado) ejecutarJs("MosiFallback.stop()")

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
        // Igual que la web: si la consulta no llegó a registrarse (lista de espera o falla de
        // notificacion_v2) la clave NO se libera; la válvula de 2 h del SQL la recupera.

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
                texto = "Conectando con el servicio de video…",
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

            Estado.ERROR -> {
                handler.removeCallbacks(tiempoConexion)
                val reconectable = puedeReconectar()
                panel(
                    cargando = false,
                    titulo = "No se pudo completar la consulta",
                    texto = mensaje ?: "Ocurrió un error en el servidor.",
                    accion = if (reconectable) "Reconectar" else "Reintentar",
                    accionSecundaria = "Salir",
                    alSecundaria = { salir() }
                ) { if (reconectable) reconectar() else reintentar() }
            }

            Estado.EN_LLAMADA, Estado.MEDICO_DESCONECTADO -> Unit
        }
    }

    private fun logCaracteristicasCamaras() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        Log.i(TAG, "📷 ===== Características de cámaras =====")
        manager.cameraIdList.forEach { id ->
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            if (c == null) {
                Log.i(TAG, "📷 Cámara $id: (no se pudo leer)")
                return@forEach
            }
            val facing = c.get(CameraCharacteristics.LENS_FACING)
            val hwLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
            val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString()
            val sensorSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            Log.i(TAG, "📷 Cámara $id:\n" +
                    "   LENS_FACING=$facing (0=FRONT, 1=BACK, 2=EXTERNAL)\n" +
                    "   HW_LEVEL=$hwLevel (0=LIMITED, 1=FULL, 2=LEGACY, 3=LEVEL_3, 4=EXTERNAL)\n" +
                    "   FLASH=$flash\n" +
                    "   AF_MODES=[$afModes]\n" +
                    "   CAPABILITIES=[$caps]\n" +
                    "   SENSOR_SIZE=$sensorSize")
        }
        Log.i(TAG, "📷 =====================================")
    }

    private fun panel(
        cargando: Boolean,
        titulo: String,
        texto: String,
        accion: String?,
        accionSecundaria: String? = null,
        alSecundaria: () -> Unit = {},
        alPulsar: () -> Unit
    ) {
        progressStatus.visibility = if (cargando) View.VISIBLE else View.GONE
        ivStatusIcon.visibility = if (cargando) View.GONE else View.VISIBLE
        tvStatusTitle.text = titulo
        tvStatusMessage.text = texto
        btnStatusAction.visibility = if (accion != null) View.VISIBLE else View.GONE
        btnStatusAction.text = accion.orEmpty()
        btnStatusAction.setOnClickListener { alPulsar() }
        btnStatusSecondary.visibility = if (accionSecundaria != null) View.VISIBLE else View.GONE
        btnStatusSecondary.text = accionSecundaria.orEmpty()
        btnStatusSecondary.setOnClickListener { alSecundaria() }
    }

    // ==========================================
    // CICLO DE VIDA
    // ==========================================
    override fun onResume() {
        super.onResume()
        // Se silencia VideoLoop solo si el usuario lo tenía con sonido (ver VideoLoopRemote.silenciarMientras)
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.silenciarMientras(appContext, TAG)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmarSalida()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        detenerPuenteCamara()
        restaurarAudioLlamada()
        colorReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        if (::api.isInitialized) liberarRecursosEnSegundoPlano()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("MosiBridge")
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()

        val appContext = applicationContext
        // Devuelve el audio solo si lo silenció la app; si el usuario lo había silenciado, sigue así
        CoroutineScope(Dispatchers.IO).launch {
            VideoLoopRemote.restaurarSilencio(appContext, TAG)
        }
    }
}
