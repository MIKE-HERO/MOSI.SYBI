package com.sybi.mosi

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.sybi.mosi.helpers.HWFatHelper
import com.sybi.mosi.helpers.IcCardHelper
import com.sybi.mosi.helpers.OxigenHelper
import com.sybi.mosi.helpers.PressureHelper
import com.sybi.mosi.helpers.TemperHelper
import java.io.File
import android.serialport.api.SerialPort
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class SerialService : Service() {

    private val openStreams = ConcurrentHashMap<String, InputStream>()
    private val openOutStreams = ConcurrentHashMap<String, OutputStream>()
    private val serialPorts = ConcurrentHashMap<String, SerialPort>()
    private val readingThreads = ConcurrentHashMap<String, Thread>()
    private val isReadingActive = ConcurrentHashMap<String, Boolean>()

    private val activePorts = mutableListOf<String>()
    private val TAG = "SerialService"

    // Armado de tramas: un hilo único entrega los datos completos a los helpers, en orden
    private val frameScheduler = Executors.newSingleThreadScheduledExecutor()
    private val assemblers = ConcurrentHashMap<String, SerialFrameAssembler>()
    private val IDLE_SLEEP_MS = 20L
    private val ERROR_SLEEP_MS = 500L

    private val localReceivers = mutableListOf<BroadcastReceiver>()
    @Volatile private var destroyed = false

    private var alturaPesoPortPath: String = ""
    private var presionPortPath: String = ""
    private var temperaturaPortPath: String = ""
    private var icCardPortPath: String = ""

    // Helpers
    private lateinit var hwFatHelper: HWFatHelper
    private lateinit var temperHelper: TemperHelper
    private lateinit var pressureHelper: PressureHelper
    private lateinit var oxigenHelper: OxigenHelper
    private lateinit var icCardHelper: IcCardHelper
    private var usbOxygenManager: UsbOxygenManager? = null

    // ✅ Flag para saber si hay una calibración en curso
    private var calibracionEnCurso = false

    private val binder = SerialBinder()

    inner class SerialBinder : Binder() {
        fun getService(): SerialService = this@SerialService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio Serial iniciado")

        // Inicializar helpers
        hwFatHelper = HWFatHelper(this)
        temperHelper = TemperHelper(this)
        pressureHelper = PressureHelper(this)
        oxigenHelper = OxigenHelper(this)
        icCardHelper = IcCardHelper(this)

        // Inicializar USB Oxygen Manager antes de abrir puertos
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("device_oxigeno", true)) {
            usbOxygenManager = UsbOxygenManager.getInstance(this)
            usbOxygenManager?.init()
        }

        // Registrar receivers
        registerReceivers()

        openAllConfiguredPorts()
    }

    /**
     * Registra un receptor y lo recuerda para darlo de baja en onDestroy.
     * Antes nunca se daban de baja: cada vez que el servicio se reiniciaba quedaba una copia "zombi"
     * que seguía recibiendo órdenes (comandos duplicados a la báscula, reaperturas de puertos) y
     * leyendo los puertos, robando tramas al servicio activo.
     */
    private fun registerLocalReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)
        localReceivers.add(receiver)
    }

    private fun registerReceivers() {
        val updateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "UPDATE_SERIAL_PORTS") {
                    updateSerialPorts()
                }
            }
        }
        registerLocalReceiver(updateReceiver, IntentFilter("UPDATE_SERIAL_PORTS"))

        val resetReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "RESET_SERIAL_RESULTS") {
                    hwFatHelper.resetAllResults()
                    temperHelper.resetAllResults()
                    pressureHelper.resetAllResults()
                    oxigenHelper.resetAllResults()
                    Log.d(TAG, "🔄 Todos los resultados reseteados")
                }
            }
        }
        registerLocalReceiver(resetReceiver, IntentFilter("RESET_SERIAL_RESULTS"))

        val commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "SEND_READ_COMMAND") {
                    val commandType = intent.getStringExtra("command_type") ?: return
                    sendReadCommand(commandType)
                }
            }
        }
        registerLocalReceiver(commandReceiver, IntentFilter("SEND_READ_COMMAND"))

        val releaseReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "RELEASE_SERIAL_PORTS") {
                    releaseAllPorts()
                }
            }
        }
        registerLocalReceiver(releaseReceiver, IntentFilter("RELEASE_SERIAL_PORTS"))

        val oxygenDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "OXYGEN_RAW_DATA") {
                    val data = intent.getByteArrayExtra("data") ?: return
                    oxigenHelper.parseData(data)
                }
            }
        }
        registerLocalReceiver(oxygenDataReceiver, IntentFilter("OXYGEN_RAW_DATA"))

        val stopCommandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "SEND_STOP_COMMAND") {
                    val commandType = intent.getStringExtra("command_type") ?: return
                    sendStopCommand(commandType)
                }
            }
        }
        registerLocalReceiver(stopCommandReceiver, IntentFilter("SEND_STOP_COMMAND"))

        val stopOxygenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "STOP_OXYGEN_MEASURE") {
                    sendStopOxygenCommand()
                }
            }
        }
        registerLocalReceiver(stopOxygenReceiver, IntentFilter("STOP_OXYGEN_MEASURE"))

        val startOxygenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "START_OXYGEN_MEASURE") {
                    Log.d(TAG, "📨 Iniciando medición de oxígeno...")
                    usbOxygenManager?.startMeasure()
                }
            }
        }
        registerLocalReceiver(startOxygenReceiver, IntentFilter("START_OXYGEN_MEASURE"))

        val oxygenResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "OXYGEN_RESULT_RECEIVED") {
                    Log.d(TAG, "✅ Resultado de oxígeno recibido, deteniendo lectura...")
                    usbOxygenManager?.stopMeasure()
                }
            }
        }
        registerLocalReceiver(oxygenResultReceiver, IntentFilter("OXYGEN_RESULT_RECEIVED"))

        val resetOxygenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "RESET_OXYGEN_MEASURE") {
                    Log.d(TAG, "🧹 Limpiando oxímetro para nueva medición")
                    oxigenHelper.resetForNewMeasurement()
                }
            }
        }
        registerLocalReceiver(resetOxygenReceiver, IntentFilter("RESET_OXYGEN_MEASURE"))

        val patientDataReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "SET_PATIENT_DATA") {
                    val birthDate = intent.getStringExtra("birth_date") ?: "01/01/1990"
                    val isMan = intent.getStringExtra("gender") == "M"

                    hwFatHelper.setUserData(birthDate, isMan)

                    Log.d(TAG, "✅ Datos del paciente actualizados: FechaNacimiento=$birthDate, Hombre=$isMan")
                }
            }
        }
        registerLocalReceiver(patientDataReceiver, IntentFilter("SET_PATIENT_DATA"))

        val queryDevicesReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "QUERY_CONNECTED_DEVICES") {
                    sendConnectedDevicesResponse()
                }
            }
        }
        registerLocalReceiver(queryDevicesReceiver, IntentFilter("QUERY_CONNECTED_DEVICES"))

        // ✅ RECEIVER DE CALIBRACIÓN
        val calibrationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "SEND_CALIBRATION_COMMAND") {
                    val tipo = intent.getStringExtra("tipo") ?: return  // "altura" o "peso"
                    val valor = intent.getDoubleExtra("valor", 0.0)

                    Log.d(TAG, "📨 Comando calibración recibido: tipo=$tipo, valor=$valor")

                    val outStream = openOutStreams[alturaPesoPortPath]
                    if (outStream == null) {
                        Log.e(TAG, "❌ No hay stream para calibración")
                        enviarResultadoCalibracion(tipo, "ERROR", "Puerto serie no disponible")
                        return
                    }

                    calibracionEnCurso = true

                    val ok = if (tipo == "altura") {
                        hwFatHelper.calibrateHeight(outStream, alturaPesoPortPath, valor)
                    } else {
                        hwFatHelper.calibrateWeight(outStream, alturaPesoPortPath, valor)
                    }

                    if (!ok) {
                        calibracionEnCurso = false
                        enviarResultadoCalibracion(tipo, "ERROR", "Error al enviar comando")
                    }
                    // Si ok = true, esperamos respuesta del dispositivo.
                    // La detección se hace en detectarCalibracion() desde el readingLoop.
                }
            }
        }
        registerLocalReceiver(calibrationReceiver, IntentFilter("SEND_CALIBRATION_COMMAND"))
    }

    /**
     * Envía el resultado de la calibración (OK / ERROR) a la activity.
     */
    private fun enviarResultadoCalibracion(tipo: String, status: String, message: String? = null) {
        val intent = Intent("CALIBRATION_RESULT")
        intent.putExtra("tipo", tipo)          // "altura" o "peso"
        intent.putExtra("status", status)      // "OK" o "ERROR"
        if (message != null) intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📤 CALIBRATION_RESULT: tipo=$tipo, status=$status, msg=$message")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun openAllConfiguredPorts() {
        val prefs = getSharedPreferences("DevicePrefs", Context.MODE_PRIVATE)
        activePorts.clear()

        // Altura/Peso
        alturaPesoPortPath = prefs.getString("device_altura_peso_port", null) ?: ""
        if (prefs.getBoolean("device_altura_peso", true) &&
            alturaPesoPortPath.isNotEmpty() &&
            alturaPesoPortPath != "No se encontraron puertos") {
            if (openPortInternalSimple(alturaPesoPortPath, SerialBaudConfig.get(prefs, "device_altura_peso"))) {
                activePorts.add(alturaPesoPortPath)
                Log.i(TAG, "✅ Puerto abierto para Altura/Peso: $alturaPesoPortPath")
            }
        }

        // Presión
        presionPortPath = prefs.getString("device_presion_port", null) ?: ""
        if (prefs.getBoolean("device_presion", true) &&
            presionPortPath.isNotEmpty() &&
            presionPortPath != "No se encontraron puertos") {
            if (openPortInternalSimple(presionPortPath, SerialBaudConfig.get(prefs, "device_presion"))) {
                activePorts.add(presionPortPath)
                Log.i(TAG, "✅ Puerto abierto para Presión: $presionPortPath")
            }
        }

        // Temperatura
        temperaturaPortPath = prefs.getString("device_temperatura_port", null) ?: ""
        if (prefs.getBoolean("device_temperatura", true) &&
            temperaturaPortPath.isNotEmpty() &&
            temperaturaPortPath != "No se encontraron puertos") {
            if (openPortInternalSimple(temperaturaPortPath, SerialBaudConfig.get(prefs, "device_temperatura"))) {
                activePorts.add(temperaturaPortPath)
                Log.i(TAG, "✅ Puerto abierto para Temperatura: $temperaturaPortPath")
            }
        }

        // Lector Tarjeta IC
        icCardPortPath = prefs.getString("device_ic_card_port", null) ?: ""
        if (prefs.getBoolean("device_ic_card", true) &&
            icCardPortPath.isNotEmpty() &&
            icCardPortPath != "No se encontraron puertos") {
            if (openPortInternalSimple(icCardPortPath, SerialBaudConfig.get(prefs, "device_ic_card"))) {
                activePorts.add(icCardPortPath)
                Log.i(TAG, "✅ Puerto abierto para Lector IC: $icCardPortPath")
            }
        }

        // Oxígeno (USB)
        if (prefs.getBoolean("device_oxigeno", true)) {
            activePorts.add("USB_OXYGEN")
            Log.i(TAG, "✅ Oxímetro USB activo")
        }

        sendConnectionStatus()
        sendConnectedDevicesResponse()
    }

    private fun sendConnectionStatus() {
        val intent = Intent("SERIAL_SERVICE_STATUS")
        intent.putExtra("active_ports", activePorts.toTypedArray())
        intent.putExtra("port_count", activePorts.size)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun hasAnyDeviceConnected(): Boolean {
        if (openStreams.isNotEmpty()) return true
        if (UsbOxygenManager.isDeviceReady()) return true
        return false
    }

    private fun sendConnectedDevicesResponse() {
        val connected = mutableListOf<String>()

        // Puertos seriales que tienen stream abierto
        for (portPath in openStreams.keys) {
            connected.add(portPath)
        }

        // Oxímetro USB
        val oxygenReady = UsbOxygenManager.isDeviceReady()
        val oxygenPresent = UsbOxygenManager.isDevicePresent(this)

        if (oxygenReady) {
            connected.add("USB_OXYGEN_READY")
        } else if (oxygenPresent) {
            connected.add("USB_OXYGEN_PRESENT")
        }

        val response = Intent("CONNECTED_DEVICES_RESPONSE")
        response.putExtra("active_ports", connected.toTypedArray())
        response.putExtra("port_count", connected.size)
        response.putExtra("serial_count", openStreams.size)
        response.putExtra("oxygen_ready", oxygenReady)
        response.putExtra("oxygen_present", oxygenPresent)
        LocalBroadcastManager.getInstance(this).sendBroadcast(response)

        Log.d(TAG, "📡 Respuesta dispositivos: serial=${openStreams.size}, oxygenReady=$oxygenReady, oxygenPresent=$oxygenPresent")
    }

    /**
     * Abre el puerto en modo binario y a [baudRate] con la librería del fabricante (SerialPort / libserial_port).
     * Sin configurar, Linux trata el puerto como una terminal de texto: corta las tramas en ciertos bytes
     * (0A, 04…), borra o se come otros (15, 7F, 11, 13…) y usa la velocidad que tuviera, lo que
     * desordena las respuestas de los sensores.
     * Si la librería falla, abre el puerto como antes para no perder el equipo.
     */
    @Synchronized
    fun openPortInternalSimple(portPath: String, baudRate: Int): Boolean {
        if (openStreams.containsKey(portPath)) {
            return true
        }

        val file = File(portPath)
        if (!file.exists()) {
            Log.e(TAG, "El puerto $portPath no existe")
            return false
        }

        try {
            val serialPort = SerialPort.getSerialPort(portPath, baudRate)
            serialPorts[portPath] = serialPort
            openStreams[portPath] = serialPort.inputStream
            openOutStreams[portPath] = serialPort.outputStream
            Log.i(TAG, "🔧 $portPath configurado: $baudRate baudios, 8N1, modo binario")
            startReadingLoop(portPath)
            return true
        } catch (e: Throwable) {
            val hint = if (!SerialBaudConfig.isStandard(baudRate)) " — $baudRate no es una velocidad estándar del puerto" else ""
            Log.e(TAG, "⚠️ No se pudo configurar $portPath a $baudRate baudios (${e.javaClass.simpleName}: ${e.message})$hint; se abre sin configurar")
        }

        return try {
            openStreams[portPath] = FileInputStream(file)
            openOutStreams[portPath] = FileOutputStream(file)
            startReadingLoop(portPath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al abrir puerto $portPath: ${e.message}")
            false
        }
    }

    private fun startReadingLoop(portPath: String) {
        if (isReadingActive.containsKey(portPath) && isReadingActive[portPath] == true) {
            return
        }

        isReadingActive[portPath] = true

        // Cada hilo lee solo el stream con el que nació: si el puerto se reabre (UPDATE_SERIAL_PORTS),
        // el hilo viejo termina en vez de repartirse los datos con el nuevo.
        val myStream = openStreams[portPath] ?: return

        // Los bytes se juntan en tramas completas antes de interpretarlos (ver SerialFrameAssembler)
        val assembler = SerialFrameAssembler(frameScheduler) { burst -> processBurst(portPath, burst) }
        assemblers[portPath] = assembler

        val thread = Thread {
            val buffer = ByteArray(4096)
            while (!destroyed && isReadingActive[portPath] == true && openStreams[portPath] === myStream) {
                try {
                    val bytesRead = myStream.read(buffer)

                    if (bytesRead > 0) {
                        val receivedData = buffer.copyOf(bytesRead)

                        // Enviar datos crudos al monitor
                        val rawIntent = Intent("SERIAL_RAW_DATA")
                        rawIntent.putExtra("port", portPath)
                        rawIntent.putExtra("data", bytesToHex(receivedData))
                        rawIntent.putExtra("type", "RX")
                        LocalBroadcastManager.getInstance(this).sendBroadcast(rawIntent)

                        assembler.append(receivedData)
                    } else {
                        // Sin datos: esperar un poco en vez de girar sin pausa (100% de CPU)
                        Thread.sleep(IDLE_SLEEP_MS)
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Thread interrumpido para $portPath")
                    break
                } catch (e: Exception) {
                    if (destroyed || isReadingActive[portPath] != true || openStreams[portPath] !== myStream) break
                    Log.e(TAG, "Error en bucle de $portPath: ${e.message}")
                    // Evita un bucle de errores sin pausa que satura la CPU y el log
                    try { Thread.sleep(ERROR_SLEEP_MS) } catch (_: InterruptedException) { break }
                }
            }
        }
        readingThreads[portPath] = thread
        thread.start()
    }

    /** Entrega una trama completa (ya armada) al helper del puerto correspondiente. */
    private fun processBurst(portPath: String, data: ByteArray) {
        if (destroyed) return
        Log.d(TAG, "📥 RX $portPath (${data.size} bytes): ${bytesToHex(data)}")
        try {
            when (portPath) {
                alturaPesoPortPath -> {
                    // ✅ Si hay una calibración en curso, detectar la respuesta primero
                    if (calibracionEnCurso) {
                        detectarCalibracion(data)
                    }
                    hwFatHelper.parseData(data)
                }
                presionPortPath -> pressureHelper.parseData(data)
                temperaturaPortPath -> temperHelper.parseData(data)
                icCardPortPath -> icCardHelper.parseData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando datos de $portPath: ${e.message}")
        }
    }

    /**
     * Detecta si los bytes recibidos corresponden a una respuesta de calibración.
     * Solo se llama cuando `calibracionEnCurso == true`.
     */
    private fun detectarCalibracion(data: ByteArray) {
        hwFatHelper.parseCalibrationData(data) { result ->
            if (!calibracionEnCurso) return@parseCalibrationData

            calibracionEnCurso = false

            when (result.type) {
                HWFatHelper.CalibrationType.WEIGHT -> {
                    if (result.status == HWFatHelper.CalibrationStatus.OK) {
                        enviarResultadoCalibracion("peso", "OK")
                    } else {
                        enviarResultadoCalibracion("peso", "ERROR", "Error del dispositivo")
                    }
                }
                HWFatHelper.CalibrationType.HEIGHT -> {
                    if (result.status == HWFatHelper.CalibrationStatus.OK) {
                        enviarResultadoCalibracion("altura", "OK")
                    } else {
                        enviarResultadoCalibracion("altura", "ERROR", "Error del dispositivo")
                    }
                }
                HWFatHelper.CalibrationType.UNKNOWN -> {
                    enviarResultadoCalibracion("desconocido", "ERROR", "Error del dispositivo")
                }
            }
        }
    }

    fun sendReadCommand(commandType: String) {
        Log.d(TAG, "📨 sendReadCommand: $commandType")

        when (commandType) {
            "ALTURA_PESO" -> {
                val outStream = openOutStreams[alturaPesoPortPath]
                if (outStream != null) {
                    // Cada medición empieza en limpio: si no, una altura o peso de la medición anterior
                    // (u otro paciente) se mostraba cuando la trama nueva no llegaba.
                    hwFatHelper.resetAlturaPesoResults()
                    hwFatHelper.sendAlturaPesoCommand(outStream, alturaPesoPortPath)
                } else {
                    Log.e(TAG, "❌ No hay stream para Altura/Peso")
                }
            }
            "COMPOSICION" -> {
                val outStream = openOutStreams[alturaPesoPortPath]
                if (outStream != null) {
                    hwFatHelper.sendComposicionCommand(outStream, alturaPesoPortPath)
                } else {
                    Log.e(TAG, "❌ No hay stream para Composición")
                }
            }
            "TEMPERATURA" -> {
                temperHelper.resetAllResults()
                Log.d(TAG, "Termómetro: esperando datos...")
            }
            "PRESION" -> {
                pressureHelper.resetAllResults()
                Log.d(TAG, "Presión: esperando datos...")
            }
            "OXIGENO" -> {
                oxigenHelper.resetAllResults()
                Log.d(TAG, "Oxímetro: esperando datos...")
                usbOxygenManager?.startMeasure()
            }
        }
    }

    fun sendStopCommand(commandType: String) {
        Log.d(TAG, "📨 sendStopCommand: $commandType")

        when (commandType) {
            "ALTURA_PESO", "COMPOSICION" -> {
                val outStream = openOutStreams[alturaPesoPortPath]
                if (outStream != null) {
                    hwFatHelper.sendStopMeasureCommand(outStream, alturaPesoPortPath)
                } else {
                    Log.e(TAG, "❌ No hay stream para Altura/Peso")
                }
            }
        }
    }

    fun sendStopOxygenCommand() {
        Log.d(TAG, "📨 Deteniendo oxímetro...")
        usbOxygenManager?.stopMeasure()
    }

    fun sendStartOxygenCommand() {
        Log.d(TAG, "📨 Iniciando oxímetro...")
        usbOxygenManager?.startMeasure()
    }

    private fun resetAllResults() {
        hwFatHelper.resetCommandFlags()
        Log.d(TAG, "🔄 Resultados reseteados")
    }

    private fun updateSerialPorts() {
        Log.i(TAG, "Actualizando puertos seriales...")
        closeAllPorts()
        openAllConfiguredPorts()
        sendConnectedDevicesResponse()
    }

    fun releaseAllPorts() {
        Log.i(TAG, "📤 Liberando todos los puertos seriales...")
        closeAllPorts()
        sendConnectedDevicesResponse()
        Log.i(TAG, "✅ Puertos liberados correctamente")
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    fun closeAllPorts() {
        Log.i(TAG, "🔒 Cerrando todos los puertos...")
        for (portPath in openStreams.keys.toList()) {
            try {
                isReadingActive[portPath] = false
                readingThreads[portPath]?.interrupt()
                val serialPort = serialPorts.remove(portPath)
                if (serialPort != null) {
                    // Cierra sus streams y el descriptor nativo del puerto
                    serialPort.close()
                } else {
                    openStreams[portPath]?.close()
                    openOutStreams[portPath]?.close()
                }
                openStreams.remove(portPath)
                openOutStreams.remove(portPath)
                Log.i(TAG, "✅ Puerto cerrado: $portPath")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al cerrar puerto $portPath: ${e.message}")
            }
        }
        openStreams.clear()
        openOutStreams.clear()
        serialPorts.clear()
        readingThreads.clear()
        isReadingActive.clear()
        activePorts.clear()
        assemblers.values.forEach { it.clear() }
        assemblers.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        // Dar de baja todos los receptores: si no, esta instancia sigue viva recibiendo órdenes
        val lbm = LocalBroadcastManager.getInstance(this)
        localReceivers.forEach { try { lbm.unregisterReceiver(it) } catch (_: Exception) {} }
        localReceivers.clear()
        usbOxygenManager?.destroy()
        usbOxygenManager = null
        closeAllPorts()
        frameScheduler.shutdownNow()
        Log.i(TAG, "Servicio destruido")
    }
}