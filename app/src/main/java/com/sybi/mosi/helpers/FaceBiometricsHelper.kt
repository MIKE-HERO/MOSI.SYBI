package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

object FaceBiometricsHelper {

    private const val TAG = "FaceBiometricsHelper"

    // Tamaño canónico del rostro alineado (entrada de FaceNet)
    private const val ALIGNED_SIZE = 160

    // Índices de landmarks de MediaPipe (478 puntos)
    // Referencia: https://github.com/google/mediapipe/blob/master/mediapipe/modules/face_geometry/data/canonical_face_model_uv_visualization.png
    private const val LM_LEFT_EYE_OUTER = 33
    private const val LM_RIGHT_EYE_OUTER = 263
    private const val LM_NOSE_TIP = 1
    private const val LM_MOUTH_LEFT = 61
    private const val LM_MOUTH_RIGHT = 291
    private const val LM_CHIN = 152
    private const val LM_FOREHEAD = 10
    private const val LM_LEFT_CHEEK = 234
    private const val LM_RIGHT_CHEEK = 454

    // Puntos canónicos para alineación afín (ArcFace 112x112, escalados a 160x160)
    // Los originales son para 112x112; multiplicamos por 160/112 = 1.4286
    private val CANONICAL_POINTS = floatArrayOf(
        54.706f, 73.851f,   // ojo izquierdo
        105.046f, 73.573f,  // ojo derecho
        80.036f, 102.481f,  // nariz
        59.356f, 131.951f,  // comisura izquierda
        101.043f, 131.72f   // comisura derecha
    )

    data class BiometricFaceData(
        val originalBitmap: Bitmap,
        val alignedFaceBitmap: Bitmap,
        val embedding: FloatArray,   // Reemplaza al vector 20D
        val ipd: Float,
        val landmarks: List<NormalizedLandmark>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as BiometricFaceData
            return embedding.contentEquals(other.embedding)
        }
        override fun hashCode(): Int = embedding.contentHashCode()
    }

    /**
     * Inicializa los modelos. Llama a esto en el onCreate de tu Activity/Application.
     */
    fun init(context: Context) {
        MediaPipeFaceHelper.init(context)
        FaceEmbeddingHelper.init(context)
    }

    /**
     * Pipeline completo:
     * 1. Detecta landmarks con MediaPipe
     * 2. Alinea el rostro a 160x160 con transformación afín
     * 3. Extrae embedding con FaceNet
     */
    fun processFace(bitmap: Bitmap, mpResult: FaceLandmarkerResult): BiometricFaceData? {
        val landmarks = mpResult.faceLandmarks().firstOrNull()
        if (landmarks == null || landmarks.size < 468) {
            Log.w(TAG, "Landmarks insuficientes: ${landmarks?.size}")
            return null
        }

        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        // Convertir landmarks normalizados (0..1) a píxeles
        fun lm(idx: Int): PointF = PointF(landmarks[idx].x() * w, landmarks[idx].y() * h)

        val leftEye = lm(LM_LEFT_EYE_OUTER)
        val rightEye = lm(LM_RIGHT_EYE_OUTER)
        val nose = lm(LM_NOSE_TIP)
        val mouthL = lm(LM_MOUTH_LEFT)
        val mouthR = lm(LM_MOUTH_RIGHT)

        // IPD
        val ipd = hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble()
        ).toFloat()

        if (ipd < 20f) {
            Log.w(TAG, "IPD muy pequeña: $ipd")
            return null
        }

        // Alineación afín → 160x160
        val aligned = alignFace(bitmap, leftEye, rightEye, nose, mouthL, mouthR)
            ?: return null

        // Embedding
        val embedding = FaceEmbeddingHelper.extractEmbedding(aligned)
            ?: return null

        return BiometricFaceData(
            originalBitmap = bitmap,
            alignedFaceBitmap = aligned,
            embedding = embedding,
            ipd = ipd,
            landmarks = landmarks
        )
    }

    /**
     * Alineación afín con 5 puntos (ArcFace-style).
     * Genera un bitmap 160x160 con el rostro centrado, ojos horizontales.
     */
    private fun alignFace(
        src: Bitmap,
        leftEye: PointF, rightEye: PointF,
        nose: PointF, mouthL: PointF, mouthR: PointF
    ): Bitmap? {
        val srcPts = floatArrayOf(
            leftEye.x, leftEye.y,
            rightEye.x, rightEye.y,
            nose.x, nose.y,
            mouthL.x, mouthL.y,
            mouthR.x, mouthR.y
        )

        val matrix = estimateAffine(srcPts, CANONICAL_POINTS) ?: return null

        return try {
            val output = Bitmap.createBitmap(ALIGNED_SIZE, ALIGNED_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(src, matrix, paint)
            output
        } catch (e: Exception) {
            Log.e(TAG, "Error alineando: ${e.message}", e)
            null
        }
    }

    /**
     * Estima matriz afín 2x3 por mínimos cuadrados (5 correspondencias).
     */
    private fun estimateAffine(src: FloatArray, dst: FloatArray): Matrix? {
        val n = src.size / 2  // 5 puntos
        val rows = n * 2      // 10 ecuaciones
        val cols = 6          // a, b, c, d, e, f

        // Construir A (10x6) y b (10)
        val A = Array(rows) { DoubleArray(cols) }
        val b = DoubleArray(rows)

        for (i in 0 until n) {
            val sx = src[i * 2].toDouble()
            val sy = src[i * 2 + 1].toDouble()
            val dx = dst[i * 2].toDouble()
            val dy = dst[i * 2 + 1].toDouble()

            A[i * 2]     = doubleArrayOf(sx, sy, 1.0, 0.0, 0.0, 0.0)
            A[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, sx, sy, 1.0)
            b[i * 2]     = dx
            b[i * 2 + 1] = dy
        }

        val params = solveLeastSquares(A, b) ?: return null

        return Matrix().apply {
            setValues(floatArrayOf(
                params[0].toFloat(), params[1].toFloat(), params[2].toFloat(),
                params[3].toFloat(), params[4].toFloat(), params[5].toFloat(),
                0f, 0f, 1f
            ))
        }
    }

    /** Resuelve A·x = b por ecuaciones normales con Gauss-Jordan. */
    private fun solveLeastSquares(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = A.size
        val n = A[0].size
        val AtA = Array(n) { DoubleArray(n) }
        val Atb = DoubleArray(n)

        for (i in 0 until n) {
            for (j in 0 until n) {
                var s = 0.0
                for (k in 0 until m) s += A[k][i] * A[k][j]
                AtA[i][j] = s
            }
            var s = 0.0
            for (k in 0 until m) s += A[k][i] * b[k]
            Atb[i] = s
        }

        // Gauss-Jordan con pivoteo
        val aug = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) aug[i][j] = AtA[i][j]
            aug[i][n] = Atb[i]
        }

        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n)
                if (abs(aug[r][col]) > abs(aug[piv][col])) piv = r
            if (abs(aug[piv][col]) < 1e-10) return null
            val tmp = aug[col]; aug[col] = aug[piv]; aug[piv] = tmp

            val div = aug[col][col]
            for (j in col..n) aug[col][j] /= div
            for (r in 0 until n) {
                if (r == col) continue
                val f = aug[r][col]
                for (j in col..n) aug[r][j] -= f * aug[col][j]
            }
        }
        return DoubleArray(n) { aug[it][n] }
    }

    /**
     * Similitud coseno entre dos embeddings (ambos normalizados L2).
     * Retorna 0..1 (1 = idénticos).
     */
    fun cosineSimilarity(e1: FloatArray, e2: FloatArray): Float {
        if (e1.size != e2.size) return 0f
        var dot = 0f
        for (i in e1.indices) dot += e1[i] * e2[i]
        return ((dot + 1f) / 2f).coerceIn(0f, 1f)
    }

    /**
     * Matching biométrico: similitud coseno pura.
     * Umbral recomendado para FaceNet: 0.72 (equivale a coseno crudo ~0.44).
     */
    fun matchFaces(live: BiometricFaceData, stored: BiometricFaceData): Float {
        if (live.embedding.size != stored.embedding.size) {
            Log.e(TAG, "Embeddings de tamaño distinto: ${live.embedding.size} vs ${stored.embedding.size}")
            return 0f
        }
        val sim = cosineSimilarity(live.embedding, stored.embedding)
        Log.d(TAG, "Similitud coseno: $sim")
        return sim
    }
}