package com.sybi.mosi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class CalibTestActivity : BaseActivity() {

    private lateinit var sideBarLayout: View
    private lateinit var btnBack: View
    private lateinit var btnTestMic: Button
    private lateinit var btnTestCamera: Button

    private lateinit var btnCalibrate: Button

    private var onPermissionsGrantedAction: (() -> Unit)? = null

    companion object {
        private const val TAG = "MicTestActivity"
        private const val TEST_MIC_URL = "https://es.mictests.com/"
        private const val TEST_CAMERA_URL = "https://es.webcamtests.com/"
        private const val REQUEST_PERMISSIONS_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_calib_test)

        sideBarLayout = findViewById(R.id.sideBarLayout)
        btnBack = findViewById(R.id.btnBackMicTest)
        btnTestMic = findViewById(R.id.btnTestMic)
        btnTestCamera = findViewById(R.id.btnTestCamera)
        btnCalibrate = findViewById(R.id.btnCalibrate)

        // --- RECEPTOR DE CAMBIO DE TEMA ---
        val colorReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newColor = intent.getStringExtra("new_color")
                if (newColor != null) {
                    aplicarColorTema(newColor)
                }
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(colorReceiver, IntentFilter("ACTION_UPDATE_THEME"))

        // Cargar el color guardado al inicio
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val savedColor = prefs.getString("BackgroundColor", "#0F3E82") ?: "#0F3E82"
        aplicarColorTema(savedColor)

        btnBack.setOnClickListener {
            finish()
        }

        btnTestMic.setOnClickListener {
            verificarPermisosYComenzar { abrirTestUrl(TEST_MIC_URL) }
        }

        btnTestCamera.setOnClickListener {
            verificarPermisosYComenzar { abrirTestUrl(TEST_CAMERA_URL) }
        }

        btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrateActivity::class.java))
        }
    }

    private fun aplicarColorTema(hexColor: String) {
        try {
            val colorInt = Color.parseColor(hexColor)
            sideBarLayout.setBackgroundColor(colorInt)
            val colorStateList = ColorStateList.valueOf(colorInt)
            btnTestMic.backgroundTintList = colorStateList
            btnTestCamera.backgroundTintList = colorStateList
            btnCalibrate.backgroundTintList = colorStateList
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando color de tema: ${e.message}")
        }
    }

    private fun verificarPermisosYComenzar(onGranted: () -> Unit) {
        onPermissionsGrantedAction = onGranted
        val permisos = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val faltantes = permisos.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltantes.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, faltantes.toTypedArray(), REQUEST_PERMISSIONS_CODE)
        } else {
            onGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGrantedAction?.invoke()
            } else {
                Toast.makeText(this, "Se necesitan permisos de cámara y micrófono para el test", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun abrirTestUrl(url: String) {
        Log.d(TAG, "🌐 Abriendo test en CustomTabs: $url")
        CustomTabsHelper.openUrl(this, url, preferFirefox = true)
    }
}
