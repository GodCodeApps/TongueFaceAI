package com.tongue.face.ai.tonguelandmarker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** CameraX preview that runs tongue_yolo_seg and draws a smoothed tongue contour. */
class TongueLandmarkerPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val previewView = PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val overlayView = TongueContourOverlayView(context)
    private val statusView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setBackgroundColor(Color.argb(175, 0, 0, 0))
        text = "未检测到舌头"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var engine: TongueSegmentationEngine? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT
    private var started = false
    private var backgroundExecutor: ExecutorService = Executors.newSingleThreadExecutor()

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
            showStatus("需要相机权限")
            return
        }

        started = true
        lifecycleOwner = owner
        showStatus("正在加载舌头模型…")
        if (backgroundExecutor.isShutdown) {
            backgroundExecutor = Executors.newSingleThreadExecutor()
        }

        backgroundExecutor.execute {
            try {
                val loadedEngine = TongueSegmentationEngine(context.applicationContext)
                if (!started) {
                    loadedEngine.close()
                    return@execute
                }
                engine = loadedEngine
                post {
                    if (started) {
                        showStatus("未检测到舌头")
                        bindCameraUseCases()
                    }
                }
            } catch (error: Throwable) {
                post { showStatus("舌头模型加载失败：${error.message ?: "未知错误"}") }
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    cameraProvider = providerFuture.get()
                    bindCameraUseCases()
                } catch (error: Exception) {
                    showStatus("相机启动失败：${error.message ?: "未知错误"}")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        started = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageAnalyzer = null
        val oldEngine = engine
        engine = null
        if (oldEngine != null && !backgroundExecutor.isShutdown) {
            backgroundExecutor.execute { oldEngine.close() }
        }
        overlayView.setResult(null)
        showStatus("未检测到舌头")
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
        if (!started || engine == null) return

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val selector = CameraSelector.Builder()
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
                analysis.setAnalyzer(backgroundExecutor) { image -> analyzeImage(image) }
            }

        provider.unbindAll()
        try {
            provider.bindToLifecycle(owner, selector, preview, imageAnalyzer)
        } catch (error: Exception) {
            showStatus("相机启动失败：${error.message ?: "未知错误"}")
        }
    }

    private fun analyzeImage(image: ImageProxy) {
        val currentEngine = engine
        if (currentEngine == null || !started) {
            image.close()
            return
        }

        val (sourceBitmap, inputBitmap) = imageToBitmap(
            image,
            cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
        try {
            val result = currentEngine.detect(inputBitmap)
            post {
                if (!started) return@post
                overlayView.setResult(result)
                if (result == null) {
                    showStatus("未检测到舌头")
                } else {
                    showStatus(null)
                }
            }
        } catch (error: Throwable) {
            post { if (started) showStatus("舌头检测失败：${error.message ?: "未知错误"}") }
        } finally {
            if (inputBitmap !== sourceBitmap) inputBitmap.recycle()
            sourceBitmap.recycle()
        }
    }

    private fun imageToBitmap(image: ImageProxy, isFrontCamera: Boolean): Pair<Bitmap, Bitmap> {
        val width = image.width
        val height = image.height
        val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rotationDegrees = image.imageInfo.rotationDegrees
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, width.toFloat(), height.toFloat())
            }
        }
        val transformed = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )
        return bitmapBuffer to transformed
    }

    private fun showStatus(message: String?) {
        statusView.text = message.orEmpty()
        statusView.visibility = if (message.isNullOrBlank()) GONE else VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
