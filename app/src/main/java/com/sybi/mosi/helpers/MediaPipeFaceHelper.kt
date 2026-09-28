package com.sybi.mosi.helpers

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Wrapper de MediaPipe Face Landmarker.
 * Detecta el rostro y devuelve 478 landmarks + blendshapes.
 */
object MediaPipeFaceHelper {

    private const val TAG = "MediaPipeFaceHelper"
    private const val MODEL_ASSET = "face_landmarker.task"

    private var faceLandmarker: FaceLandmarker? = null

    fun init(context: Context) {
        if (faceLandmarker != null) return
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setOutputFaceBlendshapes(false)   // no necesitamos blendshapes
                .setOutputFacialTransformationMatrixes(false)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "MediaPipe Face Landmarker inicializado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar MediaPipe: ${e.message}", e)
        }
    }

    /**
     * Detecta el rostro y devuelve sus landmarks.
     * Devuelve null si no hay rostro o si MediaPipe falla.
     */
    fun detect(bitmap: Bitmap): FaceLandmarkerResult? {
        val landmarker = faceLandmarker ?: return null
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detect(mpImage)
        } catch (e: Exception) {
            Log.e(TAG, "Error detectando rostro: ${e.message}", e)
            null
        }
    }

    fun close() {
        try { faceLandmarker?.close() } catch (_: Exception) {}
        faceLandmarker = null
    }
}