package com.sybi.mosi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sybi.mosi.helpers.IcCardHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IcCardHelperTest {

    private lateinit var context: Context
    private lateinit var icCardHelper: IcCardHelper
    private lateinit var receiver: BroadcastReceiver
    private var receivedCardNumber: String? = null
    private lateinit var latch: CountDownLatch

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        icCardHelper = IcCardHelper(context)
        latch = CountDownLatch(1)
        receivedCardNumber = null

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "DEVICE_DATA_RECEIVED" && intent.getStringExtra("type") == "IC_CARD") {
                    receivedCardNumber = intent.getStringExtra("card_number")
                    latch.countDown()
                }
            }
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter("DEVICE_DATA_RECEIVED"))
    }

    @After
    fun tearDown() {
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    @Test
    fun test4BytesCard() {
        // 0x00, 0x12, 0x34, 0x56 -> integer 1193046
        val data = byteArrayOf(0x00, 0x12, 0x34, 0x56)
        icCardHelper.parseData(data)

        val success = latch.await(2, TimeUnit.SECONDS)
        assertEquals(true, success)
        assertEquals("1193046", receivedCardNumber)
    }

    @Test
    fun test5BytesCardWithChecksum() {
        // bytes: 0x00, 0x12, 0x34, 0x56, suma = (0x00+0x12+0x34+0x56) & 0xFF = 0x9C
        val suma = (0x00 + 0x12 + 0x34 + 0x56) and 0xFF
        val data = byteArrayOf(0x00, 0x12, 0x34, 0x56, suma.toByte())
        icCardHelper.parseData(data)

        val success = latch.await(2, TimeUnit.SECONDS)
        assertEquals(true, success)
        assertEquals("1193046", receivedCardNumber)
    }

    @Test
    fun test5BytesCardWithoutChecksum() {
        // 5 bytes where 5th byte is NOT a valid checksum (e.g., 0xFF)
        val data = byteArrayOf(0x00, 0x12, 0x34, 0x56, 0xFF.toByte())
        icCardHelper.parseData(data)

        val success = latch.await(2, TimeUnit.SECONDS)
        assertEquals(true, success)
        assertEquals("1193046", receivedCardNumber)
    }

    @Test
    fun testAsciiTextCard() {
        val data = "0001234567\r\n".toByteArray(Charsets.US_ASCII)
        icCardHelper.parseData(data)

        val success = latch.await(2, TimeUnit.SECONDS)
        assertEquals(true, success)
        assertEquals("1234567", receivedCardNumber)
    }

    @Test
    fun test6BytesCardWithChecksumAtEnd() {
        // 5 data bytes + 1 checksum byte at the end
        val body = byteArrayOf(0x00, 0x00, 0x12, 0x34, 0x56)
        val suma = (0 until body.size).sumOf { body[it].toInt() and 0xFF } and 0xFF
        val data = body + suma.toByte()
        icCardHelper.parseData(data)

        val success = latch.await(2, TimeUnit.SECONDS)
        assertEquals(true, success)
        assertEquals("1193046", receivedCardNumber)
    }
}
