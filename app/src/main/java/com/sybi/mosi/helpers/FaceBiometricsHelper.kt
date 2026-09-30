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
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

object FaceBiometricsHelper {

    private const val TAG = "FaceBiometricsHelper"

    // Tamaño canónico del rostro alineado (entrada de FaceNet)
    private const val ALIGNED_SIZE = 160

    // ============================================================
    // Índices de landmarks de MediaPipe
    // ============================================================
    // Si el modelo tiene iris (478 puntos), usamos las pupilas (468 y 473).
    // Si solo tiene 468, usamos los centros aproximados de los ojos (159 y 386).
    private const val LM_LEFT_PUPIL = 468
    private const val LM_RIGHT_PUPIL = 473

    private const val LM_LEFT_EYE_CENTER_FALLBACK = 159   // párpado superior izq
    private const val LM_RIGHT_EYE_CENTER_FALLBACK = 386  // párpado superior der

    private const val LM_NOSE_TIP = 1
    private const val LM_MOUTH_LEFT = 61
    private const val LM_MOUTH_RIGHT = 291
    private const val LM_CHIN = 152
    private const val LM_FOREHEAD = 10
    private const val LM_LEFT_CHEEK = 234
    private const val LM_RIGHT_CHEEK = 454

    // ============================================================
    // Puntos canónicos ArcFace (112x112) escalados a 160x160
    // Factor de escala: 160/112 = 1.428571
    // ============================================================
    private const val SCALE = 160f / 112f  // 1.428571f

    private val CANONICAL_POINTS = floatArrayOf(
        38.2946f * SCALE, 51.6963f * SCALE,   // ojo izquierdo
        73.5318f * SCALE, 51.5014f * SCALE,   // ojo derecho
        56.0252f * SCALE, 71.7366f * SCALE,   // nariz
        41.5493f * SCALE, 92.3655f * SCALE,   // comisura izquierda
        70.7299f * SCALE, 92.2041f * SCALE    // comisura derecha
    )

    data class BiometricFaceData(
        val originalBitmap: Bitmap,
        val alignedFaceBitmap: Bitmap,
        val embedding: FloatArray,
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

        fun lm(idx: Int): PointF = PointF(landmarks[idx].x() * w, landmarks[idx].y() * h)

        // Usar pupilas si están disponibles (478 landmarks), sino fallback
        val hasIris = landmarks.size >= 478
        val leftEye = if (hasIris) lm(LM_LEFT_PUPIL) else lm(LM_LEFT_EYE_CENTER_FALLBACK)
        val rightEye = if (hasIris) lm(LM_RIGHT_PUPIL) else lm(LM_RIGHT_EYE_CENTER_FALLBACK)
        val nose = lm(LM_NOSE_TIP)
        val mouthL = lm(LM_MOUTH_LEFT)
        val mouthR = lm(LM_MOUTH_RIGHT)

        // IPD
        val ipd = hypot(
            (rightEye.x - leftEye.x).toDouble(),
            (rightEye.y - leftEye.y).toDouble()
        ).toFloat()

        if (ipd < 25f) {
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
     * Verifica que la calidad del frame sea suficiente para un reconocimiento fiable.
     */
    fun isFaceQualityGood(
        landmarks: List<NormalizedLandmark>,
        bitmap: Bitmap
    ): Boolean {
        if (landmarks.size < 468) return false

        var minX = 1f; var maxX = 0f; var minY = 1f; var maxY = 0f
        for (lm in landmarks) {
            if (lm.x() < minX) minX = lm.x()
            if (lm.x() > maxX) maxX = lm.x()
            if (lm.y() < minY) minY = lm.y()
            if (lm.y() > maxY) maxY = lm.y()
        }
        val faceW = (maxX - minX) * bitmap.width
        val faceH = (maxY - minY) * bitmap.height

        // 1. Rostro suficientemente grande
        if (faceW < 100f || faceH < 100f) {
            Log.d(TAG, "Calidad: rostro pequeño ($faceW x $faceH)")
            return false
        }

        // 2. Centrado horizontal (±20% del centro)
        val faceCenterX = (minX + maxX) / 2f
        if (abs(faceCenterX - 0.5f) > 0.20f) {
            Log.d(TAG, "Calidad: descentrado (cx=$faceCenterX)")
            return false
        }

        // 3. Pose frontal: ángulo entre ojos
        val hasIris = landmarks.size >= 478
        val leftEye = if (hasIris) landmarks[LM_LEFT_PUPIL] else landmarks[LM_LEFT_EYE_CENTER_FALLBACK]
        val rightEye = if (hasIris) landmarks[LM_RIGHT_PUPIL] else landmarks[LM_RIGHT_EYE_CENTER_FALLBACK]
        val eyeAngle = atan2(
            (rightEye.y() - leftEye.y()).toDouble(),
            (rightEye.x() - leftEye.x()).toDouble()
        )
        if (abs(eyeAngle) > 0.20) {  // ~11.5°
            Log.d(TAG, "Calidad: cabeza inclinada ($eyeAngle rad)")
            return false
        }

        // 4. IPD mínima
        val ipdNorm = hypot(
            (rightEye.x() - leftEye.x()).toDouble(),
            (rightEye.y() - leftEye.y()).toDouble()
        ) * bitmap.width
        if (ipdNorm < 40f) {
            Log.d(TAG, "Calidad: IPD pequeña ($ipdNorm)")
            return false
        }

        return true
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
        val n = src.size / 2
        val rows = n * 2
        val cols = 6

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
     * Similitud coseno CRUDA entre dos embeddings normalizados L2.
     * Rango: -1..1
     *   Mismo sujeto (FaceNet/ArcFace): > 0.75
     *   Distinto: < 0.5
     */
    fun cosineSimilarity(e1: FloatArray, e2: FloatArray): Float {
        if (e1.size != e2.size) return -1f
        var dot = 0f
        for (i in e1.indices) dot += e1[i] * e2[i]
        return dot.coerceIn(-1f, 1f)
    }

    /**
     * Matching biométrico: similitud coseno CRUDA.
     * Umbral recomendado: 0.80 (mismo sujeto), rechazar < 0.60.
     */
    fun matchFaces(live: BiometricFaceData, stored: BiometricFaceData): Float {
        if (live.embedding.size != stored.embedding.size) {
            Log.e(TAG, "Embeddings de tamaño distinto: ${live.embedding.size} vs ${stored.embedding.size}")
            return -1f
        }
        val sim = cosineSimilarity(live.embedding, stored.embedding)
        Log.d(TAG, "Similitud coseno cruda: $sim")
        return sim
    }
}