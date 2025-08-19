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
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import kotlin.math.*
import java.util.zip.ZipInputStream

class DataViewerActivity : AppCompatActivity() {

    private enum class Mode { RGB, DEPTH, OVERLAY }

    // prefs for viewer state
    private val prefs by lazy { getSharedPreferences("viewer_prefs", MODE_PRIVATE) }

    private val REQ_OPEN_BUNDLE = 31

    private val HFOV_DEG = 60.0   // TODO replace with real fx or FOV
    private val VFOV_DEG = 45.0

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

        // Restore last viewer state
        mode = try { Mode.valueOf(prefs.getString("viewer_mode", mode.name)!!) }
        catch (_: Exception) { Mode.OVERLAY }
        overlayAlpha = prefs.getInt("viewer_alpha", overlayAlpha)

        imgBytes   = intent.getByteArrayExtra("imageBytes")
        depthBytes = intent.getByteArrayExtra("depthBytes")
        w = intent.getIntExtra("depthWidth", 0)
        h = intent.getIntExtra("depthHeight", 0)
        sMin = intent.getIntExtra("depthScaleMin", 0)
        sMax = intent.getIntExtra("depthScaleMax", 255)

        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        val depthText = findViewById<TextView>(R.id.depthText)
        val overlay = findViewById<RoiOverlayView>(R.id.roiOverlay)
        val dimsText = findViewById<TextView>(R.id.dimsText)

        overlay.onBoxFinished = { boxOnOverlay: RectF ->
            // Map from overlay coords → image pixel coords (RGB bitmap space)
            val rectRgb = viewToImageRect(findViewById(R.id.imageView), boxOnOverlay,
                rgbBmp?.width ?: 800, rgbBmp?.height ?: 600)

            val readout = computeDimsFromRoi(rectRgb)
            dimsText?.text = readout
        }

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
            prefs.edit().putString("viewer_mode", mode.name).apply()
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
                prefs.edit().putInt("viewer_alpha", overlayAlpha).apply()
                if (mode == Mode.OVERLAY) {
                    // keep the top layer in sync while dragging
                    findViewById<ImageView>(R.id.depthImage).alpha = (overlayAlpha / 255f)
                }
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
        findViewById<Button>(R.id.openButton)?.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            }
            startActivityForResult(intent, REQ_OPEN_BUNDLE)
        }

        // initial render
        render(imageView, depthImage)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_BUNDLE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { loadBundleFromZip(it) }
        }
    }

    private fun loadBundleFromZip(uri: Uri) {
        var photo: ByteArray? = null
        var rawDepth: ByteArray? = null
        var meta: JSONObject? = null
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val name = e.name.lowercase()
                        val bytes = zis.readBytes()
                        when {
                            name.endsWith("photo.jpg")        -> photo = bytes
                            name.endsWith("depth_u8.raw")     -> rawDepth = bytes
                            name.endsWith("meta.json")        -> meta = JSONObject(bytes.decodeToString())
                        }
                        zis.closeEntry()
                        e = zis.nextEntry
                    }
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Open failed: ${t.message}", Toast.LENGTH_LONG).show(); return
        }
        if (photo == null || rawDepth == null || meta == null) {
            Toast.makeText(this, "Bundle missing files (photo.jpg, depth_u8.raw, meta.json)", Toast.LENGTH_LONG).show(); return
        }

        val dw = meta!!.optInt("depthWidth", 100)
        val dh = meta!!.optInt("depthHeight", 100)
        val sMin = meta!!.optInt("scaleMin", 0)
        val sMax = meta!!.optInt("scaleMax", 255)

        onNewFrameBytes(photo!!, rawDepth!!, dw, dh, sMin, sMax)

        // Apply to UI
        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)

        // If we're currently in overlay, refresh the top layer bitmap now.
        if (mode == Mode.OVERLAY) {
            depthColorScaled?.let { depthImage.setImageBitmap(it) }
            depthImage.alpha = (overlayAlpha / 255f)
            depthImage.visibility = View.VISIBLE
        }

        render(imageView, depthImage)

    }

    private fun onNewFrameBytes(
        imageBytes: ByteArray,
        depthBytes: ByteArray,
        depthWidth: Int,
        depthHeight: Int,
        scaleMin: Int,
        scaleMax: Int
    ) {
        // Update fields
        imgBytes = imageBytes
        this.depthBytes = depthBytes
        w = depthWidth; h = depthHeight; sMin = scaleMin; sMax = scaleMax

        // Decode / rebuild bitmaps
        rgbBmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        depthColorBmp = colorizeDepth(depthBytes, w, h, sMin, sMax)
        depthColorScaled = Bitmap.createScaledBitmap(depthColorBmp!!, rgbBmp!!.width, rgbBmp!!.height, true)

        // Apply to UI
        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        findViewById<TextView>(R.id.legendMin)?.text = sMin.toString()
        findViewById<TextView>(R.id.legendMax)?.text = sMax.toString()
        findViewById<ImageView>(R.id.colorbar)?.setImageBitmap(buildLegendBitmap(sMin, sMax))
        findViewById<TextView>(R.id.depthText)?.text =
            "Depth: ${w}×${h} • ${depthBytes.size} bytes • scale $sMin–$sMax (color)"

        render(imageView, depthImage)
    }

    private fun render(imageView: ImageView, depthImage: ImageView) {
        when (mode) {
            Mode.RGB -> {
                imageView.setImageBitmap(rgbBmp)
                depthImage.setImageDrawable(null)
                depthImage.visibility = View.GONE
            }
            Mode.DEPTH -> {
                imageView.setImageBitmap(depthColorBmp)
                depthImage.setImageDrawable(null)
                depthImage.visibility = View.GONE
            }
            Mode.OVERLAY -> {
                // RGB below, depth (scaled to RGB) above with adjustable alpha
                imageView.setImageBitmap(rgbBmp)
                depthColorScaled?.let { depthImage.setImageBitmap(it) }
                depthImage.alpha = (overlayAlpha.coerceIn(0, 255) / 255f)
                depthImage.visibility = View.VISIBLE
            }
        }
    }

    private fun viewToImageRect(iv: ImageView, rView: RectF, imgW: Int, imgH: Int): RectF {
        // Always compute using the intended image size (imgW/imgH), not iv.drawable
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val dw = imgW.toFloat()
        val dh = imgH.toFloat()

        val scale = kotlin.math.min(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f

        fun mapX(x: Float) = ((x - dx) / scale).coerceIn(0f, dw)
        fun mapY(y: Float) = ((y - dy) / scale).coerceIn(0f, dh)

        return RectF(mapX(rView.left), mapY(rView.top), mapX(rView.right), mapY(rView.bottom))
    }

    private fun computeDimsFromRoi(roiRgb: RectF): String {
        val depth = depthBytes ?: return "No depth"
        if (w <= 0 || h <= 0) return "No depth"

        // Map RGB→depth grid
        val sx = w.toFloat() / (rgbBmp?.width?.toFloat() ?: 800f)
        val sy = h.toFloat() / (rgbBmp?.height?.toFloat() ?: 600f)
        val dx0 = (roiRgb.left   * sx).roundToInt().coerceIn(0, w - 1)
        val dy0 = (roiRgb.top    * sy).roundToInt().coerceIn(0, h - 1)
        val dx1 = (roiRgb.right  * sx).roundToInt().coerceIn(0, w - 1)
        val dy1 = (roiRgb.bottom * sy).roundToInt().coerceIn(0, h - 1)
        val xs = min(dx0, dx1); val xe = max(dx0, dx1)
        val ys = min(dy0, dy1); val ye = max(dy0, dy1)

        // Robust median depth (exclude 0/255 as invalid)
        val samples = ArrayList<Int>((xe - xs + 1) * (ye - ys + 1))
        for (yy in ys..ye) for (xx in xs..xe) {
            val v = depth[yy * w + xx].toInt() and 0xFF
            if (v in 1..254) samples.add(v)
        }
        if (samples.isEmpty()) return "ROI has no usable depth"

        samples.sort()
        val medU = samples[samples.size / 2]
        fun u8ToMm(u: Int): Double {
            val span = (sMax - sMin).toDouble().coerceAtLeast(1.0)
            return sMin + (u / 255.0) * span
        }
        val zMedMm = u8ToMm(medU)

        // estimate physical L × W using pinhole + FOV
        val rgbW = (rgbBmp?.width ?: 800).toDouble()
        val rgbH = (rgbBmp?.height ?: 600).toDouble()
        val mmPerPxX = 2.0 * zMedMm * tan(Math.toRadians(HFOV_DEG / 2.0)) / rgbW
        val mmPerPxY = 2.0 * zMedMm * tan(Math.toRadians(VFOV_DEG / 2.0)) / rgbH
        val Lmm = roiRgb.width().toDouble() * mmPerPxX
        val Wmm = roiRgb.height().toDouble() * mmPerPxY

        // crude height: median ring outside minus inside
        val ring = ArrayList<Int>()
        val pad = 4
        val rx0 = max(xs - pad, 0); val ry0 = max(ys - pad, 0)
        val rx1 = min(xe + pad, w - 1); val ry1 = min(ye + pad, h - 1)
        for (yy in ry0..ry1) for (xx in rx0..rx1) {
            val onBorder = (xx < xs || xx > xe || yy < ys || yy > ye)
            if (onBorder) {
                val v = depth[yy * w + xx].toInt() and 0xFF
                if (v in 1..254) ring.add(v)
            }
        }
        val zBgMm = if (ring.isNotEmpty()) u8ToMm(ring.sorted()[ring.size / 2]) else zMedMm
        val Hmm = (zBgMm - zMedMm).coerceAtLeast(0.0)

        return "ROI @ z≈${zMedMm.roundToInt()} mm  •  " +
                "L≈${(Lmm/10).roundToInt()/10.0} cm, " +
                "W≈${(Wmm/10).roundToInt()/10.0} cm, " +
                "H≈${(Hmm/10).roundToInt()/10.0} cm"
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
