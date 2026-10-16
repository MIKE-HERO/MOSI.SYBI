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
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking

class PhoneLoginActivity : BaseActivity() {

    companion object {
        private const val TAG = "PhoneLoginActivity"
        private const val MAX_PHONE_LENGTH = 10
    }

    private lateinit var sideBar: View
    private lateinit var btnLogin: Button
    private lateinit var tvPhoneDisplay: TextView
    private var currentColor: String = "#0F3E82"
    private val phoneBuffer = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        sideBar = findViewById(R.id.sideBarLayout)
        btnLogin = findViewById(R.id.btnPhoneLogin)
        tvPhoneDisplay = findViewById(R.id.tvPhoneDisplay)

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
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        currentColor = prefs.getString("BackgroundColor", "#0F3E82")!!
        applyColorTheme(currentColor)

        // --- BOTÓN REGRESAR ---
        findViewById<View>(R.id.btnBackPhoneLogin).setOnClickListener {
            finish()
        }

        // --- CONFIGURAR TECLADO NUMÉRICO ---
        setupNumericKeypad()

        // --- LOGIN CON TELÉFONO ---
        btnLogin.setOnClickListener {
            val telefono = phoneBuffer.toString().trim()

            if (TextUtils.isEmpty(telefono)) {
                Toast.makeText(this, "Ingrese su número de teléfono", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (telefono.length < MAX_PHONE_LENGTH) {
                Toast.makeText(
                    this,
                    "Ingrese los $MAX_PHONE_LENGTH dígitos del teléfono",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            realizarLogin(telefono)
        }
    }

    // ==========================================================
    //  MINI TECLADO NUMÉRICO
    // ==========================================================
    private fun setupNumericKeypad() {
        val digitButtons = mapOf(
            R.id.btnPhoneKey0 to "0",
            R.id.btnPhoneKey1 to "1",
            R.id.btnPhoneKey2 to "2",
            R.id.btnPhoneKey3 to "3",
            R.id.btnPhoneKey4 to "4",
            R.id.btnPhoneKey5 to "5",
            R.id.btnPhoneKey6 to "6",
            R.id.btnPhoneKey7 to "7",
            R.id.btnPhoneKey8 to "8",
            R.id.btnPhoneKey9 to "9"
        )

        digitButtons.forEach { (id, digit) ->
            findViewById<View>(id).setOnClickListener {
                appendDigit(digit)
            }
        }

        findViewById<View>(R.id.btnBackspacePhone).setOnClickListener {
            removeLastDigit()
        }
    }

    private fun appendDigit(digit: String) {
        if (phoneBuffer.length >= MAX_PHONE_LENGTH) return
        phoneBuffer.append(digit)
        updatePhoneDisplay()
    }

    private fun removeLastDigit() {
        if (phoneBuffer.isNotEmpty()) {
            phoneBuffer.deleteCharAt(phoneBuffer.length - 1)
            updatePhoneDisplay()
        }
    }

    private fun updatePhoneDisplay() {
        tvPhoneDisplay.text = phoneBuffer.toString()
    }

    // ==========================================================
    //  LOGIN
    // ==========================================================
    private fun realizarLogin(telefono: String) {
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

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)
        sideBar.setBackgroundColor(color)

        // Botón "Continuar" un poco más oscuro que el color del tema
        val darkerColor = darkenColor(color, 0.60f)
        btnLogin.backgroundTintList = android.content.res.ColorStateList.valueOf(darkerColor)

        // Aplicar el color SOLO a los botones numéricos del teclado
        val keyIds = listOf(
            R.id.btnPhoneKey0,
            R.id.btnPhoneKey1,
            R.id.btnPhoneKey2,
            R.id.btnPhoneKey3,
            R.id.btnPhoneKey4,
            R.id.btnPhoneKey5,
            R.id.btnPhoneKey6,
            R.id.btnPhoneKey7,
            R.id.btnPhoneKey8,
            R.id.btnPhoneKey9
        )

        keyIds.forEach { id ->
            findViewById<TextView>(id).backgroundTintList =
                android.content.res.ColorStateList.valueOf(color)
        }

        // ❌ El botón backspace NO se colorea con el tema
    }

    /**
     * Oscurece un color multiplicando sus componentes RGB por un factor.
     * @param color Color original
     * @param factor Valor entre 0f (negro) y 1f (sin cambio). Ej: 0.85f = 15% más oscuro.
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}