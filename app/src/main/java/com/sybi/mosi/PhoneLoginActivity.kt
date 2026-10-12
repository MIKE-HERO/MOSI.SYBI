package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking

class PhoneLoginActivity : BaseActivity() {

    companion object {
        private const val TAG = "PhoneLoginActivity"
    }

    private lateinit var sideBar: View
    private lateinit var btnLogin: Button
    private var currentColor: String = "#0F3E82"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.sideBarLayout)
        btnLogin = findViewById(R.id.btnPhoneLogin)

        // --- RECEPTOR DE CAMBIO DE COLOR ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    currentColor = newColor
                    applyColorTheme(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        // --- BOTÓN REGRESAR ---
        findViewById<View>(R.id.btnBackPhoneLogin).setOnClickListener {
            finish()
        }

        // --- LOGIN CON TELÉFONO ---
        val etPhone = findViewById<EditText>(R.id.etPhoneNumber)

        btnLogin.setOnClickListener {
            val telefono = etPhone.text.toString().trim()

            if (TextUtils.isEmpty(telefono)) {
                Toast.makeText(this, "Ingrese su número de teléfono", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val database = AppDatabase.getInstance(this)
            val pacienteDao = database.pacienteDao()

            Thread {
                runBlocking {
                    var paciente = pacienteDao.obtenerPacientePorTelefono(telefono)

                    // La verdad siempre viene del servidor: antes de mostrar el perfil,
                    // se intenta refrescar el paciente con los datos de la API. Si falla
                    // (sin red, no encontrado, etc.) se sigue con lo que hay localmente.
                    if (paciente != null) {
                        try {
                            val sincronizado = PacienteSyncHelper.sincronizarPaciente(
                                paciente = paciente,
                                pacienteDao = pacienteDao,
                                resultadoDao = database.resultadoDao(),
                                repo = PacienteRemoteRepository(this@PhoneLoginActivity)
                            )
                            if (sincronizado) {
                                paciente = pacienteDao.obtenerPacientePorIdLocal(paciente.id_local) ?: paciente
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo sincronizar con el servidor, se usan datos locales: ${e.message}")
                        }
                    }

                    runOnUiThread {
                        if (paciente != null) {
                            val intent = Intent(this@PhoneLoginActivity, ProfileActivity::class.java)

                            // ✅ Solo id_local como identificador
                            intent.putExtra("id_local", paciente.id_local)

                            intent.putExtra("nombre", paciente.nombre)
                            intent.putExtra("apellido_paterno", paciente.apellido_paterno)
                            intent.putExtra("apellido_materno", paciente.apellido_materno)
                            intent.putExtra("fecha_nacimiento", paciente.fecha_nacimiento)
                            intent.putExtra("genero", paciente.genero)
                            intent.putExtra("curp", paciente.curp)
                            intent.putExtra("telefono", paciente.telefono)
                            intent.putExtra("correo", paciente.correo)
                            intent.putExtra("direccion", paciente.direccion)

                            val sessionType = this@PhoneLoginActivity.intent
                                .getStringExtra("session_type") ?: "measurement"
                            intent.putExtra("session_type", sessionType)

                            startActivity(intent)
                        } else {
                            Toast.makeText(
                                this@PhoneLoginActivity,
                                "Paciente no encontrado",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }.start()
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        sideBar.setBackgroundColor(color)
        btnLogin.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }
}