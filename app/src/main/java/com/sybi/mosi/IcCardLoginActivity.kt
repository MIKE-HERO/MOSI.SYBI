package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import kotlinx.coroutines.runBlocking

class IcCardLoginActivity : BaseActivity() {

    private lateinit var sideBar: View
    private lateinit var tvStatus: TextView
    private var currentColor: String = "#0F3E82"
    private lateinit var dataReceiver: BroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ic_login)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)

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
    }

    private fun onCardScanned(cardNumber: String) {
        Toast.makeText(this, "Tarjeta leída: $cardNumber", Toast.LENGTH_SHORT).show()
        tvStatus.text = "Tarjeta detectada: $cardNumber\nBuscando paciente..."

        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                // ✅ Buscar paciente por número de tarjeta IC
                val paciente = pacienteDao.obtenerPacientePorTarjetaIc(cardNumber)

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

                        val sessionType = intent.getStringExtra("session_type") ?: "measurement"
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
        } catch (_: Exception) {}
    }
}
