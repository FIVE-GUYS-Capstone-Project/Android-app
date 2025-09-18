package com.example.android_app.model

import android.graphics.Rect

// Reusable plane/mask types so Activity & helpers share the same structs
data class Plane(val nx: Double, val ny: Double, val nz: Double, val d: Double) {
    fun signedDistanceMm(x: Double, y: Double, z: Double): Double =
        (nx * x + ny * y + nz * z + d) / kotlin.math.sqrt(nx*nx + ny*ny + nz*nz)
    }

data class MaskResult(
    val xs: Int, val ys: Int, val xe: Int, val ye: Int,
    val width: Int, val height: Int,
    val mask: BooleanArray,
    val validFrac: Float,
    val plane: Plane,
    val heightThreshMm: Double
)

// YOLO wrapper (kept compatible — we won’t require it yet)
data class ScoredBox(val rect: Rect, val score: Float = 1f, val cls: Int = 0)

// Orientation output
data class OrientedBox(
    val cx: Float, val cy: Float,           // center (depth-grid coords)
    val wPx: Float, val hPx: Float,         // major/minor axis lengths in pixels
    val angleRad: Float                     // major-axis angle vs +X (radians)
)
enum class PoseLabel { Front, Side }
data class OrientationResult(
    val pose: PoseLabel,
    val standup: Boolean,                   // true ≈ “Standup”, false ≈ “Laydown”
    val angleDeg: Double,
    val obb: OrientedBox,
    val gapMm: Double,                      // ring-vs-inside median gap
    val hasTopFace: Boolean                 // pose==Front convenience flag
)

// Dimension result (+ simple uncertainties)
data class DimResult(
    val lengthMm: Double, val widthMm: Double, val heightMm: Double?,  // Height is null if Side
    val sigmaL: Double, val sigmaW: Double, val sigmaH: Double?,
    val confidence: Double
)
