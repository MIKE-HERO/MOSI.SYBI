package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Extrae embeddings faciales usando FaceNet (TFLite).
 * Input: bitmap alineado 160x160
 * Output: FloatArray normalizado L2
 *
 * IMPORTANTE: Verifica el tamaño real con el Log "Output shape real".
 * Si tu modelo es 512D, cambia EMBEDDING_DIM = 512.
 */
object FaceEmbeddingHelper {

    private const val TAG = "FaceEmbeddingHelper"
    private const val MODEL_ASSET = "facenet.tflite"
    private const val INPUT_SIZE = 160

    // ⚠️ Ajusta según tu modelo. Mira el Log al inicializar.
    // MobileFaceNet suele ser 192. FaceNet original: 512 o 128.
    private const val EMBEDDING_DIM = 128

    // Normalización: prueba A, B o C según el modelo.
    //   A: (pixel - 127.5) / 128.0   → FaceNet Keras original
    //   B: (pixel / 127.5) - 1.0     → equivalente a A
    //   C: pixel / 255.0             → MobileFaceNet / ArcFace
    private const val NORMALIZATION = "A"

    private var interpreter: Interpreter? = null

    fun init(context: Context) {
        if (interpreter != null) return
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_ASSET)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // setUseXNNPACK(true)
            }
            interpreter = Interpreter(model, options)

            val inShape = interpreter?.getInputTensor(0)?.shape()?.contentToString()
            val outShape = interpreter?.getOutputTensor(0)?.shape()?.contentToString()
            Log.d(TAG, "Input shape real:  $inShape")
            Log.d(TAG, "Output shape real: $outShape")
            Log.d(TAG, "EMBEDDING_DIM configurado: $EMBEDDING_DIM (debe coincidir con output)")

            // Sanity check
            val realDim = interpreter?.getOutputTensor(0)?.shape()?.lastOrNull()
            if (realDim != null && realDim != EMBEDDING_DIM) {
                Log.e(
                    TAG,
                    "⚠️ MISMATCH: modelo produce ${realDim}D pero configuraste ${EMBEDDING_DIM}D. " +
                            "Cambia EMBEDDING_DIM = $realDim"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar FaceNet: ${e.message}", e)
        }
    }

    /**
     * @param alignedFace Bitmap alineado (idealmente 160x160, se reescala si no lo es)
     * @return embedding normalizado L2, o null si falla
     */
    @Synchronized
    fun extractEmbedding(alignedFace: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.e(TAG, "Interpreter nulo. ¿Llamaste a init()?")
            return null
        }

        val scaled = if (alignedFace.width != INPUT_SIZE || alignedFace.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(alignedFace, INPUT_SIZE, INPUT_SIZE, true)
        } else alignedFace

        val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            when (NORMALIZATION) {
                "A" -> {
                    inputBuffer.putFloat((r - 127.5f) / 128f)
                    inputBuffer.putFloat((g - 127.5f) / 128f)
                    inputBuffer.putFloat((b - 127.5f) / 128f)
                }
                "B" -> {
                    inputBuffer.putFloat(r / 127.5f - 1f)
                    inputBuffer.putFloat(g / 127.5f - 1f)
                    inputBuffer.putFloat(b / 127.5f - 1f)
                }
                "C" -> {
                    inputBuffer.putFloat(r / 255f)
                    inputBuffer.putFloat(g / 255f)
                    inputBuffer.putFloat(b / 255f)
                }
                else -> {
                    inputBuffer.putFloat((r - 127.5f) / 128f)
                    inputBuffer.putFloat((g - 127.5f) / 128f)
                    inputBuffer.putFloat((b - 127.5f) / 128f)
                }
            }
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(EMBEDDING_DIM) }

        return try {
            interp.run(inputBuffer, output)
            val emb = output[0]

            // Normalización L2 (obligatoria para similitud coseno)
            var sumSq = 0.0
            for (v in emb) sumSq += (v * v).toDouble()
            val norm = sqrt(sumSq).toFloat()
            if (norm > 1e-6f) {
                for (i in emb.indices) emb[i] /= norm
            } else {
                Log.w(TAG, "Embedding con norma ~0")
                return null
            }
            emb
        } catch (e: Throwable) {
            Log.e(TAG, "Error extrayendo embedding: ${e.message}", e)
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }
}