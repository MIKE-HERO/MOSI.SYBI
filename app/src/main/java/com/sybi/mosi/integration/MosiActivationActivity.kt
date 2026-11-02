package com.sybi.mosi.integration

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.sybi.mosi.BaseActivity
import com.sybi.mosi.DeviceInfoActivity
import com.sybi.mosi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MosiActivationActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mosi_activation)

        val code = findViewById<EditText>(R.id.etActivationCode)
        val serial = findViewById<EditText>(R.id.etActivationSerial)
        val status = findViewById<TextView>(R.id.tvActivationStatus)
        val button = findViewById<Button>(R.id.btnActivateCabin)

        serial.setText(DeviceInfoActivity.getSerie(this).ifBlank {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty().uppercase()
        })

        button.setOnClickListener {
            val activationCode = code.text.toString().trim()
            val serialNumber = serial.text.toString().trim()
            if (activationCode.isBlank() || serialNumber.isBlank()) {
                status.text = "Ingrese el código de activación y el número de serie."
                status.visibility = View.VISIBLE
                return@setOnClickListener
            }
            button.isEnabled = false
            status.visibility = View.VISIBLE
            status.text = "Activando cabina de forma segura..."
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { MosiApiClient(this@MosiActivationActivity).activar(activationCode, serialNumber) }
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    result.onSuccess {
                        code.text?.clear()
                        status.text = "Cabina activada correctamente."
                        MosiTelemetryWorker.schedule(this@MosiActivationActivity)
                        MedicionesSyncWorker.schedule(this@MosiActivationActivity, 5L)
                        setResult(RESULT_OK)
                        finish()
                    }.onFailure {
                        status.text = it.message ?: "No fue posible activar la cabina."
                    }
                }
            }
        }
    }
}
