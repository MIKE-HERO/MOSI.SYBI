package com.sybi.mosi

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sybi.mosi.network.PrintJobAckRequest
import com.sybi.mosi.network.PrintJobDto
import com.sybi.mosi.network.RetrofitClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sondea la cola de impresión de recetas y las imprime en la impresora local del kiosco.
 *
 * Por qué un Service y no un poller dentro de la Activity: la teleconsulta no corre en un
 * WebView embebido, corre en Custom Tabs -- un navegador externo (ver la cabecera de
 * TelemedicineActivity). Mientras el paciente está en consulta, TelemedicineActivity está
 * detenida, y es exactamente el momento en que el médico imprime la receta. Un poller atado
 * al ciclo de vida de la Activity estaría pausado justo cuando hace falta.
 *
 * Mismo patrón que SerialService: Service plano, arrancado con startService() desde una
 * Activity en primer plano y detenido con stopService(). No es foreground service porque no
 * necesita sobrevivir a que el proceso muera -- si el kiosco se reinicia, los jobs siguen
 * en estado 'pendiente' en el servidor y se recogen en el siguiente arranque. La cola es el
 * punto de verdad, no la memoria de la app.
 *
 * Ciclo, cada CICLO_MS:
 *   1. GET usuariosWeb/printJobs?idMachine=N  -> trabajos 'pendiente' de esta cabina
 *   2. Por cada trabajo: HTML -> PDF (RecetaPdfRenderer) -> IPP (IppPrinter)
 *   3. POST usuariosWeb/printJobs/{id}/ack con "printed" o "error" + el motivo
 *
 * El ack se manda SIEMPRE, también cuando falla: si no, el servidor vuelve a entregar el
 * mismo trabajo cada ciclo y el kiosco se queda reintentando lo que nunca va a funcionar
 * (por ejemplo, un HTML que no cabe en memoria). El motivo queda en cola_impresion.detalle,
 * que es donde hay que mirar cuando una receta "no salió".
 */
class PrintJobService : Service() {

    companion object {
        private const val TAG = "PrintJobService"

        /** Cada 8 s, el intervalo que documenta sql_crear_cola_impresion.sql. */
        private const val CICLO_MS = 8_000L

        /** Tope de copias, para que un dato malo en la cola no vacíe la bandeja de papel. */
        private const val MAX_COPIAS = 5

        fun iniciar(context: Context) {
            try {
                context.startService(Intent(context, PrintJobService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo iniciar el servicio de impresión: ${e.message}")
            }
        }

        fun detener(context: Context) {
            try {
                context.stopService(Intent(context, PrintJobService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo detener el servicio de impresión: ${e.message}")
            }
        }
    }

    private val corriendo = AtomicBoolean(false)
    private var hilo: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startService() se puede llamar varias veces (p. ej. al volver del navegador): si ya
        // está sondeando, no se arranca un segundo hilo que duplicaría las impresiones.
        if (corriendo.getAndSet(true)) {
            Log.d(TAG, "Ya estaba sondeando; se ignora el arranque repetido")
            return START_STICKY
        }

        hilo = Thread({ bucle() }, "mosi-printjobs").apply {
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        corriendo.set(false)
        hilo?.interrupt()
        hilo = null
        super.onDestroy()
    }

    private fun bucle() {
        Log.d(TAG, "Sondeo de cola de impresión iniciado")
        while (corriendo.get()) {
            try {
                cicloUnaVez()
            } catch (e: Exception) {
                Log.w(TAG, "Error en el ciclo de sondeo: ${e.message}")
            }
            try {
                Thread.sleep(CICLO_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        Log.d(TAG, "Sondeo de cola de impresión detenido")
    }

    private fun cicloUnaVez() {
        val idCabina = TelemedicineSettingsActivity.getCabina(this).trim().toIntOrNull() ?: 0
        if (idCabina <= 0) {
            // Sin cabina configurada no hay forma de saber qué trabajos son de este kiosco.
            return
        }

        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        val ip = prefs.getString(ReportSettingsActivity.PREF_PRINTER_IP, "")?.trim() ?: ""
        if (ip.isEmpty()) {
            // Sin impresora configurada no se consume la cola: así los trabajos siguen
            // 'pendiente' y se imprimen cuando se configure, en vez de perderse con un ack.
            return
        }

        val respuesta = try {
            RetrofitClient.apiService.obtenerPrintJobs(idCabina).execute()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar la cola: ${e.message}")
            return
        }

        if (!respuesta.isSuccessful) {
            Log.w(TAG, "La cola respondió HTTP ${respuesta.code()}")
            return
        }

        val cuerpo = respuesta.body()
        if (cuerpo?.success != true) {
            Log.w(TAG, "La cola respondió success=false: ${cuerpo?.mensaje}")
            return
        }

        val trabajos = cuerpo.jobs ?: return
        if (trabajos.isEmpty()) return

        Log.d(TAG, "${trabajos.size} receta(s) por imprimir en la cabina $idCabina")

        val puerto = prefs.getInt("printer_port", 631)
        val ruta = prefs.getString("printer_path", "/ipp/print") ?: "/ipp/print"

        for (trabajo in trabajos) {
            if (!corriendo.get()) break
            procesar(trabajo, ip, puerto, ruta)
        }
    }

    private fun procesar(trabajo: PrintJobDto, ip: String, puerto: Int, ruta: String) {
        val idJob = trabajo.id ?: return
        val nombre = trabajo.jobName?.takeIf { it.isNotBlank() }
            ?: trabajo.idReceta?.let { "Receta $it" }
            ?: "Receta"

        val html = trabajo.html
        if (html.isNullOrBlank()) {
            confirmar(idJob, "error", "El trabajo llegó sin HTML")
            return
        }

        val pdf = RecetaPdfRenderer.generar(this, html)
        if (pdf == null) {
            confirmar(idJob, "error", "No se pudo convertir la receta a PDF en el kiosco")
            return
        }

        // copies viene de la cola; se acota para que un valor absurdo no agote el papel.
        val copias = (trabajo.copies ?: 1).coerceIn(1, MAX_COPIAS)
        var ultimoError = ""
        var impresas = 0

        for (i in 1..copias) {
            val res = IppPrinter.imprimirPdf(
                host = ip,
                port = puerto,
                path = ruta,
                jobName = if (copias > 1) "$nombre ($i/$copias)" else nombre,
                pdf = pdf
            )
            if (res.ok) {
                impresas++
            } else {
                ultimoError = res.mensaje
                Log.w(TAG, "Copia $i/$copias del job $idJob falló: ${res.mensaje}")
                break
            }
        }

        if (impresas == copias) {
            confirmar(idJob, "printed", "Impresas $impresas copia(s) en $ip")
        } else {
            confirmar(idJob, "error", "Se imprimieron $impresas de $copias. Último error: $ultimoError")
        }
    }

    private fun confirmar(idJob: Int, estado: String, detalle: String) {
        try {
            val resp = RetrofitClient.apiService
                .confirmarPrintJob(idJob, PrintJobAckRequest(estado, detalle.take(500)))
                .execute()
            if (!resp.isSuccessful || resp.body()?.success != true) {
                // Si el ack no entra, el servidor volverá a entregar este trabajo en el
                // siguiente ciclo y se reimprimiría. Queda en el log porque es la causa
                // más probable de una receta duplicada.
                Log.w(TAG, "El ack del job $idJob no se registró (HTTP ${resp.code()}); puede reimprimirse")
            } else {
                Log.d(TAG, "Job $idJob confirmado como '$estado'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo confirmar el job $idJob: ${e.message}; puede reimprimirse")
        }
    }
}
