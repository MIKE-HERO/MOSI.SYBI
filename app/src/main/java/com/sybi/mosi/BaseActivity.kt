package com.sybi.mosi

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import java.util.WeakHashMap

open class BaseActivity : AppCompatActivity() {

    private val keyboardHandler = Handler(Looper.getMainLooper())
    private val watchedViews = WeakHashMap<EditText, Boolean>()

    private val hideKeyboardRunnable = Runnable {
        hideKeyboardAndClearFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Evitar que el teclado se abra automáticamente al entrar a la pantalla
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        preventInitialFocus()
        setupTextWatchersInHierarchy()
    }

    override fun onResume() {
        super.onResume()
        preventInitialFocus()
        setupTextWatchersInHierarchy()
    }

    override fun onPause() {
        super.onPause()
        stopKeyboardInactivityTimer()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            resetKeyboardInactivityTimer()
        }
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
     * Reinicia el temporizador de inactividad del teclado (5 segundos).
     */
    fun resetKeyboardInactivityTimer() {
        keyboardHandler.removeCallbacks(hideKeyboardRunnable)
        keyboardHandler.postDelayed(hideKeyboardRunnable, 5000)
    }

    /**
     * Detiene el temporizador de inactividad del teclado.
     */
    fun stopKeyboardInactivityTimer() {
        keyboardHandler.removeCallbacks(hideKeyboardRunnable)
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
     * Busca recursivamente todos los EditText en la jerarquía de vistas
     * y les añade un TextWatcher para reiniciar el temporizador al escribir.
     */
    fun setupTextWatchersInHierarchy(root: View? = null) {
        val target = root ?: findViewById<View>(android.R.id.content) ?: return
        registerEditTexts(target)
    }

    private fun registerEditTexts(view: View) {
        if (view is EditText) {
            if (watchedViews[view] != true) {
                watchedViews[view] = true
                view.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        resetKeyboardInactivityTimer()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                registerEditTexts(view.getChildAt(i))
            }
        }
    }

    /**
     * Configura el comportamiento de teclado para un Diálogo (auto-ocultar tras 5s e inactividad).
     */
    fun setupDialogKeyboardBehavior(dialog: android.app.Dialog) {
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)

        val dialogHandler = Handler(Looper.getMainLooper())
        var dialogRunnable: Runnable? = null

        fun hideDialogKeyboard() {
            val focusView = dialog.currentFocus ?: dialog.window?.decorView
            focusView?.let { v ->
                val imm = dialog.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
            }
        }

        fun resetDialogTimer() {
            dialogRunnable?.let { dialogHandler.removeCallbacks(it) }
            dialogRunnable = Runnable { hideDialogKeyboard() }
            dialogHandler.postDelayed(dialogRunnable!!, 5000)
        }

        dialog.setOnShowListener {
            val decorView = dialog.window?.decorView
            if (decorView is ViewGroup) {
                registerEditTextsInDialog(decorView) { resetDialogTimer() }
            }
            hideDialogKeyboard()
        }

        val originalCallback = dialog.window?.callback
        if (originalCallback != null) {
            dialog.window?.callback = object : Window.Callback by originalCallback {
                override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                    if (event?.action == MotionEvent.ACTION_DOWN) {
                        resetDialogTimer()
                    }
                    return originalCallback.dispatchTouchEvent(event)
                }
            }
        }

        dialog.setOnDismissListener {
            dialogRunnable?.let { dialogHandler.removeCallbacks(it) }
        }
    }

    private fun registerEditTextsInDialog(view: View, onActivity: () -> Unit) {
        if (view is EditText) {
            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onActivity()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                registerEditTextsInDialog(view.getChildAt(i), onActivity)
            }
        }
    }
}
