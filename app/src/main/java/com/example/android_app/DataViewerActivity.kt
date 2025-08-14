package com.example.android_app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DataViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)
        val imgBytes   = intent.getByteArrayExtra("imageBytes")
        val depthBytes = intent.getByteArrayExtra("depthBytes")
        val w = intent.getIntExtra("depthWidth", 0)
        val h = intent.getIntExtra("depthHeight", 0)
        val sMin = intent.getIntExtra("depthScaleMin", 0)
        val sMax = intent.getIntExtra("depthScaleMax", 255)

        if (imgBytes == null) {
            Log.e("DataViewerActivity", "imageBytes is null")
        } else {
            // quick sanity: JPEG should start with FF D8 and end with FF D9
            val okHeader = imgBytes.size >= 4 &&
                    (imgBytes[0].toInt() and 0xFF) == 0xFF &&
                    (imgBytes[1].toInt() and 0xFF) == 0xD8
            Log.d("DataViewerActivity", "Image bytes: " +
                    "${imgBytes.size}, JPEG_SOI=$okHeader")

            val bmp = BitmapFactory.decodeByteArray(imgBytes,
                0, imgBytes.size)
            if (bmp == null) {
                Log.e("DataViewerActivity", "BitmapFactory returned null")
            } else {
                findViewById<ImageView>(R.id.imageView).setImageBitmap(bmp)
            }
        }
        if (depthBytes == null) {
            Log.w("DataViewerActivity", "No depth data passed in intent")
        } else {
            val depthText = findViewById<TextView>(R.id.depthText)
            depthText.text = "Depth bytes: ${depthBytes.size}"
            if (w > 0 && h > 0 && depthBytes.size >= w * h) {
                val depthBmp = colorizeDepth(depthBytes, w, h, sMin, sMax)
                findViewById<ImageView>(R.id.depthImage).setImageBitmap(depthBmp)
                depthText.text = "Depth: ${w}×${h} • ${depthBytes.size} bytes • scale $sMin–$sMax (color)"
            } else {
                depthText.text = "Depth bytes: ${depthBytes.size} (no META or size mismatch)"
            }
        }
        // Back button
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    /** Build a colorized bitmap from 8-bit depth using a 5-stop gradient. */
    private fun colorizeDepth(
        depth: ByteArray,
        w: Int,
        h: Int,
        sMin: Int,
        sMax: Int
        ): Bitmap {
        val lut = buildColorLut(sMin, sMax)
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val raw = depth[i].toInt() and 0xFF
            pixels[i] = lut[raw]
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Precompute 256-entry ARGB LUT with stops: blue→cyan→green→yellow→red. */
    /** Precompute a 256-entry ARGB LUT using the VIRIDIS scientific colormap. */
    private fun buildColorLut(sMin: Int, sMax: Int): IntArray {
        // Viridis anchors (ARGB): dark purple → yellow
        val stops = intArrayOf(
            0xFF440154.toInt(), // #440154
            0xFF48186A.toInt(), // #48186A
            0xFF472D7B.toInt(), // #472D7B
            0xFF424086.toInt(), // #424086
            0xFF3B528B.toInt(), // #3B528B
            0xFF31688E.toInt(), // #31688E
            0xFF2A788E.toInt(), // #2A788E
            0xFF21908C.toInt(), // #21908C
            0xFF22A884.toInt(), // #22A884
            0xFF35B779.toInt(), // #35B779
            0xFF55C667.toInt(), // #55C667
            0xFF73D055.toInt(), // #73D055
            0xFF95D840.toInt(), // #95D840
            0xFFB8DE29.toInt(), // #B8DE29
            0xFFDCE319.toInt(), // #DCE319
            0xFFFDE725.toInt()  // #FDE725
        )

        fun lerpColor(c1: Int, c2: Int, t: Float): Int {
            val a1 = (c1 ushr 24) and 0xFF; val r1 = (c1 ushr 16) and 0xFF; val g1 = (c1 ushr 8) and 0xFF; val b1 = c1 and 0xFF
            val a2 = (c2 ushr 24) and 0xFF; val r2 = (c2 ushr 16) and 0xFF; val g2 = (c2 ushr 8) and 0xFF; val b2 = c2 and 0xFF
            val a = (a1 + (a2 - a1) * t).toInt()
            val r = (r1 + (r2 - r1) * t).toInt()
            val g = (g1 + (g2 - g1) * t).toInt()
            val b = (b1 + (b2 - b1) * t).toInt()
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val lo = sMin.coerceIn(0, 255)
        val hi = sMax.coerceIn(lo + 1, 255)

        val lut = IntArray(256)
        for (v in 0..255) {
            // Normalize depth to [0,1] based on provided scale
            val t0 = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
            // Interpolate inside Viridis
            val pos = t0 * (stops.size - 1)
            val i = pos.toInt().coerceIn(0, stops.size - 2)
            val ft = pos - i
            lut[v] = lerpColor(stops[i], stops[i + 1], ft)
        }
        return lut
    }
}

