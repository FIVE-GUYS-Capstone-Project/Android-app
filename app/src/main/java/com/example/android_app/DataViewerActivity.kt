package com.example.android_app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan
import android.graphics.Rect
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType

class DataViewerActivity : AppCompatActivity() {

    private enum class Mode { RGB, DEPTH, OVERLAY }

    // prefs for viewer state
    private val prefs by lazy { getSharedPreferences("viewer_prefs", MODE_PRIVATE) }

    private val REQ_OPEN_BUNDLE = 31

    // --- Calibration / intrinsics (persisted) ---
    private var hfovDeg = 60.0   // default; persisted in prefs as 'hfov_deg'
    private var vfovDeg = 45.0   // default; persisted as 'vfov_deg'
    private var opticalDxMm = 0.0 // optional RGB↔ToF optical center offset (mm), 'opt_dx_mm'
    private var opticalDyMm = 0.0 // optional RGB↔ToF optical center offset (mm), 'opt_dy_mm'


    private var mode = Mode.OVERLAY
    private var overlayAlpha = 160 // 0..255
    private var alignDxPx = 0
    private var alignDyPx = 0
    private val ALIGN_RANGE = 80  // UI range: -40..+40


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

    private data class DepthSanity(val validFrac: Float, val gapMm: Double)

    // last saved bundle path (for sharing)
    private var lastBundleZip: File? = null
    private var lastBundleDir: File? = null
    private var currentRoiRgb: RectF? = null
    private var lastMeasurement: MeasurementResult? = null
    private var boxDetector: BoxDetector? = null

    private var palette: Palette = Palette.JET        // default now matches A010
    private var autoRangeDisplay = true               // auto [P2..P98] for display-only

    private fun applyModeUI() {
        val alphaSeek = findViewById<SeekBar>(R.id.alphaSeek)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        val enabled = (mode == Mode.OVERLAY)

        alphaSeek.isEnabled = enabled
        alphaSeek.alpha = if (enabled) 1f else 0.4f

        // ensure initial alpha is applied to the layer at first render too
        if (enabled) depthImage.alpha = (overlayAlpha.coerceIn(0, 255) / 255f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)

        // Restore last viewer state
        mode = try {
            Mode.valueOf(prefs.getString("viewer_mode", mode.name)!!)
        } catch (_: Exception) {
            Mode.OVERLAY
        }
        loadDetPrefs()
        overlayAlpha = prefs.getInt("viewer_alpha", overlayAlpha)
        alignDxPx = prefs.getInt("align_dx_px", 0)
        alignDyPx = prefs.getInt("align_dy_px", 0)
        // Load persisted calibration (Double via raw long bits)
        hfovDeg = java.lang.Double.longBitsToDouble(
            prefs.getLong("hfov_deg", java.lang.Double.doubleToRawLongBits(hfovDeg))
        )
        vfovDeg = java.lang.Double.longBitsToDouble(
            prefs.getLong("vfov_deg", java.lang.Double.doubleToRawLongBits(vfovDeg))
        )
        opticalDxMm = java.lang.Double.longBitsToDouble(
            prefs.getLong("opt_dx_mm", java.lang.Double.doubleToRawLongBits(opticalDxMm))
        )
        opticalDyMm = java.lang.Double.longBitsToDouble(
            prefs.getLong("opt_dy_mm", java.lang.Double.doubleToRawLongBits(opticalDyMm))
        )

        // Palette + auto-range (display-only)
        palette = runCatching { Palette.valueOf(prefs.getString("palette_name", palette.name)!!) }
            .getOrDefault(Palette.JET)
        autoRangeDisplay = prefs.getBoolean("auto_range_display", true)

        imgBytes = intent.getByteArrayExtra("imageBytes")
        depthBytes = intent.getByteArrayExtra("depthBytes")
        w = intent.getIntExtra("depthWidth", 0)
        h = intent.getIntExtra("depthHeight", 0)
        sMin = intent.getIntExtra("depthScaleMin", 0)
        sMax = intent.getIntExtra("depthScaleMax", 255)

        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        val depthText = findViewById<TextView>(R.id.depthText)
        val overlay = findViewById<RoiOverlayView>(R.id.roiOverlay)
        val detOverlay = findViewById<OverlayView>(R.id.overlayDetections)
        val dimsText = findViewById<TextView>(R.id.dimsText)

        // --- Alignment controls (X/Y nudgers)
        val seekX = findViewById<SeekBar>(R.id.alignSeekX)
        val seekY = findViewById<SeekBar>(R.id.alignSeekY)
        val valX = findViewById<TextView>(R.id.alignValX)
        val valY = findViewById<TextView>(R.id.alignValY)
        val reset = findViewById<Button>(R.id.alignReset)
        seekX?.max = ALIGN_RANGE; seekY?.max = ALIGN_RANGE
        fun refreshAlignLabels() {
            valX?.text = "${alignDxPx} px"
            valY?.text = "${alignDyPx} px"
        }
        seekX?.progress = alignDxPx + ALIGN_RANGE / 2
        seekY?.progress = alignDyPx + ALIGN_RANGE / 2
        refreshAlignLabels()
        fun applyAlignment() {
            // Visual path: shift the top depth layer in overlay mode
            val depthImage = findViewById<ImageView>(R.id.depthImage)
            depthImage.translationX = alignDxPx.toFloat()
            depthImage.translationY = alignDyPx.toFloat()
        }
        seekX?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                alignDxPx = p - ALIGN_RANGE / 2; prefs.edit().putInt("align_dx_px", alignDxPx)
                    .apply(); refreshAlignLabels(); applyAlignment()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        seekY?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                alignDyPx = p - ALIGN_RANGE / 2; prefs.edit().putInt("align_dy_px", alignDyPx)
                    .apply(); refreshAlignLabels(); applyAlignment()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // Long-press reset to open a simple FOV/offset calibrator (no XML required)
        reset?.setOnLongClickListener {
            showCalibrateDialog()
            true
        }
        reset?.setOnClickListener {
            alignDxPx = 0; alignDyPx = 0; seekX?.progress = ALIGN_RANGE / 2; seekY?.progress =
            ALIGN_RANGE / 2; refreshAlignLabels(); applyAlignment()
        }

        // Feed the overlay with the actual image draw area once layout is ready
        imageView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    updateOverlayBounds()
                }
            })

        overlay.onBoxFinished = { boxOnOverlay: RectF ->
            // Map from overlay coords → image pixel coords (RGB bitmap space)
            val rectRgb = viewToImageRect(
                findViewById(R.id.imageView), boxOnOverlay,
                rgbBmp?.width ?: 800, rgbBmp?.height ?: 600
            )
            // persist the ROI so the Measure button can use it
            currentRoiRgb = RectF(rectRgb)
            dimsText?.text = computeDimsFromRoi(rectRgb)
        }

        // --- Decode RGB
        imgBytes?.let {
            rgbBmp = BitmapFactory.decodeByteArray(it, 0, it.size)
            imageView.setImageBitmap(rgbBmp)
            Log.d("DataViewerActivity", "RGB ${rgbBmp?.width}x${rgbBmp?.height}")
        } ?: Log.e("DataViewerActivity", "imageBytes is null")

        // --- Colorize depth and scale to RGB size if present
        if (depthBytes != null && w > 0 && h > 0 && depthBytes!!.size >= w * h) {
            rebuildDepthVisualization()
        } else {
            depthText.text = "Depth bytes: ${depthBytes?.size ?: 0} (no META or size mismatch)"
            Log.w("DataViewerActivity", "No usable depth")
        }

        findViewById<ImageView>(R.id.depthImage).translationX = alignDxPx.toFloat()
        findViewById<ImageView>(R.id.depthImage).translationY = alignDyPx.toFloat()

        // initial legend will be set by rebuildDepthVisualization()
        findViewById<TextView>(R.id.legendMin).text = sMin.toString()
        findViewById<TextView>(R.id.legendMax).text = sMax.toString()

        val colorbar = findViewById<ImageView>(R.id.colorbar)
        colorbar.setOnClickListener {
            autoRangeDisplay = !autoRangeDisplay
            prefs.edit().putBoolean("auto_range_display", autoRangeDisplay).apply()
            rebuildDepthVisualization()
            Toast.makeText(
                this,
                "Display range: " + if (autoRangeDisplay) "Auto (P2–P98)" else "Meta ($sMin–$sMax)",
                Toast.LENGTH_SHORT
            ).show()
        }
        colorbar.setOnLongClickListener {
            palette = when (palette) {
                Palette.JET -> Palette.VIRIDIS; Palette.VIRIDIS -> Palette.GRAY; Palette.GRAY -> Palette.JET
            }
            prefs.edit().putString("palette_name", palette.name).apply()
            rebuildDepthVisualization()
            Toast.makeText(this, "Palette: ${palette.name}", Toast.LENGTH_SHORT).show()
            true
        }

        // --- Mode button cycles RGB → DEPTH → OVERLAY
        val modeButton = findViewById<Button>(R.id.modeButton)
        fun updateModeButtonText() {
            modeButton.text = "Mode: " + when (mode) {
                Mode.RGB -> "RGB"; Mode.DEPTH -> "Depth"; Mode.OVERLAY -> "Overlay"
            }
        }
        updateModeButtonText()
        applyModeUI()

        modeButton.setOnClickListener {
            mode = when (mode) {
                Mode.RGB -> Mode.DEPTH; Mode.DEPTH -> Mode.OVERLAY; Mode.OVERLAY -> Mode.RGB
            }
            prefs.edit().putString("viewer_mode", mode.name).apply()
            updateModeButtonText()
            render(imageView, depthImage)
            applyModeUI()
        }

        // --- Alpha slider
        val alphaSeek = findViewById<SeekBar>(R.id.alphaSeek)

        // Let the SeekBar keep the touch stream (prevents ScrollView from hijacking it)
        alphaSeek.setOnTouchListener { v, ev ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false // let SeekBar handle it
        }

        alphaSeek.progress = overlayAlpha
        if (mode == Mode.OVERLAY) findViewById<ImageView>(R.id.depthImage)
            .alpha = (overlayAlpha / 255f)
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

        findViewById<Button>(R.id.detectBtn)?.setOnLongClickListener {
            showDetTuningDialog(); true
        }

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
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/octet-stream")
                )
            }
            startActivityForResult(intent, REQ_OPEN_BUNDLE)
        }

        // initial render
        render(imageView, depthImage)
        updateOverlayBounds()

        // --- YOLO detector
        boxDetector = BoxDetector(this)

        // Buttons: Detect (YOLO) & Measure
        findViewById<Button>(R.id.detectBtn)?.setOnClickListener { runYoloDetect(detOverlay) }
        findViewById<Button>(R.id.measureBtn)?.setOnClickListener { runMeasurement(dimsText) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_BUNDLE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { loadBundleFromZip(it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent) // make getIntent() reflect the latest

        val newImg = intent.getByteArrayExtra("imageBytes")
        val newDepth = intent.getByteArrayExtra("depthBytes")
        val dw = intent.getIntExtra("depthWidth", 0)
        val dh = intent.getIntExtra("depthHeight", 0)
        val sMin = intent.getIntExtra("depthScaleMin", 0)
        val sMax = intent.getIntExtra("depthScaleMax", 255)

        // Only refresh the heavy pipeline when we have a full pair.
        if (newImg != null && newDepth != null && dw > 0 && dh > 0) {
            onNewFrameBytes(newImg, newDepth, dw, dh, sMin, sMax)

            // Re-render with the new data
            val imageView = findViewById<ImageView>(R.id.imageView)
            val depthImage = findViewById<ImageView>(R.id.depthImage)
            rebuildDepthVisualization()
            render(imageView, depthImage)
            updateOverlayBounds()
        }
    }

    /** Compute FOV (deg) from a drawn ROI spanning a known physical length at known distance. */
    private fun fovFromKnownTarget(
        pxLen: Float,
        knownMm: Double,
        Zmm: Double,
        imagePx: Int
    ): Double {
        if (pxLen <= 1f || knownMm <= 0 || Zmm <= 0) return Double.NaN
        val t = (imagePx * (knownMm / pxLen)) / (2.0 * Zmm)   // tan(FOV/2)
        return Math.toDegrees(2.0 * Math.atan(t))
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
                            name.endsWith("photo.jpg") -> photo = bytes
                            name.endsWith("depth_u8.raw") -> rawDepth = bytes
                            name.endsWith("meta.json") -> meta = JSONObject(bytes.decodeToString())
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
            Toast.makeText(
                this,
                "Bundle missing files (photo.jpg, depth_u8.raw, meta.json)",
                Toast.LENGTH_LONG
            ).show(); return
        }

        val dw = meta!!.optInt("depthWidth", 100)
        val dh = meta!!.optInt("depthHeight", 100)
        val sMin = meta!!.optInt("scaleMin", 0)
        val sMax = meta!!.optInt("scaleMax", 255)
        // optional: persisted alignment in bundle
        alignDxPx = meta!!.optInt("alignDxPx", alignDxPx)
        alignDyPx = meta!!.optInt("alignDyPx", alignDyPx)
        prefs.edit().putInt("align_dx_px", alignDxPx).putInt("align_dy_px", alignDyPx).apply()
        findViewById<SeekBar>(R.id.alignSeekX)?.progress = alignDxPx + ALIGN_RANGE / 2
        findViewById<SeekBar>(R.id.alignSeekY)?.progress = alignDyPx + ALIGN_RANGE / 2

        onNewFrameBytes(photo!!, rawDepth!!, dw, dh, sMin, sMax)
        rebuildDepthVisualization()

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
        depthColorScaled =
            Bitmap.createScaledBitmap(depthColorBmp!!, rgbBmp!!.width, rgbBmp!!.height, true)
        findViewById<ImageView>(R.id.depthImage).apply {
            translationX = alignDxPx.toFloat(); translationY = alignDyPx.toFloat()
        }

        // Apply to UI
        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        findViewById<TextView>(R.id.legendMin)?.text = sMin.toString()
        findViewById<TextView>(R.id.legendMax)?.text = sMax.toString()
        findViewById<ImageView>(R.id.colorbar)?.setImageBitmap(buildLegendBitmap(sMin, sMax))
        findViewById<TextView>(R.id.depthText)?.text =
            "Depth: ${w}×${h} • ${depthBytes.size} bytes • scale $sMin–$sMax (color)"

        render(imageView, depthImage)
        updateOverlayBounds()
    }

    /** Compute the 'fitCenter' content rect of imageView and send it to the overlay for clamping. */
    private fun updateOverlayBounds() {
        val iv = findViewById<ImageView>(R.id.imageView)
        val ov = findViewById<RoiOverlayView>(R.id.roiOverlay)
        val bw = rgbBmp?.width ?: return
        val bh = rgbBmp?.height ?: return
        ov.setContentBounds(computeImageDrawRect(iv, bw, bh))
    }

    private fun computeImageDrawRect(iv: ImageView, bmpW: Int, bmpH: Int): RectF {
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        val scale = min(vw / bmpW, vh / bmpH)
        val dw = bmpW * scale
        val dh = bmpH * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    private fun displayRange(depth: ByteArray?): Pair<Int, Int> {
        if (!autoRangeDisplay || depth == null) return sMin to sMax
        val hist = IntArray(256)
        for (b in depth) {
            val u = b.toInt() and 0xFF
            if (u in 1..254) hist[u]++
        }
        val total = hist.sum()
        if (total == 0) return sMin to sMax
        fun cum(frac: Double): Int {
            val target = (total * frac).toInt()
            var c = 0
            for (i in 1..254) {
                c += hist[i]; if (c >= target) return i
            }
            return 254
        }

        val lo = cum(0.02);
        val hi = cum(0.98).coerceAtLeast(lo + 1)
        return lo to hi
    }

    private fun rebuildDepthVisualization() {
        if (depthBytes == null || w <= 0 || h <= 0) return
        val (lo, hi) = displayRange(depthBytes)

        // legend + min/max labels reflect the DISPLAY range
        findViewById<TextView>(R.id.legendMin)?.text = lo.toString()
        findViewById<TextView>(R.id.legendMax)?.text = hi.toString()
        findViewById<ImageView>(R.id.colorbar)?.setImageBitmap(buildLegendBitmap(lo, hi))

        // recolor & (re)scale
        depthColorBmp = colorizeDepth(depthBytes!!, w, h, lo, hi)
        val rw = rgbBmp?.width ?: w
        val rh = rgbBmp?.height ?: h
        depthColorScaled = Bitmap.createScaledBitmap(depthColorBmp!!, rw, rh, true)

        // push to UI depending on mode
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        when (mode) {
            Mode.DEPTH -> {
                depthImage.setImageDrawable(null); findViewById<ImageView>(R.id.imageView).setImageBitmap(
                    depthColorBmp
                )
            }

            Mode.OVERLAY -> {
                depthImage.setImageBitmap(depthColorScaled); depthImage.alpha =
                    (overlayAlpha / 255f); depthImage.visibility = View.VISIBLE
            }

            else -> { /* RGB mode: nothing to do; render() handles base image */
            }
        }

        // diag line (meta vs display)
        findViewById<TextView>(R.id.depthText)?.text =
            "Depth: ${w}×${h} • ${depthBytes!!.size} bytes • meta $sMin–$sMax • display $lo–$hi • ${palette.name}"
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
                depthImage.translationX = alignDxPx.toFloat()
                depthImage.translationY = alignDyPx.toFloat()
                depthImage.visibility = View.VISIBLE
            }
        }
    }

    // Tunables for detector gating (persisted)
    data class DetParams(
        var conf: Float = 0.25f,
        var iou: Float = 0.45f,
        var areaMinPct: Float = 0.04f,   // of RGB area
        var areaMaxPct: Float = 0.90f
    )

    private var det = DetParams()

    private fun loadDetPrefs() {
        det = DetParams(
            conf = prefs.getFloat("det_conf", 0.25f),
            iou = prefs.getFloat("det_iou", 0.45f),
            areaMinPct = prefs.getFloat("det_area_min", 0.04f),
            areaMaxPct = prefs.getFloat("det_area_max", 0.90f),
        )
    }

    private fun saveDetPrefs() {
        prefs.edit()
            .putFloat("det_conf", det.conf)
            .putFloat("det_iou", det.iou)
            .putFloat("det_area_min", det.areaMinPct)
            .putFloat("det_area_max", det.areaMaxPct)
            .apply()
    }

    data class Letterbox(val scale: Float, val padX: Int, val padY: Int)

    private fun makeLetterboxedInput(src: Bitmap, size: Int = 640): Pair<Bitmap, Letterbox> {
        val s = kotlin.math.min(size / src.width.toFloat(), size / src.height.toFloat())
        val nw = (src.width * s).roundToInt()
        val nh = (src.height * s).roundToInt()
        val padX = (size - nw) / 2
        val padY = (size - nh) / 2

        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        val canvasBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvasBmp)
        c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
        return canvasBmp to Letterbox(s, padX, padY)
    }

    // --- RGB-only scoring: YOLO conf + center prior + area prior
    private fun rgbScore(r: Rect, conf: Float, imgW: Int, imgH: Int): Double {
        val area = (r.width().toLong() * r.height()).toDouble() / (imgW.toLong() * imgH.toLong())
        val cx = (r.centerX().toFloat() / imgW - 0.5f)
        val cy = (r.centerY().toFloat() / imgH - 0.5f)
        val center = (1.0 - (cx*cx + cy*cy).coerceAtMost(1f).toDouble())
        // weights: conf 60%, center 25%, area 15%
        return 0.60 * conf + 0.25 * center + 0.15 * area
    }

    private fun runYoloDetect(detOverlay: OverlayView?) {
        val rgb = rgbBmp ?: return
        val (input, lb) = makeLetterboxedInput(rgb, 640)

        // Use your existing API; if you later expose confidences, pass them through here.
        val boxes640: List<Rect> = boxDetector?.runInference(input) ?: emptyList()

        // Map 640×640 letterboxed → RGB pixel space
        val mapped: List<Triple<Rect, Float, Int>> = boxes640.map { r640 ->
            val r = Rect(
                ((r640.left   - lb.padX) / lb.scale).roundToInt().coerceIn(0, rgb.width),
                ((r640.top    - lb.padY) / lb.scale).roundToInt().coerceIn(0, rgb.height),
                ((r640.right  - lb.padX) / lb.scale).roundToInt().coerceIn(0, rgb.width),
                ((r640.bottom - lb.padY) / lb.scale).roundToInt().coerceIn(0, rgb.height)
            )
            // Until BoxDetector returns per-box confidences, use 1.0f; classId=0 placeholder.
            Triple(r, 1.0f, 0)
        }.filter { it.first.width() > 8 && it.first.height() > 8 }

        // Area priors from user-tunable prefs
        val areaMin = (det.areaMinPct * rgb.width * rgb.height).toLong()
        val areaMax = (det.areaMaxPct * rgb.width * rgb.height).toLong()

        // Score RGB-only (no depth gating here)
        val scored = mapped.mapNotNull { (r, conf, cls) ->
            val A = r.width().toLong() * r.height()
            if (A !in areaMin..areaMax) return@mapNotNull null
            val s = rgbScore(r, conf, rgb.width, rgb.height)
            Quad(r, conf, cls, s)
        }.sortedByDescending { it.s }

        if (scored.isNotEmpty()) {
            val best = scored.first()
            currentRoiRgb = RectF(best.r)
            findViewById<RoiOverlayView>(R.id.roiOverlay)?.showBox(RectF(best.r))
            detOverlay?.setOverlay(rgb, scored.map { it.r })

            // Optional: depth sanity shown only as a diagnostic (does not affect selection)
            val dbg = depthSanityForRect(best.r)
            Toast.makeText(
                this,
                "Picked ${1}/${scored.size} • conf=${"%.2f".format(best.conf)} • valid=${"%.2f".format(dbg.validFrac)} • gap=${"%.0f".format(dbg.gapMm)}mm",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, "No box after RGB filters.", Toast.LENGTH_SHORT).show()
        }
    }

    private data class Quad(val r: Rect, val conf: Float, val cls: Int, val s: Double)

    private fun depthSanityForRect(r: Rect): DepthSanity {
        // Map rect to depth grid (same mapping logic as computeDimsFromRoi, using alignDxPx/alignDyPx)
        if (depthBytes == null || w <= 0 || h <= 0 || rgbBmp == null) return DepthSanity(0f, 0.0)
        val rgbWf = rgbBmp!!.width.toFloat()
        val rgbHf = rgbBmp!!.height.toFloat()
        val sx = w.toFloat() / rgbWf
        val sy = h.toFloat() / rgbHf
        val adj = RectF(
            (r.left - alignDxPx).coerceIn(0, rgbBmp!!.width).toFloat(),
            (r.top - alignDyPx).coerceIn(0, rgbBmp!!.height).toFloat(),
            (r.right - alignDxPx).coerceIn(0, rgbBmp!!.width).toFloat(),
            (r.bottom - alignDyPx).coerceIn(0, rgbBmp!!.height).toFloat()
        )
        val xs = ((adj.left * sx).roundToInt()).coerceIn(0, w - 1)
        val ys = ((adj.top * sy).roundToInt()).coerceIn(0, h - 1)
        val xe = ((adj.right * sx).roundToInt()).coerceIn(0, w - 1)
        val ye = ((adj.bottom * sy).roundToInt()).coerceIn(0, h - 1)

        val inside = ArrayList<Int>();
        val ring = ArrayList<Int>()
        val pad = 3
        for (yy in ys..ye) for (xx in xs..xe) {
            val v = depthBytes!![yy * w + xx].toInt() and 0xFF
            if (v in 1..254) inside.add(v)
        }
        val rx0 = (xs - pad).coerceAtLeast(0);
        val ry0 = (ys - pad).coerceAtLeast(0)
        val rx1 = (xe + pad).coerceAtMost(w - 1);
        val ry1 = (ye + pad).coerceAtMost(h - 1)
        for (yy in ry0..ry1) for (xx in rx0..rx1) {
            val onBorder = (xx < xs || xx > xe || yy < ys || yy > ye)
            if (onBorder) {
                val v = depthBytes!![yy * w + xx].toInt() and 0xFF
                if (v in 1..254) ring.add(v)
            }
        }
        val validFrac =
            if ((xe - xs + 1) * (ye - ys + 1) == 0) 0f else inside.size.toFloat() / ((xe - xs + 1) * (ye - ys + 1)).toFloat()
        if (inside.isEmpty() || ring.isEmpty()) return DepthSanity(validFrac, 0.0)

        inside.sort(); ring.sort()
        fun u8ToMm(u: Int): Double {
            val span = (sMax - sMin).toDouble().coerceAtLeast(1.0)
            return sMin + (u / 255.0) * span
        }

        val zMed = u8ToMm(inside[inside.size / 2])
        val zBg = u8ToMm(ring[ring.size / 2])
        return DepthSanity(
            validFrac,
            (zBg - zMed)
        ) // positive if object is above ground plane (closer)
    }

    /** Full measurement pipeline using MeasurementEngine. */
    private fun runMeasurement(dimsText: TextView?) {
        val rgb =
            rgbBmp ?: run { Toast.makeText(this, "No RGB", Toast.LENGTH_SHORT).show(); return }
        val roi = currentRoiRgb ?: run {
            Toast.makeText(this, "Draw an ROI or tap Detect first.", Toast.LENGTH_SHORT)
                .show(); return
        }
        val depth = depthBytes ?: run {
            Toast.makeText(this, "No depth", Toast.LENGTH_SHORT).show(); return
        }

        val res = MeasurementEngine.measureFromRoi(
            roiRgb = roi, rgbW = rgb.width, rgbH = rgb.height,
            depthBytes = depth, depthW = w, depthH = h, sMin = sMin, sMax = sMax,
            hfovDeg = hfovDeg, vfovDeg = vfovDeg, alignDxPx = alignDxPx, alignDyPx = alignDyPx
        )
        lastMeasurement = res
        dimsText?.text = "L≈${"%.1f".format(res.lengthCm)} cm, " +
                "W≈${"%.1f".format(res.widthCm)} cm, " +
                "H≈${"%.1f".format(res.heightCm)} cm  •  conf=${"%.2f".format(res.confidence)}"

        if (res.satFrac > 0.25) {
            Toast.makeText(
                this,
                "Depth near range limits — move closer/farther or tilt to see the top face.",
                Toast.LENGTH_SHORT
            ).show()
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

        // Map RGB→depth grid. IMPORTANT: subtract the visual alignment (dx,dy) first so sampling matches overlay.
        val rgbWf = (rgbBmp?.width?.toFloat() ?: 800f)
        val rgbHf = (rgbBmp?.height?.toFloat() ?: 600f)
        val sx = w.toFloat() / rgbWf
        val sy = h.toFloat() / rgbHf
        val adj = RectF(
            (roiRgb.left - alignDxPx).coerceIn(0f, rgbWf),
            (roiRgb.top - alignDyPx).coerceIn(0f, rgbHf),
            (roiRgb.right - alignDxPx).coerceIn(0f, rgbWf),
            (roiRgb.bottom - alignDyPx).coerceIn(0f, rgbHf)
        )
        val dx0 = (adj.left * sx).roundToInt().coerceIn(0, w - 1)
        val dy0 = (adj.top * sy).roundToInt().coerceIn(0, h - 1)
        val dx1 = (adj.right * sx).roundToInt().coerceIn(0, w - 1)
        val dy1 = (adj.bottom * sy).roundToInt().coerceIn(0, h - 1)
        val xs = min(dx0, dx1);
        val xe = max(dx0, dx1)
        val ys = min(dy0, dy1);
        val ye = max(dy0, dy1)

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
        val mmPerPxX = 2.0 * zMedMm * tan(Math.toRadians(hfovDeg / 2.0)) / rgbW
        val mmPerPxY = 2.0 * zMedMm * tan(Math.toRadians(vfovDeg / 2.0)) / rgbH
        val Lmm = roiRgb.width().toDouble() * mmPerPxX
        val Wmm = roiRgb.height().toDouble() * mmPerPxY

        // crude height: median ring outside minus inside
        val ring = ArrayList<Int>()
        val pad = 4
        val rx0 = max(xs - pad, 0);
        val ry0 = max(ys - pad, 0)
        val rx1 = min(xe + pad, w - 1);
        val ry1 = min(ye + pad, h - 1)
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
                "L≈${(Lmm / 10).roundToInt() / 10.0} cm, " +
                "W≈${(Wmm / 10).roundToInt() / 10.0} cm, " +
                "H≈${(Hmm / 10).roundToInt() / 10.0} cm"
    }

    /** Colorize depth into ARGB using the current palette and [lo,hi] display range. */
    private fun colorizeDepth(depth: ByteArray, w: Int, h: Int, lo: Int, hi: Int): Bitmap {
        val lut = Colormaps.makeLut(palette, lo, hi)
        val pixels = IntArray(w * h) { i -> lut[depth[i].toInt() and 0xFF] }
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

    /** Horizontal legend using the current palette. */
    private fun buildLegendBitmap(lo: Int, hi: Int): Bitmap {
        val bmp = Bitmap.createBitmap(256, 10, Bitmap.Config.ARGB_8888)
        val lut = Colormaps.makeLut(palette, lo, hi)
        for (x in 0 until 256) {
            val c = lut[x]
            for (y in 0 until 10) bmp.setPixel(x, y, c)
        }
        return bmp
    }

    // ----- Used by Save/Share buttons -----

    private fun saveSessionBundle(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir(null), "captures/$stamp").apply { mkdirs() }

        // photo.jpg
        imgBytes?.let { FileOutputStream(File(dir, "photo.jpg")).use { it.write(this.imgBytes) } }

        // depth_u8.raw
        depthBytes?.let {
            FileOutputStream(
                File(
                    dir,
                    "depth_u8.raw"
                )
            ).use { it.write(this.depthBytes) }
        }

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
            // Also store the resolved working range the engine used (after defaults applied)
            .put("resolvedDepthMinMm", if (sMin == 0 && sMax == 255) 150 else sMin)
            .put("resolvedDepthMaxMm", if (sMin == 0 && sMax == 255) 1500 else sMax)
            .put("overlayAlpha", overlayAlpha)
            .put("mode", mode.name)
            .put("alignDxPx", alignDxPx)
            .put("alignDyPx", alignDyPx)
            .put("timestamp", stamp)
            // Intrinsics for this RGB size (derived from calibrated hfov/vfov)
            .put("hfovDeg", hfovDeg)
            .put("vfovDeg", vfovDeg)
            .put("cx", (rgbBmp?.width ?: 800) / 2.0)
            .put("cy", (rgbBmp?.height ?: 600) / 2.0)
            .put("fx", ((rgbBmp?.width ?: 800) / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))))
            .put("fy", ((rgbBmp?.height ?: 600) / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))))
            .put("opticalDxMm", opticalDxMm)
            .put("opticalDyMm", opticalDyMm)

        lastMeasurement?.let {
            meta.put("lengthCm", it.lengthCm)
                .put("widthCm", it.widthCm)
                .put("heightCm", it.heightCm)
                .put("measureConfidence", it.confidence)
                .put("nRoiPts", it.nRoiPts)
                .put("nPlaneInliers", it.nPlaneInliers)
                .put("roiDepthSaturationFrac", it.satFrac)
        }
        File(dir, "meta.json").writeText(meta.toString(2))
        return dir
    }

    private fun showDetTuningDialog() {
        val ctx = this
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun num(label: String, init: String): EditText =
            EditText(ctx).apply { hint = label; setText(init) }

        val eConf = num("YOLO conf (0–1)", "%.2f".format(det.conf))
        val eIou  = num("YOLO IoU (0–1)", "%.2f".format(det.iou))
        val eAmin = num("Min area %", "%.1f".format(det.areaMinPct * 100))
        val eAmax = num("Max area %", "%.1f".format(det.areaMaxPct * 100))
        listOf(eConf, eIou, eAmin, eAmax).forEach { wrap.addView(it) }

        AlertDialog.Builder(ctx)
            .setTitle("Detector thresholds")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                fun D(s: EditText, def: Double) = s.text.toString().trim().toDoubleOrNull() ?: def
                det.conf = D(eConf, det.conf.toDouble()).toFloat().coerceIn(0f, 1f)
                det.iou = D(eIou, det.iou.toDouble()).toFloat().coerceIn(0f, 1f)
                det.areaMinPct =
                    (D(eAmin, (det.areaMinPct * 100).toDouble()) / 100).toFloat().coerceIn(0f, 0.9f)
                det.areaMaxPct =
                    (D(eAmax, (det.areaMaxPct * 100).toDouble()) / 100).toFloat().coerceIn(0.1f, 1f)
                saveDetPrefs()
                Toast.makeText(ctx, "Thresholds saved", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun zipDirectory(dir: File): File {
        return ZipUtil.zip(dir, File(dir.parentFile, "${dir.name}.zip"))
    }

    // --- Simple calibration dialog: set HFOV/VFOV (deg) and optional (dx,dy) mm; values persisted in prefs.
    private fun showCalibrateDialog() {
        val ctx = this
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        fun mkNumberField(hint: String, value: Double): EditText =
            EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                this.hint = hint
                setText(String.format(java.util.Locale.US, "%.2f", value))
            }

        val eH = mkNumberField("HFOV (deg)", hfovDeg); wrap.addView(eH)
        val eV = mkNumberField("VFOV (deg)", vfovDeg); wrap.addView(eV)
        val eDx = mkNumberField("Optical Δx (mm, optional)", opticalDxMm); wrap.addView(eDx)
        val eDy = mkNumberField("Optical Δy (mm, optional)", opticalDyMm); wrap.addView(eDy)

        fun parse(et: EditText, def: Double): Double =
            et.text.toString().trim().toDoubleOrNull() ?: def

        AlertDialog.Builder(ctx)
            .setTitle("Calibrate intrinsics")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                hfovDeg = parse(eH, hfovDeg).coerceIn(20.0, 120.0)
                vfovDeg = parse(eV, vfovDeg).coerceIn(15.0, 120.0)
                opticalDxMm = parse(eDx, opticalDxMm)
                opticalDyMm = parse(eDy, opticalDyMm)
                prefs.edit()
                    .putLong("hfov_deg", java.lang.Double.doubleToRawLongBits(hfovDeg))
                    .putLong("vfov_deg", java.lang.Double.doubleToRawLongBits(vfovDeg))
                    .putLong("opt_dx_mm", java.lang.Double.doubleToRawLongBits(opticalDxMm))
                    .putLong("opt_dy_mm", java.lang.Double.doubleToRawLongBits(opticalDyMm))
                    .apply()
                Toast.makeText(
                    ctx,
                    "Saved (HFOV=${"%.1f".format(hfovDeg)}°, VFOV=${"%.1f".format(vfovDeg)}°)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("From ROI…") { _, _ ->
                val roi = currentRoiRgb
                val rgb = rgbBmp
                if (roi == null || rgb == null) {
                    Toast.makeText(this, "Draw an ROI over a known size first.", Toast.LENGTH_SHORT)
                        .show()
                    return@setNeutralButton
                }
                // Prompt for known size and distance
                val wrap2 = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad2 = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad2, pad2, pad2, pad2)
                }
                val eWmm = EditText(ctx).apply {
                    hint = "Known length (mm) along ROI axis"; setText("200")
                }
                val eZmm =
                    EditText(ctx).apply { hint = "Distance Z (mm) lens→target"; setText("600") }
                wrap2.addView(eWmm); wrap2.addView(eZmm)
                AlertDialog.Builder(ctx)
                    .setTitle("Compute FOV from ROI")
                    .setView(wrap2)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Apply") { _, _ ->
                        val known =
                            eWmm.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                        val Z = eZmm.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                        val horiz = roi.width() >= roi.height()
                        val fov = if (horiz)
                            fovFromKnownTarget(roi.width(), known, Z, rgb.width)
                        else
                            fovFromKnownTarget(roi.height(), known, Z, rgb.height)
                        if (!fov.isNaN()) {
                            if (horiz) hfovDeg = fov else vfovDeg = fov
                            prefs.edit().putLong(
                                if (horiz) "hfov_deg" else "vfov_deg",
                                java.lang.Double.doubleToRawLongBits(fov)
                            ).apply()
                            Toast.makeText(
                                ctx,
                                (if (horiz) "HFOV" else "VFOV") + " set to ${"%.1f".format(fov)}°",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .show()
            }
            .show()
    }
}


