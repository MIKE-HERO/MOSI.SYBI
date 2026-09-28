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
 * Output: FloatArray de 512 dimensiones (normalizado L2)
 */
object FaceEmbeddingHelper {

    private const val TAG = "FaceEmbeddingHelper"
    private const val MODEL_ASSET = "facenet.tflite"
    private const val INPUT_SIZE = 160
    private const val EMBEDDING_DIM = 128   // FaceNet típico. Si tu modelo es 128, cámbialo.

    private var interpreter: Interpreter? = null

    fun init(context: Context) {
        if (interpreter != null) return
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_ASSET)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // setUseXNNPACK(true)  // descomenta si tu TFLite lo soporta
            }
            interpreter = Interpreter(model, options)
            Log.d(TAG, "FaceNet inicializado. Input: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            Log.d(TAG, "FaceNet output: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar FaceNet: ${e.message}", e)
        }
    }

    /**
     * @param alignedFace Bitmap alineado (idealmente 160x160, se reescala si no lo es)
     * @return embedding 512D normalizado L2, o null si falla
     */
    fun extractEmbedding(alignedFace: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.e(TAG, "Interpreter nulo. ¿Llamaste a init()?")
            return null
        }

        val scaled = if (alignedFace.width != INPUT_SIZE || alignedFace.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(alignedFace, INPUT_SIZE, INPUT_SIZE, true)
        } else alignedFace

        // Buffer: 160 * 160 * 3 canales * 4 bytes float
        val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Normalización FaceNet: (pixel - 127.5) / 128.0  -> rango [-1, 1]
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = (pixel and 0xFF)
            inputBuffer.putFloat((r - 127.5f) / 128f)
            inputBuffer.putFloat((g - 127.5f) / 128f)
            inputBuffer.putFloat((b - 127.5f) / 128f)
        }
        inputBuffer.rewind()

        // Output: [1, 512]
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }

        return try {
            interp.run(inputBuffer, output)
            val emb = output[0]

            // Normalización L2 (importante para similitud coseno)
            var sumSq = 0.0
            for (v in emb) sumSq += (v * v).toDouble()
            val norm = sqrt(sumSq).toFloat()
            if (norm > 1e-6f) {
                for (i in emb.indices) emb[i] /= norm
            }
            emb
        } catch (e: Exception) {
            Log.e(TAG, "Error extrayendo embedding: ${e.message}", e)
            null
        }
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }
}