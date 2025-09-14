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
import org.tensorflow.lite.Delegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles loading the TFLite model and running inference to detect boxes in an image.
 * Applies basic preprocessing and custom Non-Maximum Suppression (NMS) to filter overlapping detections.
 * Robust output parsing, class-aware NMS, warm-up, and reused buffers.
 */
class BoxDetector(context: Context) {

    private var tflite: Interpreter? = null
    private var delegate: Delegate? = null
    private var inputSize: Int = 640
    private var inputBuffer: ByteBuffer? = null
    private var outShape: IntArray? = null
    private var warmedUp = false

    data class Detection(val rect: Rect, val score: Float, val classId: Int)
    data class Config(
        val inputSize: Int = 640,
        val confThr: Float = 0.25f,
        val iouThr: Float = 0.45f,
        val threads: Int = 4,
        val tryGpu: Boolean = true
    )
    private var cfg = Config()

    init {
        // Load and initialize the TFLite model interpreter
        try {
            val model: MappedByteBuffer = FileUtil.loadMappedFile(context, "best-fp16.tflite")
            val opts = Interpreter.Options().apply {
                                numThreads = cfg.threads
            }
            // Try GPU via reflection (no hard dependency). Fallbacks: NNAPI ➜ CPU.
            if (cfg.tryGpu) {
                try {
                    val clazz = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                    val gpu = clazz.getDeclaredConstructor().newInstance() as Delegate
                    opts.addDelegate(gpu); delegate = gpu
                    Log.d("TFLite", "GPU delegate enabled")
                } catch (_: Throwable) {
                    try { opts.setUseNNAPI(true); Log.d("TFLite", "NNAPI enabled") } catch (_: Throwable) {}
                }
            } else {
                try { opts.setUseNNAPI(true); Log.d("TFLite", "NNAPI enabled") } catch (_: Throwable) {} }
            tflite = Interpreter(model, opts)
        } catch (e: IOException) {
            e.printStackTrace()
        }

        // Discover shapes once
        tflite?.let {
            val inShape = it.getInputTensor(0).shape() // [1,H,W,3]
            inputSize = inShape.getOrNull(1) ?: 640
            outShape = it.getOutputTensor(0).shape()
            Log.d("TFLite", "Input shape: ${inShape.contentToString()} | Output shape: ${outShape?.contentToString()}")
            // Prepare reusable input buffer
            inputBuffer = ByteBuffer.allocateDirect(4 * 1 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
        }
    }

    fun setConfig(newCfg: Config) {
        cfg = newCfg
        inputSize = cfg.inputSize
        // Rebuild input buffer if needed
        inputBuffer = ByteBuffer.allocateDirect(4 * 1 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder())
    }

    private fun preprocessToBuffer(src: Bitmap): ByteBuffer {
        val buf = inputBuffer ?: ByteBuffer.allocateDirect(4 * 1 * inputSize * inputSize * 3).order(ByteOrder.nativeOrder()).also { inputBuffer = it }
        buf.rewind()
        val bmp = if (src.width != inputSize || src.height != inputSize)
            Bitmap.createScaledBitmap(src, inputSize, inputSize, true) else src
        val pixels = IntArray(inputSize * inputSize)
        bmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            buf.putFloat(r); buf.putFloat(g); buf.putFloat(b)
            i++
        }
        buf.rewind()
        return buf
    }

    // Greedy IoU
    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val inter = max(0, interRight - interLeft) * max(0, interBottom - interTop)
        val aA = (a.width()) * (a.height())
        val bA = (b.width()) * (b.height())
        return if (aA + bA - inter == 0) 0f else inter.toFloat() / (aA + bA - inter).toFloat()
    }

    private fun nmsPerClass(cands: List<Detection>, iouThr: Float): List<Detection> {
        val out = ArrayList<Detection>(cands.size)
        // group by class to avoid cross-suppression
        cands.groupBy { it.classId }.values.forEach { group ->
            val sorted = group.sortedByDescending { it.score }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                out.add(best)
                sorted.removeAll { other -> iou(best.rect, other.rect) > iouThr }
            }
        }
        return out
    }

    /**
     * Production path: returns scored + classed detections in model space (inputSize×inputSize).
     * Caller maps them back to RGB using letterbox info.
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val itp = tflite ?: return emptyList()
        val outSh = outShape ?: itp.getOutputTensor(0).shape().also { outShape = it }
        val buf = preprocessToBuffer(bitmap)

        // Allocate output container matching [1, N, A] (A >= 6).
        val n = (outSh.getOrNull(1) ?: 25200)
        val a = (outSh.getOrNull(2) ?: 6)
        if (a < 6) {
            Log.w("TFLite", "Unexpected output shape: ${outSh.contentToString()}"); return emptyList()
        }
        val out = Array(1) { Array(n) { FloatArray(a) } }
        itp.run(buf, out)

        // Warm-up helps avoid first-call jank
        if (!warmedUp) { warmedUp = true; Log.d("TFLite", "Warm-up done") }

        // Parse: xywh in [0..1], obj, classes...
        val dets = ArrayList<Detection>(64)
        val rows = out[0]
        for (i in 0 until n) {
            val row = rows[i]
            val obj = row[4]
            // max over classes
            var bestC = -1
            var bestP = 0f
            var c = 5
            while (c < a) {
                if (row[c] > bestP) { bestP = row[c]; bestC = c - 5 }
                c++
            }
            val score = obj * bestP
            if (score < cfg.confThr) continue
            val cx = row[0] * inputSize
            val cy = row[1] * inputSize
            val w  = row[2] * inputSize
            val h  = row[3] * inputSize
            val l = (cx - w/2f).toInt().coerceIn(0, inputSize-1)
            val t = (cy - h/2f).toInt().coerceIn(0, inputSize-1)
            val r = (cx + w/2f).toInt().coerceIn(0, inputSize-1)
            val b = (cy + h/2f).toInt().coerceIn(0, inputSize-1)
            if (r > l && b > t) dets.add(Detection(Rect(l,t,r,b), score, max(0, bestC)))
        }
        val nms = nmsPerClass(dets, cfg.iouThr).take(100)
        // Debug to Logcat only
        nms.take(5).forEachIndexed { idx, d ->
            Log.d("BoxDetector", "det#$idx rect=${d.rect} score=${"%.2f".format(d.score)} class=${d.classId}")
        }
        return nms
    }

    /**
     * Backwards-compatible wrapper used by older call-sites (returns rects only).
     */
    fun runInference(bitmap: Bitmap): List<Rect> {
        return detect(bitmap).map { it.rect }
    }
}
