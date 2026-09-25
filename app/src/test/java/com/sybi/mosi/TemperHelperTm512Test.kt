package com.sybi.mosi

import com.sybi.mosi.helpers.TemperHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Formato de texto del termómetro TM512 (según TemperLSTM512Helper del SDK del fabricante). */
class TemperHelperTm512Test {

    private fun parse(text: String) = TemperHelper.parseTm512Celsius(text.toByteArray(Charsets.US_ASCII))

    @Test
    fun leeLaTemperaturaDelTexto() {
        assertEquals(36.5, parse("Body:36.52\r")!!, 0.001)
        assertEquals(37.1, parse("T=25.0 Body: 37.06 C\r")!!, 0.001)
        assertEquals(36.0, parse("Body:36\r")!!, 0.001)
    }

    @Test
    fun ignoraLasTramasBinarias() {
        // Trama binaria del termómetro anterior (inicio 5A): no es texto TM512
        assertNull(TemperHelper.parseTm512Celsius(byteArrayOf(0x5A, 0x01, 0x02, 0x6A, 0x0E, 0x00, 0x33)))
        assertNull(parse("OK\r"))
    }
}
