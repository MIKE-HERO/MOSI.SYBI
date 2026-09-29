package com.sybi.mosi

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException

/**
 * PrintDocumentAdapter que sirve un PDF ya generado en memoria (ByteArray).
 *
 * @param onFinishCallback Se invoca cuando el sistema de impresión ya no necesita
 *        el adapter (impresión terminada o cancelada). Útil para liberar recursos
 *        y permitir que la Activity se cierre con seguridad.
 */
class PdfPrintAdapter(
    private val pdfBytes: ByteArray,
    private val jobName: String,
    private val onFinishCallback: (() -> Unit)? = null
) : PrintDocumentAdapter() {

    companion object {
        private const val TAG = "PdfPrintAdapter"
    }

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()

        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onWriteCancelled()
            return
        }

        var output: FileOutputStream? = null
        try {
            output = FileOutputStream(destination?.fileDescriptor)
            output.write(pdfBytes)
            output.flush()
            callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            Log.d(TAG, "✅ PDF escrito al destino (${pdfBytes.size} bytes)")
        } catch (e: IOException) {
            Log.e(TAG, "❌ Error escribiendo PDF: ${e.message}", e)
            callback?.onWriteFailed(e.message)
        } finally {
            try {
                output?.close()
            } catch (_: IOException) {
            }
        }
    }
    override fun onFinish() {
        super.onFinish()
        Log.d(TAG, "🏁 onFinish() del adapter")
        try {
            onFinishCallback?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Error en callback onFinish: ${e.message}")
        }
    }
}