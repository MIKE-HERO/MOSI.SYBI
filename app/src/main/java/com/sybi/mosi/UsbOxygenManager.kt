package com.sybi.mosi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort

class UsbOxygenManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbOxygenManager"
        private const val VENDOR_ID = 1659
        private const val PRODUCT_ID = 9123
        private const val ACTION_USB_PERMISSION = "com.sybi.mosi.USB_PERMISSION"
        /** Velocidad del chip PL2303 del oxímetro según el SDK del fabricante (OxygenHelper.baudRate()). */
        private const val BAUD_RATE = 38400

        // ✅ Singleton para consultar el estado desde cualquier parte
        @Volatile
        private var instance: UsbOxygenManager? = null

        fun getInstance(context: Context): UsbOxygenManager {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = UsbOxygenManager(context.applicationContext)
                    }
                }
            }
            return instance!!
        }

        fun isDeviceReady(): Boolean {
            val mgr = instance ?: return false
            return mgr.usbConnection != null &&
                    mgr.bulkReadEndpoint != null &&
                    mgr.bulkWriteEndpoint != null &&
                    mgr.isDeviceInitialized
        }

        fun isDevicePresent(context: Context): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            for ((_, device) in usbManager.deviceList) {
                if (device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID) {
                    return true
                }
            }
            return false
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkReadEndpoint: UsbEndpoint? = null
    private var bulkWriteEndpoint: UsbEndpoint? = null
    private var isReading = false
    private var readThread: Thread? = null
    private var isDeviceInitialized = false
    private var serialChipPort: UsbSerialPort? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null && isOxygenDevice(device)) {
                            Log.d(TAG, "✅ Permiso USB concedido")
                            connectToDevice(device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && isOxygenDevice(device)) {
                        requestPermission(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device == null || isOxygenDevice(device)) {
                        Log.d(TAG, "🔌 Oxímetro USB desconectado")
                        disconnect()
                    } else {
                        Log.d(TAG, "ℹ️ Ignorando desconexión de USB de otro dispositivo")
                    }
                }
            }
        }
    }

    fun init() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        findAndConnectDevice()
    }

    fun findAndConnectDevice() {
        val deviceList = usbManager.deviceList
        for ((_, device) in deviceList) {
            if (isOxygenDevice(device)) {
                requestPermission(device)
                return
            }
        }
    }

    private fun isOxygenDevice(device: UsbDevice): Boolean {
        return device.vendorId == VENDOR_ID && device.productId == PRODUCT_ID
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        usbDevice = device
        bulkReadEndpoint = null
        bulkWriteEndpoint = null
        usbInterface = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var foundRead: UsbEndpoint? = null
            var foundWrite: UsbEndpoint? = null

            for (j in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(j)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                        foundRead = endpoint
                    } else {
                        foundWrite = endpoint
                    }
                }
            }
            if (foundRead != null || foundWrite != null) {
                bulkReadEndpoint = foundRead
                bulkWriteEndpoint = foundWrite
                usbInterface = intf
                break
            }
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.e(TAG, "❌ No se pudo abrir la conexión")
            return
        }
        usbConnection = connection

        if (usbInterface != null && !connection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "❌ No se pudo reclamar la interfaz")
            return
        }

        configureSerialChip(device, connection)

        Log.d(TAG, "✅ Oxímetro USB conectado correctamente")

        // ✅ Inicializar el dispositivo y notificar el estado
        initializeDevice()
    }

    /**
     * El oxímetro es un chip USB-serie Prolific PL2303: hay que fijarle la velocidad antes de usarlo.
     * Se hace con el driver incluido en el SDK del fabricante, a 38400 baudios 8N1
     * (OxygenHelper.baudRate() del SDK_I6). Si falla, se sigue como antes, sin configurar.
     */
    private fun configureSerialChip(device: UsbDevice, connection: UsbDeviceConnection) {
        try {
            val port = ProlificSerialDriver(device).ports[0]
            port.open(connection)
            port.setParameters(BAUD_RATE, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            serialChipPort = port
            Log.d(TAG, "🔧 Oxímetro (PL2303) configurado a $BAUD_RATE baudios, 8N1")
        } catch (e: Throwable) {
            serialChipPort = null
            Log.w(TAG, "⚠️ No se pudo configurar la velocidad del oxímetro (${e.javaClass.simpleName}: ${e.message}); se usa sin configurar")
        }
    }

    private fun initializeDevice() {
        // ✅ Enviar comando de inicialización
        val initCommand = byteArrayOf(
            0xAA.toByte(), 0x55, 0x50, 0x03, 0x02, 0x01, 0x00
        )
        initCommand[6] = crc8CheckValue(initCommand, 0, 5).toByte()

        if (bulkWriteEndpoint != null) {
            val result = usbConnection?.bulkTransfer(
                bulkWriteEndpoint,
                initCommand,
                initCommand.size,
                1000
            )
            Log.d(TAG, "Comando de inicialización enviado, resultado: $result")

            if (result != null && result >= 0) {
                isDeviceInitialized = true
                Log.d(TAG, "✅ Dispositivo inicializado correctamente")
            } else {
                Log.e(TAG, "❌ Error al inicializar el dispositivo")
                isDeviceInitialized = false
            }
        }

        // Esperar a que el dispositivo se estabilice
        try {
            Thread.sleep(300)
        } catch (e: InterruptedException) {
            // Ignorar
        }

        // ✅ Notificar a la app que el estado del dispositivo cambió tras la inicialización
        val intent = Intent("USB_DEVICE_STATUS_CHANGED")
        intent.putExtra("connected", isDeviceInitialized)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun isDeviceReady(): Boolean {
        return usbConnection != null && bulkReadEndpoint != null && bulkWriteEndpoint != null && isDeviceInitialized
    }

    fun waitForDevice(timeoutMs: Int = 5000): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isDeviceReady()) {
                return true
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    // ✅ CAMBIO CRÍTICO: Corregir la lógica de isReading
    fun startMeasure() {
        Log.d(TAG, "🚀 Iniciando medición de oxímetro...")

        // ✅ Ejecutar todo en un hilo de fondo para NO bloquear el main thread
        Thread {
            if (!isDeviceReady()) {
                Log.e(TAG, "❌ Dispositivo NO está listo, intentando conectar...")
                findAndConnectDevice()

                // ✅ waitForDevice() ahora está en background, no bloquea la UI
                if (!waitForDevice(5000)) {
                    Log.e(TAG, "❌ No se pudo conectar al dispositivo")
                    // ✅ Notificar a la app que falló (sin Toast)
                    val failIntent = Intent("USB_DEVICE_STATUS_CHANGED")
                    failIntent.putExtra("connected", false)
                    failIntent.putExtra("error", "no_device")
                    LocalBroadcastManager.getInstance(context).sendBroadcast(failIntent)
                    return@Thread
                }
            }

            Log.d(TAG, "✅ Dispositivo listo, iniciando lectura...")

            if (!isReading) {
                isReading = true
                val thread = Thread {
                    val buffer = ByteArray(64)
                    var readCount = 0
                    while (isReading) {
                        try {
                            if (bulkReadEndpoint != null) {
                                val bytesRead = usbConnection?.bulkTransfer(
                                    bulkReadEndpoint, buffer, buffer.size, 1000
                                )
                                if (bytesRead != null && bytesRead > 0) {
                                    readCount++
                                    val data = buffer.copyOf(bytesRead)
                                    val hexString = data.joinToString(" ") { "%02X".format(it) }
                                    Log.d(TAG, "📥 Datos recibidos #$readCount: $hexString")

                                    val oxygenIntent = Intent("OXYGEN_RAW_DATA")
                                    oxygenIntent.putExtra("data", data)
                                    LocalBroadcastManager.getInstance(context).sendBroadcast(oxygenIntent)
                                }
                            }
                        } catch (e: Exception) {
                            if (isReading) Log.e(TAG, "❌ Error leyendo: ${e.message}")
                        }
                        try { Thread.sleep(5) } catch (e: InterruptedException) { break }
                    }
                }
                readThread = thread
                thread.start()
            }

            sendWaveCommandWithRetry()
        }.start()  // ✅ Todo en background
    }

    private fun sendWaveCommandWithRetry() {
        var attempts = 0
        val maxAttempts = 3

        while (attempts < maxAttempts) {
            val result = sendWaveCommand()
            if (result) {
                Log.d(TAG, "✅ Comando de onda enviado exitosamente")
                return
            }

            attempts++
            Log.w(TAG, "⚠️ Intento $attempts de enviar comando de onda")

            try {
                Thread.sleep(200) // Esperar antes de reintentar
            } catch (e: InterruptedException) {
                return
            }
        }

        Log.e(TAG, "❌ No se pudo enviar el comando de onda después de $maxAttempts intentos")
    }

    private fun sendWaveCommand(): Boolean {
        val data = byteArrayOf(0xAA.toByte(), 0x55, 0x50, 0x03, 0x02, 0x01, 0x00)
        data[6] = crc8CheckValue(data, 0, 5).toByte()

        Log.d(TAG, "📤 Enviando comando de onda: ${data.joinToString(" ") { "%02X".format(it) }}")

        if (bulkWriteEndpoint != null) {
            val result = usbConnection?.bulkTransfer(bulkWriteEndpoint, data, data.size, 1000)
            Log.d(TAG, "Resultado del envío: $result")
            return result != null && result >= 0
        }
        return false
    }

    fun stopMeasure() {
        Log.d(TAG, "Deteniendo medición de oxímetro...")
        isReading = false
        readThread?.interrupt()
        if (bulkWriteEndpoint != null) {
            val stopCommand = byteArrayOf(0x5B.toByte(), 0x04, 0x63, 0x38, 0xFA.toByte())
            usbConnection?.bulkTransfer(bulkWriteEndpoint, stopCommand, stopCommand.size, 1000)
        }
    }

    fun resetMeasurement() {
        Log.d(TAG, "Reseteando medición de oxímetro...")
        stopMeasure()
        isDeviceInitialized = false

        // Esperar un poco antes de reiniciar
        try {
            Thread.sleep(200)
        } catch (e: InterruptedException) {
            // Ignorar
        }

        // Reinicializar el dispositivo
        if (usbConnection != null) {
            initializeDevice()
        }
    }

    fun disconnect() {
        isReading = false
        readThread?.interrupt()
        try {
            usbConnection?.releaseInterface(usbInterface)
            usbConnection?.close()
        } catch (e: Exception) {}
        try {
            serialChipPort?.close()
        } catch (e: Exception) {}
        serialChipPort = null
        usbConnection = null
        usbInterface = null
        bulkReadEndpoint = null
        bulkWriteEndpoint = null
        isDeviceInitialized = false

        // ✅ Notificar a la app
        val intent = Intent("USB_DEVICE_STATUS_CHANGED")
        intent.putExtra("connected", false)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {}
        instance = null
    }

    private fun crc8CheckValue(bs: ByteArray, start: Int, end: Int): Int {
        val table = intArrayOf(
            0, 94, 188, 226, 97, 63, 221, 131, 194, 156, 126, 32, 163, 253, 31, 65,
            157, 195, 33, 127, 252, 162, 64, 30, 95, 1, 227, 189, 62, 96, 130, 220,
            35, 125, 159, 193, 66, 28, 254, 160, 225, 191, 93, 3, 128, 222, 60, 98,
            190, 224, 2, 92, 223, 129, 99, 61, 124, 34, 192, 158, 29, 67, 161, 255,
            70, 24, 250, 164, 39, 121, 155, 197, 132, 218, 56, 102, 229, 187, 89, 7,
            219, 133, 103, 57, 186, 228, 6, 88, 25, 71, 165, 251, 120, 38, 196, 154,
            101, 59, 217, 135, 4, 90, 184, 230, 167, 249, 27, 69, 198, 152, 122, 36,
            248, 166, 68, 26, 153, 199, 37, 123, 58, 100, 134, 216, 91, 5, 231, 185,
            140, 210, 48, 110, 237, 179, 81, 15, 78, 16, 242, 172, 47, 113, 147, 205,
            17, 79, 173, 243, 112, 46, 204, 146, 211, 141, 111, 49, 178, 236, 14, 80,
            175, 241, 19, 77, 206, 144, 114, 44, 109, 51, 209, 143, 12, 82, 176, 238,
            50, 108, 142, 208, 83, 13, 239, 177, 240, 174, 76, 18, 145, 207, 45, 115,
            202, 148, 118, 40, 171, 245, 23, 73, 8, 86, 180, 234, 105, 55, 213, 139,
            87, 9, 235, 181, 54, 104, 138, 212, 149, 203, 41, 119, 244, 170, 72, 22,
            233, 183, 85, 11, 136, 214, 52, 106, 43, 117, 151, 201, 74, 20, 246, 168,
            116, 42, 200, 150, 21, 75, 169, 247, 182, 232, 10, 84, 215, 137, 107, 53
        )
        if (bs.size <= end || start > end || start < 0) return 0
        var crc = 0
        for (i in start..end) {
            crc = table[((bs[i].toInt() and 0xFF) xor crc) and 0xFF]
        }
        return crc
    }
}