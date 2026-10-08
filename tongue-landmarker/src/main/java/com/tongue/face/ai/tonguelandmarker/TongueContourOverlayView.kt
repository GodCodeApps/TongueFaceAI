package com.tongue.face.ai.tonguelandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Draws the temporally-smoothed tongue mask as a contour over the camera preview. */
internal class TongueContourOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var result: TongueSegmentationResult? = null
    private val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 136)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setResult(result: TongueSegmentationResult?) {
        this.result = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = result ?: return
        if (current.imageWidth <= 0 || current.imageHeight <= 0) return

        val scaleFactor = max(
            width.toFloat() / current.imageWidth,
            height.toFloat() / current.imageHeight
        )
        val offsetX = (width - current.imageWidth * scaleFactor) / 2f
        val offsetY = (height - current.imageHeight * scaleFactor) / 2f

        fun toViewX(modelX: Float): Float {
            val imageX = (modelX - current.padLeft) / current.scale
            return imageX * scaleFactor + offsetX
        }

        fun toViewY(modelY: Float): Float {
            val imageY = (modelY - current.padTop) / current.scale
            return imageY * scaleFactor + offsetY
        }

        val mask = current.mask
        val maskWidth = current.maskWidth
        val maskHeight = current.maskHeight
        val threshold = 0.5f

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                val index = y * maskWidth + x
                if (mask[index] < threshold) continue

                val left = x == 0 || mask[index - 1] < threshold
                val right = x == maskWidth - 1 || mask[index + 1] < threshold
                val top = y == 0 || mask[index - maskWidth] < threshold
                val bottom = y == maskHeight - 1 || mask[index + maskWidth] < threshold

                val modelLeft = x * current.modelInputWidth.toFloat() / maskWidth
                val modelRight = (x + 1) * current.modelInputWidth.toFloat() / maskWidth
                val modelTop = y * current.modelInputHeight.toFloat() / maskHeight
                val modelBottom = (y + 1) * current.modelInputHeight.toFloat() / maskHeight

                if (left) {
                    canvas.drawLine(
                        toViewX(modelLeft),
                        toViewY(modelTop),
                        toViewX(modelLeft),
                        toViewY(modelBottom),
                        contourPaint
                    )
                }
                if (right) {
                    canvas.drawLine(
                        toViewX(modelRight),
                        toViewY(modelTop),
                        toViewX(modelRight),
                        toViewY(modelBottom),
                        contourPaint
                    )
                }
                if (top) {
                    canvas.drawLine(
                        toViewX(modelLeft),
                        toViewY(modelTop),
                        toViewX(modelRight),
                        toViewY(modelTop),
                        contourPaint
                    )
                }
                if (bottom) {
                    canvas.drawLine(
                        toViewX(modelLeft),
                        toViewY(modelBottom),
                        toViewX(modelRight),
                        toViewY(modelBottom),
                        contourPaint
                    )
                }
            }
        }
    }
}
