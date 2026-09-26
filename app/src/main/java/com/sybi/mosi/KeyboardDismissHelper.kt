package com.sybi.mosi

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Mantiene el teclado en pantalla hasta que el usuario lo pida: muestra un botón "Ocultar teclado"
 * pegado al teclado y lo oculta también al tocar fuera de los campos de texto. No hay ocultamiento
 * automático por inactividad.
 *
 * Sirve para la ventana de una Activity o de un Dialog (cada uno tiene su propia [Window]).
 */
class KeyboardDismissHelper(
    private val window: Window,
    private val onHide: () -> Unit
) {
    private val decor: View get() = window.decorView
    private val visibleRect = Rect()
    private var popup: PopupWindow? = null
    private var attached = false

    /** Verdadero mientras el teclado está en pantalla. */
    var keyboardVisible = false
        private set

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }

    fun attach() {
        if (attached) return
        attached = true
        decor.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
            view.post { refresh() }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        refreshSoon()
    }

    fun detach() {
        if (!attached) return
        attached = false
        val observer = decor.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(decor, null)
        dismissPopup()
        keyboardVisible = false
    }

    /** Llamar con cada toque que reciba la ventana. Oculta el teclado si se toca fuera de un campo de texto. */
    fun handleTouch(event: MotionEvent) {
        if (!attached || event.actionMasked != MotionEvent.ACTION_DOWN) return
        val sobreCampo = isOverEditText(decor, event.rawX.toInt(), event.rawY.toInt())
        if (sobreCampo) {
            // Al tocar un campo el teclado aparece después del toque; algunos equipos no avisan del cambio
            refreshSoon()
        } else if (keyboardVisible) {
            onHide()
        }
    }

    private fun refreshSoon() {
        decor.postDelayed({ refresh() }, 300)
        decor.postDelayed({ refresh() }, 800)
    }

    private fun refresh() {
        if (!attached || decor.windowToken == null) return
        val altura = keyboardHeight()
        keyboardVisible = altura > 0
        if (keyboardVisible) showPopup(altura) else dismissPopup()
    }

    private fun keyboardHeight(): Int {
        val ime = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        if (ime > 0) return ime
        decor.getWindowVisibleDisplayFrame(visibleRect)
        val pantalla = decor.resources.displayMetrics.heightPixels
        val cubierto = pantalla - visibleRect.bottom
        return if (cubierto > pantalla * 0.15f) cubierto else 0
    }

    private fun showPopup(alturaTeclado: Int) {
        val margen = dp(16)
        val ventana = popup ?: crearBoton().also { popup = it }
        if (ventana.isShowing) {
            ventana.update(margen, alturaTeclado + margen, -1, -1)
        } else {
            ventana.showAtLocation(decor, Gravity.BOTTOM or Gravity.END, margen, alturaTeclado + margen)
        }
    }

    private fun dismissPopup() {
        popup?.takeIf { it.isShowing }?.dismiss()
    }

    private fun crearBoton(): PopupWindow {
        val boton = TextView(decor.context).apply {
            text = "Ocultar teclado  ▾"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6263238"))
                cornerRadius = dp(28).toFloat()
            }
            setOnClickListener { onHide() }
        }
        return PopupWindow(boton, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = dp(8).toFloat()
        }
    }

    private fun isOverEditText(view: View, x: Int, y: Int): Boolean {
        if (view is EditText && view.isShown) {
            val pos = IntArray(2)
            view.getLocationOnScreen(pos)
            if (x >= pos[0] && x <= pos[0] + view.width && y >= pos[1] && y <= pos[1] + view.height) return true
        }
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                if (isOverEditText(view.getChildAt(i), x, y)) return true
            }
        }
        return false
    }

    private fun dp(valor: Int): Int = (valor * decor.resources.displayMetrics.density).toInt()
}
