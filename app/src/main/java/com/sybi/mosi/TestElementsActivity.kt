package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        /** Marca de la línea divisoria en el selector de velocidad. */
        private const val SEPARATOR = -1


    }

    private val allSwitches = mutableListOf<Switch>()
    private val configRowsMap = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_elements)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val sideBar = findViewById<View>(R.id.sideBarLayout)
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    sideBar.setBackgroundColor(Color.parseColor(newColor))
                    applySwitchTint(Color.parseColor(newColor))
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        findViewById<View>(R.id.btnBackTestElements).setOnClickListener {
            saveAllPendingSettings()
            LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_UPDATE_DEVICES"))
            finish()
        }

        val availablePorts = scanAvailablePorts()
        val container = findViewById<LinearLayout>(R.id.deviceListContainer)
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        DeviceDefaults.ensureDefaultPorts(devicePrefs)

        // --- CREAR ALTURA/PESO ---
        val alturaPesoRow = createDeviceRow("Altura / Peso", PREF_DEVICE_ALTURA_PESO, devicePrefs, availablePorts)
        container.addView(alturaPesoRow)
        configRowsMap[PREF_DEVICE_ALTURA_PESO] = alturaPesoRow

        // --- CREAR COMPOSICIÓN (debajo de Altura/Peso) ---
        val composicionRow = createComposicionRow(devicePrefs, availablePorts)
        container.addView(composicionRow)
        configRowsMap[PREF_DEVICE_COMPOSICION] = composicionRow

        // --- LÍNEA DIVISORIA DESPUÉS DE COMPOSICIÓN ---
        container.addView(createDividerLine())

        // --- CREAR PRESIÓN ARTERIAL ---
        val presionRow = createDeviceRow("Presión Arterial", PREF_DEVICE_PRESION, devicePrefs, availablePorts)
        container.addView(presionRow)
        configRowsMap[PREF_DEVICE_PRESION] = presionRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR TEMPERATURA ---
        val temperaturaRow = createDeviceRow("Temperatura Corporal", PREF_DEVICE_TEMPERATURA, devicePrefs, availablePorts)
        container.addView(temperaturaRow)
        configRowsMap[PREF_DEVICE_TEMPERATURA] = temperaturaRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR OXÍGENO ---
        val oxigenoRow = createDeviceRow("Oxígeno en Sangre", PREF_DEVICE_OXIGENO, devicePrefs, availablePorts)
        container.addView(oxigenoRow)
        configRowsMap[PREF_DEVICE_OXIGENO] = oxigenoRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR ECG ---
        val ecgRow = createDeviceRow("ECG", PREF_DEVICE_ECG, devicePrefs, availablePorts)
        container.addView(ecgRow)
        configRowsMap[PREF_DEVICE_ECG] = ecgRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR AZÚCAR ---
        val azucarRow = createDeviceRow("Azúcar en Sangre", PREF_DEVICE_AZUCAR, devicePrefs, availablePorts)
        container.addView(azucarRow)
        configRowsMap[PREF_DEVICE_AZUCAR] = azucarRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR ÁCIDO ÚRICO ---
        val acidoUricoRow = createDeviceRow("Ácido Úrico", PREF_DEVICE_ACIDO_URICO, devicePrefs, availablePorts)
        container.addView(acidoUricoRow)
        configRowsMap[PREF_DEVICE_ACIDO_URICO] = acidoUricoRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR COLESTEROL ---
        val colesterolRow = createDeviceRow("Colesterol Total", PREF_DEVICE_COLESTEROL, devicePrefs, availablePorts)
        container.addView(colesterolRow)
        configRowsMap[PREF_DEVICE_COLESTEROL] = colesterolRow

        // --- LÍNEA DIVISORIA ---
        container.addView(createDividerLine())

        // --- CREAR LECTOR TARJETA IC ---
        val icCardRow = createDeviceRow("Lector de Tarjeta IC", PREF_DEVICE_IC_CARD, devicePrefs, availablePorts)
        container.addView(icCardRow)
        configRowsMap[PREF_DEVICE_IC_CARD] = icCardRow

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
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
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

    private fun createDividerLine(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2.dp
            ).apply {
                setMargins(0, 8, 0, 8)
            }
            setBackgroundColor(Color.parseColor("#E0E0E0"))
        }
    }

    // Extensión para convertir dp a píxeles
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun createComposicionRow(
        prefs: android.content.SharedPreferences,
        availablePorts: List<String>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundResource(android.R.drawable.list_selector_background)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) } // Eliminar margen inferior

            // Ocultar si Altura/Peso no está activado
            val alturaPesoActive = prefs.getBoolean(PREF_DEVICE_ALTURA_PESO, true)
            visibility = if (alturaPesoActive) View.VISIBLE else View.GONE
        }

        // Fila superior con nombre y switch
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val textView = TextView(this).apply {
            text = "Composición Corporal"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switchView = Switch(this)
        allSwitches.add(switchView)
        switchView.apply {
            // Desactivado por defecto
            isChecked = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(textView)
        topRow.addView(switchView)
        row.addView(topRow)

        // --- FILA DE CONFIGURACIÓN ---
        val configRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (switchView.isChecked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Mensaje indicando que comparte puerto
        val infoLabel = TextView(this).apply {
            text = "Comparte puerto con Altura / Peso"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 8, 0, 8)
        }
        configRow.addView(infoLabel)

        row.addView(configRow)

        // --- Lógica del Switch ---
        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Verificar que Altura/Peso esté activado
                val alturaPesoActive = prefs.getBoolean(PREF_DEVICE_ALTURA_PESO, true)
                if (!alturaPesoActive) {
                    Toast.makeText(
                        row.context,
                        "Debe activar primero Altura / Peso",
                        Toast.LENGTH_LONG
                    ).show()
                    switchView.isChecked = false
                    return@setOnCheckedChangeListener
                }
            }

            // Guardar estado del switch
            prefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, isChecked).apply()

            // Mostrar/ocultar configuración
            configRow.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        return row
    }

    private fun createDeviceRow(
        deviceName: String,
        prefKey: String,
        prefs: android.content.SharedPreferences,
        availablePorts: List<String>
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
            setBackgroundResource(android.R.drawable.list_selector_background)
            elevation = 4f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 0) } // Eliminar margen inferior
        }

        // Fila superior con nombre y switch
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val textView = TextView(this).apply {
            text = deviceName
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switchView = Switch(this)
        allSwitches.add(switchView)
        switchView.apply {
            isChecked = prefs.getBoolean(prefKey, true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        topRow.addView(textView)
        topRow.addView(switchView)
        row.addView(topRow)

        // --- FILA DE CONFIGURACIÓN ---
        // Vertical: el puerto y la velocidad van cada uno en su propia línea
        val configRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (switchView.isChecked) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var spinnerPort: Spinner? = null

        // --- CONFIGURACIÓN SOLO PARA DISPOSITIVOS QUE USAN PUERTO SERIAL ---
        val shouldShowConfig = prefKey == PREF_DEVICE_ALTURA_PESO ||
                prefKey == PREF_DEVICE_PRESION ||
                prefKey == PREF_DEVICE_TEMPERATURA ||
                prefKey == PREF_DEVICE_AZUCAR ||
                prefKey == PREF_DEVICE_ACIDO_URICO ||
                prefKey == PREF_DEVICE_COLESTEROL ||
                prefKey == PREF_DEVICE_IC_CARD

        val isUsbDevice = prefKey == PREF_DEVICE_OXIGENO || prefKey == PREF_DEVICE_ECG

        if (shouldShowConfig) {
            // Spinner de Puertos
            val portLabel = TextView(this).apply {
                text = "Puerto:"
                textSize = 14f
                setTextColor(android.graphics.Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
            }

            spinnerPort = Spinner(this)
            val portAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, availablePorts)
            portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerPort?.adapter = portAdapter
            spinnerPort?.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )


            // Cargar puerto guardado o, si no hay, el puerto por defecto del dispositivo
            val savedPort = prefs.getString("${prefKey}_port", null)
            val defaultPort = DeviceDefaults.DEFAULT_PORTS[prefKey]

            // Preferencia: puerto guardado > puerto por defecto > primera opción disponible
            val portToSelect = when {
                savedPort != null && availablePorts.contains(savedPort) -> savedPort
                defaultPort != null && availablePorts.contains(defaultPort) -> defaultPort
                else -> null
            }

            if (portToSelect != null) {
                val position = availablePorts.indexOf(portToSelect)
                if (position >= 0) {
                    spinnerPort?.setSelection(position)
                    // Guardar el puerto por defecto la primera vez, para que quede persistido
                    if (savedPort == null) {
                        prefs.edit().putString("${prefKey}_port", portToSelect).apply()
                    }
                }
            } else if (defaultPort != null) {
                // El puerto por defecto no está disponible: avisar (opcional)
                Toast.makeText(
                    this,
                    "Puerto por defecto $defaultPort no disponible para $deviceName",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Guardar automáticamente cuando se selecciona un puerto
            spinnerPort?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedPort = availablePorts[position]
                    prefs.edit().putString("${prefKey}_port", selectedPort).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            val portRow = createConfigLine()
            portRow.addView(portLabel)
            portRow.addView(spinnerPort)
            configRow.addView(portRow)

            // Velocidad (baudios) del puerto, en su propia línea
            val baudLabel = TextView(this).apply {
                text = "Velocidad:"
                textSize = 14f
                setTextColor(android.graphics.Color.DKGRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 8, 0) }
            }
            val baudRow = createConfigLine()
            baudRow.addView(baudLabel)
            baudRow.addView(createBaudSpinner(prefs, prefKey))
            configRow.addView(baudRow)
        } else if (isUsbDevice) {
            // Oxígeno y ECG: mostrar mensaje de conexión USB
            val infoLabel = TextView(this).apply {
                text = if (prefKey == PREF_DEVICE_OXIGENO) {
                    "Se conecta por USB (no requiere puerto serial)"
                } else {
                    "Se conecta por USB o es una app externa"
                }
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 8, 0, 8)
            }
            configRow.addView(infoLabel)
        }

        row.addView(configRow)

        // --- Lógica del Switch ---
        switchView.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                // Verificar que al menos un dispositivo esté activo
                val allKeys = listOf(
                    PREF_DEVICE_ALTURA_PESO,
                    PREF_DEVICE_PRESION,
                    PREF_DEVICE_TEMPERATURA,
                    PREF_DEVICE_OXIGENO,
                    PREF_DEVICE_ECG,
                    PREF_DEVICE_AZUCAR,
                    PREF_DEVICE_ACIDO_URICO,
                    PREF_DEVICE_COLESTEROL,
                    PREF_DEVICE_IC_CARD
                )
                var activeCount = 0
                for (key in allKeys) {
                    if (prefs.getBoolean(key, true)) {
                        activeCount++
                    }
                }
                if (activeCount <= 1) {
                    Toast.makeText(
                        row.context,
                        "Debe tener activado al menos un elemento de medición",
                        Toast.LENGTH_LONG
                    ).show()
                    switchView.isChecked = true
                    return@setOnCheckedChangeListener
                }

                // Si desactiva Altura/Peso, desactivar Composición también
                if (prefKey == PREF_DEVICE_ALTURA_PESO) {
                    prefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, false).apply()
                    // Ocultar la fila de Composición
                    configRowsMap[PREF_DEVICE_COMPOSICION]?.visibility = View.GONE
                }
            }

            // Guardar estado del switch
            prefs.edit().putBoolean(prefKey, isChecked).apply()

            // Mostrar/ocultar configuración
            configRow.visibility = if (isChecked) View.VISIBLE else View.GONE

            // Si es Altura/Peso, mostrar/ocultar Composición
            if (prefKey == PREF_DEVICE_ALTURA_PESO) {
                configRowsMap[PREF_DEVICE_COMPOSICION]?.visibility = if (isChecked) View.VISIBLE else View.GONE

                // Si se activa Altura/Peso, NO activar Composición automáticamente
                // Solo mostrar la fila para que el usuario decida
                if (isChecked) {
                    // Actualizar el switch de Composición a desactivado
                    val composicionRow = configRowsMap[PREF_DEVICE_COMPOSICION]
                    if (composicionRow is LinearLayout) {
                        for (i in 0 until composicionRow.childCount) {
                            val child = composicionRow.getChildAt(i)
                            if (child is LinearLayout) {
                                for (j in 0 until child.childCount) {
                                    val subChild = child.getChildAt(j)
                                    if (subChild is Switch) {
                                        subChild.isChecked = false
                                    }
                                }
                            }
                        }
                    }
                    prefs.edit().putBoolean(PREF_DEVICE_COMPOSICION, false).apply()
                }
            }
        }

        return row
    }

    /** Línea horizontal (etiqueta + selector) dentro de la configuración de un dispositivo. */
    private fun createConfigLine() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * Selector de velocidad del puerto: de 600 a 115200 de 100 en 100 (★ = velocidad estándar),
     * más "Personalizado…" para escribir cualquier valor. Se guarda al elegir y se aplica al salir
     * de la pantalla, cuando se reabren los puertos.
     */
    private fun createBaudSpinner(prefs: android.content.SharedPreferences, prefKey: String): Spinner {
        val spinner = Spinner(this)
        spinner.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        bindBaudSpinner(spinner, prefs, prefKey)
        return spinner
    }

    private fun bindBaudSpinner(spinner: Spinner, prefs: android.content.SharedPreferences, prefKey: String) {
        val current = SerialBaudConfig.get(prefs, prefKey)
        // Arriba: "Personalizado…", el valor personalizado guardado (si lo hay) y las estándar ★.
        // Abajo: todo el rango de 600 a 115200 de 100 en 100.
        // null = "Personalizado…"; SEPARATOR = línea divisoria (no seleccionable)
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
                    bindBaudSpinner(spinner, prefs, prefKey) // la línea divisoria no es una opción
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
    }
}