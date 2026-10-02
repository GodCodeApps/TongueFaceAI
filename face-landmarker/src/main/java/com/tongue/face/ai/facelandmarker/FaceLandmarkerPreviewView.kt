package com.tongue.face.ai.facelandmarker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reusable CameraX + MediaPipe live face-landmarker view.
 *
 * Add this view to a layout and call [start] after the CAMERA permission is granted.
 */
class FaceLandmarkerPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val overlayView = FaceLandmarkerOverlayView(context)
    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setBackgroundColor(Color.argb(175, 0, 0, 0))
        visibility = VISIBLE
        text = "未检测到人脸"
    }
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var faceLandmarkerHelper: FaceLandmarkerHelper? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var started = false
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    var onFaceLandmarkerResult: ((FaceLandmarkerResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        addView(previewView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            statusView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            }
        )
    }

    fun start(owner: LifecycleOwner) {
        if (started) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("未授予相机权限")
            return
        }

        lifecycleOwner = owner
        started = true
        showStatus("未检测到人脸")
        if (backgroundExecutor.isShutdown) {
            backgroundExecutor = Executors.newSingleThreadExecutor()
        }
        faceLandmarkerHelper = FaceLandmarkerHelper(
            context = context.applicationContext,
            listener = object : FaceLandmarkerHelper.LandmarkerListener {
                override fun onResults(resultBundle: FaceLandmarkerHelper.ResultBundle) {
                    post {
                        overlayView.setResults(
                            resultBundle.result,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            RunningMode.LIVE_STREAM
                        )
                        showStatus(
                            if (isFaceNearFrameEdge(resultBundle.result)) {
                                "请完整露出脸部"
                            } else {
                                null
                            }
                        )
                        onFaceLandmarkerResult?.invoke(resultBundle.result)
                    }
                }

                override fun onEmpty() {
                    post {
                        overlayView.clear()
                        showStatus("未检测到人脸")
                    }
                }

                override fun onError(message: String) {
                    post {
                        showStatus(message)
                        onError?.invoke(message)
                    }
                }
            }
        )

        ProcessCameraProvider.getInstance(context).also { providerFuture ->
            providerFuture.addListener(
                {
                    if (!started) return@addListener
                    cameraProvider = providerFuture.get()
                    bindCameraUseCases()
                },
                ContextCompat.getMainExecutor(context)
            )
        }
    }

    fun stop() {
        started = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalyzer = null
        faceLandmarkerHelper?.let { helper ->
            backgroundExecutor.execute { helper.clearFaceLandmarker() }
        }
        faceLandmarkerHelper = null
        overlayView.clear()
        showStatus("未检测到人脸")
    }

    fun switchCamera() {
        cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        if (started) bindCameraUseCases()
    }

    override fun onDetachedFromWindow() {
        stop()
        backgroundExecutor.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraFacing)
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(backgroundExecutor) { image ->
                    faceLandmarkerHelper?.detectLiveStream(
                        image,
                        cameraFacing == CameraSelector.LENS_FACING_FRONT
                    ) ?: image.close()
                }
            }

        provider.unbindAll()
        try {
            provider.bindToLifecycle(owner, cameraSelector, preview, imageAnalyzer)
        } catch (error: Exception) {
            onError?.invoke("相机启动失败：${error.message ?: "未知错误"}")
        }
    }

    private fun isFaceNearFrameEdge(result: FaceLandmarkerResult): Boolean {
        val landmarks = result.faceLandmarks().firstOrNull() ?: return false
        val minX = landmarks.minOf { it.x() }
        val maxX = landmarks.maxOf { it.x() }
        val minY = landmarks.minOf { it.y() }
        val maxY = landmarks.maxOf { it.y() }
        return min(minX, minY) < FRAME_EDGE_THRESHOLD ||
            max(maxX, maxY) > 1f - FRAME_EDGE_THRESHOLD
    }

    private fun showStatus(message: String?) {
        statusView.text = message.orEmpty()
        statusView.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        // Landmarks this close to the frame edge usually mean part of the face is out of view.
        private const val FRAME_EDGE_THRESHOLD = 0.03f
    }
}
