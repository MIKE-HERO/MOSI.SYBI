package com.sybi.mosi

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

open class BaseActivity : AppCompatActivity() {

    // El teclado ya no se oculta por inactividad: se queda fijo hasta que se toca fuera de los
    // campos de texto o el botón "Ocultar teclado" que aparece junto a él.
    private val keyboardHelper by lazy { KeyboardDismissHelper(window) { hideKeyboardAndClearFocus() } }

    // ── Modo multicolor (ciclo del color de marca) ─────────────────────────────────────
    // Solo la pantalla de inicio cicla los colores, con transición suave (MainActivity
    // sobrescribe debeAnimarMulticolor() para activarlo). Mientras cicla, va guardando el
    // color "vivo" actual en AppPrefs. Las demás pantallas NO ciclan: al abrir toman ese
    // color vivo —el que la pantalla de inicio tenía al momento de salir— y se quedan ahí,
    // sin transición. Todas se repintan vía el aviso "ACTION_UPDATE_THEME" que ya sabían
    // escuchar, así que no hace falta tocar cada pantalla.
    private val multicolorHandler = Handler(Looper.getMainLooper())
    private var multicolorRunnable: Runnable? = null
    private var multicolorAnimator: ValueAnimator? = null
    private var multicolorColors: List<Int> = emptyList()
    private var multicolorIndex = 0
    private var multicolorColorVivo: Int = Color.parseColor("#0F3E82")
    private var multicolorSettingsReceiver: BroadcastReceiver? = null

    /** La pantalla de inicio la sobrescribe a true para ser la única que cicla (animado). */
    protected open fun debeAnimarMulticolor(): Boolean = false

    companion object {
        private const val MULTICOLOR_INTERVAL_MS = 150_000L // 2.5 minutos entre cada cambio
        private const val MULTICOLOR_TRANSITION_MS = 5000L  // duración de la transición suave
        private const val PREF_MULTICOLOR_INDEX = "MulticolorCurrentIndex"
        private const val PREF_MULTICOLOR_VIVO = "MulticolorColorVivo"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Evitar que el teclado se abra automáticamente al entrar a la pantalla
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        preventInitialFocus()
    }

    override fun onResume() {
        super.onResume()
        preventInitialFocus()
        keyboardHelper.attach()
        registrarReceptorAjustesMulticolor()
        iniciarCicloMulticolorSiAplica()
    }

    override fun onPause() {
        super.onPause()
        keyboardHelper.detach()
        // Al salir de la pantalla que cicla, dejamos guardado el color exacto en el que se
        // quedó, para que la siguiente pantalla lo tome tal cual (sin transición).
        if (debeAnimarMulticolor()) guardarColorVivo(multicolorColorVivo)
        detenerCicloMulticolor()
        desregistrarReceptorAjustesMulticolor()
    }

    // ── Implementación del ciclo multicolor ─────────────────────────────────────────────

    private fun registrarReceptorAjustesMulticolor() {
        if (multicolorSettingsReceiver != null) return
        multicolorSettingsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Cambiaron los ajustes de multicolor (se activó/desactivó o se regeneró la
                // paleta): reiniciamos el ciclo para que tome los valores nuevos de inmediato.
                detenerCicloMulticolor()
                iniciarCicloMulticolorSiAplica()
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            multicolorSettingsReceiver!!,
            IntentFilter("ACTION_UPDATE_MULTICOLOR")
        )
    }

    private fun desregistrarReceptorAjustesMulticolor() {
        multicolorSettingsReceiver?.let {
            try { LocalBroadcastManager.getInstance(this).unregisterReceiver(it) } catch (_: Exception) {}
        }
        multicolorSettingsReceiver = null
    }

    private fun iniciarCicloMulticolorSiAplica() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("MulticolorEnabled", false)) return

        val guardados = prefs.getString("MulticolorColors", null)
        multicolorColors = guardados?.split(",")?.mapNotNull { hex ->
            runCatching { Color.parseColor(hex) }.getOrNull()
        } ?: emptyList()
        if (multicolorColors.size < 2) return

        // El color del que partimos: el "vivo" que dejó la pantalla de inicio al salir.
        multicolorColorVivo = runCatching {
            Color.parseColor(prefs.getString(PREF_MULTICOLOR_VIVO, null))
        }.getOrDefault(multicolorColors[0])

        if (!debeAnimarMulticolor()) {
            // Pantallas que no ciclan: toman el color vivo tal cual y se quedan ahí.
            emitirCambioDeColor(multicolorColorVivo)
            return
        }

        // Pantalla de inicio: cicla con transición suave, arrancando desde el color vivo.
        multicolorIndex = prefs.getInt(PREF_MULTICOLOR_INDEX, 0).coerceIn(0, multicolorColors.size - 1)
        emitirCambioDeColor(multicolorColorVivo)
        programarSiguienteCambioMulticolor()
    }

    private fun programarSiguienteCambioMulticolor() {
        val runnable = Runnable { avanzarCicloMulticolor() }
        multicolorRunnable = runnable
        multicolorHandler.postDelayed(runnable, MULTICOLOR_INTERVAL_MS)
    }

    private fun avanzarCicloMulticolor() {
        if (multicolorColors.size < 2) return
        multicolorIndex = (multicolorIndex + 1) % multicolorColors.size
        val destino = multicolorColors[multicolorIndex]
        val origen = multicolorColorVivo

        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putInt(PREF_MULTICOLOR_INDEX, multicolorIndex).apply()

        multicolorAnimator?.cancel()
        multicolorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), origen, destino).apply {
            duration = MULTICOLOR_TRANSITION_MS
            addUpdateListener { anim ->
                multicolorColorVivo = anim.animatedValue as Int
                emitirCambioDeColor(multicolorColorVivo)
            }
            start()
        }
        programarSiguienteCambioMulticolor()
    }

    private fun emitirCambioDeColor(color: Int) {
        val hex = String.format("#%06X", 0xFFFFFF and color)
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent("ACTION_UPDATE_THEME").putExtra("new_color", hex))
    }

    private fun guardarColorVivo(color: Int) {
        val hex = String.format("#%06X", 0xFFFFFF and color)
        getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit()
            .putString(PREF_MULTICOLOR_VIVO, hex).apply()
    }

    private fun detenerCicloMulticolor() {
        multicolorRunnable?.let { multicolorHandler.removeCallbacks(it) }
        multicolorRunnable = null
        multicolorAnimator?.cancel()
        multicolorAnimator = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) keyboardHelper.handleTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Previene que el primer EditText u otro campo de entrada obtenga el foco
     * e invoque el teclado al abrir la pantalla.
     */
    fun preventInitialFocus() {
        val rootView = findViewById<View>(android.R.id.content)
        rootView?.isFocusableInTouchMode = true
        rootView?.requestFocus()
        currentFocus?.clearFocus()
        hideKeyboard()
    }

    /**
     * Oculta el teclado suave y quita el foco de cualquier vista enfocada actualmente.
     */
    fun hideKeyboardAndClearFocus() {
        val focusView = currentFocus ?: findViewById(android.R.id.content)
        if (focusView != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focusView.windowToken, 0)
            focusView.clearFocus()
        }
        val rootView = findViewById<View>(android.R.id.content)
        rootView?.isFocusableInTouchMode = true
        rootView?.requestFocus()
    }

    /**
     * Oculta el teclado sin modificar el foco.
     */
    fun hideKeyboard() {
        val focusView = currentFocus ?: findViewById(android.R.id.content)
        if (focusView != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focusView.windowToken, 0)
        }
    }

    /**
     * Comportamiento de teclado para un Diálogo: no se abre solo, se queda fijo y se oculta con el
     * botón "Ocultar teclado" o al tocar fuera de los campos de texto.
     */
    fun setupDialogKeyboardBehavior(dialog: android.app.Dialog) {
        val dialogWindow = dialog.window ?: return
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        fun hideDialogKeyboard() {
            val focusView = dialog.currentFocus ?: dialogWindow.decorView
            val imm = dialog.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(focusView.windowToken, 0)
            focusView.clearFocus()
        }

        val helper = KeyboardDismissHelper(dialogWindow) { hideDialogKeyboard() }

        dialog.setOnShowListener {
            helper.attach()
            hideDialogKeyboard()
        }

        val originalCallback = dialogWindow.callback
        if (originalCallback != null) {
            dialogWindow.callback = object : Window.Callback by originalCallback {
                override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                    if (event != null) helper.handleTouch(event)
                    return originalCallback.dispatchTouchEvent(event)
                }
            }
        }

        dialog.setOnDismissListener { helper.detach() }
    }
}
