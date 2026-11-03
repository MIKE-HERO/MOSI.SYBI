package com.sybi.mosi.integration

import android.os.Bundle
import android.graphics.Color
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.sybi.mosi.BaseActivity
import com.sybi.mosi.DeviceInfoActivity
import com.sybi.mosi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MosiActivationActivity : BaseActivity() {
    private lateinit var api: MosiApiClient
    private lateinit var status: TextView
    private lateinit var stepLabel: TextView
    private lateinit var back: Button
    private lateinit var serial: EditText
    private lateinit var branchSpinner: Spinner
    private lateinit var panels: List<LinearLayout>
    private var installerToken = ""
    private var branches: List<MosiSucursal> = emptyList()
    private var currentStep = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mosi_activation)
        api = MosiApiClient(this)
        status = findViewById(R.id.tvActivationStatus)
        stepLabel = findViewById(R.id.tvWizardStep)
        back = findViewById(R.id.btnWizardBack)
        serial = findViewById(R.id.etActivationSerial)
        branchSpinner = findViewById(R.id.spinnerBranch)
        panels = listOf(
            findViewById(R.id.panelInstaller), findViewById(R.id.panelCabin),
            findViewById(R.id.panelVideoLoop), findViewById(R.id.panelPrinter),
            findViewById(R.id.panelConfirm)
        )

        val suggestedSerial = DeviceInfoActivity.getSerie(this).ifBlank {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty().uppercase()
        }
        serial.setText(suggestedSerial)
        findViewById<EditText>(R.id.etLegacySerial).setText(suggestedSerial)
        findViewById<EditText>(R.id.etVideoLoopHost).setText(com.sybi.mosi.VideoLoopRemote.getSavedHost(this))
        findViewById<EditText>(R.id.etVideoLoopPort).setText(com.sybi.mosi.VideoLoopRemote.getSavedPort(this).toString())

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else packageInfo.versionCode.toLong()
        findViewById<TextView>(R.id.tvInstalledVersion).text =
            "Versión instalada ${packageInfo.versionName.orEmpty()} ($versionCode)"

        findViewById<Button>(R.id.btnInstallerLogin).setOnClickListener { loginInstaller(it as Button) }
        findViewById<Button>(R.id.btnCabinNext).setOnClickListener { validateCabin() }
        findViewById<Button>(R.id.btnVideoNext).setOnClickListener { validateVideoLoop() }
        findViewById<Button>(R.id.btnPrinterNext).setOnClickListener { showConfirmation() }
        findViewById<Button>(R.id.btnCompleteInstallation).setOnClickListener { completeInstallation(it as Button) }
        findViewById<Button>(R.id.btnUseActivationCode).setOnClickListener { showLegacy() }
        findViewById<Button>(R.id.btnActivateCabin).setOnClickListener { activateLegacy(it as Button) }
        findViewById<Button>(R.id.btnRetryAdminConnection).setOnClickListener { checkAdminConnection() }
        back.setOnClickListener { if (currentStep > 1) showStep(currentStep - 1) else showStep(1) }
        showStep(1)
        checkAdminConnection()
    }

    private fun checkAdminConnection() {
        val connection = findViewById<TextView>(R.id.tvAdminConnection)
        val retry = findViewById<Button>(R.id.btnRetryAdminConnection)
        retry.isEnabled = false
        connection.setTextColor(Color.parseColor("#52677A"))
        connection.text = "Comprobando conexión con el administrador…"
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { api.verificarServidorInstalacion() }
            withContext(Dispatchers.Main) {
                retry.isEnabled = true
                result.onSuccess {
                    connection.setTextColor(Color.parseColor("#16794A"))
                    connection.text = "Administrador conectado correctamente"
                }.onFailure {
                    connection.setTextColor(Color.parseColor("#B3261E"))
                    connection.text = "Sin conexión: ${it.message ?: "error desconocido"}"
                }
            }
        }
    }

    private fun loginInstaller(button: Button) {
        val email = findViewById<EditText>(R.id.etInstallerEmail).text.toString().trim()
        val password = findViewById<EditText>(R.id.etInstallerPassword).text.toString()
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches() || password.isBlank()) {
            showError("Capture un correo válido y la contraseña del instalador.")
            return
        }
        busy(button, "Validando instalador...") {
            val token = api.iniciarSesionInstalador(email, password)
            val loadedBranches = api.listarSucursales(token)
            if (loadedBranches.isEmpty()) throw MosiApiException("No hay sucursales activas disponibles")
            withContext(Dispatchers.Main) {
                installerToken = token
                branches = loadedBranches
                branchSpinner.adapter = ArrayAdapter(
                    this@MosiActivationActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    branches
                )
                findViewById<EditText>(R.id.etInstallerPassword).text?.clear()
                showStep(2)
            }
        }
    }

    private fun validateCabin() {
        if (serial.text.toString().trim().isBlank() || branchSpinner.selectedItemPosition !in branches.indices) {
            showError("Capture el número de serie y seleccione una sucursal.")
            return
        }
        showStep(3)
    }

    private fun validateVideoLoop() {
        val host = value(R.id.etVideoLoopHost)
        val port = value(R.id.etVideoLoopPort).toIntOrNull()
        val key = value(R.id.etVideoLoopKey)
        val email = value(R.id.etVideoLoopEmail)
        if (host.isBlank() || key.isBlank() || port == null || port !in 1..65535) {
            showError("Capture IP, puerto y clave válidos para VideoLoop.")
            return
        }
        if (email.isNotBlank() && !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("El correo de VideoLoop no es válido.")
            return
        }
        val printer = findViewById<EditText>(R.id.etPrinterHost)
        if (printer.text.toString().trim().isBlank()) printer.setText(host)
        showStep(4)
    }

    private fun showConfirmation() {
        val printerHost = value(R.id.etPrinterHost)
        val printerPort = value(R.id.etPrinterPort).toIntOrNull()
        if (printerHost.isBlank() || printerPort == null || printerPort !in 1..65535) {
            showError("Capture una IP y puerto válidos para la impresora.")
            return
        }
        val branch = branches[branchSpinner.selectedItemPosition]
        findViewById<TextView>(R.id.tvInstallSummary).text =
            "Serie: ${serial.text.toString().trim()}\n" +
                "Sucursal: ${branch.nombre}\n" +
                "Pantalla: ${value(R.id.etVideoLoopHost)}:${value(R.id.etVideoLoopPort)}\n" +
                "Impresora: $printerHost:$printerPort\n\n" +
                "Al confirmar se revocará cualquier token anterior de esta MOSI."
        showStep(5)
    }

    private fun completeInstallation(button: Button) {
        if (installerToken.isBlank()) {
            showError("La sesión del instalador se perdió. Inicie sesión nuevamente.")
            showStep(1)
            return
        }
        val branch = branches.getOrNull(branchSpinner.selectedItemPosition) ?: return
        busy(button, "Vinculando cabina con el administrador...") {
            api.completarInstalacion(
                installerToken, serial.text.toString(), branch,
                value(R.id.etVideoLoopHost), value(R.id.etVideoLoopPort).toInt(),
                value(R.id.etVideoLoopKey), value(R.id.etVideoLoopEmail),
                value(R.id.etPrinterHost), value(R.id.etPrinterPort).toInt()
            )
            withContext(Dispatchers.Main) { finishSuccess() }
        }
    }

    private fun showLegacy() {
        panels.forEach { it.visibility = View.GONE }
        findViewById<LinearLayout>(R.id.panelLegacy).visibility = View.VISIBLE
        stepLabel.text = "Activación de recuperación"
        back.visibility = View.VISIBLE
        currentStep = 1
        clearStatus()
    }

    private fun activateLegacy(button: Button) {
        val code = value(R.id.etActivationCode)
        val legacySerial = value(R.id.etLegacySerial)
        if (code.isBlank() || legacySerial.isBlank()) {
            showError("Capture código de activación y número de serie.")
            return
        }
        busy(button, "Activando cabina...") {
            api.activar(code, legacySerial)
            withContext(Dispatchers.Main) { finishSuccess() }
        }
    }

    private fun showStep(step: Int) {
        findViewById<LinearLayout>(R.id.panelLegacy).visibility = View.GONE
        currentStep = step.coerceIn(1, 5)
        panels.forEachIndexed { index, panel -> panel.visibility = if (index == currentStep - 1) View.VISIBLE else View.GONE }
        val names = listOf("Instalador", "Cabina y sucursal", "Pantalla de publicidad", "Impresora", "Confirmación")
        stepLabel.text = "Paso $currentStep de 5 · ${names[currentStep - 1]}"
        back.visibility = if (currentStep > 1) View.VISIBLE else View.GONE
        clearStatus()
    }

    private fun busy(button: Button, message: String, work: suspend () -> Unit) {
        button.isEnabled = false
        status.text = message
        status.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { work() }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    showError(error.message ?: "No fue posible completar la operación.")
                }
            }.onSuccess { withContext(Dispatchers.Main) { button.isEnabled = true } }
        }
    }

    private fun finishSuccess() {
        status.text = "Cabina vinculada y activada correctamente."
        status.visibility = View.VISIBLE
        MosiTelemetryWorker.schedule(this)
        MedicionesSyncWorker.schedule(this, 5L)
        setResult(RESULT_OK)
        finish()
    }

    private fun value(id: Int): String = findViewById<EditText>(id).text.toString().trim()
    private fun clearStatus() { status.visibility = View.GONE; status.text = "" }
    private fun showError(message: String) { status.text = message; status.visibility = View.VISIBLE }
}
