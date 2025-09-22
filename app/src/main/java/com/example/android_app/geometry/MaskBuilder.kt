package com.example.android_app.geometry

import android.graphics.Rect
import android.graphics.RectF
import com.example.android_app.model.MaskResult
import com.example.android_app.model.Plane
import kotlin.math.*

class MaskBuilder(
    private val tofHfovDeg: Double = 70.0,
    private val tofVfovDeg: Double = 60.0,
    private val abovePlaneThreshMm: Double = 20.0,
    private val ransacIters: Int = 150,
    private val ransacInlierThreshMm: Double = 6.0,
    private val roiPadPx: Int = 4
) {
    private fun projectTo3D(x: Int, y: Int, zMm: Double, w: Int, h: Int): DoubleArray {
        val fx = w / (2.0 * kotlin.math.tan(Math.toRadians(tofHfovDeg / 2.0)))
        val fy = h / (2.0 * kotlin.math.tan(Math.toRadians(tofVfovDeg / 2.0)))
        val cx = w / 2.0; val cy = h / 2.0
        val X = ((x - cx) / fx) * zMm
        val Y = ((y - cy) / fy) * zMm
        return doubleArrayOf(X, Y, zMm)
    }

    private fun planeFrom3(a: DoubleArray, b: DoubleArray, c: DoubleArray): Plane? {
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
    private fun ringPixels(xs: Int, ys: Int, xe: Int, ye: Int, pad: Int, w: Int, h: Int): IntArray {
        val rx0 = (xs - pad).coerceAtLeast(0); val ry0 = (ys - pad).coerceAtLeast(0)
        val rx1 = (xe + pad).coerceAtMost(w - 1); val ry1 = (ye + pad).coerceAtMost(h - 1)
        val idx = ArrayList<Int>()
        for (yy in ry0..ry1) for (xx in rx0..rx1) {
            val onBorder = (xx < xs || xx > xe || yy < ys || yy > ye)
            if (onBorder) idx.add(yy * w + xx)
        }
        return idx.toIntArray()
    }
    private fun ransacGroundPlane(d: ByteArray, ringIdx: IntArray, w: Int, h: Int, sMin: Int, sMax: Int): Plane? {
        if (ringIdx.size < 50) return null
        val rnd = java.util.Random(1234L)
        var best: Plane? = null
        var bestInliers = 0
        repeat(ransacIters) {
            var tries = 0
            while (tries++ < 50) {
                val aIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                val bIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                val cIdx = ringIdx[rnd.nextInt(ringIdx.size)]
                if (aIdx == bIdx || bIdx == cIdx || aIdx == cIdx) continue
                val az = d[aIdx].toInt() and 0xFF
                val bz = d[bIdx].toInt() and 0xFF
                val cz = d[cIdx].toInt() and 0xFF
                if (az !in 1..254 || bz !in 1..254 || cz !in 1..254) continue
                val ax = aIdx % w; val ay = aIdx / w
                val bx = bIdx % w; val by = bIdx / w
                val cx = cIdx % w; val cy = cIdx / w
                val A = projectTo3D(ax, ay, PixelToMetric.u8ToMm(az, sMin, sMax), w, h)
                val B = projectTo3D(bx, by, PixelToMetric.u8ToMm(bz, sMin, sMax), w, h)
                val C = projectTo3D(cx, cy, PixelToMetric.u8ToMm(cz, sMin, sMax), w, h)
                val p = planeFrom3(A, B, C) ?: continue
                var count = 0
                for (idx in ringIdx) {
                    val z = d[idx].toInt() and 0xFF
                    if (z !in 1..254) continue
                    val x = idx % w; val y = idx / w
                    val P = projectTo3D(x, y, PixelToMetric.u8ToMm(z, sMin, sMax), w, h)
                    val dist = kotlin.math.abs(p.signedDistanceMm(P[0], P[1], P[2]))
                    if (dist <= ransacInlierThreshMm) count++
                }
                if (count > bestInliers) { bestInliers = count; best = p }
                break
            }
        }
        if (best == null) {
            val vals = ringIdx.map { d[it].toInt() and 0xFF }.filter { it in 1..254 }.sorted()
            if (vals.isEmpty()) return null
            val zMed = PixelToMetric.u8ToMm(vals[vals.size / 2], sMin, sMax)
            return Plane(0.0, 0.0, 1.0, -zMed)
        }
        return best
    }
    private fun median3x3Inplace(depth: ByteArray, xs: Int, ys: Int, xe: Int, ye: Int, w: Int, h: Int) {
        val tmp = depth.copyOf()
        fun u(ix: Int, iy: Int): Int = tmp[iy * w + ix].toInt() and 0xFF
        for (y in ys..ye) for (x in xs..xe) {
            val vals = IntArray(9); var n = 0
            for (yy in (y-1).coerceAtLeast(0)..(y+1).coerceAtMost(h-1))
                for (xx in (x-1).coerceAtLeast(0)..(x+1).coerceAtMost(w-1)) {
                    val v = u(xx, yy)
                    if (v in 1..254) { vals[n++] = v }
                }
            if (n >= 3) {
                java.util.Arrays.sort(vals, 0, n)
                depth[y * w + x] = vals[n/2].toByte()
            }
        }
    }

    // Public API: build a boolean mask in the depth grid from an RGB-space ROI.
    fun build(
        roiRgb: RectF,
        rgbW: Int, rgbH: Int,
        depthBytes: ByteArray, w: Int, h: Int, sMin: Int, sMax: Int,
        alignDxPx: Int, alignDyPx: Int
    ): MaskResult? {
        val sx = w.toFloat() / rgbW.toFloat()
        val sy = h.toFloat() / rgbH.toFloat()
        val adj = RectF(
            (roiRgb.left  - alignDxPx).coerceIn(0f, rgbW.toFloat()),
            (roiRgb.top   - alignDyPx).coerceIn(0f, rgbH.toFloat()),
            (roiRgb.right - alignDxPx).coerceIn(0f, rgbW.toFloat()),
            (roiRgb.bottom- alignDyPx).coerceIn(0f, rgbH.toFloat())
        )
        val xs = ((min(adj.left, adj.right)  * sx).toInt()).coerceIn(0, w-1)
        val ys = ((min(adj.top,  adj.bottom) * sy).toInt()).coerceIn(0, h-1)
        val xe = ((max(adj.left, adj.right)  * sx).toInt()).coerceIn(0, w-1)
        val ye = ((max(adj.top,  adj.bottom) * sy).toInt()).coerceIn(0, h-1)
        val ring = ringPixels(xs, ys, xe, ye, pad = 6, w, h)
        if (ring.isEmpty()) return null

        val work = depthBytes.copyOf()
        var validCount = 0
        val totalCount = (xe - xs + 1) * (ye - ys + 1)
        for (yy in ys..ye) for (xx in xs..xe) {
            val v = work[yy * w + xx].toInt() and 0xFF
            if (v in 1..254) validCount++
        }
        val validFrac = if (totalCount == 0) 0f else validCount.toFloat() / totalCount.toFloat()
        median3x3Inplace(work, xs, ys, xe, ye, w, h)
        val plane = ransacGroundPlane(work, ring, w, h, sMin, sMax) ?: return null

        val px0 = (xs - roiPadPx).coerceAtLeast(0)
        val py0 = (ys - roiPadPx).coerceAtLeast(0)
        val px1 = (xe + roiPadPx).coerceAtMost(w - 1)
        val py1 = (ye + roiPadPx).coerceAtMost(h - 1)
        val subW = px1 - px0 + 1
        val subH = py1 - py0 + 1
        val cand = BooleanArray(subW * subH)
        var idx = 0
        for (yy in py0..py1) for (xx in px0..px1) {
            val u = work[yy * w + xx].toInt() and 0xFF
            if (u !in 1..254) { idx++; continue }
            val z = PixelToMetric.u8ToMm(u, sMin, sMax)
            val P = projectTo3D(xx, yy, z, w, h)
            val above = plane.signedDistanceMm(P[0], P[1], P[2])
            cand[idx++] = above >= abovePlaneThreshMm
        }
        // Pick largest component overlapping ROI (fast single pass)
        val best = ccBiggestOverlapWithRoi(cand, subW, subH, xs - px0, ys - py0, xe - px0, ye - py0)
        return MaskResult(px0, py0, px1, py1, subW, subH, best, validFrac, plane, abovePlaneThreshMm)
    }

    private fun ccBiggestOverlapWithRoi(
        cand: BooleanArray, width: Int, height: Int,
        roiXs: Int, roiYs: Int, roiXe: Int, roiYe: Int
    ): BooleanArray {
        val visited = BooleanArray(cand.size)
        var bestMask = BooleanArray(cand.size)
        var bestOverlap = -1
        fun inside(x:Int,y:Int)= x in 0 until width && y in 0 until height
        val qx = IntArray(width*height); val qy = IntArray(width*height)
        for (y in 0 until height) for (x in 0 until width) {
            val idx0 = y*width + x
            if (!cand[idx0] || visited[idx0]) continue
            var h=0; var t=0; var overlap=0
            qx[t]=x; qy[t]=y; t++
            visited[idx0]=true
            val cur = ArrayList<Int>()
            while (h < t) {
                val cx = qx[h]; val cy = qy[h]; h++
                val id = cy*width + cx
                cur.add(id)
                val gx = cx + roiXs; val gy = cy + roiYs
                if (gx in roiXs..roiXe && gy in roiYs..roiYe) overlap++
                val dirs = intArrayOf(1,0, -1,0, 0,1, 0,-1)
                var i=0
                while (i<8) {
                    val nx = cx + dirs[i]; val ny = cy + dirs[i+1]; i+=2
                    if (!inside(nx,ny)) continue
                    val nid = ny*width + nx
                    if (!cand[nid] || visited[nid]) continue
                    visited[nid]=true; qx[t]=nx; qy[t]=ny; t++
                }
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestMask = BooleanArray(cand.size)
                for (id in cur) bestMask[id]=true
            }
        }
        return bestMask
    }
}