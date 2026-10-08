package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Paciente
import com.sybi.mosi.database.Resultado
import kotlinx.coroutines.runBlocking

/**
 * Pantalla propia del informe de salud (estilo MediTech). La usa el flujo "salir con informe"
 * desde la medición. El armado del contenido vive en [InformeBuilder], que también usa
 * ResultsActivity para mostrar el informe como su pantalla final.
 */
class InformeActivity : BaseActivity() {

    companion object {
        const val EXTRA_ID_LOCAL = "id_local"
        private const val MAX_MEDICIONES = 21 // actual + 20 anteriores
    }

    private lateinit var topBar: View
    private lateinit var container: LinearLayout
    private var idLocal: Long = 0L
    private var colorTema: Int = Color.parseColor("#0F3E82")

    private val uiHandler = Handler(Looper.getMainLooper())
    private val d get() = resources.displayMetrics.density

    private val colorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val nuevo = intent.getStringExtra("new_color") ?: return
            colorTema = runCatching { Color.parseColor(nuevo) }.getOrDefault(colorTema)
            topBar.setBackgroundColor(colorTema)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_informe)

        topBar = findViewById(R.id.topInformeBar)
        container = findViewById(R.id.informeContainer)

        idLocal = intent.getLongExtra(EXTRA_ID_LOCAL, 0L)

        colorTema = runCatching {
            Color.parseColor(getSharedPreferences("AppPrefs", MODE_PRIVATE).getString("BackgroundColor", "#0F3E82"))
        }.getOrDefault(Color.parseColor("#0F3E82"))
        topBar.setBackgroundColor(colorTema)
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        findViewById<Button>(R.id.btnBackInforme).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnImprimirInforme).setOnClickListener {
            android.widget.Toast.makeText(this, "La exportación a PDF del informe se agregará en una siguiente versión", android.widget.Toast.LENGTH_SHORT).show()
        }

        cargarDatos()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(colorReceiver) } catch (_: Exception) {}
    }

    private fun cargarDatos() {
        container.addView(TextView(this).apply {
            text = "Cargando informe..."
            textSize = 18f
            setTextColor(Color.parseColor("#757575"))
            gravity = Gravity.CENTER
            setPadding(0, (60 * d).toInt(), 0, 0)
        })

        Thread {
            var paciente: Paciente? = null
            var mediciones: List<Resultado> = emptyList()
            runBlocking {
                val db = AppDatabase.getInstance(this@InformeActivity)
                paciente = if (idLocal != 0L) db.pacienteDao().obtenerPacientePorIdLocal(idLocal) else null
                mediciones = if (idLocal != 0L) {
                    db.resultadoDao().obtenerResultadosPorIdLocal(idLocal).take(MAX_MEDICIONES)
                } else {
                    // Invitado (id_local=0): el historial de id_local=0 es compartido por todos
                    // los invitados, así que solo mostramos la medición más reciente (la actual).
                    db.resultadoDao().obtenerResultadosPorIdLocal(0L).take(1)
                }
            }
            uiHandler.post {
                container.removeAllViews()
                val builder = InformeBuilder(this, container, colorTema)
                if (mediciones.isEmpty()) {
                    builder.mensajeVacio("No hay mediciones registradas para este paciente.")
                } else {
                    builder.construir(paciente, idLocal, mediciones)
                }
            }
        }.start()
    }
}
