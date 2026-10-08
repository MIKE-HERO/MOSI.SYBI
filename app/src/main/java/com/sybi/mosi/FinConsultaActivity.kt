package com.sybi.mosi

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Trampolín que recibe el deep link que dispara la página de telemedicina cuando TERMINA la
 * videollamada (ver abajo). La app no puede detectar el fin de la llamada por su cuenta porque
 * ocurre dentro del navegador (Firefox, otro proceso), así que la propia página la avisa
 * redirigiendo a este deep link al finalizar. Al recibirlo, cerramos el flujo del navegador y
 * devolvemos al menú principal. No muestra ninguna interfaz.
 *
 * Deep link esperado: mosi://consulta-finalizada
 *
 * Para que esto funcione, la página de telemedicina debe, al terminar la consulta, redirigir a
 * ese deep link (por ejemplo `window.location.href = 'mosi://consulta-finalizada'` dentro del
 * handler del botón "Finalizar", que es un gesto del usuario y por eso el navegador permite el
 * salto al esquema). Ese es el único cambio necesario del lado del servidor/página.
 */
class FinConsultaActivity : Activity() {

    companion object {
        private const val TAG = "FinConsultaActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "📴 Deep link de fin de consulta recibido: ${intent?.data}")

        // Reiniciar el servicio serial por si la pantalla de telemedicina lo dejó detenido
        // (es idempotente: onCreate de SerialService reabre todos los puertos).
        runCatching { startService(Intent(this, SerialService::class.java)) }

        // Volver al menú principal limpiando la pila: esto destruye la pantalla de telemedicina
        // (su onDestroy restaura el sonido de VideoLoop) y deja el Custom Tab atrás; como el menú
        // principal es el HOME del quiosco, el navegador no queda a la vista.
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        finish()
    }
}
