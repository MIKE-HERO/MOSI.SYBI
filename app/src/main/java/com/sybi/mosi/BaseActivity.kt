package com.sybi.mosi

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity

open class BaseActivity : AppCompatActivity() {

    // El teclado ya no se oculta por inactividad: se queda fijo hasta que se toca fuera de los
    // campos de texto o el botón "Ocultar teclado" que aparece junto a él.
    private val keyboardHelper by lazy { KeyboardDismissHelper(window) { hideKeyboardAndClearFocus() } }

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
    }

    override fun onPause() {
        super.onPause()
        keyboardHelper.detach()
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
