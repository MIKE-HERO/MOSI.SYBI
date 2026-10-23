package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.helpers.Camera2Helper
import java.io.File

/**
 * Configuración de periféricos: Lector de Tarjetas IC, Impresoras y Cámaras.
 */
class MeasurementParametersActivity : BaseActivity() {

    companion object {
        private const val SEPARATOR = -1
    }

    private val allSwitches = mutableListOf<Switch>()
    private var themeColor: Int = Color.parseColor("#0F3E82")
    private lateinit var sideBar: View
    private lateinit var btnGuardar: Button
    private lateinit var tvCameraSectionHeader: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement_parameters)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.themeAccentBar)
        btnGuardar = findViewById(R.id.btnGuardarParametros)
        tvCameraSectionHeader = findViewById(R.id.tvCameraSectionHeader)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                themeColor = Color.parseColor(newColor)
                sideBar.setBackgroundColor(themeColor)
                btnGuardar.backgroundTintList = android.content.res.ColorStateList.valueOf(themeColor)
                tvCameraSectionHeader.setTextColor(themeColor)
                applySwitchTint(themeColor)
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        themeColor = Color.parseColor(savedColor)
        sideBar.setBackgroundColor(themeColor)
        btnGuardar.backgroundTintList = android.content.res.ColorStateList.valueOf(themeColor)
        tvCameraSectionHeader.setTextColor(themeColor)

        findViewById<View>(R.id.btnBackMeasurementParameters).setOnClickListener {
            saveAllPendingSettings()
            finish()
        }

        btnGuardar.setOnClickListener {
            saveAllPendingSettings()
            finish()
        }

        val availablePorts = scanAvailablePorts()
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        DeviceDefaults.ensureDefaultPorts(devicePrefs)

        // --- LECTOR TARJETA IC ---
        val switchIcCard = findViewById<Switch>(R.id.switchIcCard)
        val layoutIcCardConfig = findViewById<LinearLayout>(R.id.layoutIcCardConfig)
        val spinnerIcCardPort = findViewById<Spinner>(R.id.spinnerIcCardPort)
        val spinnerIcCardBaud = findViewById<Spinner>(R.id.spinnerIcCardBaud)
        allSwitches.add(switchIcCard)

        val prefKeyIc = TestElementsActivity.PREF_DEVICE_IC_CARD
        switchIcCard.isChecked = devicePrefs.getBoolean(prefKeyIc, true)
        layoutIcCardConfig.visibility = if (switchIcCard.isChecked) View.VISIBLE else View.GONE
        switchIcCard.setOnCheckedChangeListener { _, isChecked ->
            devicePrefs.edit().putBoolean(prefKeyIc, isChecked).apply()
            layoutIcCardConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        setupPortSpinner(spinnerIcCardPort, prefKeyIc, devicePrefs, availablePorts)
        bindBaudSpinner(spinnerIcCardBaud, devicePrefs, prefKeyIc)

        // --- IMPRESORA TÉRMICA DE TICKETS ---
        val switchThermalPrinter = findViewById<Switch>(R.id.switchThermalPrinter)
        val layoutThermalPrinterConfig = findViewById<LinearLayout>(R.id.layoutThermalPrinterConfig)
        val spinnerThermalPrinterPort = findViewById<Spinner>(R.id.spinnerThermalPrinterPort)
        val spinnerThermalPrinterBaud = findViewById<Spinner>(R.id.spinnerThermalPrinterBaud)
        allSwitches.add(switchThermalPrinter)

        val prefKeyPrinter = TestElementsActivity.PREF_DEVICE_THERMAL_PRINTER
        switchThermalPrinter.isChecked = devicePrefs.getBoolean(prefKeyPrinter, true)
        layoutThermalPrinterConfig.visibility = if (switchThermalPrinter.isChecked) View.VISIBLE else View.GONE
        switchThermalPrinter.setOnCheckedChangeListener { _, isChecked ->
            devicePrefs.edit().putBoolean(prefKeyPrinter, isChecked).apply()
            layoutThermalPrinterConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        setupPortSpinner(spinnerThermalPrinterPort, prefKeyPrinter, devicePrefs, availablePorts)
        bindBaudSpinner(spinnerThermalPrinterBaud, devicePrefs, prefKeyPrinter)

        // --- IMPRESORA DE RED (IPP) ---
        val etPrinterIp = findViewById<EditText>(R.id.etPrinterIp)
        etPrinterIp.setText(devicePrefs.getString("printer_ip", "") ?: "")
        etPrinterIp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                devicePrefs.edit().putString("printer_ip", s.toString().trim()).apply()
            }
        })

        // --- CONFIGURACIÓN DE CÁMARAS ---
        val availableCameras = Camera2Helper.getAvailableCameraIds(this).ifEmpty { listOf("0") }

        // Cámara Principal
        val switchMainCameraMirror = findViewById<Switch>(R.id.switchMainCameraMirror)
        val spinnerMainCameraId = findViewById<Spinner>(R.id.spinnerMainCameraId)
        allSwitches.add(switchMainCameraMirror)
        setupCameraRow("pref_main_camera_id", "pref_main_camera_mirror", true, switchMainCameraMirror, spinnerMainCameraId, devicePrefs, availableCameras)

        // Cámara Secundaria
        val switchSecondaryCameraMirror = findViewById<Switch>(R.id.switchSecondaryCameraMirror)
        val spinnerSecondaryCameraId = findViewById<Spinner>(R.id.spinnerSecondaryCameraId)
        allSwitches.add(switchSecondaryCameraMirror)
        setupCameraRow("pref_secondary_camera_id", "pref_secondary_camera_mirror", false, switchSecondaryCameraMirror, spinnerSecondaryCameraId, devicePrefs, availableCameras)

        applySwitchTint(themeColor)
    }

    override fun onPause() {
        super.onPause()
        saveAllPendingSettings()
    }

    private fun saveAllPendingSettings() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("UPDATE_SERIAL_PORTS"))
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_UPDATE_DEVICES"))
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("CONFIG_UPDATED"))
    }

    private fun scanAvailablePorts(): List<String> {
        val ports = mutableListOf<String>()
        val dir = File("/dev")
        if (dir.exists() && dir.isDirectory) {
            val files = dir.listFiles()
            if (files != null) {
                for (file in files) {
                    val name = file.name
                    if (name.startsWith("ttyS") || name.startsWith("ttyUSB") || name.startsWith("ttyACM")) {
                        ports.add("/dev/$name")
                    }
                }
            }
        }
        if (ports.isEmpty()) {
            ports.add("No se encontraron puertos")
        }
        return ports
    }

    private fun setupPortSpinner(
        spinner: Spinner,
        prefKey: String,
        prefs: android.content.SharedPreferences,
        availablePorts: List<String>
    ) {
        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availablePorts)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = portAdapter

        val savedPort = prefs.getString("${prefKey}_port", null)
        val defaultPort = DeviceDefaults.DEFAULT_PORTS[prefKey]

        val portToSelect = when {
            savedPort != null && availablePorts.contains(savedPort) -> savedPort
            defaultPort != null && availablePorts.contains(defaultPort) -> defaultPort
            else -> null
        }

        if (portToSelect != null) {
            val position = availablePorts.indexOf(portToSelect)
            if (position >= 0) {
                spinner.setSelection(position)
                if (savedPort == null) {
                    prefs.edit().putString("${prefKey}_port", portToSelect).apply()
                }
            }
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPort = availablePorts[position]
                prefs.edit().putString("${prefKey}_port", selectedPort).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun bindBaudSpinner(spinner: Spinner, prefs: android.content.SharedPreferences, prefKey: String) {
        val current = SerialBaudConfig.get(prefs, prefKey)
        val standardInRange = SerialBaudConfig.OPTIONS.filter { SerialBaudConfig.isStandard(it) }
        val values = mutableListOf<Int?>(null)
        if (current !in SerialBaudConfig.OPTIONS) values.add(current)
        values.addAll(standardInRange)
        values.add(SEPARATOR)
        values.addAll(SerialBaudConfig.OPTIONS)
        val labels = values.map { v ->
            when {
                v == null -> "✎ Personalizado…"
                v == SEPARATOR -> "──── Todas (600 a 115200) ────"
                v !in SerialBaudConfig.OPTIONS -> "$v (personalizado)"
                SerialBaudConfig.isStandard(v) -> "$v ★"
                else -> "$v"
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.onItemSelectedListener = null
        spinner.adapter = adapter
        spinner.setSelection(values.indexOf(current), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = values[position]
                if (value == null) {
                    showCustomBaudDialog(spinner, prefs, prefKey)
                } else if (value == SEPARATOR) {
                    bindBaudSpinner(spinner, prefs, prefKey)
                } else if (value != SerialBaudConfig.get(prefs, prefKey)) {
                    SerialBaudConfig.set(prefs, prefKey, value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showCustomBaudDialog(spinner: Spinner, prefs: android.content.SharedPreferences, prefKey: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(SerialBaudConfig.get(prefs, prefKey).toString())
            setSelection(text.length)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Velocidad personalizada")
            .setMessage(
                "Escriba la velocidad en baudios (${SerialBaudConfig.CUSTOM_MIN} a ${SerialBaudConfig.CUSTOM_MAX}).\n\n" +
                        "Las marcadas con ★ son las estándar que admite el puerto."
            )
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                val value = input.text.toString().trim().toIntOrNull()
                if (value == null || value !in SerialBaudConfig.CUSTOM_MIN..SerialBaudConfig.CUSTOM_MAX) {
                    Toast.makeText(this, "Velocidad no válida", Toast.LENGTH_SHORT).show()
                } else {
                    SerialBaudConfig.set(prefs, prefKey, value)
                }
                bindBaudSpinner(spinner, prefs, prefKey)
            }
            .setNegativeButton("Cancelar") { _, _ -> bindBaudSpinner(spinner, prefs, prefKey) }
            .setOnCancelListener { bindBaudSpinner(spinner, prefs, prefKey) }
            .create()
        setupDialogKeyboardBehavior(dialog)
        dialog.show()
    }

    private fun setupCameraRow(
        prefIdKey: String,
        prefMirrorKey: String,
        defaultMirror: Boolean,
        switchMirror: Switch,
        spinner: Spinner,
        prefs: android.content.SharedPreferences,
        availableCameras: List<String>
    ) {
        if (!prefs.contains(prefMirrorKey)) {
            prefs.edit().putBoolean(prefMirrorKey, defaultMirror).apply()
        }
        switchMirror.isChecked = prefs.getBoolean(prefMirrorKey, defaultMirror)

        val displayNames = availableCameras.map { id ->
            when (id) {
                "0" -> "Cámara 0 (Frontal)"
                "1" -> "Cámara 1 (Externa)"
                else -> "Cámara $id (Externa)"
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames.ifEmpty { listOf("Cámara 0 (USB / Estándar)") })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val savedId = prefs.getString(prefIdKey, availableCameras.firstOrNull() ?: "0")
        val savedIndex = availableCameras.indexOf(savedId)
        if (savedIndex >= 0) {
            spinner.setSelection(savedIndex)
        }

        switchMirror.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(prefMirrorKey, isChecked).apply()
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedCameraId = availableCameras.getOrNull(position) ?: "0"
                prefs.edit().putString(prefIdKey, selectedCameraId).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
    }
}
