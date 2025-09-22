package com.example.android_app.geometry

import com.example.android_app.model.*
import kotlin.math.*

object OrientationEstimator {
    /**
     * Compute OBB and pose from a depth-grid mask.
     * @param gapMm median ring-minus-inside gap (used to decide Front vs Side)
     */
    fun estimate(mask: MaskResult, gapMm: Double): OrientationResult {
        // Gather points in global depth-grid coordinates
        val pts = ArrayList<Pair<Float,Float>>(mask.width*mask.height/2)
        var i=0
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask.mask[i++]) pts.add(Pair((mask.xs + x).toFloat(), (mask.ys + y).toFloat()))
        }
        if (pts.size < 8) {
            // Fallback tiny box; caller should gate on validFrac before.
            val obb = OrientedBox(0f,0f,0f,0f,0f)
            return OrientationResult(PoseLabel.Side, false, 0.0, obb, gapMm, false)
        }

        // Mean
        var sx=0.0; var sy=0.0
        for ((x,y) in pts) { sx += x; sy += y }
        val n = pts.size.toDouble()
        val mx = sx / n; val my = sy / n

        // Covariance matrix [a b; b c]
        var a=0.0; var b=0.0; var c=0.0
        for ((x,y) in pts) {
            val dx = x - mx; val dy = y - my
            a += dx*dx; b += dx*dy; c += dy*dy
        }
        a /= n; b /= n; c /= n

        // Principal axis angle (major) for 2x2 covariance
        val theta = 0.5 * atan2(2.0*b, a - c)         // radians vs +X
        val vx = cos(theta); val vy = sin(theta)
        val vx2 = -sin(theta); val vy2 = cos(theta)   // minor axis (perp.)

        // Extents along principal axes (centered at mean)
        var minU=Double.POSITIVE_INFINITY; var maxU=Double.NEGATIVE_INFINITY
        var minV=Double.POSITIVE_INFINITY; var maxV=Double.NEGATIVE_INFINITY
        for ((x,y) in pts) {
            val dx = x - mx; val dy = y - my
            val u = dx*vx + dy*vy
            val v = dx*vx2 + dy*vy2
            if (u < minU) minU = u; if (u > maxU) maxU = u
            if (v < minV) minV = v; if (v > maxV) maxV = v
        }
        val majorLen = (maxU - minU).toFloat()
        val minorLen = (maxV - minV).toFloat()

        val angleDeg = Math.toDegrees(theta)
        val isTopFace = gapMm >= 10.0
        val pose = if (isTopFace) PoseLabel.Front else PoseLabel.Side
        val standup = kotlin.math.abs(90.0 - kotlin.math.abs(angleDeg)) < 25.0

        val obb = OrientedBox(mx.toFloat(), my.toFloat(), majorLen, minorLen, theta.toFloat())
        return OrientationResult(pose, standup, angleDeg, obb, gapMm, isTopFace)
    }
}