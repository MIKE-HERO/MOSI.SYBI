package com.sybi.mosi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat

/**
 * Captura el micrófono directamente con AudioRecord y entrega los frames como PCM de 16 bits
 * mono, para esquivar un bug de este WebView: `getUserMedia({audio:true})` se cuelga sin resolver
 * ni rechazar nunca porque `onPermissionRequest` jamás llega a invocarse para la solicitud (ver
 * TelemedicineActivity.camaraQueRequierePuente, mismo tipo de equipo/WebView limitado).
 *
 * Los frames se entregan por [onFrame] para que quien los reciba arme un MediaStreamTrack de
 * audio con la Web Audio API (AudioContext + ScriptProcessorNode -> MediaStreamAudioDestinationNode),
 * en vez de pedirle el micrófono al WebView.
 */
class MicrofonoPuenteNativo(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val MS_POR_BLOQUE = 40
    }

    private var audioRecord: AudioRecord? = null
    private var hiloFondo: HandlerThread? = null
    private var activo = false

    fun iniciar(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
        if (activo) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Sin permiso de micrófono")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            onError("Tasa de muestreo no soportada por el micrófono ($minBuffer)")
            return
        }

        // VOICE_COMMUNICATION trae cancelación de eco/ruido en la mayoría de los equipos, pero no
        // está garantizada; si el HAL de audio no la soporta, se reintenta con el micrófono normal.
        fun crear(fuente: Int) = try {
            AudioRecord(fuente, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
        } catch (e: Exception) {
            null
        }

        var record = crear(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            record = crear(MediaRecorder.AudioSource.MIC)
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            onError("AudioRecord no se pudo inicializar")
            return
        }
        val grabador: AudioRecord = record

        activo = true
        audioRecord = grabador
        val hilo = HandlerThread("MicrofonoPuenteNativo").apply { start() }
        hiloFondo = hilo
        Handler(hilo.looper).post {
            try {
                grabador.startRecording()
            } catch (e: Exception) {
                onError("No se pudo iniciar la grabación: ${e.message}")
                return@post
            }
            // ~40 ms por bloque: poca latencia sin saturar el bridge JS con demasiadas llamadas/segundo.
            val bloque = ShortArray(SAMPLE_RATE * MS_POR_BLOQUE / 1000)
            while (activo) {
                val leidos = grabador.read(bloque, 0, bloque.size)
                if (leidos <= 0) continue
                val bytes = ByteArray(leidos * 2)
                for (i in 0 until leidos) {
                    val s = bloque[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                onFrame(bytes)
            }
        }
    }

    fun detener() {
        if (!activo) return
        activo = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        hiloFondo?.quitSafely()
        hiloFondo = null
    }
}
