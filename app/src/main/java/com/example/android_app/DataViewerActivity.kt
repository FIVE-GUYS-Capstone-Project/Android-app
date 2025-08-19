package com.example.android_app

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataViewerActivity : AppCompatActivity() {

    private enum class Mode { RGB, DEPTH, OVERLAY }

    private var mode = Mode.OVERLAY
    private var overlayAlpha = 160 // 0..255

    private var rgbBmp: Bitmap? = null
    private var depthColorBmp: Bitmap? = null
    private var depthColorScaled: Bitmap? = null

    // keep raw inputs around for saving bundle
    private var imgBytes: ByteArray? = null
    private var depthBytes: ByteArray? = null
    private var w: Int = 0
    private var h: Int = 0
    private var sMin: Int = 0
    private var sMax: Int = 255

    // last saved bundle path (for sharing)
    private var lastBundleZip: File? = null
    private var lastBundleDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        imgBytes   = intent.getByteArrayExtra("imageBytes")
        depthBytes = intent.getByteArrayExtra("depthBytes")
        w = intent.getIntExtra("depthWidth", 0)
        h = intent.getIntExtra("depthHeight", 0)
        sMin = intent.getIntExtra("depthScaleMin", 0)
        sMax = intent.getIntExtra("depthScaleMax", 255)

        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        val depthText = findViewById<TextView>(R.id.depthText)

        // --- Decode RGB
        imgBytes?.let {
            rgbBmp = BitmapFactory.decodeByteArray(it, 0, it.size)
            imageView.setImageBitmap(rgbBmp)
            Log.d("DataViewerActivity", "RGB ${rgbBmp?.width}x${rgbBmp?.height}")
        } ?: Log.e("DataViewerActivity", "imageBytes is null")

        // --- Colorize depth and scale to RGB size if present
        if (depthBytes != null && w > 0 && h > 0 && depthBytes!!.size >= w * h) {
            depthColorBmp = colorizeDepth(depthBytes!!, w, h, sMin, sMax)
            val rw = rgbBmp?.width ?: w
            val rh = rgbBmp?.height ?: h
            depthColorScaled = Bitmap.createScaledBitmap(depthColorBmp!!, rw, rh, true)
            depthImage.setImageBitmap(depthColorBmp) // native res preview
            depthText.text = "Depth: ${w}×${h} • ${depthBytes!!.size} bytes • scale $sMin–$sMax (color)"
        } else {
            depthText.text = "Depth bytes: ${depthBytes?.size ?: 0} (no META or size mismatch)"
            Log.w("DataViewerActivity", "No usable depth")
        }

        // --- Legend
        findViewById<TextView>(R.id.legendMin).text = sMin.toString()
        findViewById<TextView>(R.id.legendMax).text = sMax.toString()
        findViewById<ImageView>(R.id.colorbar).setImageBitmap(buildLegendBitmap(sMin, sMax))

        // --- Mode button cycles RGB → DEPTH → OVERLAY
        val modeButton = findViewById<Button>(R.id.modeButton)
        modeButton.setOnClickListener {
            mode = when (mode) {
                Mode.RGB -> Mode.DEPTH
                Mode.DEPTH -> Mode.OVERLAY
                Mode.OVERLAY -> Mode.RGB
            }
            modeButton.text = "Mode: " + when (mode) {
                Mode.RGB -> "RGB"
                Mode.DEPTH -> "Depth"
                Mode.OVERLAY -> "Overlay"
            }
            render(imageView, depthImage)
        }

        // --- Alpha slider
        val alphaSeek = findViewById<SeekBar>(R.id.alphaSeek)
        alphaSeek.progress = overlayAlpha
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                overlayAlpha = progress
                if (mode == Mode.OVERLAY) render(imageView, depthImage)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Back
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // --- Save & Share (Patch 3 wires provider + share)
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            try {
                lastBundleDir = saveSessionBundle()
                Toast.makeText(this, "Saved to ${lastBundleDir?.name}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("DataViewerActivity", "Save failed", e)
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.shareButton).setOnClickListener {
            try {
                if (lastBundleDir == null) lastBundleDir = saveSessionBundle()
                lastBundleZip = zipDirectory(lastBundleDir!!)
                ShareUtil.shareZip(this, lastBundleZip!!)
            } catch (e: Exception) {
                Log.e("DataViewerActivity", "Share failed", e)
                Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // initial render
        render(imageView, depthImage)
    }

    private fun render(imageView: ImageView, depthImage: ImageView) {
        when (mode) {
            Mode.RGB -> {
                imageView.setImageBitmap(rgbBmp)
                depthImage.alpha = 1f
                depthImage.setImageBitmap(depthColorBmp)
            }
            Mode.DEPTH -> {
                imageView.setImageBitmap(depthColorBmp) // show native depth color alone
                depthImage.alpha = 0f
            }
            Mode.OVERLAY -> {
                val rgb = rgbBmp
                val dsc = depthColorScaled
                if (rgb != null && dsc != null) {
                    imageView.setImageBitmap(compositeOverlay(rgb, dsc, overlayAlpha))
                }
                depthImage.alpha = 0f
            }
        }
    }

    /** Colorize depth to Viridis (sMin..sMax) into ARGB bitmap. */
    private fun colorizeDepth(depth: ByteArray, w: Int, h: Int, sMin: Int, sMax: Int): Bitmap {
        val lut = buildViridis(sMin, sMax)
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val raw = depth[i].toInt() and 0xFF
            pixels[i] = lut[raw]
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    /** Compose depth over RGB using Canvas alpha (fast). */
    private fun compositeOverlay(rgb: Bitmap, depthScaled: Bitmap, alpha255: Int): Bitmap {
        val out = rgb.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val p = Paint().apply { alpha = alpha255.coerceIn(0, 255) }
        c.drawBitmap(depthScaled, 0f, 0f, p)
        return out
    }

    /** Small horizontal legend bitmap (Viridis). */
    private fun buildLegendBitmap(sMin: Int, sMax: Int): Bitmap {
        val width = 256
        val height = 10
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val lut = buildViridis(sMin, sMax)
        for (x in 0 until width) {
            val v = x // 0..255
            val col = lut[v]
            for (y in 0 until height) bmp.setPixel(x, y, col)
        }
        return bmp
    }

    /** Viridis LUT normalized by sMin..sMax (perceptually uniform). */
    private fun buildViridis(sMin: Int, sMax: Int): IntArray {
        // Precomputed Viridis 256x RGB (shortened: we parametric-blend here to keep code short)
        // This approximation is good enough for our display purposes.
        fun viridisRGB(t: Float): Int {
            // t in [0,1]; polynomial fit
            val r = ( 0.281f + 1.5f*t - 1.8f*t*t + 1.1f*t*t*t ).coerceIn(0f,1f)
            val g = ( 0.0f   + 1.3f*t + 0.3f*t*t  - 0.8f*t*t*t ).coerceIn(0f,1f)
            val b = ( 0.33f  + 0.2f*t + 1.2f*t*t - 1.2f*t*t*t ).coerceIn(0f,1f)
            return (0xFF shl 24) or
                    ((r*255f).toInt() shl 16) or
                    ((g*255f).toInt() shl 8) or
                    (b*255f).toInt()
        }
        val lo = sMin.coerceIn(0, 255)
        val hi = sMax.coerceIn(lo + 1, 255)
        val lut = IntArray(256)
        for (v in 0..255) {
            val t = ((v - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
            lut[v] = viridisRGB(t)
        }
        return lut
    }

    // ----- Patch 3 helpers (save/share); used by Save/Share buttons -----

    private fun saveSessionBundle(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "captures/$stamp").apply { mkdirs() }

        // photo.jpg
        imgBytes?.let { FileOutputStream(File(dir, "photo.jpg")).use { it.write(this.imgBytes) } }

        // depth_u8.raw
        depthBytes?.let { FileOutputStream(File(dir, "depth_u8.raw")).use { it.write(this.depthBytes) } }

        // depth_color.png
        depthColorBmp?.let {
            FileOutputStream(File(dir, "depth_color.png")).use { fos ->
                it.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        }

        // meta.json
        val meta = JSONObject()
            .put("depthWidth", w)
            .put("depthHeight", h)
            .put("scaleMin", sMin)
            .put("scaleMax", sMax)
            .put("overlayAlpha", overlayAlpha)
            .put("mode", mode.name)
            .put("timestamp", stamp)
        File(dir, "meta.json").writeText(meta.toString(2))

        return dir
    }

    private fun zipDirectory(dir: File): File {
        return ZipUtil.zip(dir, File(dir.parentFile, "${dir.name}.zip"))
    }
}
