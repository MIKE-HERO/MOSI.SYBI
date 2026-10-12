package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.repository.PacienteRemoteRepository
import com.sybi.mosi.repository.PacienteSyncHelper
import kotlinx.coroutines.runBlocking

class IcCardLoginActivity : BaseActivity() {

    companion object {
        private const val TAG = "IcCardLoginActivity"
    }

    private lateinit var sideBar: View
    private lateinit var tvStatus: TextView
    private var currentColor: String = "#0F3E82"
    private lateinit var dataReceiver: BroadcastReceiver
    private var colorReceiver: BroadcastReceiver? = null
    private var sessionType: String = "measurement"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ic_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

        // ✅ Guardar el session_type que viene de la activity que abrió esta pantalla
        sessionType = intent.getStringExtra("session_type") ?: "measurement"

        sideBar = findViewById(R.id.sideBarLayout)
        tvStatus = findViewById(R.id.tvIcStatus)

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")!!
        currentColor = savedColor
        sideBar.setBackgroundColor(Color.parseColor(savedColor))

        findViewById<View>(R.id.btnBackIcLogin).setOnClickListener { finish() }

        dataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "DEVICE_DATA_RECEIVED") {
                    val type = intent.getStringExtra("type")
                    if (type == "IC_CARD") {
                        val cardNumber = intent.getStringExtra("card_number") ?: ""
                        if (cardNumber.isNotEmpty()) {
                            onCardScanned(cardNumber)
                        }
                    }
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(dataReceiver, IntentFilter("DEVICE_DATA_RECEIVED"))

        // Repintar la barra lateral si cambia el color de marca (ajuste manual o ciclo multicolor)
        colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color") ?: return
                currentColor = newColor
                sideBar.setBackgroundColor(Color.parseColor(newColor))
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver!!, IntentFilter("ACTION_UPDATE_THEME"))
    }

    private fun onCardScanned(cardNumber: String) {
        Toast.makeText(this, "Tarjeta leída: $cardNumber", Toast.LENGTH_SHORT).show()
        tvStatus.text = "Tarjeta detectada: $cardNumber\nBuscando paciente..."

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                var paciente = pacienteDao.obtenerPacientePorTarjetaIc(cardNumber)

                // La verdad siempre viene del servidor: antes de mostrar el perfil,
                // se intenta refrescar el paciente con los datos de la API. Si falla
                // (sin red, no encontrado, etc.) se sigue con lo que hay localmente.
                if (paciente != null) {
                    try {
                        val sincronizado = PacienteSyncHelper.sincronizarPaciente(
                            paciente = paciente,
                            pacienteDao = pacienteDao,
                            resultadoDao = database.resultadoDao(),
                            repo = PacienteRemoteRepository(this@IcCardLoginActivity)
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
                        val intent = Intent(this@IcCardLoginActivity, ProfileActivity::class.java)
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

                        // ✅ Usar el session_type guardado al abrir esta activity
                        intent.putExtra("session_type", sessionType)

                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this@IcCardLoginActivity, "Tarjeta no registrada: $cardNumber", Toast.LENGTH_LONG).show()
                        tvStatus.text = "Pase su tarjeta IC por el lector..."
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver)
            colorReceiver?.let { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) }
        } catch (_: Exception) {}
    }
}