package com.example.android_app.geometry

import com.example.android_app.model.*
import kotlin.math.*

object PixelToMetric {
    private const val A010_MIN_MM = 200
    private const val A010_MAX_MM = 2500
    private const val INVERT_U8 = true

    private fun resolveRange(sMin: Int, sMax: Int) =
        if (sMin == 0 && sMax == 255) A010_MIN_MM to A010_MAX_MM else sMin to sMax
    fun mmPerPxX(zMm: Double, fx: Double) = zMm / fx
    fun mmPerPxY(zMm: Double, fy: Double) = zMm / fy

    /** Map an 8-bit depth sample to mm, honoring A010's real range when meta is 0–255. */
    fun u8ToMm(u: Int, sMin: Int, sMax: Int): Double {
        val (minMm, maxMm) = resolveRange(sMin, sMax)
        val span = (maxMm - minMm).toDouble().coerceAtLeast(1.0)
        val t = (u.coerceIn(0, 255)) / 255.0
        val tEff = if (INVERT_U8) (1.0 - t) else t
        return minMm + tEff * span
    }

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
        val uMed = vals[vals.size / 2]
        return u8ToMm(uMed, sMin, sMax)
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

    /**
     * Plane-space dimensions from a built mask + plane.
     * Returns Triple(L_mm, W_mm, H_mm?) where L≥W, or null if not enough valid points.
     *
     * @param depth            ToF depth bytes (u8)
     * @param w,h              depth grid size
     * @param sMin,sMax        meta scale (0..255 or real mm range)
     * @param mask             MaskResult carrying plane + boolean mask in a sub-rect
     * @param tofHfovDeg,Vfov  ToF FOVs (deg) to build intrinsics for 3D projection
     * @param heightQuantile   quantile of "above-plane" distances to report as height
     * @param minPoints        minimum in-mask valid points required
     */

    fun estimateDimsPlaneSpace(
        depth: ByteArray,
        w: Int,
        h: Int,
        sMin: Int,
        sMax: Int,
        mask: MaskResult,
        tofHfovDeg: Double,
        tofVfovDeg: Double,
        heightQuantile: Double = 0.90,
        minPoints: Int = 60
    ): Triple<Double, Double, Double?>? {
        if (w <= 0 || h <= 0) return null
        if (mask.width <= 0 || mask.height <= 0) return null

        // Unit plane normal
        val nx = mask.plane.nx; val ny = mask.plane.ny; val nz = mask.plane.nz
        val nLen = kotlin.math.sqrt(nx*nx + ny*ny + nz*nz)
        if (nLen < 1e-9) return null
        val nux = nx / nLen; val nuy = ny / nLen; val nuz = nz / nLen

        // Build plane basis (e1,e2) orthonormal to n
        val anyx = if (kotlin.math.abs(nux) < 0.9) 1.0 else 0.0
        val anyy = if (kotlin.math.abs(nux) < 0.9) 0.0 else 1.0
        val anyz = 0.0
        var e1x = anyy*nuz - anyz*nuy
        var e1y = anyz*nux - anyx*nuz
        var e1z = anyx*nuy - anyy*nux
        val e1n = kotlin.math.sqrt(e1x*e1x + e1y*e1y + e1z*e1z).let { if (it < 1e-12) 1.0 else it }
        e1x /= e1n; e1y /= e1n; e1z /= e1n
        val e2x = nuy*e1z - nuz*e1y
        val e2y = nuz*e1x - nux*e1z
        val e2z = nux*e1y - nuy*e1x

        // ToF intrinsics (rectilinear)
        val fxTof = w / (2.0 * kotlin.math.tan(Math.toRadians(tofHfovDeg / 2.0)))
        val fyTof = h / (2.0 * kotlin.math.tan(Math.toRadians(tofVfovDeg / 2.0)))
        val cxTof = w / 2.0
        val cyTof = h / 2.0

        fun projectTo3D(ix: Int, iy: Int, zMm: Double): DoubleArray {
            val X = ((ix - cxTof) / fxTof) * zMm
            val Y = ((iy - cyTof) / fyTof) * zMm
            return doubleArrayOf(X, Y, zMm)
        }

        val uv = ArrayList<DoubleArray>(mask.width * mask.height)
        val heights = ArrayList<Double>(mask.width * mask.height)
        var i = 0
        val xs = mask.xs; val ys = mask.ys
        for (yy in ys..mask.ye) {
            for (xx in xs..mask.xe) {
                if (!mask.mask[i++]) continue
                val u = depth[yy * w + xx].toInt() and 0xFF
                if (u !in 1..254) continue
                val z = u8ToMm(u, sMin, sMax)
                val P = projectTo3D(xx, yy, z)
                val dist = mask.plane.signedDistanceMm(P[0], P[1], P[2]) // + above plane
                // Project orthogonally onto plane: P - dist * n
                val Qx = P[0] - dist * nux
                val Qy = P[1] - dist * nuy
                val Qz = P[2] - dist * nuz
                // Map to plane 2D coords
                val u2 = Qx*e1x + Qy*e1y + Qz*e1z
                val v2 = Qx*e2x + Qy*e2y + Qz*e2z
                uv.add(doubleArrayOf(u2, v2))
                if (dist > 0.0) heights.add(dist)
            }
        }
        if (uv.size < minPoints) return null

        val (spanMajor, spanMinor) = pcaExtents2D(uv) ?: return null
        val Lmm = kotlin.math.max(spanMajor, spanMinor)
        val Wmm = kotlin.math.min(spanMajor, spanMinor)
        val Hmm = if (heights.isNotEmpty()) {
            val arr = heights.toDoubleArray().apply { sort() }
            percentile(arr, heightQuantile)
        } else null
        return Triple(Lmm, Wmm, Hmm)
    }

    // ---- helpers (private) -------------------------------------------------
    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val p = (q.coerceIn(0.0, 1.0) * (sorted.size - 1))
        val i = kotlin.math.floor(p).toInt()
        val f = p - i
        return if (i + 1 < sorted.size) sorted[i] * (1.0 - f) + sorted[i + 1] * f else sorted[i]
    }

    /** 2D PCA extents (returns major/minor spans), input in any linear units. */
    private fun pcaExtents2D(uv: ArrayList<DoubleArray>): Pair<Double, Double>? {
        val n = uv.size
        if (n < 10) return null
        var mu = 0.0; var mv = 0.0
        for (p in uv) { mu += p[0]; mv += p[1] }
        mu /= n; mv /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in uv) {
            val du = p[0] - mu; val dv = p[1] - mv
            sxx += du * du; syy += dv * dv; sxy += du * dv
        }
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = kotlin.math.max(0.0, tr * tr - 4.0 * det)
        val l1 = 0.5 * (tr + kotlin.math.sqrt(disc))
        val l2 = 0.5 * (tr - kotlin.math.sqrt(disc))
        // Major eigenvector (robust to degeneracy)
        val v1x = if (kotlin.math.abs(sxy) > 1e-12) sxy else (l1 - syy)
        val v1y = if (kotlin.math.abs(sxy) > 1e-12) (l1 - sxx) else sxy
        val n1 = kotlin.math.hypot(v1x, v1y).let { if (it < 1e-12) 1.0 else it }
        val ax = v1x / n1; val ay = v1y / n1
        val bx = -ay; val by = ax
        var minA = Double.POSITIVE_INFINITY; var maxA = Double.NEGATIVE_INFINITY
        var minB = Double.POSITIVE_INFINITY; var maxB = Double.NEGATIVE_INFINITY
        for (p in uv) {
            val pa = (p[0]-mu) * ax + (p[1]-mv) * ay
            val pb = (p[0]-mu) * bx + (p[1]-mv) * by
            if (pa < minA) minA = pa; if (pa > maxA) maxA = pa
            if (pb < minB) minB = pb; if (pb > maxB) maxB = pb
        }
        return Pair(maxA - minA, maxB - minB)
    }
}