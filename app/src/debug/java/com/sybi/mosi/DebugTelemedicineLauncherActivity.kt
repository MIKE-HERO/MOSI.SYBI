package com.sybi.mosi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Solo debug: habilita telemedicina (y opcionalmente cambia la URL base y la cabina)
 * y abre TelemedicineActivity directamente, para probar contra tools/telemedicina-mock.
 */
class DebugTelemedicineLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val editor = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE).edit()
            .putBoolean(TelemedicineSettingsActivity.PREF_TELEMEDICINE_ENABLED, true)
        intent.getStringExtra("base_url")?.let {
            editor.putString(TelemedicineSettingsActivity.PREF_TELEMEDICINE_BASE_URL, it)
        }
        intent.getStringExtra("cabina")?.let {
            editor.putString(TelemedicineSettingsActivity.PREF_CABINA_NUMBER, it)
        }
        editor.commit()

        val idUsuarioWeb = intent.getIntExtra("id_usuario_web", 1)
        Log.d("DebugTelemedicine", "🧪 Abriendo telemedicina con id_usuario_web=$idUsuarioWeb")
        startActivity(Intent(this, TelemedicineActivity::class.java).apply {
            putExtra(TelemedicineActivity.EXTRA_ID_USUARIO_WEB, idUsuarioWeb)
        })
        finish()
    }
}
