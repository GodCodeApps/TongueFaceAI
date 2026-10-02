package com.tongue.face.ai.facelandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

/** Draws MediaPipe face landmarks over the CameraX preview. */
class FaceLandmarkerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var runningMode = RunningMode.LIVE_STREAM
    private var imageWidth = 1
    private var imageHeight = 1
    private var scaleFactor = 1f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.face_landmarker_primary)
        strokeWidth = LANDMARK_STROKE_WIDTH
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = LANDMARK_STROKE_WIDTH
        style = Paint.Style.FILL
    }

    fun setResults(
        result: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.LIVE_STREAM
    ) {
        this.results = result
        this.imageHeight = imageHeight.coerceAtLeast(1)
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.runningMode = runningMode
        recalculateScale()
        invalidate()
    }

    fun clear() {
        results = null
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        recalculateScale()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val faceResult = results ?: return
        if (faceResult.faceLandmarks().isEmpty()) return

        val scaledImageWidth = imageWidth * scaleFactor
        val scaledImageHeight = imageHeight * scaleFactor
        val offsetX = (width - scaledImageWidth) / 2f
        val offsetY = (height - scaledImageHeight) / 2f

        faceResult.faceLandmarks().forEach { landmarks ->
            drawFaceLandmarks(canvas, landmarks, offsetX, offsetY)
            drawFaceConnectors(canvas, landmarks, offsetX, offsetY)
        }
    }

    private fun recalculateScale() {
        if (width == 0 || height == 0) return
        scaleFactor = when (runningMode) {
            RunningMode.LIVE_STREAM -> max(
                width.toFloat() / imageWidth,
                height.toFloat() / imageHeight
            )
            else -> min(
                width.toFloat() / imageWidth,
                height.toFloat() / imageHeight
            )
        }
    }

    private fun drawFaceLandmarks(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        landmarks.forEach { landmark ->
            canvas.drawPoint(
                landmark.x() * imageWidth * scaleFactor + offsetX,
                landmark.y() * imageHeight * scaleFactor + offsetY,
                pointPaint
            )
        }
    }

    private fun drawFaceConnectors(
        canvas: Canvas,
        landmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val start = landmarks.getOrNull(connector.start())
            val end = landmarks.getOrNull(connector.end())
            if (start != null && end != null) {
                canvas.drawLine(
                    start.x() * imageWidth * scaleFactor + offsetX,
                    start.y() * imageHeight * scaleFactor + offsetY,
                    end.x() * imageWidth * scaleFactor + offsetX,
                    end.y() * imageHeight * scaleFactor + offsetY,
                    linePaint
                )
            }
        }
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 4f
    }
}
