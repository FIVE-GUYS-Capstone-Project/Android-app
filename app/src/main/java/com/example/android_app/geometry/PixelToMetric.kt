package com.example.android_app.geometry

import com.example.android_app.model.*
import kotlin.math.*

object PixelToMetric {
    fun mmPerPxX(zMm: Double, fx: Double) = zMm / fx
    fun mmPerPxY(zMm: Double, fy: Double) = zMm / fy

    // Median of in-mask depth (u8 units → mm)
    fun medianDepthMm(depth: ByteArray, w: Int, h: Int, sMin: Int, sMax: Int, m: MaskResult): Double {
        val vals = ArrayList<Int>()
        var i = 0
        for (yy in 0 until m.height) for (xx in 0 until m.width) {
            if (!m.mask[i++]) continue
            val gx = m.xs + xx; val gy = m.ys + yy
            val u = depth[gy * w + gx].toInt() and 0xFF
            if (u in 1..254) vals.add(u)
        }
        if (vals.isEmpty()) return 0.0
        vals.sort()
        val uMed = vals[vals.size/2]
        val span = (sMax - sMin).toDouble().coerceAtLeast(1.0)
        return sMin + (uMed / 255.0) * span
    }

    /** Dimension estimate from OBB and depth. */
    fun estimateDims(
        obb: OrientedBox,
        zMedMm: Double,
        fx: Double, fy: Double,
        validFrac: Float,
        gapMm: Double,
        maskAreaPx: Int
    ): DimResult {
        val mmX = mmPerPxX(zMedMm, fx)
        val mmY = mmPerPxY(zMedMm, fy)
        val Lmm = obb.wPx * mmX
        val Wmm = obb.hPx * mmY
        val hasH = gapMm >= 10.0
        val Hmm = if (hasH) max(0.0, gapMm) else Double.NaN

        // crude but useful uncertainties (px localization + depth spread)
        val sigmaPx = 0.7  // sub-pixel/edge ambiguity
        val sigmaL = sqrt( (sigmaPx*mmX).pow(2) + (0.01 * Lmm).pow(2) )
        val sigmaW = sqrt( (sigmaPx*mmY).pow(2) + (0.01 * Wmm).pow(2) )
        val sigmaH = if (hasH) max(3.0, 0.10 * Hmm) else Double.NaN

        // Confidence: valid depth + clear separation + enough area
        val areaTerm = min(1.0, maskAreaPx / 8000.0)         // saturate for mid-size boxes
        val gapTerm  = min(1.0, gapMm / 25.0)
        val conf = (0.45 * validFrac) + (0.35 * gapTerm) + (0.20 * areaTerm)

        return DimResult(
            lengthMm = Lmm, widthMm = Wmm, heightMm = if (hasH) Hmm else null,
            sigmaL = sigmaL, sigmaW = sigmaW, sigmaH = if (hasH) sigmaH else null,
            confidence = conf.coerceIn(0.0, 1.0)
        )
    }
}
