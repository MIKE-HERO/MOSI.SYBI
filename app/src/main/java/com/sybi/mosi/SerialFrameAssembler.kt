package com.sybi.mosi

import java.io.ByteArrayOutputStream
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Junta los bytes que llegan de un puerto serie en "ráfagas" completas.
 *
 * Un puerto serie no entrega la respuesta de un sensor de una sola vez: cada lectura devuelve lo que
 * haya llegado en ese instante (a veces 2 bytes, a veces 5). Los helpers (HWFat, Presión, Temperatura)
 * esperan la trama completa en un solo arreglo; si llegaba partida, se descartaba y la medición nunca
 * aparecía. Aquí se acumulan los bytes y se entregan juntos cuando el puerto queda en silencio [gapMs]
 * (el sensor terminó de responder), o como máximo cada [maxWaitMs] / [maxBytes] si transmite sin pausas.
 *
 * Todas las entregas se hacen en el hilo de [scheduler], en el mismo orden en que llegaron los datos.
 */
class SerialFrameAssembler(
    private val scheduler: ScheduledExecutorService,
    private val gapMs: Long = DEFAULT_GAP_MS,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val onBurst: (ByteArray) -> Unit,
) {
    private val buffer = ByteArrayOutputStream()
    private var firstByteAt = 0L
    private var pendingFlush: ScheduledFuture<*>? = null

    /** Agrega bytes recibidos; programa la entrega para cuando el puerto quede en silencio. */
    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (buffer.size() == 0) firstByteAt = now
            buffer.write(data, 0, data.size)

            pendingFlush?.cancel(false)
            val delay = if (buffer.size() >= maxBytes || now - firstByteAt >= maxWaitMs) 0L else gapMs
            pendingFlush = scheduler.schedule({ flush() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Descarta lo acumulado (por ejemplo, al cerrar el puerto). */
    fun clear() {
        synchronized(this) {
            pendingFlush?.cancel(false)
            pendingFlush = null
            buffer.reset()
        }
    }

    private fun flush() {
        val burst: ByteArray
        synchronized(this) {
            pendingFlush = null
            if (buffer.size() == 0) return
            burst = buffer.toByteArray()
            buffer.reset()
        }
        onBurst(burst)
    }

    companion object {
        /** A 9600 baudios un byte tarda ~1 ms; 40 ms de silencio indican que el sensor terminó de responder. */
        const val DEFAULT_GAP_MS = 40L
        const val DEFAULT_MAX_WAIT_MS = 300L
        const val DEFAULT_MAX_BYTES = 2048
    }
}
