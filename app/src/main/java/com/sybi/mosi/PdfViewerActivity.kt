package com.sybi.mosi

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Visor de PDF propio, dentro del proceso de la app.
 *
 * imprimirResultados() (ResultsActivity) antes abría el informe con un Intent ACTION_VIEW hacia
 * un visor externo. En la tablet real (Android 7.1, 2GB de RAM) eso significaba lanzar un proceso
 * aparte justo después de que esta app ya había usado buena parte de la memoria generando el PDF;
 * si ese visor externo no existía, era viejo o simplemente no soportaba bien el equipo, se caía o
 * el sistema lo mataba por falta de memoria — y como es OTRO proceso, ningún try/catch de esta app
 * podía capturarlo: se veía como "la app se rompe y queda en pantalla negra", sin aviso.
 *
 * Renderizando las páginas aquí mismo con PdfRenderer evitamos depender de lo que (si acaso) venga
 * instalado en el equipo, y cualquier error queda dentro de nuestro propio proceso, donde sí se
 * puede capturar y mostrar un mensaje en vez de una pantalla negra.
 */
class PdfViewerActivity : BaseActivity() {

    companion object {
        const val EXTRA_PDF_PATH = "pdf_path"
        private const val TAG = "PdfViewerActivity"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageCount = 0
    private var currentPage = 0
    private var currentBitmap: Bitmap? = null

    private lateinit var imgPage: ImageView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        imgPage = findViewById(R.id.imgPdfPage)
        pbLoading = findViewById(R.id.pbPdfLoading)
        tvPageIndicator = findViewById(R.id.tvPdfPageIndicator)
        btnPrev = findViewById(R.id.btnPdfPrev)
        btnNext = findViewById(R.id.btnPdfNext)

        findViewById<View>(R.id.btnPdfExit).setOnClickListener { finish() }
        btnPrev.setOnClickListener { mostrarPagina(currentPage - 1) }
        btnNext.setOnClickListener { mostrarPagina(currentPage + 1) }

        val path = intent.getStringExtra(EXTRA_PDF_PATH)
        if (path.isNullOrBlank() || !File(path).exists()) {
            Toast.makeText(this, "No se encontró el archivo del informe", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        abrirPdf(path)
    }

    private fun abrirPdf(path: String) {
        try {
            pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd!!)
            renderer = r
            pageCount = r.pageCount
            if (pageCount == 0) {
                Toast.makeText(this, "El informe no tiene páginas", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            mostrarPagina(0)
        } catch (e: Throwable) {
            Log.e(TAG, "No se pudo abrir el PDF: ${e.message}", e)
            Toast.makeText(this, "No se pudo abrir el informe: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun mostrarPagina(index: Int) {
        val r = renderer ?: return
        if (index < 0 || index >= pageCount) return

        // Reciclar la página anterior ANTES de reservar la nueva (no después): si el equipo
        // está muy justo de RAM, sostener las dos a la vez durante la reserva es exactamente lo
        // que puede tirar el intento a la basura. Un informe de varias páginas no debe ir
        // acumulando bitmaps completos en memoria.
        currentBitmap?.recycle()
        currentBitmap = null

        pbLoading.visibility = View.VISIBLE
        try {
            var bitmap: Bitmap? = null
            r.openPage(index).use { page ->
                // Escala moderada al ancho de pantalla: de sobra para leerse bien sin gastar
                // memoria de más en un equipo donde la RAM ya es el recurso más escaso. Si aun
                // así falla por memoria, se reintenta un par de veces a la mitad de tamaño.
                var scale = (resources.displayMetrics.widthPixels.toFloat() / page.width)
                    .coerceAtMost(1.3f).coerceAtLeast(0.1f)
                var intentos = 0
                while (bitmap == null && intentos < 3) {
                    try {
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                    } catch (oom: OutOfMemoryError) {
                        intentos++
                        scale /= 2f
                        Log.w(TAG, "Sin memoria renderizando la página $index; reintentando más pequeño (intento $intentos)")
                    }
                }
            }

            if (bitmap == null) {
                Toast.makeText(this, "No hay memoria suficiente para mostrar esta página", Toast.LENGTH_LONG).show()
                return
            }

            currentBitmap = bitmap
            imgPage.setImageBitmap(bitmap)
            currentPage = index
            tvPageIndicator.text = "Página ${index + 1} de $pageCount"
            btnPrev.isEnabled = index > 0
            btnNext.isEnabled = index < pageCount - 1
        } catch (e: Throwable) {
            Log.e(TAG, "Error mostrando la página $index: ${e.message}", e)
            Toast.makeText(this, "No se pudo mostrar la página: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pbLoading.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentBitmap?.recycle()
        currentBitmap = null
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }
}
