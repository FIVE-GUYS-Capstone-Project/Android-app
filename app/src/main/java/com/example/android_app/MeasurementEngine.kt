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
    val notes: String = "",
    val satFrac: Double = 0.0
)

object MeasurementEngine {

    private const val DEFAULT_TOF_MM_MIN = 150.0
    private const val DEFAULT_TOF_MM_MAX = 1500.0

    private fun byteToMm(u8: Int, mmMin: Double, mmScale: Double): Double =
        mmMin + (u8.coerceIn(0,255) / 255.0) * mmScale

    fun measureFromRoi(
        roiRgb: RectF,
        rgbW: Int, rgbH: Int,
        depthBytes: ByteArray,
        depthW: Int, depthH: Int,
        sMin: Int, sMax: Int,
        hfovDeg: Double, vfovDeg: Double,
        alignDxPx: Int = 0, alignDyPx: Int = 0,
        ringPadDepthPx: Int = 6,
        planeThreshMm: Double = 8.0,
        ransacIters: Int = 120
    ): MeasurementResult {

        val (mmMin, mmMax) = if (sMin == 0 && sMax == 255)
            DEFAULT_TOF_MM_MIN to DEFAULT_TOF_MM_MAX else sMin.toDouble() to sMax.toDouble()
        val mmScale = (mmMax - mmMin).coerceAtLeast(1.0)

        if (depthW <= 0 || depthH <= 0) {
            return MeasurementResult(0.0,0.0,0.0,0.0,0,0, "No depth grid", 0.0)
        }

        val fx = (rgbW / (2.0 * tan(Math.toRadians(hfovDeg / 2.0))))
        val fy = (rgbH / (2.0 * tan(Math.toRadians(vfovDeg / 2.0))))
        val cx = rgbW / 2.0
        val cy = rgbH / 2.0

        val adj = RectF(roiRgb.left - alignDxPx, roiRgb.top - alignDyPx,
            roiRgb.right - alignDxPx, roiRgb.bottom - alignDyPx)
        val sx = depthW.toDouble() / rgbW
        val sy = depthH.toDouble() / rgbH
        fun clamp(v:Int, lo:Int, hi:Int) = max(lo, min(hi, v))

        val dx0 = clamp(floor(adj.left  * sx).toInt(), 0, depthW-1)
        val dy0 = clamp(floor(adj.top   * sy).toInt(), 0, depthH-1)
        val dx1 = clamp( ceil(adj.right * sx).toInt(), 0, depthW-1)
        val dy1 = clamp( ceil(adj.bottom* sy).toInt(), 0, depthH-1)
        if (dx1 - dx0 < 2 || dy1 - dy0 < 2) return MeasurementResult(0.0,0.0,0.0,0.0,0,0,"ROI too small")

        fun depthAt(x:Int,y:Int) = depthBytes[y*depthW + x].toInt() and 0xFF
        fun to3D(ix:Int, iy:Int, mm:Double): DoubleArray {
            val xRgb = (ix + 0.5) * (rgbW.toDouble() / depthW) + alignDxPx
            val yRgb = (iy + 0.5) * (rgbH.toDouble() / depthH) + alignDyPx
            val Z = mm
            val X = (xRgb - cx) / fx * Z
            val Y = (yRgb - cy) / fy * Z
            return doubleArrayOf(X, Y, Z)
        }

        var totalRoi = 0; var satCount = 0
        for (y in dy0..dy1) for (x in dx0..dx1) {
            val u = depthAt(x, y); totalRoi++; if (u <= 2 || u >= 253) satCount++
        }
        val satFrac = if (totalRoi > 0) satCount.toDouble() / totalRoi else 0.0

        val roiPts = ArrayList<DoubleArray>()
        for (y in dy0..dy1) for (x in dx0..dx1) {
            val u = depthAt(x,y)
            if (u in 1..254) roiPts.add(to3D(x,y, byteToMm(u, mmMin, mmScale)))
        }
        if (roiPts.size < 40) return MeasurementResult(0.0,0.0,0.0,0.0,roiPts.size,0,"Not enough ROI points", satFrac)

        val seg = segmentParcelMaskFromRoi(
            Rect(dx0, dy0, dx1, dy1),
            depthBytes, depthW, depthH, sMin, sMax,
            mmMin, mmMax, fx, fy, cx, cy, rgbW, rgbH, alignDxPx, alignDyPx,
            planeGapMm = 20.0
        )

        if (seg.n > 40) {
            val filtered = ArrayList<DoubleArray>(seg.n)
            for (y in dy0..dy1) for (x in dx0..dx1) {
                if (seg.mask[y*depthW + x]) {
                    val u = depthAt(x,y); if (u in 1..254)
                        filtered.add(to3D(x,y, byteToMm(u, mmMin, mmScale)))
                }
            }
            if (filtered.size >= 40) { roiPts.clear(); roiPts.addAll(filtered) }
        }

        val rx0 = max(dx0 - ringPadDepthPx, 0)
        val ry0 = max(dy0 - ringPadDepthPx, 0)
        val rx1 = min(dx1 + ringPadDepthPx, depthW-1)
        val ry1 = min(dy1 + ringPadDepthPx, depthH-1)
        val ringPts = ArrayList<DoubleArray>()
        for (y in ry0..ry1) for (x in rx0..rx1) {
            val outside = (x < dx0 || x > dx1 || y < dy0 || y > dy1)
            if (!outside) continue
            val u = depthAt(x,y)
            if (u in 1..254) ringPts.add(to3D(x,y, byteToMm(u, mmMin, mmScale)))
        }

        val plane = fitPlaneRansac(ringPts, ransacIters, planeThreshMm)
            ?: run {
                val zMed = ringPts.map { it[2] }.sorted()[ringPts.size/2]
                Plane(doubleArrayOf(0.0,0.0,1.0), -zMed, ringPts.size, ringPts.size, 1.0)
            }

        fun signedDistMm(p: DoubleArray) = (plane.n[0]*p[0] + plane.n[1]*p[1] + plane.n[2]*p[2] + plane.d)

        val heights = DoubleArray(roiPts.size)
        val proj2 = ArrayList<DoubleArray>(roiPts.size)

        val n = plane.n
        val any = if (abs(n[0])<0.8) doubleArrayOf(1.0,0.0,0.0) else doubleArrayOf(0.0,1.0,0.0)
        val e1 = normalize(cross(any, n))
        val e2 = cross(n, e1)

        for ((i,p) in roiPts.withIndex()) {
            val dist = signedDistMm(p); heights[i] = dist
            val proj = doubleArrayOf(p[0] - dist*n[0], p[1] - dist*n[1], p[2] - dist*n[2])
            val u = dot(proj, e1); val v = dot(proj, e2)
            proj2.add(doubleArrayOf(u,v))
        }

        val hMm = percentile(heights.filter { it >= 0.0 }, 0.90)
        val (lenMm, widMm) = pcaExtents2D(proj2)
        val roiDensity = roiPts.size.toDouble() / ((dx1-dx0+1) * (dy1-dy0+1)).coerceAtLeast(1)
        val conf = (plane.inlierRatio * 0.5 + roiDensity * 0.5).coerceIn(0.0, 1.0)

        return MeasurementResult(lenMm/10.0, widMm/10.0, hMm/10.0, conf, roiPts.size, plane.inliers,
            notes = if (satFrac > 0.25) "Depth near sensor limits" else "", satFrac = satFrac)
    }

    private data class Plane(val n: DoubleArray, val d: Double, val inliers: Int, val trials: Int, val inlierRatio: Double)
    private data class SegMask(val w:Int, val h:Int, val mask:BooleanArray, val n:Int)

    private fun segmentParcelMaskFromRoi(
        roiDepthRect: Rect, depthU8: ByteArray, w: Int, h: Int,
        sMin:Int, sMax:Int, mmMin: Double, mmMax: Double,
        fx: Double, fy: Double, cx: Double, cy: Double,
        rgbW: Int, rgbH: Int, alignDxPx: Int, alignDyPx: Int,
        planeGapMm: Double, ransacIters: Int = 100, planeInlierThrMm: Double = 8.0
    ): SegMask {
        val smooth = median3x3U8(depthU8, w, h)
        val spanMm = (mmMax - mmMin).coerceAtLeast(1.0)
        fun to3D(ix:Int, iy:Int, mm:Double): DoubleArray {
            val xRgb = (ix + 0.5) * (rgbW.toDouble() / w) + alignDxPx
            val yRgb = (iy + 0.5) * (rgbH.toDouble() / h) + alignDyPx
            val X = (xRgb - cx) / fx * mm
            val Y = (yRgb - cy) / fy * mm
            return doubleArrayOf(X, Y, mm)
        }
        fun u8ToMm(u:Int) = mmMin + (u.coerceIn(0,255) / 255.0) * spanMm

        val xs = roiDepthRect.left.coerceIn(0, w-1); val xe = roiDepthRect.right.coerceIn(xs, w-1)
        val ys = roiDepthRect.top.coerceIn(0, h-1);  val ye = roiDepthRect.bottom.coerceIn(ys, h-1)

        val support = ArrayList<DoubleArray>()
        val bandTop = (ye + 2).coerceAtMost(h-1); val bandBot = (ye + 12).coerceAtMost(h-1)
        for (y in bandTop..bandBot) for (x in xs..xe) {
            val u = smooth[y*w + x].toInt() and 0xFF
            if (u in 1..254) support.add(to3D(x,y, u8ToMm(u)))
        }
        if (support.size < 25) {
            val pad = 4
            val rx0 = (xs - pad).coerceAtLeast(0); val ry0 = (ys - pad).coerceAtLeast(0)
            val rx1 = (xe + pad).coerceAtMost(w-1); val ry1 = (ye + pad).coerceAtMost(h-1)
            for (y in ry0..ry1) for (x in rx0..rx1) {
                val inside = (x in xs..xe && y in ys..ye); if (inside) continue
                val u = smooth[y*w + x].toInt() and 0xFF
                if (u in 1..254) support.add(to3D(x,y, u8ToMm(u)))
            }
        }

        val plane = fitPlaneRansac(support, ransacIters, planeInlierThrMm) ?: fitPlaneLsq(support) ?: return SegMask(w,h, BooleanArray(w*h), 0)

        val mask = BooleanArray(w*h); var count = 0
        for (y in ys..ye) for (x in xs..xe) {
            val u = smooth[y*w + x].toInt() and 0xFF
            if (u !in 1..254) continue
            val p = to3D(x,y, u8ToMm(u))
            val dist = plane.n[0]*p[0] + plane.n[1]*p[1] + plane.n[2]*p[2] + plane.d
            if (dist >= planeGapMm) { mask[y*w + x] = true; count++ }
        }

        val kept = BooleanArray(w*h)
        var bestCount = 0
        val seen = BooleanArray(w*h)
        val q = java.util.ArrayDeque<Int>()
        fun idx(i:Int,j:Int)=j*w+i
        for (y in ys..ye) for (x in xs..xe) {
            val i = idx(x,y); if (!mask[i] || seen[i]) continue
            var c = 0; q.clear(); q.add(i); seen[i]=true
            val comp = java.util.ArrayList<Int>(256)
            while (q.isNotEmpty()) {
                val v = q.removeFirst(); comp.add(v); c++
                val vx = v % w; val vy = v / w
                val nbs = intArrayOf(
                    idx((vx-1).coerceAtLeast(xs), vy),
                    idx((vx+1).coerceAtMost(xe), vy),
                    idx(vx, (vy-1).coerceAtLeast(ys)),
                    idx(vx, (vy+1).coerceAtMost(ye))
                )
                for (ni in nbs) if (!seen[ni] && mask[ni]) { seen[ni]=true; q.add(ni) }
            }
            if (c > bestCount) {
                java.util.Arrays.fill(kept, false)
                for (v in comp) kept[v] = true
                bestCount = c
            }
        }
        return SegMask(w, h, kept, bestCount)
    }

    private fun fitPlaneRansac(points: List<DoubleArray>, iters: Int, thrMm: Double): Plane? {
        if (points.size < 3) return null
        val rnd = Random(42)
        var best: Plane? = null
        repeat(iters) {
            val i1 = rnd.nextInt(points.size)
            var i2 = rnd.nextInt(points.size); while (i2==i1) i2 = rnd.nextInt(points.size)
            var i3 = rnd.nextInt(points.size); while (i3==i1 || i3==i2) i3 = rnd.nextInt(points.size)
            val p1 = points[i1]; val p2 = points[i2]; val p3 = points[i3]
            val n = cross(doubleArrayOf(p2[0]-p1[0], p2[1]-p1[1], p2[2]-p1[2]),
                doubleArrayOf(p3[0]-p1[0], p3[1]-p1[1], p3[2]-p1[2]))
            val nlen = norm(n); if (nlen < 1e-6) return@repeat
            val nu = doubleArrayOf(n[0]/nlen, n[1]/nlen, n[2]/nlen)
            val d = -(nu[0]*p1[0] + nu[1]*p1[1] + nu[2]*p1[2])

            var inl = 0
            for (p in points) {
                val dist = abs(nu[0]*p[0] + nu[1]*p[1] + nu[2]*p[2] + d)
                if (dist <= thrMm) inl++
            }
            if (best==null || inl > best!!.inliers) best = Plane(nu, d, inl, points.size, inl.toDouble()/points.size)
        }
        return best
    }

    private fun fitPlaneLsq(points: List<DoubleArray>): Plane? {
        if (points.size < 3) return null
        var mx=0.0; var my=0.0; var mz=0.0
        for (p in points) { mx+=p[0]; my+=p[1]; mz+=p[2] }
        val n = points.size.toDouble()
        mx/=n; my/=n; mz/=n
        var sxx=0.0; var sxy=0.0; var sxz=0.0; var syy=0.0; var syz=0.0; var szz=0.0
        for (p in points) {
            val x=p[0]-mx; val y=p[1]-my; val z=p[2]-mz
            sxx+=x*x; sxy+=x*y; sxz+=x*z; syy+=y*y; syz+=y*z; szz+=z*z
        }
        fun covDot(v: DoubleArray) = doubleArrayOf(
            sxx*v[0] + sxy*v[1] + sxz*v[2],
            sxy*v[0] + syy*v[1] + syz*v[2],
            sxz*v[0] + syz*v[1] + szz*v[2]
        )
        fun rq(v: DoubleArray): Double {
            val Cv = covDot(v); return (v[0]*Cv[0] + v[1]*Cv[1] + v[2]*Cv[2]) / (v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        }
        val cands = arrayOf(doubleArrayOf(1.0,0.0,0.0), doubleArrayOf(0.0,1.0,0.0), doubleArrayOf(0.0,0.0,1.0),
            doubleArrayOf(1.0,1.0,0.0), doubleArrayOf(1.0,0.0,1.0), doubleArrayOf(0.0,1.0,1.0))
        var best = cands[0]; var bestVal = Double.POSITIVE_INFINITY
        for (v in cands) { val r = rq(v); if (r < bestVal) { bestVal = r; best = v } }
        val nrm = normalize(best); val d = -(nrm[0]*mx + nrm[1]*my + nrm[2]*mz)
        return Plane(nrm, d, points.size, points.size, 1.0)
    }

    private fun median3x3U8(src: ByteArray, w: Int, h: Int, ignoreZero255: Boolean = true): ByteArray {
        val dst = ByteArray(src.size); val win = IntArray(9)
        fun ok(v: Int) = if (!ignoreZero255) true else (v in 1..254)
        for (y in 0 until h) for (x in 0 until w) {
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w-1); val yy = (y + dy).coerceIn(0, h-1)
                val v = src[yy*w + xx].toInt() and 0xFF; if (ok(v)) { win[k++] = v }
            }
            if (k == 0) { dst[y*w + x] = 0 } else {
                java.util.Arrays.sort(win, 0, k); dst[y*w + x] = win[k/2].toByte()
            }
        }
        return dst
    }

    private fun pcaExtents2D(pts: List<DoubleArray>): Pair<Double,Double> {
        val muX = pts.sumOf { it[0] } / pts.size; val muY = pts.sumOf { it[1] } / pts.size
        var sxx=0.0; var sxy=0.0; var syy=0.0
        for (p in pts) { val x=p[0]-muX; val y=p[1]-muY; sxx+=x*x; sxy+=x*y; syy+=y*y }
        sxx/=pts.size; sxy/=pts.size; syy/=pts.size
        val t = sxx + syy; val det = sxx*syy - sxy*sxy
        val disc = max(0.0, t*t/4.0 - det)
        val l1 = t/2.0 + sqrt(disc); val l2 = t/2.0 - sqrt(disc)
        val e1x = if (abs(sxy) > 1e-9) (l1 - syy)/sxy else 1.0; val e1y = 1.0
        val elen = sqrt(e1x*e1x + e1y*e1y)
        val u1x = e1x/elen; val u1y = e1y/elen; val u2x = -u1y; val u2y = u1x
        var min1=Double.POSITIVE_INFINITY; var max1=Double.NEGATIVE_INFINITY
        var min2=Double.POSITIVE_INFINITY; var max2=Double.NEGATIVE_INFINITY
        for (p in pts) {
            val x=p[0]-muX; val y=p[1]-muY
            val a = x*u1x + y*u1y; val b = x*u2x + y*u2y
            if (a<min1) min1=a; if (a>max1) max1=a
            if (b<min2) min2=b; if (b>max2) max2=b
        }
        return Pair(max1-min1, max2-min2)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
    private fun norm(a: DoubleArray) = sqrt(a[0]*a[0]+a[1]*a[1]+a[2]*a[2])
    private fun normalize(a: DoubleArray): DoubleArray { val n = norm(a); return doubleArrayOf(a[0]/n,a[1]/n,a[2]/n) }
    private fun percentile(vals: List<Double>, p: Double): Double {
        if (vals.isEmpty()) return 0.0
        val s = vals.sorted(); val idx = ((s.size-1) * p).coerceIn(0.0, (s.size-1).toDouble())
        val lo = floor(idx).toInt(); val hi = ceil(idx).toInt()
        return if (lo==hi) s[lo] else (s[lo]*(hi-idx) + s[hi]*(idx-lo))
    }
}
