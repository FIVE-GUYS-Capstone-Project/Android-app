package com.example.android_app

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.*
import java.util.Random

data class MeasurementResult(
    val lengthCm: Double,
    val widthCm: Double,
    val heightCm: Double,
    val confidence: Double,
    val nRoiPts: Int,
    val nPlaneInliers: Int,
    val notes: String = ""
)

/**
 * Depth→3D → plane (RANSAC on ring) → project to plane → PCA (2D) → OBB extents.
 * Assumptions:
 *  - depth is 8-bit, 0/255 invalid, sMin..sMax are true millimetres (or close).
 *  - ROI is in *RGB pixel* coordinates.
 *  - HFOV/VFOV are approximate but consistent; fx, fy derived from them.
 *  - alignDx/alignDy are the visual nudges you apply to the depth overlay.
 */
object MeasurementEngine {

    fun measureFromRoi(
        roiRgb: RectF,
        rgbW: Int, rgbH: Int,
        depthBytes: ByteArray,
        depthW: Int, depthH: Int,
        sMin: Int, sMax: Int,
        hfovDeg: Double, vfovDeg: Double,
        alignDxPx: Int = 0, alignDyPx: Int = 0,
        ringPadDepthPx: Int = 6,
        planeThreshMm: Double = 8.0,   // inlier distance for table plane (mm)
        ransacIters: Int = 120
    ): MeasurementResult {

        if (depthW <= 0 || depthH <= 0) {
            return MeasurementResult(0.0,0.0,0.0,0.0,0,0, "No depth grid")
        }

        // fx, fy in pixel units
        val fx = (rgbW / (2.0 * tan(Math.toRadians(hfovDeg / 2.0))))
        val fy = (rgbH / (2.0 * tan(Math.toRadians(vfovDeg / 2.0))))
        val cx = rgbW / 2.0
        val cy = rgbH / 2.0

        // Map the RGB-space ROI to the depth grid, compensating visual alignment.
        val adj = RectF(
            roiRgb.left - alignDxPx,
            roiRgb.top  - alignDyPx,
            roiRgb.right - alignDxPx,
            roiRgb.bottom - alignDyPx
        )
        val sx = depthW.toDouble() / rgbW
        val sy = depthH.toDouble() / rgbH

        fun clamp(v:Int, lo:Int, hi:Int) = max(lo, min(hi, v))

        val dx0 = clamp(floor(adj.left  * sx).toInt(), 0, depthW-1)
        val dy0 = clamp(floor(adj.top   * sy).toInt(), 0, depthH-1)
        val dx1 = clamp( ceil(adj.right * sx).toInt(), 0, depthW-1)
        val dy1 = clamp( ceil(adj.bottom* sy).toInt(), 0, depthH-1)

        if (dx1 - dx0 < 2 || dy1 - dy0 < 2) {
            return MeasurementResult(0.0,0.0,0.0,0.0,0,0,"ROI too small")
        }

        // Helpers
        fun u8ToMm(u: Int): Double {
            val span = (sMax - sMin).toDouble().coerceAtLeast(1.0)
            return sMin + (u / 255.0) * span
        }
        fun depthAt(x:Int,y:Int) = depthBytes[y*depthW + x].toInt() and 0xFF
        fun to3D_fromDepthIdx(ix:Int, iy:Int, mm:Double): Triple<Double,Double,Double> {
            // back-project via RGB intrinsics by mapping depth pixel to RGB pixel
            val xRgb = (ix + 0.5) * (rgbW.toDouble() / depthW) + alignDxPx
            val yRgb = (iy + 0.5) * (rgbH.toDouble() / depthH) + alignDyPx
            val Z = mm
            val X = (xRgb - cx) / fx * Z
            val Y = (yRgb - cy) / fy * Z
            return Triple(X, Y, Z)
        }

        // Collect ROI points (valid) in 3D
        val roiPts = ArrayList<DoubleArray>()
        for (y in dy0..dy1) for (x in dx0..dx1) {
            val u = depthAt(x,y)
            if (u in 1..254) {
                val mm = u8ToMm(u)
                val (X,Y,Z) = to3D_fromDepthIdx(x,y,mm)
                roiPts.add(doubleArrayOf(X,Y,Z))
            }
        }
        if (roiPts.size < 40) {
            return MeasurementResult(0.0,0.0,0.0,0.0,roiPts.size,0,"Not enough ROI points")
        }

        // Build ring (background) for plane fit
        val rx0 = clamp(dx0 - ringPadDepthPx, 0, depthW-1)
        val ry0 = clamp(dy0 - ringPadDepthPx, 0, depthH-1)
        val rx1 = clamp(dx1 + ringPadDepthPx, 0, depthW-1)
        val ry1 = clamp(dy1 + ringPadDepthPx, 0, depthH-1)
        val ringPts = ArrayList<DoubleArray>()
        for (y in ry0..ry1) for (x in rx0..rx1) {
            val outside = (x < dx0 || x > dx1 || y < dy0 || y > dy1)
            if (!outside) continue
            val u = depthAt(x,y)
            if (u in 1..254) {
                val mm = u8ToMm(u)
                val (X,Y,Z) = to3D_fromDepthIdx(x,y,mm)
                ringPts.add(doubleArrayOf(X,Y,Z))
            }
        }

        // --- RANSAC plane on ring (table/floor)
        val plane = fitPlaneRansac(ringPts, ransacIters, planeThreshMm)
            ?: // fallback: plane z=median
            run {
                val zMed = ringPts.map { it[2] }.sorted()[ringPts.size/2]
                Plane(doubleArrayOf(0.0,0.0,1.0), -zMed, ringPts.size, ringPts.size, 1.0)
            }

        // Signed distance (mm) of P to plane n·P + d = 0; |n|=1
        fun signedDistMm(p: DoubleArray) = (plane.n[0]*p[0] + plane.n[1]*p[1] + plane.n[2]*p[2] + plane.d)

        // Project ROI points to plane (for L/W) and compute height
        val heights = DoubleArray(roiPts.size)
        val proj2 = ArrayList<DoubleArray>(roiPts.size) // points in plane 2D coordinates

        // Build orthonormal basis (e1,e2) on plane
        val n = plane.n
        val any = if (abs(n[0])<0.8) doubleArrayOf(1.0,0.0,0.0) else doubleArrayOf(0.0,1.0,0.0)
        val e1 = normalize(cross(any, n))
        val e2 = cross(n, e1)

        for ((i,p) in roiPts.withIndex()) {
            val dist = signedDistMm(p)
            heights[i] = dist
            // Projection onto plane, then local 2D coords
            val proj = doubleArrayOf(
                p[0] - dist*n[0],
                p[1] - dist*n[1],
                p[2] - dist*n[2]
            )
            val u = dot(proj, e1)
            val v = dot(proj, e2)
            proj2.add(doubleArrayOf(u, v))
        }

        // Robust height: 90th percentile (avoid small dents) minus ~0 (plane)
        val hMm = percentile(heights.filter { it >= 0.0 }, 0.90)

        // PCA (2D) on proj2 → extents along principal axes
        val (lenMm, widMm) = pcaExtents2D(proj2)

        // Confidence (simple): inlier ratio × ROI density × ring quality
        val roiDensity = roiPts.size.toDouble() / ((dx1-dx0+1) * (dy1-dy0+1)).coerceAtLeast(1)
        val conf = (plane.inlierRatio * 0.5 + roiDensity * 0.5).coerceIn(0.0, 1.0)

        return MeasurementResult(
            lengthCm = lenMm / 10.0,
            widthCm  = widMm / 10.0,
            heightCm = hMm   / 10.0,
            confidence = conf,
            nRoiPts = roiPts.size,
            nPlaneInliers = plane.inliers
        )
    }

    // ---------- math helpers ----------
    private data class Plane(
        val n: DoubleArray, // unit normal
        val d: Double,      // offset
        val inliers: Int,
        val trials: Int,
        val inlierRatio: Double
    )

    private fun fitPlaneRansac(points: List<DoubleArray>, iters: Int, thrMm: Double): Plane? {
        if (points.size < 3) return null
        val rnd = Random(42)
        var best: Plane? = null
        repeat(iters) {
            // sample 3 distinct pts
            val i1 = rnd.nextInt(points.size)
            var i2 = rnd.nextInt(points.size); while (i2==i1) i2 = rnd.nextInt(points.size)
            var i3 = rnd.nextInt(points.size); while (i3==i1 || i3==i2) i3 = rnd.nextInt(points.size)
            val p1 = points[i1]; val p2 = points[i2]; val p3 = points[i3]
            val n = cross(
                doubleArrayOf(p2[0]-p1[0], p2[1]-p1[1], p2[2]-p1[2]),
                doubleArrayOf(p3[0]-p1[0], p3[1]-p1[1], p3[2]-p1[2])
            )
            val nlen = norm(n)
            if (nlen < 1e-6) return@repeat
            val nu = doubleArrayOf(n[0]/nlen, n[1]/nlen, n[2]/nlen)
            val d = -(nu[0]*p1[0] + nu[1]*p1[1] + nu[2]*p1[2])

            var inl = 0
            for (p in points) {
                val dist = abs(nu[0]*p[0] + nu[1]*p[1] + nu[2]*p[2] + d)
                if (dist <= thrMm) inl++
            }
            if (best==null || inl > best!!.inliers) {
                best = Plane(nu, d, inl, points.size, inl.toDouble()/points.size)
            }
        }
        return best
    }

    private fun pcaExtents2D(pts: List<DoubleArray>): Pair<Double,Double> {
        // mean
        val muX = pts.sumOf { it[0] } / pts.size
        val muY = pts.sumOf { it[1] } / pts.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pts) {
            val x = p[0]-muX; val y = p[1]-muY
            sxx += x*x; sxy += x*y; syy += y*y
        }
        sxx /= pts.size; sxy /= pts.size; syy /= pts.size
        // eigenvalues of [[sxx,sxy],[sxy,syy]]
        val t = sxx + syy
        val det = sxx*syy - sxy*sxy
        val disc = max(0.0, t*t/4.0 - det)
        val l1 = t/2.0 + sqrt(disc)
        val l2 = t/2.0 - sqrt(disc)
        // project extents along eigenvectors by dotting
        val e1x = if (abs(sxy) > 1e-9) (l1 - syy)/sxy else 1.0
        val e1y = 1.0
        val elen = sqrt(e1x*e1x + e1y*e1y)
        val u1x = e1x/elen; val u1y = e1y/elen
        val u2x = -u1y; val u2y = u1x

        var min1=Double.POSITIVE_INFINITY; var max1=Double.NEGATIVE_INFINITY
        var min2=Double.POSITIVE_INFINITY; var max2=Double.NEGATIVE_INFINITY
        for (p in pts) {
            val x = p[0]-muX; val y=p[1]-muY
            val a = x*u1x + y*u1y
            val b = x*u2x + y*u2y
            if (a<min1) min1=a; if (a>max1) max1=a
            if (b<min2) min2=b; if (b>max2) max2=b
        }
        // raw extents ≈ length & width (mm)
        return Pair(max1-min1, max2-min2)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(
            a[1]*b[2] - a[2]*b[1],
            a[2]*b[0] - a[0]*b[2],
            a[0]*b[1] - a[1]*b[0]
        )
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]
    private fun norm(a: DoubleArray) = sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2])
    private fun normalize(a: DoubleArray): DoubleArray { val n = norm(a); return doubleArrayOf(a[0]/n,a[1]/n,a[2]/n) }
    private fun percentile(vals: List<Double>, p: Double): Double {
        if (vals.isEmpty()) return 0.0
        val s = vals.sorted()
        val idx = ((s.size-1) * p).coerceIn(0.0, (s.size-1).toDouble())
        val lo = floor(idx).toInt(); val hi = ceil(idx).toInt()
        return if (lo==hi) s[lo] else (s[lo]*(hi-idx) + s[hi]*(idx-lo))
    }
}
