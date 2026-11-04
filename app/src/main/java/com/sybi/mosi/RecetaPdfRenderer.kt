package com.sybi.mosi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Convierte el HTML de una receta en un PDF imprimible.
 *
 * ¿Por qué hace falta? La cola de impresión entrega HTML -- el mismo que el navegador del
 * médico manda a window.print() -- pero IppPrinter solo sabe enviar application/pdf (o
 * application/octet-stream como auto-detección). Alguien tiene que convertir, y le toca al
 * kiosco: generar el PDF en el servidor obligaría a meter una librería de render en el PHP
 * legacy de medicalcenter.
 *
 * ¿Por qué rasteriza en vez de usar el framework de impresión de Android?
 * WebView.createPrintDocumentAdapter() daría un PDF con texto vectorial, que es mejor, pero
 * para manejarlo sin el diálogo del sistema hay que subclasear
 * PrintDocumentAdapter.LayoutResultCallback y PrintDocumentAdapter.WriteResultCallback, y sus
 * constructores NO son públicos: desde código de aplicación solo se consiguen por reflexión.
 * En un kiosco que debe imprimir sin que nadie toque la pantalla, el diálogo no es opción.
 * Así que se renderiza a bitmap y se arma el PDF con android.graphics.pdf.PdfDocument, que es
 * exactamente el mismo camino que ya usa ResultsActivity para el informe de resultados.
 *
 * Consecuencia a tener presente: el PDF sale como imagen. Para una receta impresa está bien,
 * pero no es texto seleccionable y pesa más que un PDF vectorial.
 */
object RecetaPdfRenderer {

    private const val TAG = "RecetaPdfRenderer"

    // Tamaño de página en puntos PostScript (1/72"), que es la unidad de PdfDocument.
    // Carta, que es el tamaño en el que están diseñadas las recetas del sistema.
    private const val ANCHO_PT = 612
    private const val ALTO_PT = 792

    // El WebView se mide en píxeles. Se renderiza a 2x el tamaño en puntos para que el texto
    // no salga pixelado al imprimir, y se escala al dibujar en el PDF.
    private const val ESCALA = 2

    /** Margen de seguridad por si el HTML trae imágenes remotas (logos) que tardan en cargar. */
    private const val ESPERA_CARGA_MS = 12_000L
    private const val ESPERA_ASENTADO_MS = 1_200L

    /**
     * BLOQUEANTE -- llamar desde un hilo en segundo plano, nunca del principal (crea el WebView
     * en el hilo principal por su cuenta y espera el resultado).
     *
     * @return el PDF en bytes, o null si no se pudo renderizar.
     */
    fun generar(context: Context, html: String): ByteArray? {
        if (html.isBlank()) {
            Log.w(TAG, "HTML vacío, no hay nada que imprimir")
            return null
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "generar() se llamó en el hilo principal; se aborta para no bloquear la UI")
            return null
        }

        val anchoPx = ANCHO_PT * ESCALA
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var alturaContenidoPx = 0
        var fallo = false

        Handler(Looper.getMainLooper()).post {
            try {
                val wv = WebView(context.applicationContext)
                webView = wv
                wv.settings.javaScriptEnabled = true
                // Las recetas traen logos por URL absoluta; sin esto no cargarían.
                wv.settings.blockNetworkImage = false
                wv.settings.loadsImagesAutomatically = true
                wv.setBackgroundColor(Color.WHITE)

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // onPageFinished NO garantiza que las imágenes ya estén pintadas, solo
                        // que el documento se cargó. Se da un margen y luego se mide.
                        view?.postDelayed({
                            alturaContenidoPx = (view.contentHeight * view.scale).toInt()
                            latch.countDown()
                        }, ESPERA_ASENTADO_MS)
                    }
                }

                // Se mide y se dispone a lo ancho de la página; la altura se deja libre para
                // que contentHeight refleje el documento completo.
                wv.layout(0, 0, anchoPx, 1)
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo preparar el WebView: ${e.message}", e)
                fallo = true
                latch.countDown()
            }
        }

        val aTiempo = try {
            latch.await(ESPERA_CARGA_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!aTiempo || fallo) {
            Log.e(TAG, if (fallo) "Fallo al preparar el WebView" else "El HTML no acabó de cargar en ${ESPERA_CARGA_MS}ms")
            liberar(webView)
            return null
        }

        if (alturaContenidoPx <= 0) {
            Log.e(TAG, "El HTML renderizó con altura 0; no se genera PDF")
            liberar(webView)
            return null
        }

        val bytes = dibujar(webView, anchoPx, alturaContenidoPx)
        liberar(webView)
        return bytes
    }

    /**
     * Captura el WebView completo y lo reparte en páginas carta.
     * Se ejecuta en el hilo principal porque dibujar una View exige su hilo.
     */
    private fun dibujar(wv: WebView?, anchoPx: Int, alturaPx: Int): ByteArray? {
        if (wv == null) return null

        val latch = CountDownLatch(1)
        var salida: ByteArray? = null

        Handler(Looper.getMainLooper()).post {
            var completo: Bitmap? = null
            try {
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(anchoPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(alturaPx, View.MeasureSpec.EXACTLY)
                )
                wv.layout(0, 0, anchoPx, alturaPx)

                completo = Bitmap.createBitmap(anchoPx, alturaPx, Bitmap.Config.ARGB_8888)
                val lienzo = Canvas(completo)
                lienzo.drawColor(Color.WHITE)
                wv.draw(lienzo)

                val documento = PdfDocument()
                val altoPaginaPx = ALTO_PT * ESCALA
                val paginas = Math.max(1, Math.ceil(alturaPx.toDouble() / altoPaginaPx).toInt())

                for (i in 0 until paginas) {
                    val info = PdfDocument.PageInfo.Builder(ANCHO_PT, ALTO_PT, i + 1).create()
                    val pagina = documento.startPage(info)

                    val desde = i * altoPaginaPx
                    val hasta = Math.min(alturaPx, desde + altoPaginaPx)
                    val origen = Rect(0, desde, anchoPx, hasta)
                    // Alto proporcional: la última página casi nunca está llena, y estirarla
                    // deformaría el contenido.
                    val altoDestinoPt = ((hasta - desde).toFloat() / ESCALA).toInt()
                    val destino = Rect(0, 0, ANCHO_PT, altoDestinoPt)

                    pagina.canvas.drawColor(Color.WHITE)
                    pagina.canvas.drawBitmap(completo, origen, destino, null)
                    documento.finishPage(pagina)
                }

                val bos = ByteArrayOutputStream()
                documento.writeTo(bos)
                documento.close()
                salida = bos.toByteArray()
                Log.d(TAG, "PDF generado: ${salida?.size} bytes, $paginas página(s)")
            } catch (e: OutOfMemoryError) {
                // Una receta larga a 2x en una tablet de 2GB puede no caber. Mejor fallar
                // explícito y que el job quede en 'error' con el motivo, que morir en silencio.
                Log.e(TAG, "Sin memoria al rasterizar la receta: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error armando el PDF: ${e.message}", e)
            } finally {
                completo?.recycle()
                latch.countDown()
            }
        }

        try {
            latch.await(ESPERA_CARGA_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return salida
    }

    private fun liberar(wv: WebView?) {
        if (wv == null) return
        Handler(Looper.getMainLooper()).post {
            try {
                wv.stopLoading()
                wv.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error liberando el WebView: ${e.message}")
            }
        }
    }
}
