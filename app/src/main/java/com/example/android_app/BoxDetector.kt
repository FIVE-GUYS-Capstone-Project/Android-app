package com.example.android_app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

class BoxDetector(context: Context) {

    private var tflite: Interpreter? = null

    init {
        try {
            val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best-fp16.tflite")
            tflite = Interpreter(model)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        val inputShape = tflite?.getInputTensor(0)?.shape() // [1, 3, 640, 640]
        val outputShape = tflite?.getOutputTensor(0)?.shape()

        Log.d("TFLite", "Input shape: ${inputShape?.contentToString()}")
        Log.d("TFLite", "Output shape: ${outputShape?.contentToString()}")
    }

    fun runInference(bitmap: Bitmap): Rect? {
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
        boxes.take(5).forEachIndexed { index, it ->
            Log.d("TFLite", "Box[$index]: cx=${it[0]}, cy=${it[1]}, w=${it[2]}, h=${it[3]}, obj=${it[4]}, conf=${it[5]}, product=${it[4]*it[5]}")
        }
        Log.d("TFLite", "Detected ${boxes.count { it[4] * it[5] > 0.3f }} boxes above threshold.")

        val bestBox = boxes
            .filter { it[4] > 0.4f && it[5] > 0.4f }  // objectness + class conf
            .maxByOrNull { it[4] * it[5] }

        val topBoxes = boxes
            .filter { it[4] > 0.3f && it[5] > 0.3f }
            .sortedByDescending { it[4] * it[5] }
            .take(3)

        topBoxes.forEachIndexed { index, box ->
            Log.d("TFLite", "Top[$index]: cx=${box[0]}, cy=${box[1]}, w=${box[2]}, h=${box[3]}, score=${box[4] * box[5]}")
            // Optional: draw this box in yellow, for example
        }

        // Convert from [cx, cy, w, h] to [left, top, right, bottom]
        if (bestBox != null) {
            val cx = bestBox[0] * 640
            val cy = bestBox[1] * 640
            val w = bestBox[2] * 640
            val h = bestBox[3] * 640

            val left = (cx - w / 2).toInt().coerceIn(0, 639)
            val top = (cy - h / 2).toInt().coerceIn(0, 639)
            val right = (cx + w / 2).toInt().coerceIn(0, 639)
            val bottom = (cy + h / 2).toInt().coerceIn(0, 639)

            return Rect(left, top, right, bottom)
        }
        return null // No valid box found
    }
}


