package com.sybi.mosi.helpers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.io.OutputStream
import java.util.Calendar

class HWFatHelper(private val context: Context) {

    companion object {
        private const val TAG = "HWFatHelper"
    }

    private val dataBuffer = mutableListOf<Byte>()  // Buffer de todos los datos recibidos

    // Tiempos de espera
    private var noDataTimeoutMs: Long = 10000       // 10 segundos sin datos para reenviar
    private var maxRetries: Int = 1                 // Solo un reintento

    // Variables de resultados
    var lastHeight: Double? = null
    var lastWeight: Double? = null
    var lastIMC: Double? = null

    // Variables para composición corporal
    private var muscle = 0f
    private var boneMuscle = 0f
    private var metabolism = 0
    private var fatRate = 0f
    private var waterRate = 0f
    private var visceralFat = 0f
    private var bodyAge = 0
    private var bodyScore = 0
    private var idealWeight = 0f
    private var protein = 0f
    private var mineral = 0f
    private var bmi = 0f
    private var fat = 0f
    private var water = 0f
    private var notFat = 0f
    private var proteinRate = 0f

    // ✅ NUEVAS VARIABLES PARA CASOS FALTANTES
    private var cellFluidIn = 0f
    private var cellFluidEx = 0f
    private var whr = 0f
    private var controlMuscle = 0f
    private var controlWeight = 0f
    private var controlFat = 0f
    private var fatRateTrunk = 0f
    private var fatRateRightHand = 0f
    private var fatRateLeftHand = 0f
    private var fatRateRightFoot = 0f
    private var fatRateLeftFoot = 0f
    private var muscleTrunk = 0f
    private var muscleRightHand = 0f
    private var muscleLeftHand = 0f
    private var muscleRightFoot = 0f
    private var muscleLeftFoot = 0f
    private var fatType = 0

    // Variables públicas
    var lastFatRate: Double? = null
    var lastWaterRate: Double? = null
    var lastMuscle: Double? = null
    var lastMetabolism: Int? = null
    var lastBoneMuscle: Double? = null
    var lastVisceralFat: Double? = null
    var lastBodyAge: Int? = null
    var lastBodyScore: Int? = null
    var lastIdealWeight: Double? = null
    var lastProtein: Double? = null
    var lastMineral: Double? = null
    var lastFatKg: Double? = null
    var lastWaterKg: Double? = null
    var lastNotFat: Double? = null
    var lastProteinRate: Double? = null
    var lastBMI: Double? = null

    // ✅ NUEVAS VARIABLES PÚBLICAS PARA RESULTADOS FALTANTES
    var lastCellFluidIn: Double? = null
    var lastCellFluidEx: Double? = null
    var lastWhr: Double? = null
    var lastControlMuscle: Double? = null
    var lastControlWeight: Double? = null
    var lastControlFat: Double? = null
    var lastFatRateTrunk: Double? = null
    var lastFatRateRightHand: Double? = null
    var lastFatRateLeftHand: Double? = null
    var lastFatRateRightFoot: Double? = null
    var lastFatRateLeftFoot: Double? = null
    var lastMuscleTrunk: Double? = null
    var lastMuscleRightHand: Double? = null
    var lastMuscleLeftHand: Double? = null
    var lastMuscleRightFoot: Double? = null
    var lastMuscleLeftFoot: Double? = null
    var lastFatType: Int? = null

    private var alturaPesoCommandSent = false
    private var composicionCommandSent = false
    private var hasSentError = false
    private var lastDataTime: Long = 0
    private var errorThread: Thread? = null
    private var retryThread: Thread? = null
    private var isMeasurementActive = false
    private var retryCount = 0
    private var hasReceivedAnyData = false
    private var hasReceivedFinalCommand = false
    private var outputStream: OutputStream? = null
    private var currentPortPath: String = ""

    // ✅ Variable para fecha de nacimiento
    private var userBirthDate: String = "01/01/1990"
    private var userIsMan: Boolean = true

    private val mFatData = FatData()


    class FatData {
        var impedanceLeftHand = 0f
        var impedanceRightHand = 0f
        var impedanceLeftFoot = 0f
        var impedanceRightFoot = 0f
        var impedanceTrunk = 0f
        var muscle = 0f
        var mineral = 0f
        var boneMuscle = 0f
        var proteinRate = 0f
        var bmi = 0f
        var metabolism = 0
        var fatRate = 0f
        var waterRate = 0f
        var idealWeight = 0f
        var visceralFat = 0f
        var fat = 0f
        var water = 0f
        var notFat = 0f
        var protein = 0f
        var bodyAge = 0
        var bodyScore = 0

        // ✅ NUEVOS CAMPOS PARA CASOS FALTANTES
        var cellFluidIn = 0f
        var cellFluidEx = 0f
        var whr = 0f
        var controlMuscle = 0f
        var controlWeight = 0f
        var controlFat = 0f
        var fatRateTrunk = 0f
        var fatRateRightHand = 0f
        var fatRateLeftHand = 0f
        var fatRateRightFoot = 0f
        var fatRateLeftFoot = 0f
        var muscleTrunk = 0f
        var muscleRightHand = 0f
        var muscleLeftHand = 0f
        var muscleRightFoot = 0f
        var muscleLeftFoot = 0f
        var fatType = 0

        fun clean() {
            impedanceLeftHand = 0f
            impedanceRightHand = 0f
            impedanceLeftFoot = 0f
            impedanceRightFoot = 0f
            impedanceTrunk = 0f
            muscle = 0f
            mineral = 0f
            boneMuscle = 0f
            proteinRate = 0f
            bmi = 0f
            metabolism = 0
            fatRate = 0f
            waterRate = 0f
            idealWeight = 0f
            visceralFat = 0f
            fat = 0f
            water = 0f
            notFat = 0f
            protein = 0f
            bodyAge = 0
            bodyScore = 0

            // ✅ Limpiar nuevos campos
            cellFluidIn = 0f
            cellFluidEx = 0f
            whr = 0f
            controlMuscle = 0f
            controlWeight = 0f
            controlFat = 0f
            fatRateTrunk = 0f
            fatRateRightHand = 0f
            fatRateLeftHand = 0f
            fatRateRightFoot = 0f
            fatRateLeftFoot = 0f
            muscleTrunk = 0f
            muscleRightHand = 0f
            muscleLeftHand = 0f
            muscleRightFoot = 0f
            muscleLeftFoot = 0f
            fatType = 0
        }
    }

    ////////////////////////////////////////////////////////
    fun setUserData(birthDate: String, isMan: Boolean) {
        userBirthDate = birthDate
        userIsMan = isMan
        Log.d(TAG, "Datos usuario: FechaNacimiento=$birthDate, Hombre=$isMan, Edad=${calcularEdadDesdeFecha(userBirthDate)}")
    }

    fun resetCommandFlags() {
        alturaPesoCommandSent = false
        composicionCommandSent = false
        hasSentError = false
        cancelErrorThread()
        cancelRetryThread()
    }

    fun resetAlturaPesoResults() {
        lastHeight = null
        lastWeight = null
        lastIMC = null
        alturaPesoCommandSent = false
    }

    fun sendStopMeasureCommand(outStream: OutputStream, portPath: String) {
        try {
            val stopCommand = byteArrayOf(0x5B.toByte(), 0x04, 0x63, 0x38, 0xFA.toByte())
            outStream.write(stopCommand)
            outStream.flush()

            Log.d(TAG, "📤 Comando de parada enviado: ${bytesToHex(stopCommand)}")

            val txIntent = Intent("SERIAL_RAW_DATA")
            txIntent.putExtra("port", portPath)
            txIntent.putExtra("data", bytesToHex(stopCommand))
            txIntent.putExtra("type", "TX")
            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)

            alturaPesoCommandSent = false
            composicionCommandSent = false
            isMeasurementActive = false
            cancelErrorThread()
            cancelRetryThread()

        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar comando de parada: ${e.message}")
        }
    }

    fun sendAlturaPesoCommand(outStream: OutputStream, portPath: String) {
        // Si quedó una composición sin terminar, sus datos se interpretarían como composición y la
        // altura/peso nunca llegaría a la pantalla. Al pedir altura/peso, la composición ya no está en curso.
        if (isMeasurementActive) {
            Log.w(TAG, "⚠️ Composición anterior sin terminar: se cancela para medir altura/peso")
            isMeasurementActive = false
            composicionCommandSent = false
            hasReceivedFinalCommand = false
            cancelErrorThread()
            cancelRetryThread()
            stopFakeProgressTimer()
        }
        try {
            val readCommand = byteArrayOf(0x5B.toByte(), 0x04, 0x63, 0x37, 0xF9.toByte())
            outStream.write(readCommand)
            outStream.flush()
            alturaPesoCommandSent = true

            Log.d(TAG, "📤 Comando Altura/Peso enviado: ${bytesToHex(readCommand)}")

            val txIntent = Intent("SERIAL_RAW_DATA")
            txIntent.putExtra("port", portPath)
            txIntent.putExtra("data", bytesToHex(readCommand))
            txIntent.putExtra("type", "TX")
            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar comando Altura/Peso: ${e.message}")
        }
    }

    fun sendComposicionCommand(outStream: OutputStream, portPath: String): Boolean {
        if (lastHeight == null || lastWeight == null) {
            Log.d(TAG, "⚠️ No hay datos de altura y peso para composición")
            val intent = Intent("DEVICE_DATA_RECEIVED")
            intent.putExtra("type", "STATUS")
            intent.putExtra("data", "⚠️ Primero mide altura y peso")
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
            return false
        }

        try {
            // Guardar referencia al stream y puerto para reintentos
            outputStream = outStream
            currentPortPath = portPath

            // ✅ Limpiar buffer antes de iniciar
            dataBuffer.clear()

            resetFatData()
            
            // ✅ Enviar progreso inicial y arrancar hilo de progreso constante
            sendProgress(10)
            startFakeProgressTimer()

            hasSentError = false
            lastDataTime = System.currentTimeMillis()
            isMeasurementActive = true
            retryCount = 0
            hasReceivedAnyData = false
            hasReceivedFinalCommand = false
            cancelErrorThread()
            cancelRetryThread()

            // ✅ Usar edad calculada automáticamente
            val edad = calcularEdadDesdeFecha(userBirthDate)
            val cmdBytes = buildFatCommand(lastWeight!!, lastHeight!!, edad, userIsMan)
            val cmdString = bytesToHex(cmdBytes)

            outStream.write(cmdBytes)
            outStream.flush()
            composicionCommandSent = true

            Log.d(TAG, "📤 Comando Composición enviado: $cmdString")
            Log.d(TAG, "📤 Peso: $lastWeight kg, Altura: $lastHeight cm, Edad: $edad, Hombre: $userIsMan")
            Log.d(TAG, "📊 Estado inicial: isMeasurementActive=$isMeasurementActive, hasReceivedAnyData=$hasReceivedAnyData, retryCount=$retryCount")

            val txIntent = Intent("SERIAL_RAW_DATA")
            txIntent.putExtra("port", portPath)
            txIntent.putExtra("data", cmdString)
            txIntent.putExtra("type", "TX")
            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)

            // Iniciar temporizador para detectar si no hay datos
            startNoDataTimer()

            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar comando FAT: ${e.message}")
            composicionCommandSent = false
            isMeasurementActive = false
            return false
        }
    }

    private fun startNoDataTimer() {
        cancelRetryThread()

        retryThread = Thread {
            try {
                Thread.sleep(noDataTimeoutMs)

                Log.d(TAG, "⏰ Temporizador de $noDataTimeoutMs ms completado")
                Log.d(TAG, "📊 Estado: isMeasurementActive=$isMeasurementActive, hasReceivedAnyData=$hasReceivedAnyData, retryCount=$retryCount")

                // ✅ SOLO REENVIAR SI NO SE HA RECIBIDO NINGÚN DATO
                if (isMeasurementActive && !hasReceivedAnyData && retryCount < maxRetries) {
                    retryCount++
                    Log.d(TAG, "⏰ Sin datos recibidos, reintentando ($retryCount/$maxRetries)...")

                    // Reenviar comando
                    if (outputStream != null && lastWeight != null && lastHeight != null) {
                        val edad = calcularEdadDesdeFecha(userBirthDate)
                        val cmdBytes = buildFatCommand(lastWeight!!, lastHeight!!, edad, userIsMan)
                        val cmdString = bytesToHex(cmdBytes)

                        try {
                            outputStream!!.write(cmdBytes)
                            outputStream!!.flush()

                            Log.d(TAG, "📤 Comando reenviado: $cmdString")

                            val txIntent = Intent("SERIAL_RAW_DATA")
                            txIntent.putExtra("port", currentPortPath)
                            txIntent.putExtra("data", cmdString)
                            txIntent.putExtra("type", "TX")
                            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)

                            // Reiniciar temporizador
                            startNoDataTimer()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error al reenviar comando: ${e.message}")
                            sendComposicionError("Error al reenviar comando")
                        }
                    } else {
                        Log.e(TAG, "❌ No se puede reenviar: outputStream=$outputStream, lastWeight=$lastWeight, lastHeight=$lastHeight")
                        sendComposicionError("Error al reenviar comando")
                    }
                } else if (isMeasurementActive && !hasReceivedAnyData && retryCount >= maxRetries) {
                    // ✅ SOLO MARCAR ERROR SI NO SE RECIBIÓ NINGÚN DATO
                    Log.d(TAG, "❌ Sin datos después de $maxRetries reintentos")
                    sendComposicionError("No se recibieron datos del dispositivo")
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "⚠️ Temporizador interrumpido")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en temporizador: ${e.message}")
            }
        }
        retryThread?.start()
        Log.d(TAG, "⏱️ Temporizador de reintento iniciado")
    }

    private fun cancelRetryThread() {
        retryThread?.interrupt()
        retryThread = null
    }

    private fun cancelErrorThread() {
        errorThread?.interrupt()
        errorThread = null
    }

    fun parseData(data: ByteArray) {
        // ✅ USAR LA LÓGICA DEL FABRICANTE
        if (isMeasurementActive) {
            // ✅ IMPORTANTE: Actualizar flag ANTES de procesar
            hasReceivedAnyData = true
            lastDataTime = System.currentTimeMillis()

            // Procesar datos usando la lógica del fabricante
            var index = 0
            while (index < data.size) {
                if ((data[index].toInt() and 0xFF) == 0x28) {
                    if (data.size <= index + 1) {
                        return
                    }
                    val length = data[index + 1].toInt() and 0xFF
                    if (data.size <= index + length) {
                        return
                    }
                    val sum = calculateChecksum(data, index, length)
                    if (sum == (data[index + length].toInt() and 0xFF)) {
                        if (length > 2) {
                            analysis(data, index, length)
                        }
                        index += length
                    }
                }
                index++
            }
        } else {
            // ✅ NO ESTAMOS EN COMPOSICIÓN, PROCESAR ALTURA/PESO DIRECTAMENTE
            parseAlturaPesoData(data)
        }
    }

    private fun analysis(bytes: ByteArray, index: Int, length: Int) {
        val type = bytes[index + 2].toInt() and 0xFF
        val data = bytes[index + 3].toInt() and 0xFF

        // ✅ COMANDO FINAL (0x40)
        if (115 == type && length > 3) {
            if (61 == data && length == 5) {
                val data2 = bytes[index + 4].toInt() and 0xFF
                if (data2 == 64) {
                    // ✅ COMANDO FINAL - ENVIAR RESULTADOS INMEDIATAMENTE
                    Log.d(TAG, "✅ FAT: Comando final recibido (0x40)")
                    hasReceivedFinalCommand = true
                    cancelErrorThread()

                    // ✅ ENVIAR RESULTADOS CON mFatData
                    sendFatResult()
                    return
                }
            }
        }

        // ✅ DATOS DE PESO (0xD1)
        if (209 == type && length == 5) {
            return
        }

        // ✅ DATOS DE ALTURA (0xD2)
        if (210 == type && length == 5) {
            return
        }

        // ✅ DATOS DE COMPOSICIÓN (0xFA)
        if (250 == type && length > 4) {
            parseFatData(bytes, index, length)
        }
    }

    private fun parseAlturaPesoData(data: ByteArray) {
        var index = 0
        while (index < data.size) {
            if ((data[index].toInt() and 0xFF) == 0x28) {
                if (index + 2 < data.size) {
                    val length = data[index + 1].toInt() and 0xFF
                    val type = data[index + 2].toInt() and 0xFF

                    if (index + length < data.size) {
                        val checksum = calculateChecksum(data, index, length)
                        val receivedChecksum = data[index + length].toInt() and 0xFF

                        if (checksum == receivedChecksum && length > 2) {
                            when (type) {
                                0xD1 -> {
                                    if (length == 7) {
                                        val weightRaw = ((data[index + 3].toInt() and 0xFF) shl 24) or
                                                ((data[index + 4].toInt() and 0xFF) shl 16) or
                                                ((data[index + 5].toInt() and 0xFF) shl 8) or
                                                (data[index + 6].toInt() and 0xFF)
                                        lastWeight = weightRaw / 1000.0
                                        sendAlturaPesoResult()
                                    }
                                }
                                0xD2 -> {
                                    if (length == 5) {
                                        val heightRaw = ((data[index + 3].toInt() and 0xFF) shl 8) or
                                                (data[index + 4].toInt() and 0xFF)
                                        lastHeight = heightRaw / 10.0
                                        sendAlturaPesoResult()
                                    }
                                }
                            }
                            index += length
                        } else {
                            index++
                        }
                    } else {
                        index++
                    }
                } else {
                    index++
                }
            } else {
                index++
            }
        }
    }

    private fun sendAlturaPesoResult() {
        if (lastWeight != null && lastHeight != null) {
            lastIMC = lastWeight!! / ((lastHeight!! / 100.0) * (lastHeight!! / 100.0))

            val intent = Intent("DEVICE_DATA_RECEIVED")
            intent.putExtra("type", "ALTURA_PESO")
            intent.putExtra("height", lastHeight!!)
            intent.putExtra("weight", lastWeight!!)
            intent.putExtra("imc", lastIMC!!)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

            Log.d(TAG, "✅ Altura/Peso: $lastHeight cm, $lastWeight kg")
        }
    }

    // ✅ FUNCIÓN COMPLETA CON TODOS LOS CASOS POSIBLES DE COMPOSICIÓN
    private fun parseFatData(data: ByteArray, index: Int, length: Int) {
        if (length <= 4) return

        val dataType = data[index + 3].toInt() and 0xFF
        
        // ✅ Marcar si recibimos impedancias (IDs 0x01 a 0x05)
        if (dataType in 0x01..0x05) receivedImpedance = true

        // ✅ Calcular progreso lógico
        val progress = when (dataType) {
            in 0x01..0x05 -> 20 + (dataType * 5) // 25% a 45% (Impedancias)
            in 0x06..0x11 -> 45 + ((dataType - 0x05) * 3) // 48% a 81% (Básicos)
            in 0x12..0x25 -> 81 + (dataType - 0x11) // 82% a 94% (Segmentos)
            0x26 -> 96 // Último parámetro
            else -> 0
        }
        if (progress > 0) sendProgress(progress)

        when {
            length == 5 -> {
                when (dataType) {
                    0x10 -> {
                        mFatData.bodyAge = data[index + 4].toInt() and 0xFF
                        lastBodyAge = mFatData.bodyAge
                        Log.d(TAG, "FAT Body Age: ${mFatData.bodyAge}")
                    }
                    0x11 -> {
                        mFatData.bodyScore = data[index + 4].toInt() and 0xFF
                        lastBodyScore = mFatData.bodyScore
                        Log.d(TAG, "FAT Body Score: ${mFatData.bodyScore}")
                    }
                }
            }
            length == 6 -> {
                val value = ((data[index + 4].toInt() and 0xFF) shl 8) or (data[index + 5].toInt() and 0xFF)

                when (dataType) {
                    // ✅ Impedancias
                    0x01 -> { mFatData.impedanceLeftHand = value / 10.0f }
                    0x02 -> { mFatData.impedanceRightHand = value / 10.0f }
                    0x03 -> { mFatData.impedanceLeftFoot = value / 10.0f }
                    0x04 -> { mFatData.impedanceRightFoot = value / 10.0f }
                    0x05 -> { mFatData.impedanceTrunk = value / 10.0f }

                    // ✅ Músculo
                    0x06 -> {
                        mFatData.muscle = value / 10.0f
                        lastMuscle = mFatData.muscle.toDouble()
                        Log.d(TAG, "FAT Músculo: ${mFatData.muscle} kg")
                    }

                    // ✅ Minerales
                    0x07 -> {
                        mFatData.mineral = value / 10.0f
                        lastMineral = mFatData.mineral.toDouble()
                    }

                    // ✅ Masa Ósea
                    0x08 -> {
                        mFatData.boneMuscle = value / 10.0f
                        lastBoneMuscle = mFatData.boneMuscle.toDouble()
                    }

                    // ✅ Tasa de Proteína
                    0x09 -> {
                        mFatData.proteinRate = value / 10.0f
                        lastProteinRate = mFatData.proteinRate.toDouble()
                    }

                    // ✅ IMC
                    0x0A -> {
                        mFatData.bmi = value / 10.0f
                        lastBMI = mFatData.bmi.toDouble()
                    }

                    // ✅ Metabolismo
                    0x0B -> {
                        mFatData.metabolism = value
                        lastMetabolism = mFatData.metabolism
                        Log.d(TAG, "FAT Metabolismo: ${mFatData.metabolism} kcal")
                    }

                    // ✅ Tasa de Grasa
                    0x0C -> {
                        mFatData.fatRate = value / 10.0f
                        lastFatRate = mFatData.fatRate.toDouble()
                        Log.d(TAG, "FAT Grasa: ${mFatData.fatRate} %")
                    }

                    // ✅ Tasa de Agua
                    0x0D -> {
                        mFatData.waterRate = value / 10.0f
                        lastWaterRate = mFatData.waterRate.toDouble()
                        Log.d(TAG, "FAT Agua: ${mFatData.waterRate} %")
                    }

                    // ✅ Peso Ideal
                    0x0E -> {
                        mFatData.idealWeight = value / 10.0f
                        lastIdealWeight = mFatData.idealWeight.toDouble()
                    }

                    // ✅ Grasa Visceral
                    0x0F -> {
                        mFatData.visceralFat = value / 10.0f
                        lastVisceralFat = mFatData.visceralFat.toDouble()
                        Log.d(TAG, "FAT Grasa Visceral: ${mFatData.visceralFat}")
                    }

                    // ✅ Control de Músculo (con signo)
                    0x12 -> {
                        val b = data[index + 4]
                        val isSign = (b.toInt() and 0x80) == 0x80
                        var value16 = ((b.toInt() and 0x7F) shl 8) or (data[index + 5].toInt() and 0xFF)
                        if (isSign) value16 = -value16
                        mFatData.controlMuscle = value16 / 10.0f
                        lastControlMuscle = mFatData.controlMuscle.toDouble()
                    }

                    // ✅ Control de Peso (con signo)
                    0x13 -> {
                        val b = data[index + 4]
                        val isSign = (b.toInt() and 0x80) == 0x80
                        var value17 = ((b.toInt() and 0x7F) shl 8) or (data[index + 5].toInt() and 0xFF)
                        if (isSign) value17 = -value17
                        mFatData.controlWeight = value17 / 10.0f
                        lastControlWeight = mFatData.controlWeight.toDouble()
                    }

                    // ✅ Control de Grasa (con signo)
                    0x14 -> {
                        val b = data[index + 4]
                        val isSign = (b.toInt() and 0x80) == 0x80
                        var value18 = ((b.toInt() and 0x7F) shl 8) or (data[index + 5].toInt() and 0xFF)
                        if (isSign) value18 = -value18
                        mFatData.controlFat = value18 / 10.0f
                        lastControlFat = mFatData.controlFat.toDouble()
                    }

                    // ✅ Tasa de Grasa Tronco
                    0x15 -> {
                        mFatData.fatRateTrunk = value / 10.0f
                        lastFatRateTrunk = mFatData.fatRateTrunk.toDouble()
                    }

                    // ✅ Tasa de Grasa Mano Derecha
                    0x16 -> {
                        mFatData.fatRateRightHand = value / 10.0f
                        lastFatRateRightHand = mFatData.fatRateRightHand.toDouble()
                    }

                    // ✅ Tasa de Grasa Mano Izquierda
                    0x17 -> {
                        mFatData.fatRateLeftHand = value / 10.0f
                        lastFatRateLeftHand = mFatData.fatRateLeftHand.toDouble()
                    }

                    // ✅ Tasa de Grasa Pie Derecho
                    0x18 -> {
                        mFatData.fatRateRightFoot = value / 10.0f
                        lastFatRateRightFoot = mFatData.fatRateRightFoot.toDouble()
                    }

                    // ✅ Tasa de Grasa Pie Izquierdo
                    0x19 -> {
                        mFatData.fatRateLeftFoot = value / 10.0f
                        lastFatRateLeftFoot = mFatData.fatRateLeftFoot.toDouble()
                    }

                    // ✅ Músculo Tronco
                    0x1A -> {
                        mFatData.muscleTrunk = value / 10.0f
                        lastMuscleTrunk = mFatData.muscleTrunk.toDouble()
                    }

                    // ✅ Músculo Mano Derecha
                    0x1B -> {
                        mFatData.muscleRightHand = value / 10.0f
                        lastMuscleRightHand = mFatData.muscleRightHand.toDouble()
                    }

                    // ✅ Músculo Mano Izquierda
                    0x1C -> {
                        mFatData.muscleLeftHand = value / 10.0f
                        lastMuscleLeftHand = mFatData.muscleLeftHand.toDouble()
                    }

                    // ✅ Músculo Pie Derecho
                    0x1D -> {
                        mFatData.muscleRightFoot = value / 10.0f
                        lastMuscleRightFoot = mFatData.muscleRightFoot.toDouble()
                    }

                    // ✅ Músculo Pie Izquierdo
                    0x1E -> {
                        mFatData.muscleLeftFoot = value / 10.0f
                        lastMuscleLeftFoot = mFatData.muscleLeftFoot.toDouble()
                    }

                    // ✅ Relación Cintura-Cadera (WHR)
                    0x1F -> {
                        mFatData.whr = value / 100.0f
                        lastWhr = mFatData.whr.toDouble()
                    }

                    // ✅ Grasa (kg)
                    0x20 -> {
                        mFatData.fat = value / 10.0f
                        lastFatKg = mFatData.fat.toDouble()
                        Log.d(TAG, "FAT Fat: ${mFatData.fat} kg")
                    }

                    // ✅ Agua (kg)
                    0x21 -> {
                        mFatData.water = value / 10.0f
                        lastWaterKg = mFatData.water.toDouble()
                        Log.d(TAG, "FAT Water: ${mFatData.water} kg")
                    }

                    // ✅ Masa Libre de Grasa (kg)
                    0x22 -> {
                        mFatData.notFat = value / 10.0f
                        lastNotFat = mFatData.notFat.toDouble()
                    }

                    // ✅ Proteína (kg)
                    0x23 -> {
                        mFatData.protein = value / 10.0f
                        lastProtein = mFatData.protein.toDouble()
                    }

                    // ✅ Fluido Celular Interno
                    0x24 -> {
                        mFatData.cellFluidIn = value / 10.0f
                        lastCellFluidIn = mFatData.cellFluidIn.toDouble()
                    }

                    // ✅ Fluido Celular Externo
                    0x25 -> {
                        mFatData.cellFluidEx = value / 10.0f
                        lastCellFluidEx = mFatData.cellFluidEx.toDouble()
                    }

                    // ✅ Tipo de Grasa
                    0x26 -> {
                        mFatData.fatType = data[index + 5].toInt() and 0xFF
                        lastFatType = mFatData.fatType
                    }
                }
            }
        }
    }

    fun calcularEdadDesdeFecha(fechaNacimiento: String): Int {
        return try {
            val partes = fechaNacimiento.split("/")
            val dia = partes[0].toInt()
            val mes = partes[1].toInt()
            val año = partes[2].toInt()

            val calendario = Calendar.getInstance()
            val añoActual = calendario.get(Calendar.YEAR)
            val mesActual = calendario.get(Calendar.MONTH) + 1
            val diaActual = calendario.get(Calendar.DAY_OF_MONTH)

            var edad = añoActual - año
            if (mesActual < mes || (mesActual == mes && diaActual < dia)) {
                edad--
            }
            edad
        } catch (e: Exception) {
            30 // Valor por defecto
        }
    }

    private fun sendComposicionError(message: String) {
        if (hasSentError) return
        
        stopFakeProgressTimer()

        val intent = Intent("DEVICE_DATA_RECEIVED")
        intent.putExtra("type", "COMPOSICION_ERROR")
        intent.putExtra("message", message)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

        hasSentError = true
        isMeasurementActive = false
        hasReceivedFinalCommand = false
        cancelErrorThread()
        cancelRetryThread()

        Log.d(TAG, "❌ Error enviado: $message")
    }

    private fun sendFatResult() {
        stopFakeProgressTimer()
        
        // ✅ Enviar progreso final
        sendProgress(100)

        // ✅ ENVIAR TODOS LOS RESULTADOS SIN CONDICIONES
        if (mFatData.fatRate <= 0 && mFatData.fat <= 0) {
            Log.d(TAG, "❌ No se envían resultados: no hay datos de grasa")
            // La medición terminó (llegó el comando final): cerrarla aunque no haya datos,
            // para no dejar el helper en modo composición y avisar a la pantalla.
            isMeasurementActive = false
            hasSentError = false
            sendComposicionError("No se recibieron datos de composición")
            return
        }

        // Cancelar threads
        cancelErrorThread()
        cancelRetryThread()
        hasSentError = false
        isMeasurementActive = false

        val intent = Intent("DEVICE_DATA_RECEIVED")
        intent.putExtra("type", "COMPOSICION")

        // ✅ Datos básicos
        intent.putExtra("fat_rate", mFatData.fatRate.toDouble())
        intent.putExtra("water_rate", mFatData.waterRate.toDouble())
        intent.putExtra("muscle", mFatData.muscle.toDouble())
        intent.putExtra("metabolism", mFatData.metabolism)
        intent.putExtra("visceral_fat", mFatData.visceralFat.toDouble())
        intent.putExtra("bone_muscle", mFatData.boneMuscle.toDouble())
        intent.putExtra("body_age", mFatData.bodyAge)
        intent.putExtra("body_score", mFatData.bodyScore)
        intent.putExtra("ideal_weight", mFatData.idealWeight.toDouble())
        intent.putExtra("protein", mFatData.protein.toDouble())
        intent.putExtra("mineral", mFatData.mineral.toDouble())
        intent.putExtra("fat", mFatData.fat.toDouble())
        intent.putExtra("water", mFatData.water.toDouble())
        intent.putExtra("not_fat", mFatData.notFat.toDouble())
        intent.putExtra("protein_rate", mFatData.proteinRate.toDouble())
        intent.putExtra("bmi", mFatData.bmi.toDouble())

        // ✅ Datos adicionales (nuevos)
        intent.putExtra("cell_fluid_in", mFatData.cellFluidIn.toDouble())
        intent.putExtra("cell_fluid_ex", mFatData.cellFluidEx.toDouble())
        intent.putExtra("whr", mFatData.whr.toDouble())
        intent.putExtra("control_muscle", mFatData.controlMuscle.toDouble())
        intent.putExtra("control_weight", mFatData.controlWeight.toDouble())
        intent.putExtra("control_fat", mFatData.controlFat.toDouble())
        intent.putExtra("fat_rate_trunk", mFatData.fatRateTrunk.toDouble())
        intent.putExtra("fat_rate_right_hand", mFatData.fatRateRightHand.toDouble())
        intent.putExtra("fat_rate_left_hand", mFatData.fatRateLeftHand.toDouble())
        intent.putExtra("fat_rate_right_foot", mFatData.fatRateRightFoot.toDouble())
        intent.putExtra("fat_rate_left_foot", mFatData.fatRateLeftFoot.toDouble())
        intent.putExtra("muscle_trunk", mFatData.muscleTrunk.toDouble())
        intent.putExtra("muscle_right_hand", mFatData.muscleRightHand.toDouble())
        intent.putExtra("muscle_left_hand", mFatData.muscleLeftHand.toDouble())
        intent.putExtra("muscle_right_foot", mFatData.muscleRightFoot.toDouble())
        intent.putExtra("muscle_left_foot", mFatData.muscleLeftFoot.toDouble())
        intent.putExtra("fat_type", mFatData.fatType)

        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        Log.d(TAG, "📤 Broadcast COMPOSICION enviado")
        Log.d(TAG, "📊 Datos enviados: fatRate=${mFatData.fatRate}, waterRate=${mFatData.waterRate}, fat=${mFatData.fat}, water=${mFatData.water}, muscle=${mFatData.muscle}, metabolism=${mFatData.metabolism}, visceralFat=${mFatData.visceralFat}, protein=${mFatData.protein}, mineral=${mFatData.mineral}, notFat=${mFatData.notFat}, proteinRate=${mFatData.proteinRate}, bmi=${mFatData.bmi}")

        // Resetear flags
        hasReceivedFinalCommand = false
        hasReceivedAnyData = false
    }

    private fun buildFatCommand(weight: Double, height: Double, age: Int, isMan: Boolean): ByteArray {
        val bs = byteArrayOf(
            0x5B, 0x0C, 0x63, 0x3C,
            0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B, 0x0C
        )

        val w = (weight * 1000).toInt()
        bs[7] = (w and 0xFF).toByte()
        bs[6] = ((w shr 8) and 0xFF).toByte()
        bs[5] = ((w shr 16) and 0xFF).toByte()
        bs[4] = ((w shr 24) and 0xFF).toByte()

        val h = (height * 10).toInt()
        bs[9] = (h and 0xFF).toByte()
        bs[8] = ((h shr 8) and 0xFF).toByte()

        bs[10] = if (isMan) 1 else 0
        bs[11] = (age and 0xFF).toByte()
        bs[12] = (calculateChecksum(bs, 0, 12) and 0xFF).toByte()

        return bs
    }

    private fun calculateChecksum(data: ByteArray, start: Int, length: Int): Int {
        var sum = 0
        for (i in start until start + length) {
            sum = (sum + (data[i].toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    private var currentComposicionProgress = 0
    private var receivedImpedance = false
    private var progressThread: Thread? = null

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun sendProgress(percent: Int) {
        var finalPercent = percent
        
        // ✅ Evitar que el progreso retroceda
        if (finalPercent <= currentComposicionProgress && finalPercent < 100) return
        
        // ✅ Filtro: Si no hemos recibido impedancia, no pasar de 20% mediante datos serie
        // (El timer automático sí podrá pasarlo después)
        if (!receivedImpedance && finalPercent > 20 && finalPercent < 100) {
            // Si el timer ya va más adelante, no forzar 15
            if (currentComposicionProgress > 15) return
            finalPercent = 15
        }

        currentComposicionProgress = finalPercent
        
        val intent = Intent("DEVICE_DATA_RECEIVED")
        intent.putExtra("type", "COMPOSICION_PROGRESS")
        intent.putExtra("progress", finalPercent)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    private fun startFakeProgressTimer() {
        stopFakeProgressTimer()
        progressThread = Thread {
            try {
                // Pequeña espera inicial
                Thread.sleep(2000)
                while (isMeasurementActive && currentComposicionProgress < 95) {
                    Thread.sleep(1000) // Incrementar cada segundo
                    if (isMeasurementActive) {
                        val nextProgress = currentComposicionProgress + 5
                        if (nextProgress <= 95) {
                            sendProgress(nextProgress)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Timer detenido
            }
        }
        progressThread?.start()
    }

    private fun stopFakeProgressTimer() {
        progressThread?.interrupt()
        progressThread = null
    }

    private fun resetFatData() {
        mFatData.clean()
        currentComposicionProgress = 0
        receivedImpedance = false
        stopFakeProgressTimer()

        // ✅ Limpiar variables locales
        muscle = 0f
        boneMuscle = 0f
        metabolism = 0
        fatRate = 0f
        waterRate = 0f
        visceralFat = 0f
        bodyAge = 0
        bodyScore = 0
        idealWeight = 0f
        protein = 0f
        mineral = 0f
        bmi = 0f
        fat = 0f
        water = 0f
        notFat = 0f
        proteinRate = 0f

        // ✅ Limpiar nuevas variables locales
        cellFluidIn = 0f
        cellFluidEx = 0f
        whr = 0f
        controlMuscle = 0f
        controlWeight = 0f
        controlFat = 0f
        fatRateTrunk = 0f
        fatRateRightHand = 0f
        fatRateLeftHand = 0f
        fatRateRightFoot = 0f
        fatRateLeftFoot = 0f
        muscleTrunk = 0f
        muscleRightHand = 0f
        muscleLeftHand = 0f
        muscleRightFoot = 0f
        muscleLeftFoot = 0f
        fatType = 0

        // ✅ Limpiar buffer
        dataBuffer.clear()
    }

    fun resetAllResults() {
        // ✅ Resetear altura/peso
        lastHeight = null
        lastWeight = null
        lastIMC = null

        // ✅ Resetear composición corporal
        lastFatRate = null
        lastWaterRate = null
        lastFatKg = null
        lastWaterKg = null
        lastNotFat = null
        lastMuscle = null
        lastIdealWeight = null
        lastProtein = null
        lastMineral = null
        lastMetabolism = null
        lastBoneMuscle = null
        lastVisceralFat = null
        lastBodyAge = null
        lastBodyScore = null
        lastProteinRate = null
        lastBMI = null

        // ✅ Resetear nuevas variables de composición
        lastCellFluidIn = null
        lastCellFluidEx = null
        lastWhr = null
        lastControlMuscle = null
        lastControlWeight = null
        lastControlFat = null
        lastFatRateTrunk = null
        lastFatRateRightHand = null
        lastFatRateLeftHand = null
        lastFatRateRightFoot = null
        lastFatRateLeftFoot = null
        lastMuscleTrunk = null
        lastMuscleRightHand = null
        lastMuscleLeftHand = null
        lastMuscleRightFoot = null
        lastMuscleLeftFoot = null
        lastFatType = null

        // ✅ Resetear variables internas de composición
        resetFatData()
        resetCommandFlags()
    }

    // ============================================================
// CALIBRACIÓN
// ============================================================

    /**
     * Envía el comando de calibración de peso.
     * @param weight Peso real del objeto patrón en kg (ej: 50.0)
     */
    fun calibrateWeight(outStream: OutputStream, portPath: String, weight: Double): Boolean {
        return try {
            val bs = byteArrayOf(0x5B, 0x08, 0x63, 0x2C, 0, 0, 0, 0, 0)
            val w = (weight * 1000).toInt()
            bs[7] = (w and 0xFF).toByte()
            bs[6] = ((w shr 8) and 0xFF).toByte()
            bs[5] = ((w shr 16) and 0xFF).toByte()
            bs[4] = ((w shr 24) and 0xFF).toByte()
            bs[8] = (calculateChecksum(bs, 0, 8) and 0xFF).toByte()

            outStream.write(bs)
            outStream.flush()

            Log.d(TAG, "📤 Comando CALIBRACIÓN PESO enviado: ${bytesToHex(bs)} (${weight} kg)")

            val txIntent = Intent("SERIAL_RAW_DATA")
            txIntent.putExtra("port", portPath)
            txIntent.putExtra("data", bytesToHex(bs))
            txIntent.putExtra("type", "TX")
            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al calibrar peso: ${e.message}")
            false
        }
    }

    /**
     * Envía el comando de calibración de altura.
     * @param height Altura real del objeto patrón en cm (ej: 170.0)
     */
    fun calibrateHeight(outStream: OutputStream, portPath: String, height: Double): Boolean {
        return try {
            val bs = byteArrayOf(0x5B, 0x06, 0x63, 0x1D, 0, 0, 0)
            val h = (height * 10).toInt()
            bs[5] = (h and 0xFF).toByte()
            bs[4] = ((h shr 8) and 0xFF).toByte()
            bs[6] = (calculateChecksum(bs, 0, 6) and 0xFF).toByte()

            outStream.write(bs)
            outStream.flush()

            Log.d(TAG, "📤 Comando CALIBRACIÓN ALTURA enviado: ${bytesToHex(bs)} (${height} cm)")

            val txIntent = Intent("SERIAL_RAW_DATA")
            txIntent.putExtra("port", portPath)
            txIntent.putExtra("data", bytesToHex(bs))
            txIntent.putExtra("type", "TX")
            LocalBroadcastManager.getInstance(context).sendBroadcast(txIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error al calibrar altura: ${e.message}")
            false
        }
    }

    /**
     * Interpreta los bytes recibidos durante la calibración.
     * Debe llamarse desde parseData() cuando NO estamos en medición.
     */
    fun parseCalibrationData(data: ByteArray, onResult: (CalibrationResult) -> Unit) {
        var index = 0
        while (index < data.size) {
            if ((data[index].toInt() and 0xFF) == 0x28) {
                if (index + 2 >= data.size) break
                val length = data[index + 1].toInt() and 0xFF
                if (index + length >= data.size) break

                val checksum = calculateChecksum(data, index, length)
                if (checksum != (data[index + length].toInt() and 0xFF)) {
                    index++
                    continue
                }

                val type = data[index + 2].toInt() and 0xFF
                val subType = data[index + 3].toInt() and 0xFF

                // type 0x73 = 's' = 115 (respuestas del dispositivo)
                if (type == 115 && length >= 4) {
                    when (subType) {
                        45 -> { // 0x2D CALIBRATION_WEIGHT
                            onResult(CalibrationResult(CalibrationType.WEIGHT, CalibrationStatus.OK))
                            return
                        }
                        30 -> { // 0x1E CALIBRATION_HEIGHT
                            onResult(CalibrationResult(CalibrationType.HEIGHT, CalibrationStatus.OK))
                            return
                        }
                        67 -> { // 0x43 FAT_ERR
                            onResult(CalibrationResult(CalibrationType.UNKNOWN, CalibrationStatus.ERROR))
                            return
                        }
                    }
                }
                index += length
            } else {
                index++
            }
        }
    }

    enum class CalibrationType { WEIGHT, HEIGHT, UNKNOWN }
    enum class CalibrationStatus { OK, ERROR }
    data class CalibrationResult(val type: CalibrationType, val status: CalibrationStatus)
}