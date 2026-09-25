package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.runBlocking
import java.util.Calendar

class ProfileActivity : BaseActivity() {

    private lateinit var topProfileBar: View
    private lateinit var btnAccess: Button
    private var currentColor: String = "#0F3E82"

    // ✅ Datos cargados desde Room
    private var idLocal: Long = 0L
    private var idUsuarioWeb: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_profile)

        topProfileBar = findViewById(R.id.topProfileBar)
        btnAccess = findViewById(R.id.btnAccess)
        val imgProfile = findViewById<ShapeableImageView>(R.id.imgProfile)

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
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82")
        currentColor = savedColor!!
        applyColorTheme(savedColor)

        findViewById<View>(R.id.btnBackProfile).setOnClickListener { finish() }

        // --- DATOS DEL INTENT ---
        idLocal = intent.getLongExtra("id_local", 0L)
        val nombre = intent.getStringExtra("nombre") ?: "Desconocido"
        val apellidoPaterno = intent.getStringExtra("apellido_paterno") ?: ""
        val apellidoMaterno = intent.getStringExtra("apellido_materno") ?: ""
        val telefono = intent.getStringExtra("telefono") ?: ""
        val fechaNacimiento = intent.getStringExtra("fecha_nacimiento") ?: ""
        val genero = intent.getStringExtra("genero") ?: "M"
        val sessionType = intent.getStringExtra("session_type") ?: "measurement"

        // --- MOSTRAR DATOS ---
        findViewById<TextView>(R.id.tvUserName).text = "$nombre $apellidoPaterno $apellidoMaterno"
        findViewById<TextView>(R.id.tvPatientId).text = idLocal.toString()
        findViewById<TextView>(R.id.tvPatientPhone).text = telefono
        findViewById<TextView>(R.id.tvPatientBirthDate).text = fechaNacimiento
        findViewById<TextView>(R.id.tvPatientGender).text = generoDisplay(genero)
        findViewById<TextView>(R.id.tvPatientAge).text = calcularEdad(fechaNacimiento).toString()

        val btnNotMe = findViewById<Button>(R.id.btnNotMe)

        cargarFotoPacientePorIdLocal(idLocal, imgProfile)

        // ✅ Bloquear botón hasta tener idUsuarioWeb cargado
        btnAccess.isEnabled = false

        cargarIdUsuarioWeb(idLocal) {
            runOnUiThread {
                btnAccess.isEnabled = true
            }
        }

        // --- BOTÓN ACCESO ---
        btnAccess.setOnClickListener {
            if (sessionType == "telemedicine") {
                val intent = Intent(this, TelemedicineActivity::class.java)
                intent.putExtra("id_usuario_web", idUsuarioWeb)
                startActivity(intent)
            } else {
                val intent = Intent(this, MeasurementActivity::class.java)
                intent.putExtra("id_local", idLocal)
                intent.putExtra("id_usuario_web", idUsuarioWeb)
                intent.putExtra("nombre", nombre)
                intent.putExtra("apellido_paterno", apellidoPaterno)
                intent.putExtra("apellido_materno", apellidoMaterno)
                intent.putExtra("telefono", telefono)
                intent.putExtra("fecha_nacimiento", fechaNacimiento)
                intent.putExtra("genero", genero)
                startActivity(intent)
            }
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        btnNotMe.setOnClickListener { finish() }
    }

    /**
     * ✅ Carga el id_usuario_web del paciente desde Room.
     */
    private fun cargarIdUsuarioWeb(idLocal: Long, onComplete: () -> Unit) {
        if (idLocal == 0L) {
            android.util.Log.w("ProfileActivity", "⚠️ idLocal=0, no se puede cargar idUsuarioWeb")
            onComplete()
            return
        }

        Thread {
            runBlocking {
                try {
                    val db = AppDatabase.getInstance(this@ProfileActivity)
                    val p = db.pacienteDao().obtenerPacientePorIdLocal(idLocal)
                    idUsuarioWeb = p?.id_usuario_web ?: 0
                    android.util.Log.d("ProfileActivity",
                        "📥 id_usuario_web cargado desde Room: $idUsuarioWeb")
                } catch (e: Exception) {
                    android.util.Log.e("ProfileActivity",
                        "❌ Error cargando id_usuario_web: ${e.message}", e)
                } finally {
                    onComplete()
                }
            }
        }.start()
    }

    // ✅ CARGAR LA FOTO DEL PACIENTE
    private fun cargarFotoPacientePorIdLocal(idLocal: Long, imageView: ShapeableImageView) {
        val database = AppDatabase.getInstance(this)
        val pacienteDao = database.pacienteDao()

        Thread {
            runBlocking {
                try {
                    val paciente = pacienteDao.obtenerPacientePorIdLocal(idLocal)

                    if (paciente != null && paciente.foto != null) {
                        val fotoBase64 = paciente.foto

                        runOnUiThread {
                            try {
                                val bytes = Base64.decode(fotoBase64, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                if (bitmap != null) {
                                    imageView.setImageBitmap(bitmap)
                                } else {
                                    imageView.setImageResource(R.drawable.ic_launcher_background)
                                }
                            } catch (e: Exception) {
                                imageView.setImageResource(R.drawable.ic_launcher_background)
                            }
                        }
                    } else {
                        runOnUiThread {
                            imageView.setImageResource(R.drawable.ic_launcher_background)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        imageView.setImageResource(R.drawable.ic_launcher_background)
                    }
                }
            }
        }.start()
    }

    private fun generoDisplay(codigo: String): String {
        return when (codigo.uppercase()) {
            "M"  -> "Masculino"
            "F"  -> "Femenino"
            "NB" -> "No binario"
            "TM" -> "Transgénero masculino"
            "TF" -> "Transgénero femenino"
            "GF" -> "Género fluido"
            "AG" -> "Agénero"
            "ND" -> "Prefiero no decir"
            "O"  -> "Otro"
            ""   -> "No especificado"
            else -> codigo
        }
    }

    private fun applyColorTheme(colorHex: String) {
        val color = Color.parseColor(colorHex)

        topProfileBar.setBackgroundColor(color)
        btnAccess.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

        val imgProfile = findViewById<ShapeableImageView>(R.id.imgProfile)
        imgProfile.strokeColor = android.content.res.ColorStateList.valueOf(color)

        val rootView = findViewById<View>(android.R.id.content)
        rootView.setBackgroundColor(Color.parseColor("#F5F5F5"))
    }

    private fun calcularEdad(fechaNacimiento: String): Int {
        return try {
            val partes = fechaNacimiento.split("/")
            if (partes.size == 3) {
                val dia = partes[0].toInt()
                val mes = partes[1].toInt()
                val anio = partes[2].toInt()

                val calendario = Calendar.getInstance()
                val anioActual = calendario.get(Calendar.YEAR)
                val mesActual = calendario.get(Calendar.MONTH) + 1
                val diaActual = calendario.get(Calendar.DAY_OF_MONTH)

                var edad = anioActual - anio
                if (mesActual < mes || (mesActual == mes && diaActual < dia)) {
                    edad--
                }
                edad
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}