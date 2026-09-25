package com.sybi.mosi

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simula cómo entrega los bytes un puerto serie real (en pedazos) y verifica que las tramas
 * lleguen completas a los helpers, que es lo que ellos esperan.
 */
class SerialFrameAssemblerTest {

    private lateinit var scheduler: ScheduledExecutorService
    private val bursts = CopyOnWriteArrayList<ByteArray>()

    // Tramas con el formato que interpretan los helpers
    private val alturaPeso = bytes(0x28, 0x07, 0xD1, 0x00, 0x01, 0x11, 0x70) + checksum(0x28, 0x07, 0xD1, 0x00, 0x01, 0x11, 0x70)
    private val temperatura = bytes(0x5A, 0x01, 0x02, 0x6A, 0x0E, 0x00, 0x33)   // 36.90 °C
    private val presion = bytes(0xD0, 0xC2, 0x07, 0x00, 0x78, 0x50, 0x48, 0x2A) // 120/80, pulso 72

    @Before
    fun setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor()
        bursts.clear()
    }

    @After
    fun tearDown() {
        scheduler.shutdownNow()
    }

    private fun assembler() = SerialFrameAssembler(scheduler) { bursts.add(it) }

    @Test
    fun tramaPartidaEnBytesSueltosLlegaCompleta() {
        for (frame in listOf(alturaPeso, temperatura, presion)) {
            bursts.clear()
            val a = assembler()
            frame.forEach { a.append(byteArrayOf(it)); Thread.sleep(1) } // 1 byte por lectura, como a 9600 baudios
            waitForSilence()
            assertEquals("una sola trama para ${hex(frame)}", 1, bursts.size)
            assertArrayEquals(frame, bursts[0])
        }
    }

    @Test
    fun tramaPartidaEnPedazosDeTodosLosTamanosLlegaCompleta() {
        val frame = alturaPeso
        for (chunk in 1..frame.size) {
            bursts.clear()
            val a = assembler()
            frame.toList().chunked(chunk).forEach { a.append(it.toByteArray()); Thread.sleep(2) }
            waitForSilence()
            assertEquals("pedazos de $chunk bytes", 1, bursts.size)
            assertArrayEquals("pedazos de $chunk bytes", frame, bursts[0])
        }
    }

    @Test
    fun respuestasSeparadasPorSilencioSeEntreganPorSeparado() {
        val a = assembler()
        a.append(temperatura.copyOfRange(0, 3)); Thread.sleep(2)
        a.append(temperatura.copyOfRange(3, temperatura.size))
        Thread.sleep(150) // el sensor calla antes de la siguiente respuesta
        a.append(presion.copyOfRange(0, 5)); Thread.sleep(2)
        a.append(presion.copyOfRange(5, presion.size))
        waitForSilence()
        assertEquals(2, bursts.size)
        assertArrayEquals(temperatura, bursts[0])
        assertArrayEquals(presion, bursts[1])
    }

    @Test
    fun transmisionContinuaSeEntregaSinEsperarPorSiempre() {
        val a = assembler()
        val start = System.currentTimeMillis()
        // Un equipo que no deja de transmitir nunca tiene silencio: igual debe entregarse cada ~300 ms
        while (System.currentTimeMillis() - start < 1000) {
            a.append(alturaPeso)
            Thread.sleep(10)
        }
        waitForSilence()
        assertTrue("se entregó varias veces (${bursts.size})", bursts.size >= 3)
        assertEquals("no se perdió ningún byte", 0, bursts.sumOf { it.size } % alturaPeso.size)
    }

    @Test
    fun clearDescartaLoPendiente() {
        val a = assembler()
        a.append(alturaPeso.copyOfRange(0, 4))
        a.clear()
        waitForSilence()
        assertEquals(0, bursts.size)
    }

    private fun waitForSilence() = Thread.sleep(SerialFrameAssembler.DEFAULT_GAP_MS * 4)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun checksum(vararg v: Int) = byteArrayOf((v.sum() and 0xFF).toByte())
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }
}
