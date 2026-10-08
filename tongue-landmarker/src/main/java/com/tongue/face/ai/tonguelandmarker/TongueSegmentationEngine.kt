package com.tongue.face.ai.tonguelandmarker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Runs the exported tongue_yolo_seg model and keeps a small temporal mask filter. */
internal class TongueSegmentationEngine(context: Context) : AutoCloseable {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private var smoothedMask: FloatArray? = null

    init {
        val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
        }
        session = environment.createSession(modelBytes, sessionOptions)
        inputName = session.inputNames.first()
    }

    fun detect(bitmap: Bitmap): TongueSegmentationResult? {
        val letterbox = createLetterbox(bitmap)
        val input = FloatArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 3)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        letterbox.bitmap.getPixels(
            pixels,
            0,
            MODEL_INPUT_SIZE,
            0,
            0,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE
        )
        letterbox.bitmap.recycle()

        val planeSize = MODEL_INPUT_SIZE * MODEL_INPUT_SIZE
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = ((pixel shr 16) and 0xff) / 255f
            input[planeSize + index] = ((pixel shr 8) and 0xff) / 255f
            input[planeSize * 2 + index] = (pixel and 0xff) / 255f
        }

        val inputTensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())
        )

        val outputs = try {
            session.run(mapOf(inputName to inputTensor))
        } finally {
            inputTensor.close()
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val detections = outputs[0].value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val prototypes = outputs[1].value as Array<Array<Array<FloatArray>>>
            val bestDetection = findBestDetection(detections) ?: run {
                smoothedMask = null
                return null
            }

            val rawMask = decodeMask(bestDetection, prototypes[0])
            val filteredMask = smoothMask(rawMask)
            return TongueSegmentationResult(
                mask = filteredMask,
                maskWidth = prototypes[0][0][0].size,
                maskHeight = prototypes[0][0].size,
                modelInputWidth = MODEL_INPUT_SIZE,
                modelInputHeight = MODEL_INPUT_SIZE,
                padLeft = letterbox.padLeft,
                padTop = letterbox.padTop,
                scale = letterbox.scale,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                confidence = bestDetection.confidence
            )
        } finally {
            outputs.close()
        }
    }

    override fun close() {
        session.close()
        smoothedMask = null
    }

    private fun findBestDetection(detections: Array<Array<FloatArray>>): Detection? {
        var best: Detection? = null
        for (row in detections[0]) {
            if (row.size < 7) continue
            val confidence = row[4]
            val classIndex = row[5].roundToInt()
            if (classIndex != 0 || confidence < CONFIDENCE_THRESHOLD) continue
            if (best == null || confidence > best.confidence) {
                best = Detection(
                    x1 = row[0],
                    y1 = row[1],
                    x2 = row[2],
                    y2 = row[3],
                    confidence = confidence,
                    coefficients = row.copyOfRange(6, row.size)
                )
            }
        }
        return best
    }

    private fun decodeMask(
        detection: Detection,
        prototypes: Array<Array<FloatArray>>
    ): FloatArray {
        val channels = min(detection.coefficients.size, prototypes.size)
        val maskHeight = prototypes[0].size
        val maskWidth = prototypes[0][0].size
        val mask = FloatArray(maskWidth * maskHeight)

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val modelX = (x + 0.5f) * MODEL_INPUT_SIZE / maskWidth
                val modelY = (y + 0.5f) * MODEL_INPUT_SIZE / maskHeight
                val index = y * maskWidth + x

                // YOLOv8-seg masks must be cropped to the selected detection box.
                if (modelX < detection.x1 || modelX > detection.x2 ||
                    modelY < detection.y1 || modelY > detection.y2
                ) {
                    mask[index] = 0f
                    continue
                }

                var logit = 0f
                for (channel in 0 until channels) {
                    logit += detection.coefficients[channel] * prototypes[channel][y][x]
                }
                mask[index] = 1f / (1f + exp(-logit))
            }
        }
        return mask
    }

    private fun smoothMask(rawMask: FloatArray): FloatArray {
        val previous = smoothedMask
        if (previous == null || previous.size != rawMask.size) {
            smoothedMask = rawMask.copyOf()
        } else {
            for (i in rawMask.indices) {
                smoothedMask!![i] =
                    TEMPORAL_ALPHA * rawMask[i] + (1f - TEMPORAL_ALPHA) * smoothedMask!![i]
            }
        }
        return smoothedMask!!.copyOf()
    }

    private fun createLetterbox(source: Bitmap): Letterbox {
        val scale = min(
            MODEL_INPUT_SIZE.toFloat() / source.width,
            MODEL_INPUT_SIZE.toFloat() / source.height
        )
        val resizedWidth = max(1, (source.width * scale).roundToInt())
        val resizedHeight = max(1, (source.height * scale).roundToInt())
        val padLeft = (MODEL_INPUT_SIZE - resizedWidth) / 2
        val padTop = (MODEL_INPUT_SIZE - resizedHeight) / 2

        val bitmap = Bitmap.createBitmap(
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            source,
            null,
            android.graphics.Rect(
                padLeft,
                padTop,
                padLeft + resizedWidth,
                padTop + resizedHeight
            ),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return Letterbox(bitmap, scale, padLeft, padTop)
    }

    private data class Detection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val confidence: Float,
        val coefficients: FloatArray
    )

    private data class Letterbox(
        val bitmap: Bitmap,
        val scale: Float,
        val padLeft: Int,
        val padTop: Int
    )

    companion object {
        private const val MODEL_ASSET = "tongue_yolo_seg.onnx"
        private const val MODEL_INPUT_SIZE = 480
        private const val CONFIDENCE_THRESHOLD = 0.15f
        private const val TEMPORAL_ALPHA = 0.65f
    }
}

internal data class TongueSegmentationResult(
    val mask: FloatArray,
    val maskWidth: Int,
    val maskHeight: Int,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val padLeft: Int,
    val padTop: Int,
    val scale: Float,
    val imageWidth: Int,
    val imageHeight: Int,
    val confidence: Float
)
