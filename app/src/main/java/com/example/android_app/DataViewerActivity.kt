package com.example.android_app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.example.android_app.model.MaskResult
import com.example.android_app.model.OrientationResult
import com.example.android_app.model.DimResult
import com.example.android_app.model.PoseLabel
import com.example.android_app.model.Plane
import com.example.android_app.geometry.PixelToMetric
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DataViewerActivity : AppCompatActivity() {

    private enum class Mode { RGB, DEPTH, OVERLAY }

    // prefs for viewer state
    private val prefs by lazy { getSharedPreferences("viewer_prefs", MODE_PRIVATE) }
    private val REQ_OPEN_BUNDLE = 31
    private val REQ_PICK_GT_MASK = 71

    // --- Calibration / intrinsics (persisted)
    private var hfovDeg = 60.0
    private var vfovDeg = 45.0
    private var opticalDxMm = 0.0
    private var opticalDyMm = 0.0
    private val TOF_HFOV_DEG = 70.0
    private val TOF_VFOV_DEG = 60.0

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
    private var showYoloBox = true
    private val ALIGN_RANGE = 80

    private var rgbBmp: Bitmap? = null
    private var depthColorBmp: Bitmap? = null
    private var depthColorScaled: Bitmap? = null

    private val ABOVE_PLANE_THRESH_MM = 20.0
    private val RANSAC_ITERS = 150
    private val RANSAC_INLIER_THRESH_MM = 6.0
    private val ROI_PAD_PX = 4

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

    // Maixsense A010 real working range (when meta is 0..255)
    private val A010_MIN_MM = 200
    private val A010_MAX_MM = 2500

    private var boxDetector: BoxDetector? = null
    private var lastDetRect: Rect? = null
    private var lastDetScore: Float = 0f

    private var palette: Palette = Palette.JET
    private var autoRangeDisplay = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_viewer)
        ocvReady = OpenCVLoader.initDebug()

        // Restore viewer state from prefs (then force RGB so UI shows only normal image)
        mode = try {
            Mode.valueOf(prefs.getString("viewer_mode", mode.name)!!)
        } catch (_: Exception) {
            Mode.OVERLAY
        }
        showYoloBox = prefs.getBoolean("show_yolo_box", true)
        overlayAlpha = prefs.getInt("viewer_alpha", overlayAlpha)
        alignDxPx = 0
        alignDyPx = 0
        prefs.edit().putInt("align_dx_px", 0).putInt("align_dy_px", 0).apply()

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
        val overlay = findViewById<RoiOverlayView?>(R.id.roiOverlay)
        val detOverlay = findViewById<OverlayView?>(R.id.overlayDetections)

        // ROI overlay callback → compute dimensions from selected ROI
        overlay?.onBoxFinished = { boxOnOverlay: RectF ->
            val rectRgb = viewToImageRect(
                findViewById(R.id.imageView), boxOnOverlay,
                rgbBmp?.width ?: 800, rgbBmp?.height ?: 600
            )
            currentRoiRgb = RectF(rectRgb)
            // We no longer print to dimsText; values will show in the 3 fields after Measure
        }

        // Decode RGB
        imgBytes?.let {
            val bmp = BitmapFactory.decodeByteArray(it, 0, it.size)
            if (bmp == null) {
                Toast.makeText(this, "Invalid image data.", Toast.LENGTH_LONG).show()
            } else {
                rgbBmp = bmp
                imageView.setImageBitmap(rgbBmp)
                Log.d("DataViewerActivity", "RGB ${rgbBmp?.width}x${rgbBmp?.height}")
            }
        } ?: Log.e("DataViewerActivity", "imageBytes is null")

        // Depth visualization built but we keep it HIDDEN in RGB mode
        if (depthBytes != null && w > 0 && h > 0 && depthBytes!!.size >= w * h) {
            rebuildDepthVisualization()
        } else {
            Log.w("DataViewerActivity", "No usable depth")
        }

        // Force UI to start in RGB so only the normal image is visible
        mode = Mode.RGB
        prefs.edit().putString("viewer_mode", mode.name).apply()

        // Wire up Mode button (kept hidden in your XML; still works if you enable it)
        val modeButton = findViewById<Button?>(R.id.modeButton)
        fun updateModeButtonText() {
            modeButton?.text = "Mode: " + when (mode) {
                Mode.RGB -> "RGB"; Mode.DEPTH -> "Depth"; Mode.OVERLAY -> "Overlay"
            }
        }
        updateModeButtonText()
        applyModeUI()

        modeButton?.setOnClickListener {
            mode = when (mode) {
                Mode.RGB -> Mode.DEPTH; Mode.DEPTH -> Mode.OVERLAY; Mode.OVERLAY -> Mode.RGB
            }
            prefs.edit().putString("viewer_mode", mode.name).apply()
            updateModeButtonText()
            render(imageView, depthImage)
            applyModeUI()
        }

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.returnButton)?.setOnClickListener { finish() }

        // YOLO detector config (if present)
        boxDetector = BoxDetector(this).apply {
            setConfig(
                BoxDetector.Config(
                    inputSize = 640,
                    confThr = det.conf.coerceIn(0f, 1f),
                    iouThr = det.iou.coerceIn(0f, 1f),
                    threads = 4,
                    tryGpu = true
                )
            )
        }

        // Measure button
        findViewById<Button>(R.id.detectBtn)?.setOnClickListener {
            runDetectAndMeasure(detOverlay, /*dimsText*/ null)
        }

        // initial render enforces visibility by mode (RGB → depth layer hidden)
        render(imageView, depthImage)
        updateOverlayBounds()

        // Layout listener updates overlay bounds once the ImageView is laid out
        imageView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    updateOverlayBounds()
                }
            }
        )
    }

    // === Rendering logic: keep depth hidden in RGB ===
    private fun render(imageView: ImageView, depthImage: ImageView) {
        when (mode) {
            Mode.RGB -> {
                imageView.visibility = View.VISIBLE
                depthImage.visibility = View.GONE
            }
            Mode.DEPTH -> {
                imageView.visibility = View.GONE
                depthImage.visibility = View.VISIBLE
            }
            Mode.OVERLAY -> {
                imageView.visibility = View.VISIBLE
                depthImage.visibility = View.VISIBLE
                depthImage.alpha = (overlayAlpha.coerceIn(0, 255) / 255f)
            }
        }
    }

    private fun applyModeUI() {
        val alphaSeek = findViewById<SeekBar?>(R.id.alphaSeek)
        val depthImage = findViewById<ImageView?>(R.id.depthImage)
        val enabled = (mode == Mode.OVERLAY)
        alphaSeek?.isEnabled = enabled
        alphaSeek?.alpha = if (enabled) 1f else 0.4f
        if (enabled) depthImage?.alpha = (overlayAlpha.coerceIn(0, 255) / 255f)
    }

    // === Detect + Measure (existing pipeline), UI update on main thread ===
    private var currentRoiRgb: RectF? = null

    private fun runDetectAndMeasure(detOverlay: OverlayView?, dimsText: TextView?) {
        val rgb = rgbBmp ?: run {
            Toast.makeText(this, "No RGB image.", Toast.LENGTH_SHORT).show(); return
        }

        // Prefer manual ROI; else YOLO
        val manual = currentRoiRgb
        val yolo = if (manual == null) pickRoiWithYolo(rgb) else null
        val fromYolo = (manual == null && yolo != null)
        val roi: RectF = manual ?: yolo?.let { RectF(it) } ?: run {
            Toast.makeText(this, "No ROI — draw a box or tap Measure again.", Toast.LENGTH_SHORT).show()
            return
        }
        currentRoiRgb = roi

        // Draw the right overlay (red for YOLO, white for manual)
        val iv = findViewById<ImageView>(R.id.imageView)
        val onView = mapRgbRectToView(roi, iv, rgb.width, rgb.height)
        val roiOverlay = findViewById<RoiOverlayView>(R.id.roiOverlay)

        detOverlay?.clear()
        if (fromYolo && showYoloBox) {
            detOverlay?.showBoxes(listOf(onView), android.graphics.Color.RED, 3f)
            roiOverlay?.visibility = View.GONE
        } else {
            roiOverlay?.visibility = View.VISIBLE
            roiOverlay?.showBox(onView)
        }

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

                // Build mask, estimate orientation, estimate dims (your existing geometry)
                val mask = maskBuilder.build(
                    roi, rgb.width, rgb.height,
                    depth, w, h, sMin, sMax, alignDxPx, alignDyPx
                ) ?: run {
                    runOnUiThread {
                        setBusy(false)
                        Toast.makeText(this, "Mask/plane failed — try moving closer.", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                val dbg = depthSanityForRect(Rect(roi.left.toInt(), roi.top.toInt(), roi.right.toInt(), roi.bottom.toInt()))
                val orient = com.example.android_app.geometry.OrientationEstimator.estimate(mask, dbg.gapMm)

                val fxEff = if (!fxCal.isNaN()) fxCal else rgb.width / (2.0 * Math.tan(Math.toRadians(hfovDeg / 2.0)))
                val fyEff = if (!fyCal.isNaN()) fyCal else rgb.height / (2.0 * Math.tan(Math.toRadians(vfovDeg / 2.0)))
                val zMedMm = com.example.android_app.geometry.PixelToMetric.medianDepthMm(depth, w, h, sMin, sMax, mask)
                val maskArea = mask.mask.count { it }
                val planeDims = PixelToMetric.estimateDimsPlaneSpace(
                    depth = depth, w = w, h = h, sMin = sMin, sMax = sMax,
                    mask = mask,
                    tofHfovDeg = TOF_HFOV_DEG, tofVfovDeg = TOF_VFOV_DEG,
                    heightQuantile = 0.90, minPoints = 60
                )
                val dimsBase = com.example.android_app.geometry.PixelToMetric.estimateDims(
                    orient.obb, zMedMm, fxEff, fyEff, mask.validFrac, orient.gapMm, maskArea
                )
                val dims = if (planeDims != null) {
                    val (Lmm_ps, Wmm_ps, H_ps) = planeDims
                    val Lw = if (Lmm_ps >= Wmm_ps) Lmm_ps else Wmm_ps
                    val Ww = if (Lmm_ps >= Wmm_ps) Wmm_ps else Lmm_ps
                    dimsBase.copy(
                        lengthMm = Lw,
                        widthMm = Ww,
                        heightMm = if (orient.pose == PoseLabel.Front) H_ps else null
                    )
                } else dimsBase

                runOnUiThread {
                    lastMask = mask
                    lastOrientation = orient
                    lastDims = dims
                    updateUiWithResults(orient, dims, detOverlay, /*dimsText*/ null)
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
        findViewById<ProgressBar?>(R.id.measProgress)?.visibility = if (b) View.VISIBLE else View.GONE
    }

    // ===== UI update: write into 3 TextViews (Length/Width/Height) =====
    private fun updateUiWithResults(
        orient: OrientationResult,
        dims: DimResult,
        detOverlay: OverlayView?,
        dimsText: TextView? // legacy param; unused now
    ) {
        // Orientation chip (hidden by default in your XML)
        val chip = findViewById<TextView?>(R.id.orientationChip)
        val pose = if (orient.pose == PoseLabel.Front) "Front" else "Side"
        val stand = if (orient.standup) "Standup" else "Laydown"
        chip?.text = "$pose • $stand • ${"%.0f".format(orient.angleDeg)}°"

        // Convert mm -> cm
        val lCm = dims.lengthMm / 10.0
        val wCm = dims.widthMm / 10.0
        val hCm = dims.heightMm?.let { it / 10.0 } // may be null

        // Update dedicated fields (they exist in your XML "Dimension" column)
        findViewById<TextView?>(R.id.dimLength)?.text = "≈${"%.1f".format(lCm)} cm"
        findViewById<TextView?>(R.id.dimWidth)?.text  = "≈${"%.1f".format(wCm)} cm"
        findViewById<TextView?>(R.id.dimHeight)?.text = hCm?.let { "≈${"%.1f".format(it)} cm" } ?: "—"
        // dimsText remains hidden (visibility gone in your XML)
    }

    // ===== Helpers below =====

    private fun computeImageDrawRect(iv: ImageView, bw: Int, bh: Int): RectF {
        val vw = iv.width.toFloat(); val vh = iv.height.toFloat()
        if (vw <= 0f || vh <= 0f) return RectF(0f, 0f, 0f, 0f)
        val sx = vw / bw; val sy = vh / bh
        val s = min(sx, sy)
        val dw = bw * s; val dh = bh * s
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    /** Map an RGB-bitmap-space rect (px) into the ImageView's canvas coords (view px). */
    private fun mapRgbRectToView(r: RectF, iv: ImageView, rgbW: Int, rgbH: Int): RectF {
        val draw = computeImageDrawRect(iv, rgbW, rgbH)
        val sx = draw.width() / rgbW.toFloat()
        val sy = draw.height() / rgbH.toFloat()
        return RectF(
            draw.left + r.left * sx,
            draw.top + r.top * sy,
            draw.left + r.right * sx,
            draw.top + r.bottom * sy
        )
    }

    private fun updateOverlayBounds() {
        // No-op placeholder if your RoiOverlayView manages this internally.
        // If you had bounds syncing logic here originally, keep it.
    }

    private fun pickRoiWithYolo(rgb: Bitmap): Rect? {
        val (input, lb) = makeLetterboxedInput(rgb, 640)
        val dets = boxDetector?.detect(input) ?: emptyList()
        if (dets.isEmpty()) return null

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

        val areaMin = (det.areaMinPct * rgb.width * rgb.height).toLong()
        val areaMax = (det.areaMaxPct * rgb.width * rgb.height).toLong()
        val gated = mapped.filter {
            val A = it.r.width().toLong() * it.r.height().toLong()
            A in areaMin..areaMax
        }
        if (gated.isEmpty()) return null

        fun centerPrior(r: Rect): Double {
            val cx = r.centerX().toFloat() / rgb.width - 0.5f
            val cy = r.centerY().toFloat() / rgb.height - 0.5f
            return (1.0 - (cx * cx + cy * cy).coerceAtMost(1f).toDouble())
        }
        val best = gated.maxWithOrNull(
            compareBy<M> { it.score }
                .thenBy { centerPrior(it.r) }
                .thenBy { it.r.width().toLong() * it.r.height().toLong() }
        )!!

        val keep = lastDetRect?.let { prev ->
            val iou = iou(prev, best.r)
            val better = best.score >= (lastDetScore + 0.05f)
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

    // ===== Stubs you already have somewhere in your file =====
    private fun rebuildDepthVisualization() { /* your existing implementation */ }
    private fun viewToImageRect(iv: ImageView, boxOnOverlay: RectF, rgbW: Int, rgbH: Int): RectF {
        // your existing implementation that maps overlay coords to RGB px
        return boxOnOverlay
    }

    private data class Letterbox(val scale: Float, val padX: Float, val padY: Float)
    private fun makeLetterboxedInput(rgb: Bitmap, size: Int): Pair<Bitmap, Letterbox> {
        // your existing implementation; return bitmap + letterbox info
        return Pair(rgb, Letterbox(1f, 0f, 0f))
    }

    private fun depthSanityForRect(r: Rect): DepthSanity {
        // your existing implementation; include valid fraction & gap mm
        return DepthSanity(validFrac = 1f, gapMm = 0.0)
    }

    private data class DepthSanity(val validFrac: Float, val gapMm: Double)

    object det {
        // your persisted tuning; placeholders for compilation
        var conf: Float = 0.25f
        var iou: Float = 0.45f
        var areaMinPct: Float = 0.05f
        var areaMaxPct: Float = 0.80f
    }

    enum class Palette { JET, VIRIDIS, GRAY }
}
