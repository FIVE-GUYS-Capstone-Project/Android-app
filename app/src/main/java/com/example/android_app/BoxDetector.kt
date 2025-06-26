package com.example.android_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.MappedByteBuffer
import kotlin.math.max
import kotlin.math.min

class BoxDetector(context: Context) {

    private var tflite: Interpreter? = null

    init {
        try {
            val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best-fp16.tflite")
            tflite = Interpreter(model)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val inputShape = tflite?.getInputTensor(0)?.shape()
        val outputShape = tflite?.getOutputTensor(0)?.shape()
        Log.d("TFLite", "Input shape: ${inputShape?.contentToString()}")
        Log.d("TFLite", "Output shape: ${outputShape?.contentToString()}")
    }

    fun runInference(bitmap: Bitmap): List<Rect> {
        val inputImage = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Log.d("TFLite", "Running inference on thread: ${Thread.currentThread().name}")

        val inputBuffer = Array(1) { Array(640) { Array(640) { FloatArray(3) } } }
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val pixel = inputImage.getPixel(x, y)
                inputBuffer[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255.0f
                inputBuffer[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255.0f
                inputBuffer[0][y][x][2] = (pixel and 0xFF) / 255.0f
            }
        }

        val outputBuffer = Array(1) { Array(25200) { FloatArray(6) } }
        tflite?.run(inputBuffer, outputBuffer)
        val boxes = outputBuffer[0]

        // Extract raw boxes + score
        val scoredBoxes = boxes.filter { it[4] > 0.4f && it[5] > 0.4f }
            .mapNotNull { box ->
                val score = box[4] * box[5]
                val cx = box[0] * 640
                val cy = box[1] * 640
                val w = box[2] * 640
                val h = box[3] * 640

                val left = (cx - w / 2).toInt().coerceIn(0, 639)
                val top = (cy - h / 2).toInt().coerceIn(0, 639)
                val right = (cx + w / 2).toInt().coerceIn(0, 639)
                val bottom = (cy + h / 2).toInt().coerceIn(0, 639)

                if (right > left && bottom > top) {
                    ScoredBox(Rect(left, top, right, bottom), score)
                } else null
            }

        val finalBoxes = applyNMS(scoredBoxes, iouThreshold = 0.5f).map { it.rect }
        Log.d("TFLite", "Returning ${finalBoxes.size} boxes after NMS")
        return finalBoxes
    }

    // Helper class for NMS
    private data class ScoredBox(val rect: Rect, val score: Float)

    private fun applyNMS(boxes: List<ScoredBox>, iouThreshold: Float): List<ScoredBox> {
        val selected = mutableListOf<ScoredBox>()
        val sortedBoxes = boxes.sortedByDescending { it.score }.toMutableList()

        while (sortedBoxes.isNotEmpty()) {
            val best = sortedBoxes.removeAt(0)
            selected.add(best)

            sortedBoxes.removeAll { other ->
                iou(best.rect, other.rect) > iouThreshold
            }
        }
        return selected
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interArea = max(0, interRight - interLeft) * max(0, interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)

        return if (aArea + bArea - interArea == 0) 0f else interArea.toFloat() / (aArea + bArea - interArea)
    }
}
