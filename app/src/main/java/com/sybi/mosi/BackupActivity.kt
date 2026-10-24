package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exportar/importar pacientes y mediciones a un .json en almacenamiento público (sobrevive
 * desinstalar/reinstalar la app, a diferencia de getExternalFilesDir). Ver BackupManager.
 */
class BackupActivity : BaseActivity() {

    private lateinit var sideBar: View
    private lateinit var tvClienteActivo: TextView
    private lateinit var tvEstado: TextView
    private lateinit var btnExportar: Button
    private lateinit var btnImportar: Button

    private val pickBackupLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            importarDesde(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_backup)

        sideBar = findViewById(R.id.themeAccentBar)
        tvClienteActivo = findViewById(R.id.tvClienteActivo)
        tvEstado = findViewById(R.id.tvEstadoBackup)
        btnExportar = findViewById(R.id.btnExportar)
        btnImportar = findViewById(R.id.btnImportar)

        findViewById<View>(R.id.btnBackBackup).setOnClickListener { finish() }
        btnExportar.setOnClickListener { exportar() }
        btnImportar.setOnClickListener { abrirSelectorDeArchivo() }

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                sideBar.setBackgroundColor(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        mostrarClienteActivo()
    }

    private fun mostrarClienteActivo() {
        val devicePrefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val idCliente = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE, "") ?: ""
        val nombreCliente = devicePrefs.getString(TelemedicineSettingsActivity.PREF_CLIENTE_NOMBRE, "") ?: ""
        tvClienteActivo.text = if (idCliente.isBlank()) {
            "Cliente activo: ninguno configurado (ve a Ajustes > Cliente)"
        } else {
            "Cliente activo: $nombreCliente ($idCliente)"
        }
    }

    private fun exportar() {
        btnExportar.isEnabled = false
        tvEstado.text = "Exportando..."
        lifecycleScope.launch {
            val resultado = BackupManager.exportar(this@BackupActivity)
            btnExportar.isEnabled = true
            resultado.onSuccess { archivo ->
                tvEstado.text = "✅ Respaldo guardado en:\n${archivo.absolutePath}"
                Toast.makeText(this@BackupActivity, "Respaldo exportado correctamente", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                tvEstado.text = "❌ Error al exportar: ${e.message}"
            }
        }
    }

    private fun abrirSelectorDeArchivo() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            pickBackupLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el selector de archivos: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importarDesde(uri: Uri) {
        btnImportar.isEnabled = false
        tvEstado.text = "Importando..."
        lifecycleScope.launch {
            val archivoTemporal = withContext(Dispatchers.IO) {
                try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                    File(cacheDir, "respaldo_importar.json").apply { writeBytes(bytes) }
                } catch (e: Exception) {
                    null
                }
            }

            if (archivoTemporal == null) {
                btnImportar.isEnabled = true
                tvEstado.text = "❌ No se pudo leer el archivo seleccionado"
                return@launch
            }

            when (val resultado = BackupManager.importar(this@BackupActivity, archivoTemporal)) {
                is BackupManager.ResultadoImportacion.Exito -> {
                    tvEstado.text = "✅ Importación completa:\n" +
                        "Pacientes nuevos: ${resultado.pacientesImportados} (ya existían: ${resultado.pacientesOmitidos})\n" +
                        "Mediciones nuevas: ${resultado.resultadosImportados} (ya existían: ${resultado.resultadosOmitidos})"
                    Toast.makeText(this@BackupActivity, "Respaldo importado correctamente", Toast.LENGTH_LONG).show()
                }
                is BackupManager.ResultadoImportacion.ClienteNoCoincide -> {
                    tvEstado.text = "❌ Este respaldo es de otro cliente:\n" +
                        "Respaldo: ${resultado.nombreClienteRespaldo} (${resultado.idClienteRespaldo})\n" +
                        "Configurado aquí: ${resultado.idClienteActual}\n\n" +
                        "No se importó nada, para evitar mezclar pacientes de distintos clientes."
                }
                is BackupManager.ResultadoImportacion.Error -> {
                    tvEstado.text = "❌ ${resultado.mensaje}"
                }
            }
            btnImportar.isEnabled = true
        }
    }
}
