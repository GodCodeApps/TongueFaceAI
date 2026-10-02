package com.tongue.face.ai.facelandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/** Small wrapper around the MediaPipe Face Landmarker task for live camera frames. */
class FaceLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener,
    private val runningMode: RunningMode = RunningMode.LIVE_STREAM
) {

    private var faceLandmarker: FaceLandmarker? = null

    init {
        setupFaceLandmarker()
    }

    fun setupFaceLandmarker() {
        clearFaceLandmarker()
        val baseOptions = BaseOptions.builder()
            .setDelegate(Delegate.CPU)
            .setModelAssetPath(MP_FACE_LANDMARKER_TASK)
            .build()

        try {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinFaceDetectionConfidence(DEFAULT_CONFIDENCE)
                .setMinTrackingConfidence(DEFAULT_CONFIDENCE)
                .setMinFacePresenceConfidence(DEFAULT_CONFIDENCE)
                .setNumFaces(DEFAULT_NUM_FACES)
                .setOutputFaceBlendshapes(true)
                .setRunningMode(runningMode)
                .setResultListener(this::returnLivestreamResult)
                .setErrorListener { error -> listener.onError(error.message ?: "MediaPipe 人脸检测失败") }
                .build()
            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (error: RuntimeException) {
            listener.onError("MediaPipe 人脸模型初始化失败：${error.message ?: "未知错误"}")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        check(runningMode == RunningMode.LIVE_STREAM) {
            "FaceLandmarkerHelper must use LIVE_STREAM mode"
        }

        val frameTime = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, width.toFloat(), height.toFloat())
            }
        }
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )
        faceLandmarker?.detectAsync(BitmapImageBuilder(rotatedBitmap).build(), frameTime)
    }

    fun clearFaceLandmarker() {
        faceLandmarker?.close()
        faceLandmarker = null
    }

    private fun returnLivestreamResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        if (result.faceLandmarks().isEmpty()) {
            listener.onEmpty()
            return
        }
        listener.onResults(
            ResultBundle(
                result = result,
                inferenceTime = SystemClock.uptimeMillis() - result.timestampMs(),
                inputImageHeight = input.height,
                inputImageWidth = input.width
            )
        )
    }

    data class ResultBundle(
        val result: FaceLandmarkerResult,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    interface LandmarkerListener {
        fun onResults(resultBundle: ResultBundle)
        fun onEmpty()
        fun onError(message: String)
    }

    companion object {
        private const val MP_FACE_LANDMARKER_TASK = "face_landmarker.task"
        private const val DEFAULT_CONFIDENCE = 0.5f
        private const val DEFAULT_NUM_FACES = 1
    }
}
