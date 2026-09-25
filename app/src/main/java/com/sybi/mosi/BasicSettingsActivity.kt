package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class BasicSettingsActivity : BaseActivity() {

    companion object {
        private const val TAG = "BasicSettingsActivity"
    }

    private lateinit var sideBarLayout: View
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtCurrentDate: TextView

    private lateinit var seekBarBrightness: SeekBar
    private lateinit var txtBrightnessValue: TextView
    private lateinit var imgBrightnessIcon: ImageView
    private lateinit var imgAndroidIcon: ImageView

    private lateinit var seekBarVolume: SeekBar
    private lateinit var txtVolumeValue: TextView
    private lateinit var imgVolumeIcon: ImageView

    private lateinit var seekBarVolume2: SeekBar
    private lateinit var txtVolume2Value: TextView
    private lateinit var imgVolume2Icon: ImageView
    private lateinit var swVideoMeasurementMute: Switch

    private lateinit var btnWifiSettings: LinearLayout
    private lateinit var imgWifiIcon: ImageView
    private lateinit var txtWifiStatus: TextView

    private lateinit var btnEthernetSettings: LinearLayout
    private lateinit var imgEthernetIcon: ImageView
    private lateinit var txtEthernetStatus: TextView

    private lateinit var audioManager: AudioManager
    private var maxVolume: Int = 100

    /** Último volumen conocido de la pantalla secundaria (VideoLoop). */
    private var lastSecondaryVolume: Int = 70
    /** Estado de mute local de la pantalla principal (solo icono). */
    private var mainMuted: Boolean = false
    /** Estado de mute local de la pantalla secundaria (solo icono). */
    private var secondaryMuted: Boolean = false

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            actualizarFechaHoraActual()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val newColor = intent.getStringExtra("new_color")
            if (newColor != null) {
                aplicarColorTema(newColor)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_basic_settings)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        initViews()
        setupListeners()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        aplicarColorTema(savedColor)

        setupBrightnessControl()
        setupVolumeControl()
        setupVolume2Control()
        setupMeasurementMuteSwitch()
    }

    override fun onResume() {
        super.onResume()
        timeHandler.post(timeRunnable)
        actualizarEstadoWifi()
        actualizarEstadoEthernet()
        actualizarControlVolumenUI()
        actualizarControlVolumen2UI()
        actualizarIconosMute()
    }

    override fun onPause() {
        super.onPause()
        timeHandler.removeCallbacks(timeRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver)
        } catch (_: Exception) { }
    }

    private fun initViews() {
        sideBarLayout = findViewById(R.id.sideBarLayout)

        txtCurrentTime = findViewById(R.id.txtCurrentTime)
        txtCurrentDate = findViewById(R.id.txtCurrentDate)

        seekBarBrightness = findViewById(R.id.seekBarBrightness)
        txtBrightnessValue = findViewById(R.id.txtBrightnessValue)
        imgBrightnessIcon = findViewById(R.id.imgBrightnessIcon)
        imgAndroidIcon = findViewById(R.id.imgAndroidIcon)

        seekBarVolume = findViewById(R.id.seekBarVolume)
        txtVolumeValue = findViewById(R.id.txtVolumeValue)
        imgVolumeIcon = findViewById(R.id.imgVolumeIcon)

        seekBarVolume2 = findViewById(R.id.seekBarVolume2)
        txtVolume2Value = findViewById(R.id.txtVolume2Value)
        imgVolume2Icon = findViewById(R.id.imgVolume2Icon)
        swVideoMeasurementMute = findViewById(R.id.swVideoMeasurementMute)

        btnWifiSettings = findViewById(R.id.btnWifiSettings)
        imgWifiIcon = findViewById(R.id.imgWifiIcon)
        txtWifiStatus = findViewById(R.id.txtWifiStatus)

        btnEthernetSettings = findViewById(R.id.btnEthernetSettings)
        imgEthernetIcon = findViewById(R.id.imgEthernetIcon)
        txtEthernetStatus = findViewById(R.id.txtEthernetStatus)
    }

    private fun setupListeners() {
        // Regresar
        findViewById<View>(R.id.btnBackBasicSettings).setOnClickListener { finish() }

        // Reloj/Fecha -> Ajustes de Fecha y Hora de Android
        findViewById<View>(R.id.btnDateTimeCard).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudieron abrir los ajustes de Fecha y Hora", Toast.LENGTH_SHORT).show()
            }
        }

        // Ajustes de Android
        findViewById<View>(R.id.btnAndroidSettings).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "No se pudieron abrir los ajustes de Android", Toast.LENGTH_SHORT).show()
            }
        }

        // Wi-Fi
        btnWifiSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "No se pudieron abrir los ajustes de Wi-Fi", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Ethernet
        btnEthernetSettings.setOnClickListener {
            try {
                val intent = Intent("android.settings.ETHERNET_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e2: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e3: Exception) {
                        Toast.makeText(this, "No se pudieron abrir los ajustes de Ethernet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Icono de volumen principal -> Mute del sistema
        imgVolumeIcon.setOnClickListener { toggleMainMute() }

        // Icono de volumen secundaria -> Mute del VideoLoop
        imgVolume2Icon.setOnClickListener { toggleSecondaryMute() }
    }

    // ============================================================
    // MUTE PRINCIPAL (sistema)
    // ============================================================
    private fun toggleMainMute() {
        mainMuted = !mainMuted
        try {
            val target = if (mainMuted) 0 else {
                // Restaurar al 70% si estaba en mute
                val pct = 70
                (pct * maxVolume / 100).coerceIn(0, maxVolume)
            }
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            val pct = if (maxVolume > 0) (target * 100 / maxVolume).coerceIn(0, 100) else 0
            seekBarVolume.progress = pct
            txtVolumeValue.text = "$pct%"
        } catch (e: Exception) {
            Log.e(TAG, "Error toggle mute principal: ${e.message}")
        }
        actualizarIconosMute()
    }

    // ============================================================
    // MUTE SECUNDARIA (VideoLoop remoto)
    // ============================================================
    private fun toggleSecondaryMute() {
        secondaryMuted = !secondaryMuted
        val hostPort = VideoLoopRemote.getSavedHostPort(this)
        if (hostPort.isBlank()) {
            Toast.makeText(this, "Configura primero la IP de la pantalla secundaria", Toast.LENGTH_SHORT).show()
            secondaryMuted = !secondaryMuted
            return
        }
        lifecycleScope.launch {
            val ok = VideoLoopRemote.sendMuted(this@BasicSettingsActivity, secondaryMuted)
            if (!ok.ok) {
                Toast.makeText(this@BasicSettingsActivity, "No se pudo cambiar el mute de la pantalla secundaria", Toast.LENGTH_SHORT).show()
                secondaryMuted = !secondaryMuted
            }
            actualizarIconosMute()
        }
    }

    private fun actualizarIconosMute() {
        imgVolumeIcon.setImageResource(if (mainMuted) R.drawable.ic_mute else R.drawable.ic_volume)
        imgVolume2Icon.setImageResource(if (secondaryMuted) R.drawable.ic_mute else R.drawable.ic_volume)
        // Reaplicar el tinte porque al cambiar el drawable se pierde
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        val colorInt = Color.parseColor(savedColor)
        imgVolumeIcon.setColorFilter(colorInt)
        imgVolume2Icon.setColorFilter(colorInt)
    }

    // ============================================================
    // SWITCH DE MEDICIÓN (VideoLoop)
    // ============================================================
    private fun setupMeasurementMuteSwitch() {
        swVideoMeasurementMute.isChecked =
            VideoLoopRemote.isMeasurementMuteEnabled(this)
        swVideoMeasurementMute.setOnCheckedChangeListener { _, checked ->
            VideoLoopRemote.setMeasurementMuteEnabled(this, checked)
        }
    }

    // ============================================================
    // ACTUALIZAR FECHA Y HORA EN TIEMPO REAL
    // ============================================================
    private fun actualizarFechaHoraActual() {
        try {
            val now = Date()
            val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
            val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("es", "ES"))

            txtCurrentTime.text = timeFormat.format(now)

            val rawDateStr = dateFormat.format(now)
            val formattedDateStr = rawDateStr.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale("es", "ES")) else it.toString()
            }
            txtCurrentDate.text = formattedDateStr
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando fecha/hora: ${e.message}")
        }
    }

    // ============================================================
    // CONTROL DE BRILLO
    // ============================================================
    private fun setupBrightnessControl() {
        var currentBrightnessPercent = 80

        try {
            val systemBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            currentBrightnessPercent = (systemBrightness * 100 / 255).coerceIn(10, 100)
        } catch (e: Exception) {
            val winBrightness = window.attributes.screenBrightness
            if (winBrightness >= 0) {
                currentBrightnessPercent = (winBrightness * 100).toInt().coerceIn(10, 100)
            }
        }

        seekBarBrightness.progress = currentBrightnessPercent
        txtBrightnessValue.text = "$currentBrightnessPercent%"

        seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val valPct = progress.coerceAtLeast(5)
                txtBrightnessValue.text = "$valPct%"

                val lp = window.attributes
                lp.screenBrightness = valPct / 100f
                window.attributes = lp

                try {
                    if (Settings.System.canWrite(applicationContext)) {
                        val sysValue = (valPct * 255 / 100).coerceIn(1, 255)
                        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, sysValue)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "No se pudo guardar brillo del sistema: ${e.message}")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ============================================================
    // CONTROL DE VOLUMEN PRINCIPAL (sistema)
    // ============================================================
    private fun setupVolumeControl() {
        actualizarControlVolumenUI()

        seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtVolumeValue.text = "$progress%"
                if (fromUser && maxVolume > 0) {
                    val targetVolume = (progress * maxVolume / 100).coerceIn(0, maxVolume)
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al cambiar volumen: ${e.message}")
                    }
                }
                if (fromUser) {
                    mainMuted = (progress == 0)
                    actualizarIconosMute()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun actualizarControlVolumenUI() {
        try {
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val pct = if (maxVolume > 0) (currentVol * 100 / maxVolume).coerceIn(0, 100) else 0
            seekBarVolume.progress = pct
            txtVolumeValue.text = "$pct%"
            mainMuted = (pct == 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo volumen: ${e.message}")
        }
    }

    // ============================================================
    // CONTROL DE VOLUMEN SECUNDARIA (VideoLoop)
    // ============================================================
    private fun setupVolume2Control() {
        // Cargar el último valor guardado localmente
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        lastSecondaryVolume = prefs.getInt("videoloop_last_volume", 70).coerceIn(0, 100)
        seekBarVolume2.progress = lastSecondaryVolume
        txtVolume2Value.text = "$lastSecondaryVolume%"

        seekBarVolume2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                txtVolume2Value.text = "$progress%"
                if (fromUser) {
                    secondaryMuted = (progress == 0)
                    actualizarIconosMute()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val vol = seekBar?.progress ?: return
                lastSecondaryVolume = vol
                getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
                    .edit().putInt("videoloop_last_volume", vol).apply()

                val hostPort = VideoLoopRemote.getSavedHostPort(this@BasicSettingsActivity)
                if (hostPort.isBlank()) {
                    Toast.makeText(this@BasicSettingsActivity, "Configura primero la IP de la pantalla secundaria", Toast.LENGTH_SHORT).show()
                    return
                }
                lifecycleScope.launch {
                    val result = VideoLoopRemote.setVolume(this@BasicSettingsActivity, vol)
                    if (!result.ok) {
                        Toast.makeText(this@BasicSettingsActivity, "No se pudo cambiar el volumen: ${result.error}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun actualizarControlVolumen2UI() {
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        lastSecondaryVolume = prefs.getInt("videoloop_last_volume", lastSecondaryVolume).coerceIn(0, 100)
        seekBarVolume2.progress = lastSecondaryVolume
        txtVolume2Value.text = "$lastSecondaryVolume%"
    }

    // ============================================================
    // ESTADO DE RED (WI-FI Y ETHERNET)
    // ============================================================
    private fun actualizarEstadoWifi() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (!wifiManager.isWifiEnabled) {
                txtWifiStatus.text = "Apagado"
                txtWifiStatus.setTextColor(Color.parseColor("#9E9E9E"))
                imgWifiIcon.setColorFilter(Color.parseColor("#9E9E9E"))
                return
            }

            val wifiInfo = wifiManager.connectionInfo
            val isWifiConnectedByWifiManager = wifiInfo != null && wifiInfo.networkId != -1 && wifiInfo.bssid != null

            var isWifiConnectedByCM = false
            try {
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    isWifiConnectedByCM = true
                }
            } catch (_: Exception) {}

            if (isWifiConnectedByWifiManager || isWifiConnectedByCM) {
                val ssidRaw = wifiInfo?.ssid ?: ""
                val ssid = if (ssidRaw.startsWith("\"") && ssidRaw.endsWith("\"")) {
                    ssidRaw.substring(1, ssidRaw.length - 1)
                } else ssidRaw

                val networkName = if (ssid.isNotEmpty() && ssid != "<unknown ssid>") ssid else "Conectado"

                txtWifiStatus.text = networkName
                txtWifiStatus.setTextColor(Color.parseColor("#2E7D32"))
                imgWifiIcon.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                txtWifiStatus.text = "Sin conexión"
                txtWifiStatus.setTextColor(Color.parseColor("#E65100"))
                imgWifiIcon.setColorFilter(Color.parseColor("#FF9800"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando Wi-Fi: ${e.message}")
            txtWifiStatus.text = "Sin conexión"
            txtWifiStatus.setTextColor(Color.parseColor("#666666"))
            imgWifiIcon.setColorFilter(Color.parseColor("#0F3E82"))
        }
    }

    private fun tieneAccesoInternet(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            false
        }
    }

    private fun actualizarEstadoEthernet() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var isEthernetConnected = false

            val activeNetwork = cm.activeNetworkInfo
            if (activeNetwork != null && activeNetwork.type == ConnectivityManager.TYPE_ETHERNET && activeNetwork.isConnected) {
                isEthernetConnected = true
            } else {
                val networks = cm.allNetworks
                for (network in networks) {
                    val caps = cm.getNetworkCapabilities(network)
                    val info = cm.getNetworkInfo(network)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) && info?.isConnected == true) {
                        isEthernetConnected = true
                        break
                    }
                }
            }

            if (isEthernetConnected) {
                val ipAddress = obtenerIpEthernet()
                txtEthernetStatus.text = if (ipAddress.isNotEmpty()) ipAddress else "Conectado"
                txtEthernetStatus.setTextColor(Color.parseColor("#2E7D32"))
                imgEthernetIcon.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                txtEthernetStatus.text = "No conectado"
                txtEthernetStatus.setTextColor(Color.parseColor("#9E9E9E"))
                imgEthernetIcon.setColorFilter(Color.parseColor("#9E9E9E"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando Ethernet: ${e.message}")
            txtEthernetStatus.text = "No conectado"
            txtEthernetStatus.setTextColor(Color.parseColor("#666666"))
            imgEthernetIcon.setColorFilter(Color.parseColor("#0F3E82"))
        }
    }

    private fun obtenerIpEthernet(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (iface in interfaces) {
                if (iface.name.lowercase().contains("eth")) {
                    val addrs = Collections.list(iface.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: ""
                            if (host.indexOf(':') < 0) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo IP de Ethernet: ${e.message}")
        }
        return ""
    }

    // ============================================================
    // APLICAR TEMA DINÁMICO
    // ============================================================
    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            val colorStateList = ColorStateList.valueOf(colorInt)

            sideBarLayout.setBackgroundColor(colorInt)
            txtCurrentTime.setTextColor(colorInt)

            seekBarBrightness.progressTintList = colorStateList
            seekBarBrightness.thumbTintList = colorStateList


            seekBarVolume.progressTintList = colorStateList
            seekBarVolume.thumbTintList = colorStateList

            seekBarVolume2.progressTintList = colorStateList
            seekBarVolume2.thumbTintList = colorStateList

            txtBrightnessValue.setTextColor(colorInt)
            txtVolumeValue.setTextColor(colorInt)
            txtVolume2Value.setTextColor(colorInt)

            imgBrightnessIcon.setColorFilter(colorInt)
            imgVolumeIcon.setColorFilter(colorInt)
            imgVolume2Icon.setColorFilter(colorInt)

            // El switch de medición también toma el color del tema
            swVideoMeasurementMute.thumbTintList = colorStateList
            swVideoMeasurementMute.trackTintList = ColorStateList.valueOf(
                Color.argb(80, Color.red(colorInt), Color.green(colorInt), Color.blue(colorInt))
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color de tema: ${e.message}")
        }
    }
}