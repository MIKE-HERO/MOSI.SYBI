package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.File

class TestElementsActivity : BaseActivity() {

    companion object {
        const val PREF_DEVICE_ALTURA_PESO = "device_altura_peso"
        const val PREF_DEVICE_COMPOSICION = "device_composicion"
        const val PREF_DEVICE_PRESION = "device_presion"
        const val PREF_DEVICE_TEMPERATURA = "device_temperatura"
        const val PREF_DEVICE_OXIGENO = "device_oxigeno"
        const val PREF_DEVICE_ECG = "device_ecg"
        const val PREF_DEVICE_AZUCAR = "device_azucar"
        const val PREF_DEVICE_ACIDO_URICO = "device_acido_urico"
        const val PREF_DEVICE_COLESTEROL = "device_colesterol"
        const val PREF_DEVICE_IC_CARD = "device_ic_card"
        const val PREF_DEVICE_THERMAL_PRINTER = "device_thermal_printer"

        /** Derivaciones de ECG (6 o 12) configuradas en Elementos de prueba. */
        const val PREF_ECG_DERIVACIONES = "ecg_derivaciones"

        /** Marca de la línea divisoria en el selector de velocidad. */
        private const val SEPARATOR = -1
    }

    private val allSwitches = mutableListOf<Switch>()
    private val allRadioButtons = mutableListOf<RadioButton>()
    private var themeColor: Int = Color.parseColor("#0F3E82")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_elements)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val themeAccentBar = findViewById<View>(R.id.themeAccentBar)
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    themeColor = Color.parseColor(newColor)
                    themeAccentBar.setBackgroundColor(themeColor)
                    applySwitchTint(themeColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        themeColor = Color.parseColor(savedColor)
        themeAccentBar.setBackgroundColor(themeColor)

        findViewById<View>(R.id.btnBackTestElements).setOnClickListener {
            saveAllPendingSettings()
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_UPDATE_DEVICES"))
            finish()
        }

        val availablePorts = scanAvailablePorts()
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        DeviceDefaults.ensureDefaultPorts(devicePrefs)

        // 1. Altura / Peso
        val switchAlturaPeso = findViewById<Switch>(R.id.switchAlturaPeso)
        val layoutAlturaPesoConfig = findViewById<LinearLayout>(R.id.layoutAlturaPesoConfig)
        val spinnerAlturaPesoPort = findViewById<Spinner>(R.id.spinnerAlturaPesoPort)
        val spinnerAlturaPesoBaud = findViewById<Spinner>(R.id.spinnerAlturaPesoBaud)
        allSwitches.add(switchAlturaPeso)
        setupSerialDevice(PREF_DEVICE_ALTURA_PESO, switchAlturaPeso, layoutAlturaPesoConfig, spinnerAlturaPesoPort, spinnerAlturaPesoBaud, devicePrefs, availablePorts)

        // 2. Composición Corporal
        val switchComposicion = findViewById<Switch>(R.id.switchComposicion)
        val layoutComposicionConfig = findViewById<LinearLayout>(R.id.layoutComposicionConfig)
        val cardComposicion = findViewById<LinearLayout>(R.id.cardComposicion)
        allSwitches.add(switchComposicion)

        if (!devicePrefs.contains(PREF_DEVICE_COMPOSICION)) {
            devicePrefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, true).apply()
        }
        switchComposicion.isChecked = devicePrefs.getBoolean(PREF_DEVICE_COMPOSICION, true)
        layoutComposicionConfig.visibility = if (switchComposicion.isChecked) View.VISIBLE else View.GONE
        cardComposicion.visibility = if (switchAlturaPeso.isChecked) View.VISIBLE else View.GONE

        switchComposicion.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !devicePrefs.getBoolean(PREF_DEVICE_ALTURA_PESO, true)) {
                Toast.makeText(this, "Debe activar primero Altura / Peso", Toast.LENGTH_LONG).show()
                switchComposicion.isChecked = false
                return@setOnCheckedChangeListener
            }
            devicePrefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, isChecked).apply()
            layoutComposicionConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Listener especial de Altura / Peso para actualizar Composición
        switchAlturaPeso.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !checkActiveDevicesCount(PREF_DEVICE_ALTURA_PESO, devicePrefs)) {
                Toast.makeText(this, "Debe tener activado al menos un elemento de medición", Toast.LENGTH_LONG).show()
                switchAlturaPeso.isChecked = true
                return@setOnCheckedChangeListener
            }
            devicePrefs.edit().putBoolean(PREF_DEVICE_ALTURA_PESO, isChecked).apply()
            layoutAlturaPesoConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            cardComposicion.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                devicePrefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, false).apply()
                switchComposicion.isChecked = false
                layoutComposicionConfig.visibility = View.GONE
            }
        }

        // 3. Presión Arterial
        val switchPresion = findViewById<Switch>(R.id.switchPresion)
        val layoutPresionConfig = findViewById<LinearLayout>(R.id.layoutPresionConfig)
        val spinnerPresionPort = findViewById<Spinner>(R.id.spinnerPresionPort)
        val spinnerPresionBaud = findViewById<Spinner>(R.id.spinnerPresionBaud)
        allSwitches.add(switchPresion)
        setupSerialDevice(PREF_DEVICE_PRESION, switchPresion, layoutPresionConfig, spinnerPresionPort, spinnerPresionBaud, devicePrefs, availablePorts)

        // 4. Temperatura Corporal
        val switchTemperatura = findViewById<Switch>(R.id.switchTemperatura)
        val layoutTemperaturaConfig = findViewById<LinearLayout>(R.id.layoutTemperaturaConfig)
        val spinnerTemperaturaPort = findViewById<Spinner>(R.id.spinnerTemperaturaPort)
        val spinnerTemperaturaBaud = findViewById<Spinner>(R.id.spinnerTemperaturaBaud)
        allSwitches.add(switchTemperatura)
        setupSerialDevice(PREF_DEVICE_TEMPERATURA, switchTemperatura, layoutTemperaturaConfig, spinnerTemperaturaPort, spinnerTemperaturaBaud, devicePrefs, availablePorts)

        // 5. Oxígeno en Sangre
        val switchOxigeno = findViewById<Switch>(R.id.switchOxigeno)
        val layoutOxigenoConfig = findViewById<LinearLayout>(R.id.layoutOxigenoConfig)
        allSwitches.add(switchOxigeno)
        setupSimpleDevice(PREF_DEVICE_OXIGENO, switchOxigeno, layoutOxigenoConfig, devicePrefs)

        // 6. ECG
        val switchEcg = findViewById<Switch>(R.id.switchEcg)
        val layoutEcgConfig = findViewById<LinearLayout>(R.id.layoutEcgConfig)
        val rgEcgDerivaciones = findViewById<RadioGroup>(R.id.rgEcgDerivaciones)
        val rbEcg6 = findViewById<RadioButton>(R.id.rbEcg6)
        val rbEcg12 = findViewById<RadioButton>(R.id.rbEcg12)
        allSwitches.add(switchEcg)
        allRadioButtons.add(rbEcg6)
        allRadioButtons.add(rbEcg12)
        setupSimpleDevice(PREF_DEVICE_ECG, switchEcg, layoutEcgConfig, devicePrefs)

        val currentDerivations = devicePrefs.getInt(PREF_ECG_DERIVACIONES, 6)
        if (currentDerivations == 12) rbEcg12.isChecked = true else rbEcg6.isChecked = true
        rgEcgDerivaciones.setOnCheckedChangeListener { _, checkedId ->
            val derivaciones = if (checkedId == R.id.rbEcg12) 12 else 6
            devicePrefs.edit().putInt(PREF_ECG_DERIVACIONES, derivaciones).apply()
        }

        // 7. Azúcar en Sangre
        val switchAzucar = findViewById<Switch>(R.id.switchAzucar)
        val layoutAzucarConfig = findViewById<LinearLayout>(R.id.layoutAzucarConfig)
        val spinnerAzucarPort = findViewById<Spinner>(R.id.spinnerAzucarPort)
        val spinnerAzucarBaud = findViewById<Spinner>(R.id.spinnerAzucarBaud)
        allSwitches.add(switchAzucar)
        setupSerialDevice(PREF_DEVICE_AZUCAR, switchAzucar, layoutAzucarConfig, spinnerAzucarPort, spinnerAzucarBaud, devicePrefs, availablePorts)

        // 8. Ácido Úrico
        val switchAcidoUrico = findViewById<Switch>(R.id.switchAcidoUrico)
        val layoutAcidoUricoConfig = findViewById<LinearLayout>(R.id.layoutAcidoUricoConfig)
        val spinnerAcidoUricoPort = findViewById<Spinner>(R.id.spinnerAcidoUricoPort)
        val spinnerAcidoUricoBaud = findViewById<Spinner>(R.id.spinnerAcidoUricoBaud)
        allSwitches.add(switchAcidoUrico)
        setupSerialDevice(PREF_DEVICE_ACIDO_URICO, switchAcidoUrico, layoutAcidoUricoConfig, spinnerAcidoUricoPort, spinnerAcidoUricoBaud, devicePrefs, availablePorts)

        // 9. Colesterol Total
        val switchColesterol = findViewById<Switch>(R.id.switchColesterol)
        val layoutColesterolConfig = findViewById<LinearLayout>(R.id.layoutColesterolConfig)
        val spinnerColesterolPort = findViewById<Spinner>(R.id.spinnerColesterolPort)
        val spinnerColesterolBaud = findViewById<Spinner>(R.id.spinnerColesterolBaud)
        allSwitches.add(switchColesterol)
        setupSerialDevice(PREF_DEVICE_COLESTEROL, switchColesterol, layoutColesterolConfig, spinnerColesterolPort, spinnerColesterolBaud, devicePrefs, availablePorts)

        val initialColor = Color.parseColor(savedColor)
        applySwitchTint(initialColor)
    }

    override fun onPause() {
        super.onPause()
        saveAllPendingSettings()
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_UPDATE_DEVICES"))
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("CONFIG_UPDATED"))
    }

    private fun saveAllPendingSettings() {
        val updateIntent = Intent("UPDATE_SERIAL_PORTS")
        LocalBroadcastManager.getInstance(this).sendBroadcast(updateIntent)
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

    private fun checkActiveDevicesCount(prefKeyToDisable: String, prefs: android.content.SharedPreferences): Boolean {
        val allKeys = listOf(
            PREF_DEVICE_ALTURA_PESO,
            PREF_DEVICE_PRESION,
            PREF_DEVICE_TEMPERATURA,
            PREF_DEVICE_OXIGENO,
            PREF_DEVICE_ECG,
            PREF_DEVICE_AZUCAR,
            PREF_DEVICE_ACIDO_URICO,
            PREF_DEVICE_COLESTEROL
        )
        var activeCount = 0
        for (key in allKeys) {
            if (key == prefKeyToDisable) continue
            if (prefs.getBoolean(key, true)) {
                activeCount++
            }
        }
        return activeCount >= 1
    }

    private fun setupSimpleDevice(
        prefKey: String,
        switchView: Switch,
        configLayout: LinearLayout,
        prefs: android.content.SharedPreferences
    ) {
        switchView.isChecked = prefs.getBoolean(prefKey, true)
        configLayout.visibility = if (switchView.isChecked) View.VISIBLE else View.GONE

        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !checkActiveDevicesCount(prefKey, prefs)) {
                Toast.makeText(this, "Debe tener activado al menos un elemento de medición", Toast.LENGTH_LONG).show()
                switchView.isChecked = true
                return@setOnCheckedChangeListener
            }
            prefs.edit().putBoolean(prefKey, isChecked).apply()
            configLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
    }

    private fun setupSerialDevice(
        prefKey: String,
        switchView: Switch,
        configLayout: LinearLayout,
        spinnerPort: Spinner,
        spinnerBaud: Spinner,
        prefs: android.content.SharedPreferences,
        availablePorts: List<String>
    ) {
        setupSimpleDevice(prefKey, switchView, configLayout, prefs)

        val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availablePorts)
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerPort.adapter = portAdapter

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
                spinnerPort.setSelection(position)
                if (savedPort == null) {
                    prefs.edit().putString("${prefKey}_port", portToSelect).apply()
                }
            }
        }

        spinnerPort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPort = availablePorts[position]
                prefs.edit().putString("${prefKey}_port", selectedPort).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        bindBaudSpinner(spinnerBaud, prefs, prefKey)
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
                    if (!SerialBaudConfig.isStandard(value)) warnNonStandardBaud(value)
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
                    if (!SerialBaudConfig.isStandard(value)) warnNonStandardBaud(value)
                }
                bindBaudSpinner(spinner, prefs, prefKey)
            }
            .setNegativeButton("Cancelar") { _, _ -> bindBaudSpinner(spinner, prefs, prefKey) }
            .setOnCancelListener { bindBaudSpinner(spinner, prefs, prefKey) }
            .create()
        setupDialogKeyboardBehavior(dialog)
        dialog.show()
    }

    private fun warnNonStandardBaud(value: Int) {
        Toast.makeText(
            this,
            "$value no es una velocidad estándar: si el puerto no la admite, se abrirá sin configurar (ver log)",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applySwitchTint(originalColor: Int) {
        for (sw in allSwitches) {
            sw.thumbDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
            sw.trackDrawable?.setColorFilter(originalColor, PorterDuff.Mode.SRC_ATOP)
        }
        for (rb in allRadioButtons) {
            rb.buttonTintList = android.content.res.ColorStateList.valueOf(originalColor)
        }
    }
}
