package com.sybi.mosi

import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.database.AppDatabase
import com.sybi.mosi.database.Resultado
import com.sybi.mosi.repository.MedicionesSender
import kotlinx.coroutines.runBlocking
import java.io.File

class ResultTableActivity : BaseActivity() {

    companion object {
        private const val TAG = "ResultTableActivity"
    }

    private lateinit var resultsContainer: LinearLayout
    private lateinit var topBar: View
    private lateinit var btnResend: Button
    private lateinit var btnViewEcg: Button
    private lateinit var btnDelete: Button

    // ✅ Multi-selección: guardamos los ids de resultado seleccionados
    private val seleccionados = mutableSetOf<Long>()

    // Cache de resultados actualmente en pantalla (para acceder por id)
    private var resultadosCache: List<Resultado> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_table)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        resultsContainer = findViewById(R.id.resultsContainer)
        topBar = findViewById(R.id.topResultBar)
        btnResend = findViewById(R.id.btnResend)
        btnViewEcg = findViewById(R.id.btnViewEcg)
        btnDelete = findViewById(R.id.btnDelete)

        val savedColor = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        applyColorTheme(savedColor)

        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra("new_color")?.let { applyColorTheme(it) }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        findViewById<View>(R.id.btnBackResultTable).setOnClickListener { finish() }

        btnResend.setOnClickListener { reenviarSeleccionados() }
        btnViewEcg.setOnClickListener { verEcgSeleccionado() }
        btnDelete.setOnClickListener { confirmarEliminarSeleccionados() }

        actualizarBotones()
        loadResults()
    }

    private fun applyColorTheme(colorHex: String) {
        topBar.setBackgroundColor(Color.parseColor(colorHex))
        findViewById<View>(android.R.id.content)
            .setBackgroundColor(Color.parseColor("#F5F5F5"))
    }

    private fun loadResults() {
        val db = AppDatabase.getInstance(this)
        val resultadoDao = db.resultadoDao()
        val pacienteDao = db.pacienteDao()

        Thread {
            runBlocking {
                val resultados = resultadoDao.obtenerTodosLosResultados()

                // Cache de nombres por id_local
                val nombres = mutableMapOf<Long, String>()
                for (r in resultados) {
                    if (r.id_local != 0L && !nombres.containsKey(r.id_local)) {
                        val p = pacienteDao.obtenerPacientePorIdLocal(r.id_local)
                        nombres[r.id_local] = if (p != null) {
                            "${p.nombre} ${p.apellido_paterno}".trim()
                        } else "(Desconocido)"
                    }
                }

                runOnUiThread {
                    resultadosCache = resultados
                    // Limpiar selección de ids que ya no existen
                    val idsExistentes = resultados.map { it.id_resultado }.toSet()
                    seleccionados.retainAll(idsExistentes)
                    renderResults(resultados, nombres)
                }
            }
        }.start()
    }

    private fun renderResults(
        resultados: List<Resultado>,
        nombres: Map<Long, String>
    ) {
        resultsContainer.removeAllViews()

        if (resultados.isEmpty()) {
            resultsContainer.addView(TextView(this).apply {
                text = "No hay resultados registrados"
                textSize = 18f
                setTextColor(Color.parseColor("#757575"))
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 50, 0, 0) }
            })
            actualizarBotones()
            return
        }

        var contador = resultados.size

        for (resultado in resultados) {
            val nombrePaciente = if (resultado.id_local != 0L) {
                nombres[resultado.id_local] ?: "(Desconocido)"
            } else "(Invitado)"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(4, 4, 4, 4)
                setBackgroundColor(
                    if (resultados.indexOf(resultado) % 2 == 0) Color.WHITE
                    else Color.parseColor("#F5F5F5")
                )
            }

            // ✅ CheckBox de multi-selección
            val check = CheckBox(this).apply {
                isClickable = false
                isFocusable = false
                buttonTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#0F3E82")
                )
                layoutParams = LinearLayout.LayoutParams(
                    android.util.TypedValue.applyDimension(
                        android.util.TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics
                    ).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                tag = "check_${resultado.id_resultado}"
                isChecked = seleccionados.contains(resultado.id_resultado)
            }
            row.addView(check)

            // Columnas
            row.addView(createCell(contador.toString(), 40))
            row.addView(createCell(resultado.id_usuario_web.toString(), 50))
            row.addView(createCell(nombrePaciente, 100))
            row.addView(createCell(resultado.altura, 60))
            row.addView(createCell(resultado.peso, 60))
            row.addView(createCell(resultado.imc, 60))
            row.addView(createCell(resultado.grasa_corporal, 80))
            row.addView(createCell(resultado.grasa_corporal_kg, 80))
            row.addView(createCell(resultado.agua_corporal, 80))
            row.addView(createCell(resultado.agua_corporal_kg, 80))
            row.addView(createCell(resultado.masa_muscular, 90))
            row.addView(createCell(resultado.masa_libre_grasa, 100))
            row.addView(createCell(resultado.proteina, 80))
            row.addView(createCell(resultado.minerales, 80))
            row.addView(createCell(resultado.metabolismo_basal, 80))
            row.addView(createCell(resultado.grasa_visceral, 80))
            row.addView(createCell(resultado.peso_ideal, 80))
            row.addView(createCell(resultado.tipo_grasa, 80))
            row.addView(createCell(resultado.sistolica, 60))
            row.addView(createCell(resultado.diastolica, 60))
            row.addView(createCell(resultado.pulso, 60))
            row.addView(createCell(resultado.temperatura, 60))
            row.addView(createCell(resultado.temperatura_f, 60))
            row.addView(createCell(resultado.spo2, 60))
            row.addView(createCell(resultado.frecuencia_pulso, 60))
            row.addView(createCell(resultado.indice_perfusion, 60))
            row.addView(createCell(resultado.frecuencia_cardiaca, 80))
            row.addView(createCell(resultado.eje_p, 60))
            row.addView(createCell(resultado.eje_qrs, 60))
            row.addView(createCell(resultado.eje_t, 60))
            row.addView(createCell(resultado.intervalo_pr, 60))
            row.addView(createCell(resultado.duracion_qrs, 60))
            row.addView(createCell(resultado.intervalo_qt, 60))
            row.addView(createCell(resultado.qt_corregido, 60))
            row.addView(createCell(resultado.onda_rv5, 60))
            row.addView(createCell(resultado.onda_sv1, 60))
            row.addView(createCell(resultado.resultado_ecg, 80))
            row.addView(createCell(resultado.fecha_medicion, 120))

            // ✅ Toggle al tocar la fila
            row.setOnClickListener {
                toggleSeleccion(resultado.id_resultado)
                check.isChecked = seleccionados.contains(resultado.id_resultado)
            }

            resultsContainer.addView(row)
            contador--
        }

        actualizarBotones()
    }

    /**
     * Añade o quita un id del set de seleccionados.
     */
    private fun toggleSeleccion(idResultado: Long) {
        if (seleccionados.contains(idResultado)) {
            seleccionados.remove(idResultado)
        } else {
            seleccionados.add(idResultado)
        }
        actualizarBotones()
    }

    /**
     * Reglas de habilitación:
     * - Sincronizar: 1+ seleccionados
     * - Reenviar:    1+ seleccionados
     * - Ver ECG:     exactamente 1 seleccionado Y tiene archivo
     * - Eliminar:    1+ seleccionados
     */
    private fun actualizarBotones() {
        val haySeleccion = seleccionados.isNotEmpty()
        val exactamenteUno = seleccionados.size == 1


        btnResend.isEnabled = haySeleccion

        val algunoSinId = seleccionados.any { id ->
            resultadosCache.firstOrNull { it.id_resultado == id }?.id_usuario_web == 0
        }

        if (algunoSinId && haySeleccion) {
            Toast.makeText(
                this,
                "Algunos resultados no tienen usuario web. Sincroniza desde la tabla de pacientes.",
                Toast.LENGTH_LONG
            ).show()
        }

        val unico = if (exactamenteUno) {
            resultadosCache.firstOrNull { it.id_resultado == seleccionados.first() }
        } else null
        val tieneEcg = unico != null &&
                unico.ruta_ecg.isNotEmpty() &&
                File(unico.ruta_ecg).exists()
        btnViewEcg.isEnabled = tieneEcg

        btnDelete.isEnabled = haySeleccion

        val alpha = 0.4f
        btnResend.alpha = if (btnResend.isEnabled) 1f else alpha
        btnViewEcg.alpha = if (btnViewEcg.isEnabled) 1f else alpha
        btnDelete.alpha = if (btnDelete.isEnabled) 1f else alpha
    }


    // ── Reenviar (multi) ─────────────────────────────────

    private fun reenviarSeleccionados() {
        val ids = seleccionados.toList()
        if (ids.isEmpty()) return

        val paraEnviar = resultadosCache
            .filter { ids.contains(it.id_resultado) && it.id_usuario_web > 0 }

        if (paraEnviar.isEmpty()) {
            Toast.makeText(this, "Ninguno tiene usuario web. Sincroniza primero.", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "📤 Reenviando ${paraEnviar.size} resultado(s)...", Toast.LENGTH_SHORT).show()

        Thread {
            val sender = MedicionesSender(this)
            var exitosos = 0
            var fallidos = 0

            for (r in paraEnviar) {
                try {
                    val exito = runBlocking {
                        val request = sender.buildFromResultado(r)
                        sender.enviar(request)
                    }
                    if (exito) exitosos++ else fallidos++
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error reenviando id=${r.id_resultado}: ${e.message}", e)
                    fallidos++
                }
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    "✅ $exitosos enviados, ❌ $fallidos fallidos",
                    Toast.LENGTH_LONG
                ).show()
                seleccionados.clear()
                loadResults()
            }
        }.start()
    }

    // ── Ver ECG (solo uno) ───────────────────────────────

    private fun verEcgSeleccionado() {
        if (seleccionados.size != 1) {
            Toast.makeText(this, "Selecciona exactamente 1 resultado", Toast.LENGTH_SHORT).show()
            return
        }

        val id = seleccionados.first()
        val r = resultadosCache.firstOrNull { it.id_resultado == id } ?: return

        if (r.ruta_ecg.isEmpty()) {
            Toast.makeText(this, "Esta medición no tiene ECG", Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(r.ruta_ecg)
        if (!file.exists()) {
            Toast.makeText(this, "Archivo no encontrado", Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            Toast.makeText(this, "No se pudo leer la imagen", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_fullscreen_image)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        dialog.findViewById<ImageView>(R.id.fullscreenImageView).apply {
            setImageBitmap(bitmap)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.findViewById<ImageView>(R.id.btnCloseFullscreen)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ── Eliminar (multi) ─────────────────────────────────

    private fun confirmarEliminarSeleccionados() {
        val n = seleccionados.size
        if (n == 0) return

        AlertDialog.Builder(this)
            .setTitle("Eliminar resultados")
            .setMessage("¿Seguro que quieres eliminar $n resultado(s)?\n\nEsta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> eliminarSeleccionados() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun eliminarSeleccionados() {
        val ids = seleccionados.toList()
        if (ids.isEmpty()) return

        Thread {
            val db = AppDatabase.getInstance(this)
            var eliminados = 0

            for (id in ids) {
                try {
                    val r = resultadosCache.firstOrNull { it.id_resultado == id }
                    runBlocking { db.resultadoDao().eliminarResultado(id) }

                    // Borrar archivo ECG
                    if (r != null && r.ruta_ecg.isNotEmpty()) {
                        try { File(r.ruta_ecg).delete() } catch (_: Exception) {}
                    }
                    eliminados++
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error eliminando id=$id: ${e.message}", e)
                }
            }

            runOnUiThread {
                Toast.makeText(this, "✅ $eliminados eliminados", Toast.LENGTH_SHORT).show()
                seleccionados.clear()
                loadResults()
            }
        }.start()
    }

    // ── Helpers ──────────────────────────────────────────

    private fun createCell(text: String, widthDp: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#333333"))
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP,
                    widthDp.toFloat(),
                    resources.displayMetrics
                ).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(2, 2, 2, 2)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }
}