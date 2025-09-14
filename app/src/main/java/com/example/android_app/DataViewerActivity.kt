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
import android.graphics.Rect
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import com.example.android_app.model.MaskResult
import com.example.android_app.model.OrientationResult
import com.example.android_app.model.DimResult
import com.example.android_app.model.PoseLabel
import com.example.android_app.model.Plane
import com.example.android_app.geometry.PixelToMetric

class DataViewerActivity : AppCompatActivity() {

    private enum class Mode { RGB, DEPTH, OVERLAY }

    // prefs for viewer state
    private val prefs by lazy { getSharedPreferences("viewer_prefs", MODE_PRIVATE) }

    private val REQ_OPEN_BUNDLE = 31

    private val REQ_PICK_GT_MASK = 71

    // --- Calibration / intrinsics (persisted) ---
    private var hfovDeg = 60.0   // default; persisted in prefs as 'hfov_deg'
    private var vfovDeg = 45.0   // default; persisted as 'vfov_deg'
    private var opticalDxMm = 0.0 // optional RGB↔ToF optical center offset (mm), 'opt_dx_mm'
    private var opticalDyMm = 0.0 // optional RGB↔ToF optical center offset (mm), 'opt_dy_mm'
    private val TOF_HFOV_DEG = 70.0
    private val TOF_VFOV_DEG = 60.0

    // --- Undistort LUT (built once per resolution) ---
    private var mapX: FloatArray? = null
    private var mapY: FloatArray? = null
    private var lutW = 0; private var lutH = 0

    private var useCompatCenterCrop = false
    private var useUndistort = true
    private var fxCal = Double.NaN
    private var fyCal = Double.NaN
    private var cxCal = Double.NaN
    private var cyCal = Double.NaN
    private var k1 = 0.0; private var k2 = 0.0; private var k3 = 0.0; private var k4 = 0.0

    private var ocvReady = false
    private var map1: Mat? = null
    private var map2: Mat? = null
    private var Knew: Mat? = null
    private var mapW = 0; private var mapH = 0

    private var mode = Mode.OVERLAY
    private var overlayAlpha = 160 // 0..255
    private var alignDxPx = 0
    private var alignDyPx = 0
    private val ALIGN_RANGE = 80  // UI range: -40..+40

    private var rgbBmp: Bitmap? = null
    private var depthColorBmp: Bitmap? = null
    private var depthColorScaled: Bitmap? = null

    private val ABOVE_PLANE_THRESH_MM = 20.0      // keep points this far above plane
    private val RANSAC_ITERS = 150               // plane-fit iterations
    private val RANSAC_INLIER_THRESH_MM = 6.0    // plane residual for inliers
    private val ROI_PAD_PX = 4                   // CC search pad around ROI (depth grid)

    private var lastMask: MaskResult? = null
    private var lastOrientation: OrientationResult? = null
    private var lastDims: DimResult? = null
    private val maskBuilder by lazy { com.example.android_app.geometry.MaskBuilder(TOF_HFOV_DEG, TOF_VFOV_DEG) }

    // keep raw inputs around for saving bundle
    private var imgBytes: ByteArray? = null
    private var depthBytes: ByteArray? = null
    private var w: Int = 0
    private var h: Int = 0
    private var sMin: Int = 0
    private var sMax: Int = 255

    // Maixsense A010 real working range (used when meta is normalized 0..255)
    private val A010_MIN_MM = 200
    private val A010_MAX_MM = 2500

    private data class DepthSanity(val validFrac: Float, val gapMm: Double)

    // last saved bundle path (for sharing)
    private var lastBundleZip: File? = null
    private var lastBundleDir: File? = null
    private var currentRoiRgb: RectF? = null
    private var lastMeasurement: MeasurementResult? = null
    private var boxDetector: BoxDetector? = null
    private var lastDetRect: Rect? = null
    private var lastDetScore: Float = 0f

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
        ocvReady = OpenCVLoader.initDebug()

        // Restore last viewer state
        mode = try {
            Mode.valueOf(prefs.getString("viewer_mode", mode.name)!!)
        } catch (_: Exception) {
            Mode.OVERLAY
        }
        loadDetPrefs()
        overlayAlpha = prefs.getInt("viewer_alpha", overlayAlpha)
        // Hard-lock alignment to 0 since RGB↔Depth rectification is now stable.
        alignDxPx = 0
        alignDyPx = 0
        prefs.edit().putInt("align_dx_px", 0).putInt("align_dy_px", 0).apply()

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

        findViewById<ImageView>(R.id.depthImage).translationX = 0f
        findViewById<ImageView>(R.id.depthImage).translationY = 0f

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

        // Long-press to open the intrinsics/offset calibration dialog.
        modeButton.setOnLongClickListener { showCalibrateDialog(); true }

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

        useCompatCenterCrop = prefs.getBoolean("compat_center_crop", false)
        useUndistort = prefs.getBoolean("use_undistort", true)
        fxCal = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_fx", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        fyCal = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_fy", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        cxCal = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_cx", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        cyCal = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_cy", java.lang.Double.doubleToRawLongBits(Double.NaN)))
        k1   = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_k1", 0))
        k2   = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_k2", 0))
        k3   = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_k3", 0))
        k4   = java.lang.Double.longBitsToDouble(prefs.getLong("rgb_k4", 0))

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
        // Once det prefs are loaded (loadDetPrefs() is already called earlier), push into detector:
                boxDetector?.setConfig(
                    BoxDetector.Config(
                        inputSize = 640,
                        confThr = det.conf.coerceIn(0f,1f),
                        iouThr = det.iou.coerceIn(0f,1f),
                        threads = 4,
                        tryGpu = true
                    )
                )

        // Button: Detect = pick ROI + (background) Orientation + Dimension
        findViewById<Button>(R.id.detectBtn)?.setOnClickListener {
            runDetectAndMeasure(detOverlay, dimsText)
        }

        // Keep IoU evaluator (long-press on the result text now)
        findViewById<TextView>(R.id.dimsText)?.setOnLongClickListener {
            if (lastMask == null) {
                Toast.makeText(this, "Build a mask first (Detect or draw ROI).", Toast.LENGTH_SHORT).show()
                true
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                startActivityForResult(intent, REQ_PICK_GT_MASK)
                true
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_BUNDLE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { loadBundleFromZip(it) }
        }
        if (requestCode == REQ_PICK_GT_MASK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null && lastMask != null) {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val gt = BitmapFactory.decodeStream(ins)
                    val iou = computeIoU(lastMask!!, gt)
                    Toast.makeText(this, "Mask IoU = ${"%.3f".format(iou)}", Toast.LENGTH_LONG).show()
                }
            }
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

    // --- Depth scale helper (U8 -> mm, inclusive of meta scale) ---
    private fun u8ToMm(u: Int): Double {
        return PixelToMetric.u8ToMm(u, sMin, sMax)
    }

    // Effective range we actually use for mm mapping
    private fun resolvedDepthRange(): Pair<Int, Int> =
        if (sMin == 0 && sMax == 255) A010_MIN_MM to A010_MAX_MM else sMin to sMax

    // --- Map RGB ROI -> depth rect (reuses same mapping logic as computeDimsFromRoi) ---
    private fun mapRgbRoiToDepthRect(roiRgb: RectF): Rect {
        val rgbWf = (rgbBmp?.width ?: 800).toFloat()
        val rgbHf = (rgbBmp?.height ?: 600).toFloat()
        val sx = w / rgbWf; val sy = h / rgbHf
        val adj = RectF(
            (roiRgb.left  - alignDxPx).coerceIn(0f, rgbWf),
            (roiRgb.top   - alignDyPx).coerceIn(0f, rgbHf),
            (roiRgb.right - alignDxPx).coerceIn(0f, rgbWf),
            (roiRgb.bottom- alignDyPx).coerceIn(0f, rgbHf)
        )
        // First pass (no optical shift) → rough depth
        val ax0 = (adj.left * sx).roundToInt().coerceIn(0, w-1)
        val ay0 = (adj.top  * sy).roundToInt().coerceIn(0, h-1)
        val ax1 = (adj.right* sx).roundToInt().coerceIn(0, w-1)
        val ay1 = (adj.bottom*sy).roundToInt().coerceIn(0, h-1)
        val xs0 = min(ax0, ax1); val xe0 = max(ax0, ax1)
        val ys0 = min(ay0, ay1); val ye0 = max(ay0, ay1)
        val samples = ArrayList<Int>()
        for (yy in ys0..ye0) for (xx in xs0..xe0) {
            val u = depthBytes!![yy*w + xx].toInt() and 0xFF
            if (u in 1..254) samples.add(u)
        }
        val zMedMm = if (samples.isNotEmpty())
            PixelToMetric.u8ToMm(samples.sorted()[samples.size/2], sMin, sMax)
        else 600.0

        val fxTof = w / (2.0 * Math.tan(Math.toRadians(TOF_HFOV_DEG/2.0)))
        val fyTof = h / (2.0 * Math.tan(Math.toRadians(TOF_VFOV_DEG/2.0)))
        val shiftXpx = (opticalDxMm / zMedMm) * fxTof
        val shiftYpx = (opticalDyMm / zMedMm) * fyTof

        val dx0 = ((adj.left  * sx) + shiftXpx).roundToInt().coerceIn(0, w-1)
        val dy0 = ((adj.top   * sy) + shiftYpx).roundToInt().coerceIn(0, h-1)
        val dx1 = ((adj.right * sx) + shiftXpx).roundToInt().coerceIn(0, w-1)
        val dy1 = ((adj.bottom* sy) + shiftYpx).roundToInt().coerceIn(0, h-1)
        return Rect(min(dx0,dx1), min(dy0,dy1), max(dx0,dx1), max(dy0,dy1))
    }

    private fun computeIoU(m: MaskResult, gtBmp: Bitmap): Double {
        // Resize GT mask to depth grid size so we can compare 1:1
        val gt = Bitmap.createScaledBitmap(gtBmp, w, h, false)

        fun predAt(x: Int, y: Int): Boolean {
            if (x < m.xs || x > m.xe || y < m.ys || y > m.ye) return false
            val px = x - m.xs
            val py = y - m.ys
            return m.mask[py * m.width + px]
        }

        var inter = 0
        var union = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = predAt(x, y)

            // Treat any non-transparent OR non-black pixel as foreground in the GT bitmap.
            val pix = gt.getPixel(x, y)
            val g = ((pix ushr 24) != 0) || ((pix and 0x00FFFFFF) != 0)

            if (p || g) union++
            if (p && g) inter++
        }
        return if (union == 0) 0.0 else inter.toDouble() / union.toDouble()
    }

    // --- 3×3 median in-place over a sub-rect (ignores 0/255) ---
    private fun median3x3Inplace(depth: ByteArray, xs: Int, ys: Int, xe: Int, ye: Int) {
        val tmp = depth.copyOf()
        fun u(ix: Int, iy: Int): Int = tmp[iy * w + ix].toInt() and 0xFF
        for (y in ys..ye) for (x in xs..xe) {
            val vals = IntArray(9); var n = 0
            var k = 0
            for (yy in (y-1).coerceAtLeast(0)..(y+1).coerceAtMost(h-1))
                for (xx in (x-1).coerceAtLeast(0)..(x+1).coerceAtMost(w-1)) {
                    val v = u(xx, yy)
                    if (v in 1..254) { vals[n++] = v }
                    k++
                }
            if (n >= 3) {                    // need a few valid neighbors
                java.util.Arrays.sort(vals, 0, n)
                depth[y * w + x] = vals[n/2].toByte()
            }
        }
    }

    // --- Build ring index around a rect (pad pixels) ---
    private fun ringPixels(xs: Int, ys: Int, xe: Int, ye: Int, pad: Int): IntArray {
        val rx0 = (xs - pad).coerceAtLeast(0); val ry0 = (ys - pad).coerceAtLeast(0)
        val rx1 = (xe + pad).coerceAtMost(w - 1); val ry1 = (ye + pad).coerceAtMost(h - 1)
        val idx = ArrayList<Int>()
        for (yy in ry0..ry1) for (xx in rx0..rx1) {
            val onBorder = (xx < xs || xx > xe || yy < ys || yy > ye)
            if (onBorder) idx.add(yy * w + xx)
        }
        return idx.toIntArray()
    }

    // --- Project depth px -> 3D (camera coords) using ToF FOVs ---
    private fun projectTo3D(x: Int, y: Int, zMm: Double): DoubleArray {
        val fxTof = w / (2.0 * Math.tan(Math.toRadians(TOF_HFOV_DEG / 2.0)))
        val fyTof = h / (2.0 * Math.tan(Math.toRadians(TOF_VFOV_DEG / 2.0)))
        val cxTof = w / 2.0; val cyTof = h / 2.0
        val X = ((x - cxTof) / fxTof) * zMm
        val Y = ((y - cyTof) / fyTof) * zMm
        val Z = zMm
        return doubleArrayOf(X, Y, Z)
    }

    // --- Plane from 3 points ---
    private fun planeFrom3(a: DoubleArray, b: DoubleArray, c: DoubleArray): Plane? {
        // n = (b-a) × (c-a)
        val ux = b[0]-a[0]; val uy = b[1]-a[1]; val uz = b[2]-a[2]
        val vx = c[0]-a[0]; val vy = c[1]-a[1]; val vz = c[2]-a[2]
        val nx = uy*vz - uz*vy
        val ny = uz*vx - ux*vz
        val nz = ux*vy - uy*vx
        val n2 = nx*nx + ny*ny + nz*nz
        if (n2 < 1e-12) return null
        val d = -(nx*a[0] + ny*a[1] + nz*a[2])
        return Plane(nx, ny, nz, d)
    }

    // --- RANSAC plane on ring pixels (uses mm distances) ---
    private fun ransacGroundPlane(depth: ByteArray, ringIdx: IntArray): Plane? {
        if (ringIdx.size < 50) return null
        val rnd = java.util.Random(1234L)
        var best: Plane? = null
        var bestInliers = 0

        repeat(RANSAC_ITERS) {
            // sample 3 valid, well-separated ring pixels
            var aIdx = -1; var bIdx = -1; var cIdx = -1
            var tries = 0
            while (tries++ < 50) {
                aIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                bIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                cIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                if (aIdx == bIdx || bIdx == cIdx || aIdx == cIdx) continue
                val az = depth[aIdx].toInt() and 0xFF
                val bz = depth[bIdx].toInt() and 0xFF
                val cz = depth[cIdx].toInt() and 0xFF
                if (az !in 1..254 || bz !in 1..254 || cz !in 1..254) continue
                val ax = aIdx % w; val ay = aIdx / w
                val bx = bIdx % w; val by = bIdx / w
                val cx = cIdx % w; val cy = cIdx / w
                val A = projectTo3D(ax, ay, u8ToMm(az))
                val B = projectTo3D(bx, by, u8ToMm(bz))
                val C = projectTo3D(cx, cy, u8ToMm(cz))
                val p = planeFrom3(A, B, C) ?: continue
                // score: inliers in ring
                var count = 0
                for (idx in ringIdx) {
                    val z = depth[idx].toInt() and 0xFF
                    if (z !in 1..254) continue
                    val x = idx % w; val y = idx / w
                    val P = projectTo3D(x, y, u8ToMm(z))
                    val dist = kotlin.math.abs(p.signedDistanceMm(P[0], P[1], P[2]))
                    if (dist <= RANSAC_INLIER_THRESH_MM) count++
                }
                if (count > bestInliers) { bestInliers = count; best = p }
                break
            }
        }

        // If nothing good: fall back to flat plane z = median(ring)
        if (best == null) {
            val vals = ringIdx.map { depth[it].toInt() and 0xFF }.filter { it in 1..254 }.sorted()
            if (vals.isEmpty()) return null
            val zMed = u8ToMm(vals[vals.size/2])
            // z = zMed  =>  0*x + 0*y + 1*z - zMed = 0  => n=(0,0,1), d=-zMed
            return Plane(0.0, 0.0, 1.0, -zMed)
        }
        return best
    }

    // --- Connected component on boolean grid (sub-rect). Returns mask of "best" component ---
    private fun ccBiggestOverlapWithRoi(
        cand: BooleanArray, width: Int, height: Int,
        roiXs: Int, roiYs: Int, roiXe: Int, roiYe: Int
    ): BooleanArray {
        val visited = BooleanArray(cand.size)
        var bestMask = BooleanArray(cand.size)
        var bestOverlap = -1

        fun inside(x:Int,y:Int)= x in 0 until width && y in 0 until height
        val qx = IntArray(width*height)
        val qy = IntArray(width*height)

        for (y in 0 until height) for (x in 0 until width) {
            val idx = y*width + x
            if (!cand[idx] || visited[idx]) continue
            var h=0; var t=0
            qx[t]=x; qy[t]=y; t++
            visited[idx]=true
            var overlap=0
            val curMask = ArrayList<Int>()

            while (h < t) {
                val cx = qx[h]; val cy = qy[h]; h++
                val cidx = cy*width + cx
                curMask.add(cidx)
                val gx = cx + (roiXs)  // sub-rect -> global depth coords
                val gy = cy + (roiYs)
                if (gx >= roiXs && gx <= roiXe && gy >= roiYs && gy <= roiYe) overlap++

                val dirs = intArrayOf(1,0, -1,0, 0,1, 0,-1)
                var i=0
                while (i<8) {
                    val nx = cx + dirs[i]; val ny = cy + dirs[i+1]; i+=2
                    if (!inside(nx,ny)) continue
                    val nidx = ny*width + nx
                    if (!cand[nidx] || visited[nidx]) continue
                    visited[nidx]=true
                    qx[t]=nx; qy[t]=ny; t++
                }
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestMask = BooleanArray(cand.size)
                for (id in curMask) bestMask[id]=true
            }
        }
        return bestMask
    }

    private fun makeMaskDepthBitmapFull(mr: MaskResult): Bitmap {
        val arr = IntArray(w * h) { 0 }
        var i = 0
        for (yy in mr.ys..mr.ye) for (xx in mr.xs..mr.xe) {
            if (mr.mask[i++]) {
                arr[yy * w + xx] = 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(arr, 0, w, 0, 0, w, h)
        }
    }

    // --- Build a colored overlay bitmap (depth-grid mask -> RGB size) ---
    private fun makeMaskOverlayBitmap(mr: MaskResult, color: Int = 0x6600FF00.toInt()): Bitmap {
        val arr = IntArray(w * h) { 0 }
        var i = 0
        for (yy in mr.ys..mr.ye) for (xx in mr.xs..mr.xe) {
            if (mr.mask[i++]) arr[yy * w + xx] = color
        }
        val maskBmpDepth = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        maskBmpDepth.setPixels(arr, 0, w, 0, 0, w, h)
        val rw = rgbBmp?.width ?: w; val rh = rgbBmp?.height ?: h
        return Bitmap.createScaledBitmap(maskBmpDepth, rw, rh, false)
    }

    /** Pick ROI (YOLO or existing), then run mask → orientation → dimension off the UI thread. */
    private fun runDetectAndMeasure(detOverlay: OverlayView?, dimsText: TextView?) {
        val rgb = rgbBmp ?: return
        // Reuse your existing YOLO mapping code to propose the best box.
        val roi: RectF? = pickRoiWithYolo(rgb)?.let { RectF(it) } ?: currentRoiRgb
        if (roi == null) {
            Toast.makeText(this, "No ROI — draw a box or tap Detect again.", Toast.LENGTH_SHORT).show()
            return
        }
        currentRoiRgb = roi
        findViewById<RoiOverlayView>(R.id.roiOverlay)?.showBox(RectF(roi))

        setBusy(true)
        Thread {
            try {
                val depth = depthBytes
                if (depth == null || w <= 0 || h <= 0) {
                    runOnUiThread {
                        setBusy(false)
                        Toast.makeText(this, "No depth available.", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                // Build mask in depth grid from ROI
                val mask = maskBuilder.build(
                    roi, rgb.width, rgb.height,
                    depth, w, h, sMin, sMax, alignDxPx, alignDyPx
                )
                if (mask == null) {
                    runOnUiThread {
                        setBusy(false)
                        Toast.makeText(this, "Mask/plane failed — try moving closer.", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                // Gap & orientation
                val dbg = depthSanityForRect(Rect(roi.left.toInt(), roi.top.toInt(), roi.right.toInt(), roi.bottom.toInt()))
                val orient = com.example.android_app.geometry.OrientationEstimator.estimate(mask, dbg.gapMm)

                // Depth @ ROI (median) + px→mm + dimensions
                val fxEff = if (!fxCal.isNaN()) fxCal else rgb.width  / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))
                val fyEff = if (!fyCal.isNaN()) fyCal else rgb.height / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))
                val zMedMm = com.example.android_app.geometry.PixelToMetric.medianDepthMm(depth, w, h, sMin, sMax, mask)
                val maskArea = mask.mask.count { it }
                val dims = com.example.android_app.geometry.PixelToMetric.estimateDims(
                    orient.obb, zMedMm, fxEff, fyEff, mask.validFrac, orient.gapMm, maskArea
                )

                // Ship to UI once
                runOnUiThread {
                    lastMask = mask
                    lastOrientation = orient
                    lastDims = dims
                    updateUiWithResults(orient, dims, detOverlay, dimsText)
                    setBusy(false)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    Toast.makeText(this, "Measure failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

        private fun setBusy(b: Boolean) {
            findViewById<Button>(R.id.detectBtn)?.isEnabled = !b
            findViewById<android.widget.ProgressBar?>(R.id.measProgress)?.visibility = if (b) View.VISIBLE else View.GONE
        }

        private fun updateUiWithResults(
            orient: OrientationResult,
            dims: DimResult,
            detOverlay: OverlayView?,
            dimsText: TextView?
            ) {

            // Chip
            val chip = findViewById<TextView?>(R.id.orientationChip)
            val pose = if (orient.pose == PoseLabel.Front) "Front" else "Side"
            val stand = if (orient.standup) "Standup" else "Laydown"
            chip?.text = "$pose • $stand • ${"%.0f".format(orient.angleDeg)}°"

            // Dims string
            val l = dims.lengthMm / 10.0
            val wcm = dims.widthMm / 10.0
            val hPart = if (dims.heightMm != null) ", H≈${"%.1f".format(dims.heightMm/10.0)} cm" else ""
            val conf = " • conf=${"%.2f".format(dims.confidence)}"
            dimsText?.text = "L≈${"%.1f".format(l)} cm, W≈${"%.1f".format(wcm)} cm$hPart$conf"
        }

    private fun pickRoiWithYolo(rgb: Bitmap): Rect? {
        val (input, lb) = makeLetterboxedInput(rgb, 640)
        val dets = boxDetector?.detect(input) ?: emptyList()
        if (dets.isEmpty()) return null

        // Map back to RGB space and keep score/class
        data class M(val r: Rect, val score: Float, val cls: Int)
        val mapped: List<M> = dets.map { d ->
            val r = Rect(
                ((d.rect.left   - lb.padX) / lb.scale).roundToInt().coerceIn(0, rgb.width),
                ((d.rect.top    - lb.padY) / lb.scale).roundToInt().coerceIn(0, rgb.height),
                ((d.rect.right  - lb.padX) / lb.scale).roundToInt().coerceIn(0, rgb.width),
                ((d.rect.bottom - lb.padY) / lb.scale).roundToInt().coerceIn(0, rgb.height)
            )
            M(r, d.score, d.classId)
        }.filter { it.r.width() > 8 && it.r.height() > 8 }
        if (mapped.isEmpty()) return null

        // Area gates (same as before)
        val areaMin = (det.areaMinPct * rgb.width * rgb.height).toLong()
        val areaMax = (det.areaMaxPct * rgb.width * rgb.height).toLong()
        val gated = mapped.filter {
            val A = it.r.width().toLong() * it.r.height().toLong()
            A in areaMin..areaMax
        }
        if (gated.isEmpty()) return null

        // Prefer highest score; area/center are just tiebreakers now.
        fun centerPrior(r: Rect): Double {
            val cx = r.centerX().toFloat() / rgb.width - 0.5f
            val cy = r.centerY().toFloat() / rgb.height - 0.5f
            return (1.0 - (cx*cx + cy*cy).coerceAtMost(1f).toDouble())
        }
        val best = gated.maxWithOrNull(
            compareBy<M> { it.score }
                .thenBy { centerPrior(it.r) }
                .thenBy { it.r.width().toLong() * it.r.height().toLong() }
        )!!

        // Hysteresis: keep last if similar and new isn’t meaningfully better.
        val keep = lastDetRect?.let { prev ->
            val iou = iou(prev, best.r)
            val better = best.score >= (lastDetScore + 0.05f)
            android.util.Log.d("Detector", "bestScore=${"%.2f".format(best.score)} last=${"%.2f".format(lastDetScore)} IoU=${"%.2f".format(iou)} keep=${(iou>=0.5f && !better)}")
            (iou >= 0.5f && !better)
        } ?: false
        if (!keep) { lastDetRect = Rect(best.r); lastDetScore = best.score }
        return lastDetRect
    }

    private fun iou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val inter = max(0, interRight - interLeft) * max(0, interBottom - interTop)
        val aA = a.width() * a.height()
        val bA = b.width() * b.height()
        return if (aA + bA - inter == 0) 0f else inter.toFloat() / (aA + bA - inter).toFloat()
    }

    // --- Draw mask onto the top overlay layer (depthImage) without changing modes ---
    private fun showMaskOverlay(mr: MaskResult) {
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        val base = depthColorScaled ?: return
        val merged = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(merged).drawBitmap(makeMaskOverlayBitmap(mr), 0f, 0f, null)
        depthImage.setImageBitmap(merged)
    }

    /** pipeline: ROI -> boolean mask in depth grid */
    private fun buildDepthMaskFromRoi(roiRgb: RectF): MaskResult? {
        if (depthBytes == null || w <= 0 || h <= 0) return null

        // Map ROI to depth rect; compute ring for plane
        val r = mapRgbRoiToDepthRect(roiRgb)
        val xs = r.left; val xe = r.right; val ys = r.top; val ye = r.bottom
        val ring = ringPixels(xs, ys, xe, ye, pad = 6)
        if (ring.isEmpty()) return null

        // Validity stats inside ROI + smoothing (in-place copy)
        val d = depthBytes!!.copyOf()
        var validCount = 0; val totalCount = (xe - xs + 1) * (ye - ys + 1)
        for (yy in ys..ye) for (xx in xs..xe) {
            val v = d[yy * w + xx].toInt() and 0xFF
            if (v in 1..254) validCount++
        }
        val validFrac = if (totalCount == 0) 0f else validCount.toFloat() / totalCount.toFloat()
        // 3×3 median denoise inside ROI only
        median3x3Inplace(d, xs, ys, xe, ye)

        // Ground plane via RANSAC on ring (robust), fallback to flat
        val plane = ransacGroundPlane(d, ring) ?: return null

        // Candidate pixels: valid & ≥ threshold above plane (ROI ± pad for connectivity)
        val px0 = (xs - ROI_PAD_PX).coerceAtLeast(0)
        val py0 = (ys - ROI_PAD_PX).coerceAtLeast(0)
        val px1 = (xe + ROI_PAD_PX).coerceAtMost(w - 1)
        val py1 = (ye + ROI_PAD_PX).coerceAtMost(h - 1)
        val subW = px1 - px0 + 1
        val subH = py1 - py0 + 1
        val cand = BooleanArray(subW * subH)

        var idx = 0
        for (yy in py0..py1) for (xx in px0..px1) {
            val u = d[yy * w + xx].toInt() and 0xFF
            if (u !in 1..254) { idx++; continue }
            val z = u8ToMm(u)
            val P = projectTo3D(xx, yy, z)
            val above = plane.signedDistanceMm(P[0], P[1], P[2])
            cand[idx++] = above >= ABOVE_PLANE_THRESH_MM
        }

        // Connected component: pick the component with biggest overlap with the ROI
        val best = ccBiggestOverlapWithRoi(
            cand, subW, subH,
            roiXs = xs - px0, roiYs = ys - py0, roiXe = xe - px0, roiYe = ye - py0
        )

        return MaskResult(
            xs = px0, ys = py0, xe = px1, ye = py1,
            width = subW, height = subH,
            mask = best,
            validFrac = validFrac,
            plane = plane,
            heightThreshMm = ABOVE_PLANE_THRESH_MM
        )
    }

    private fun buildRectifyMapsIfNeeded(w: Int, h: Int) {
        if (!ocvReady) return
        if (map1 != null && mapW == w && mapH == h) return

        // Source intrinsics (use calibrated if present, else synthesize from current RGB FOVs)
        if (fxCal.isNaN() || fyCal.isNaN() || cxCal.isNaN() || cyCal.isNaN()) {
            fxCal = w / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))
            fyCal = h / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))
            cxCal = w / 2.0
            cyCal = h / 2.0
        }

        val K = Mat.eye(3, 3, CvType.CV_64F)
        K.put(0, 0, fxCal); K.put(0, 2, cxCal)
        K.put(1, 1, fyCal); K.put(1, 2, cyCal)

        // Standard Brown–Conrady distortion vector (k1,k2,p1,p2,k3).
        // If you only have radial k1..k3, map them best-effort and keep p1=p2=0.
        val D = Mat.zeros(1, 5, CvType.CV_64F)
        D.put(0, 0, k1, k2, 0.0, 0.0, k3)  // k4 ignored; fine for our use

        // Rectify RGB but keep its own (calibrated/persisted) FOV — don't coerce to ToF FOV.
        val fxTarget = w / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))
        val fyTarget = h / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))

        Knew = Mat.eye(3, 3, CvType.CV_64F)
        Knew!!.put(0, 0, fxTarget); Knew!!.put(1, 1, fyTarget)
        Knew!!.put(0, 2, w / 2.0);  Knew!!.put(1, 2, h / 2.0)

        val R = Mat.eye(3, 3, CvType.CV_64F)
        val size = Size(w.toDouble(), h.toDouble())

        map1 = Mat(); map2 = Mat()
        Calib3d.initUndistortRectifyMap(K, D, R, Knew, size, CvType.CV_32FC1, map1, map2)
        mapW = w; mapH = h

        // Persist rectified intrinsics (so MeasurementEngine uses the same geometry)
        fxCal = fxTarget; fyCal = fyTarget; cxCal = w / 2.0; cyCal = h / 2.0
        prefs.edit()
            .putLong("rgb_fx", java.lang.Double.doubleToRawLongBits(fxCal))
            .putLong("rgb_fy", java.lang.Double.doubleToRawLongBits(fyCal))
            .putLong("rgb_cx", java.lang.Double.doubleToRawLongBits(cxCal))
            .putLong("rgb_cy", java.lang.Double.doubleToRawLongBits(cyCal))
            .apply()
    }

    private fun undistortBitmapOCV(src: Bitmap): Bitmap {
        buildRectifyMapsIfNeeded(src.width, src.height)
        val m1 = map1 ?: return centerCropApprox100deg(src)
        val m2 = map2 ?: return centerCropApprox100deg(src)

        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val dst = Mat()
        Imgproc.remap(srcMat, dst, m1, m2, Imgproc.INTER_LANCZOS4)

        // Convert once, then free Mats quickly
        val rectified = Bitmap.createBitmap(dst.cols(), dst.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dst, rectified)
        srcMat.release(); dst.release()

        val sharpened = unsharpMask(rectified, 0.22f)
        rectified.recycle()            // free the intermediate bitmap ASAP
        return sharpened
    }

    // Unused parameter removed; same logic.
    private fun unsharpMask(src: Bitmap, amount: Float = 0.18f): Bitmap {
        val w = src.width; val h = src.height; val n = w * h
        val inPix = IntArray(n)
        src.getPixels(inPix, 0, w, 0, 0, w, h)

        fun blur3x3(): IntArray {
            val out = IntArray(n)
            fun clamp(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
            var i = 0
            for (y in 0 until h) for (x in 0 until w) {
                var sr=0; var sg=0; var sb=0; var cnt=0
                for (yy in y-1..y+1) for (xx in x-1..x+1) {
                    val cx = clamp(xx, 0, w-1); val cy = clamp(yy, 0, h-1)
                    val c = inPix[cy*w + cx]
                    sr += (c ushr 16) and 255; sg += (c ushr 8) and 255; sb += c and 255; cnt++
                }
                out[i++] = (0xFF shl 24) or ((sr/cnt) shl 16) or ((sg/cnt) shl 8) or (sb/cnt)
            }
            return out
        }

        val blurred = blur3x3()
        val outPix = IntArray(n)
        val a = amount.coerceIn(0f, 1f)

        fun clamp8(v: Int): Int = when {
            v < 0 -> 0
            v > 255 -> 255
            else -> v
        }

        for (i in 0 until n) {
            val s = inPix[i]; val b = blurred[i]
            val sr = (s ushr 16) and 255; val sg = (s ushr 8) and 255; val sb = s and 255
            val br = (b ushr 16) and 255; val bg = (b ushr 8) and 255; val bb = b and 255
            val r = clamp8((sr + a * (sr - br)).toInt())
            val g = clamp8((sg + a * (sg - bg)).toInt())
            val bl= clamp8((sb + a * (sb - bb)).toInt())
            outPix[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(outPix, 0, w, 0, 0, w, h)
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

    private fun buildUndistortMapsIfNeeded(w: Int, h: Int) {
        if (!useUndistort || useCompatCenterCrop) return
        if (mapX != null && lutW == w && lutH == h) return
        if (fxCal.isNaN() || fyCal.isNaN() || cxCal.isNaN() || cyCal.isNaN()) return

        lutW = w; lutH = h
        mapX = FloatArray(w * h)
        mapY = FloatArray(w * h)

        // For each *undistorted* pixel, compute where to sample in the *distorted* source (Brown–Conrady radial)
        var i = 0
        for (y in 0 until h) {
            val yn = (y - cyCal) / fyCal
            for (x in 0 until w) {
                val xn = (x - cxCal) / fxCal
                val r2 = xn*xn + yn*yn
                val radial = 1.0 + k1*r2 + k2*r2*r2 + k3*r2*r2*r2 + k4*r2*r2*r2*r2
                val xd = xn * radial
                val yd = yn * radial
                mapX!![i] = (fxCal * xd + cxCal).toFloat()
                mapY!![i] = (fyCal * yd + cyCal).toFloat()
                i++
            }
        }
    }

    private fun undistortBitmap(src: Bitmap): Bitmap {
        if (!useUndistort || useCompatCenterCrop) return src
        buildUndistortMapsIfNeeded(src.width, src.height)
        val mx = mapX ?: return src
        val my = mapY ?: return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val srcPix = IntArray(src.width * src.height); src.getPixels(srcPix, 0, src.width, 0, 0, src.width, src.height)
        val outPix = IntArray(src.width * src.height)

        fun sampleBilinear(ix: Float, iy: Float): Int {
            val x0 = kotlin.math.floor(ix).toInt().coerceIn(0, src.width - 1)
            val y0 = kotlin.math.floor(iy).toInt().coerceIn(0, src.height - 1)
            val x1 = (x0 + 1).coerceIn(0, src.width - 1)
            val y1 = (y0 + 1).coerceIn(0, src.height - 1)
            val fx = ix - x0; val fy = iy - y0
            fun p(x: Int, y: Int) = srcPix[y * src.width + x]
            // Interpolate in ARGB (premul not required for plain photos)
            val c00 = p(x0, y0); val c10 = p(x1, y0); val c01 = p(x0, y1); val c11 = p(x1, y1)
            fun ch(s: Int) = intArrayOf((s shr 16) and 255, (s shr 8) and 255, s and 255)
            val a = 255
            val (r00,g00,b00) = ch(c00); val (r10,g10,b10) = ch(c10); val (r01,g01,b01) = ch(c01); val (r11,g11,b11) = ch(c11)
            fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
            val rx0 = lerp(r00.toDouble(), r10.toDouble(), fx.toDouble()); val rx1 = lerp(r01.toDouble(), r11.toDouble(), fx.toDouble())
            val gx0 = lerp(g00.toDouble(), g10.toDouble(), fx.toDouble()); val gx1 = lerp(g01.toDouble(), g11.toDouble(), fx.toDouble())
            val bx0 = lerp(b00.toDouble(), b10.toDouble(), fx.toDouble()); val bx1 = lerp(b01.toDouble(), b11.toDouble(), fx.toDouble())
            val r = lerp(rx0, rx1, fy.toDouble()).toInt().coerceIn(0,255)
            val g = lerp(gx0, gx1, fy.toDouble()).toInt().coerceIn(0,255)
            val b = lerp(bx0, bx1, fy.toDouble()).toInt().coerceIn(0,255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        for (i in outPix.indices) outPix[i] = sampleBilinear(mx[i], my[i])
        out.setPixels(outPix, 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    private fun centerCropApprox100deg(src: Bitmap): Bitmap {
        if (!useCompatCenterCrop) return src
        // keep square pixels, crop ~central 60–65% for 160°→~100° (tweak if needed)
        val crop = (0.65f * kotlin.math.min(src.width, src.height)).toInt()
        val x0 = (src.width - crop) / 2
        val y0 = (src.height - crop) / 2
        return Bitmap.createBitmap(src, x0, y0, crop, crop)
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

    private fun rectifyToTofOrFallback(src: Bitmap): Bitmap {
        return try {
            if (!ocvReady) return centerCropApprox100deg(src)
            undistortBitmapOCV(src)  // Option B inside
        } catch (_: Throwable) {
            centerCropApprox100deg(src)
        }
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
        rgbBmp = rectifyToTofOrFallback(rgbBmp!!)
        depthColorBmp = colorizeDepth(depthBytes, w, h, sMin, sMax)
        depthColorScaled = Bitmap.createScaledBitmap(depthColorBmp!!, rgbBmp!!.width, rgbBmp!!.height, true)

        // Apply to UI
        val imageView = findViewById<ImageView>(R.id.imageView)
        val depthImage = findViewById<ImageView>(R.id.depthImage)
        findViewById<TextView>(R.id.legendMin)?.text = sMin.toString()
        findViewById<TextView>(R.id.legendMax)?.text = sMax.toString()
        findViewById<ImageView>(R.id.colorbar)?.setImageBitmap(buildLegendBitmap(sMin, sMax))
        val (useMin, useMax) = resolvedDepthRange()
        findViewById<TextView>(R.id.depthText)?.text =
            "Depth: ${w}×${h} • ${depthBytes.size} bytes • meta $sMin–$sMax • used $useMin–$useMax (mm)"

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

        // diag line (meta vs display vs used)
        val (useMin, useMax) = resolvedDepthRange()
        findViewById<TextView>(R.id.depthText)?.text =
            "Depth: ${w}×${h} • ${depthBytes!!.size} bytes • meta $sMin–$sMax • used $useMin–$useMax • display $lo–$hi • ${palette.name}"
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
                depthImage.translationX = 0f
                depthImage.translationY = 0f
                depthImage.visibility = View.VISIBLE
            }
        }
    }

    // Tunables for detector gating (persisted)
    data class DetParams(
        var conf: Float = 0.35f,
        var iou: Float = 0.45f,
        var areaMinPct: Float = 0.03f,   // of RGB area
        var areaMaxPct: Float = 0.85f
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

    private fun runYoloDetect(detOverlay: OverlayView?, dimsText: TextView?) {
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
            runMeasurement(dimsText)

        } else {
            Toast.makeText(this, "No box after RGB filters.", Toast.LENGTH_SHORT).show()
        }
    }

    private data class Quad(val r: Rect, val conf: Float, val cls: Int, val s: Double)

    private fun depthSanityForRect(r: Rect): DepthSanity {
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
        val zMed = u8ToMm(inside[inside.size / 2])
        val zBg  = u8ToMm(ring[ring.size / 2])
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

        val dbg = depthSanityForRect(Rect(roi.left.toInt(), roi.top.toInt(), roi.right.toInt(), roi.bottom.toInt()))
        if (dbg.validFrac < 0.60f) {
            Toast.makeText(this, "Not enough valid depth — move 20–40 cm closer and avoid glare.", Toast.LENGTH_SHORT).show()
            return
        }
        if (dbg.gapMm < 10.0) { // top face not evident
            Toast.makeText(this, "Tilt to see the top face, then retry.", Toast.LENGTH_SHORT).show()
            return
        }

        // build + preview mask (diagnostic overlay) ---
        val mr = buildDepthMaskFromRoi(roi)
        if (mr == null) {
            Toast.makeText(this, "Mask build failed (insufficient depth/plane).", Toast.LENGTH_SHORT).show()
        } else {
            // Optional: preview overlay on top depth layer
            lastMask = mr
            showMaskOverlay(mr)
            Toast.makeText(
                this,
                "ROI valid=${"%.0f".format(100*mr.validFrac)}% • plane fit ok • thresh=${mr.heightThreshMm.toInt()}mm",
                Toast.LENGTH_SHORT
            ).show()
        }

        val res = MeasurementEngine.measureFromRoi(
            roiRgb = roi, rgbW = rgb.width, rgbH = rgb.height,
            depthBytes = depth, depthW = w, depthH = h, sMin = sMin, sMax = sMax,
            hfovDeg = hfovDeg, vfovDeg = vfovDeg, alignDxPx = alignDxPx, alignDyPx = alignDyPx,
            fxOverride = if (fxCal.isNaN()) Double.NaN else fxCal,
            fyOverride = if (fyCal.isNaN()) Double.NaN else fyCal,
            cxOverride = if (cxCal.isNaN()) Double.NaN else cxCal,
            cyOverride = if (cyCal.isNaN()) Double.NaN else cyCal
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
        val zMedMm = u8ToMm(medU)

        // estimate physical L × W using pinhole + FOV
        val rgbW = (rgbBmp?.width ?: 800).toDouble()
        val rgbH = (rgbBmp?.height ?: 600).toDouble()
        val fxEff = if (!fxCal.isNaN()) fxCal
                    else rgbW / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))
        val fyEff = if (!fyCal.isNaN()) fyCal
                    else rgbH / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))

        val mmPerPxX = zMedMm / fxEff   // equivalent to the FOV formula, but tied to rectified K
        val mmPerPxY = zMedMm / fyEff
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

        lastMask?.let { m ->
            FileOutputStream(File(dir, "mask_depth.png")).use { fos ->
                makeMaskDepthBitmapFull(m).compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        }

        // meta.json
        val meta = JSONObject()
            .put("depthWidth", w)
            .put("depthHeight", h)
            .put("scaleMin", sMin)
            .put("scaleMax", sMax)
            // Also store the resolved working range the engine used (after defaults applied)
            .put("resolvedDepthMinMm", if (sMin == 0 && sMax == 255) A010_MIN_MM else sMin)
            .put("resolvedDepthMaxMm", if (sMin == 0 && sMax == 255) A010_MAX_MM else sMax)
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
            .put("compatCenterCrop", useCompatCenterCrop)
            .put("useUndistort", useUndistort)
            .put("fx", if (!fxCal.isNaN()) fxCal else ((rgbBmp?.width ?: 800) / (2.0 * Math.tan(Math.toRadians(hfovDeg/2.0))))) // keep prior as fallback:contentReference[oaicite:4]{index=4}
            .put("fy", if (!fyCal.isNaN()) fyCal else ((rgbBmp?.height ?: 600) / (2.0 * Math.tan(Math.toRadians(vfovDeg/2.0)))))
            .put("cx", if (!cxCal.isNaN()) cxCal else (rgbBmp?.width ?: 800) / 2.0)   // keep existing keys too:contentReference[oaicite:5]{index=5}
            .put("cy", if (!cyCal.isNaN()) cyCal else (rgbBmp?.height ?: 600) / 2.0)

        lastMask?.let { m ->
            meta.put("maskHeightThreshMm", m.heightThreshMm)
                .put("maskValidFrac", m.validFrac)
                .put("plane_nx", m.plane.nx)
                .put("plane_ny", m.plane.ny)
                .put("plane_nz", m.plane.nz)
                .put("plane_d",  m.plane.d)
        }

        lastMeasurement?.let {
            meta.put("lengthCm", it.lengthCm)
                .put("widthCm", it.widthCm)
                .put("heightCm", it.heightCm)
                .put("measureConfidence", it.confidence)
                .put("nRoiPts", it.nRoiPts)
                .put("nPlaneInliers", it.nPlaneInliers)
                .put("roiDepthSaturationFrac", it.satFrac)
        }
        // Orientation & dimension (production path)
        lastOrientation?.let { o ->
            meta.put("poseLabel", if (o.pose == PoseLabel.Front) "Front" else "Side")
                .put("standup", o.standup)
                .put("angleDeg", o.angleDeg)
                .put("gapMm", o.gapMm)
        }
        lastDims?.let { d ->
            meta.put("lengthMm_prod", d.lengthMm)
                .put("widthMm_prod", d.widthMm)
                .put("heightMm_prod", d.heightMm ?: JSONObject.NULL)
                .put("sigmaL_mm", d.sigmaL)
                .put("sigmaW_mm", d.sigmaW)
                .put("sigmaH_mm", d.sigmaH ?: JSONObject.NULL)
                .put("confidence_prod", d.confidence)
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


