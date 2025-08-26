package com.example.android_app

enum class Palette { VIRIDIS, JET, GRAY }

object Colormaps {
    fun makeLut(p: Palette, lo: Int, hi: Int): IntArray {
        val loC = lo.coerceIn(0, 255)
        val hiC = hi.coerceIn(loC + 1, 255)
        val lut = IntArray(256)
        for (v in 0..255) {
            val t = ((v - loC).toFloat() / (hiC - loC)).coerceIn(0f, 1f)
            val rgb = when (p) {
                Palette.VIRIDIS -> viridisRGB(t)
                Palette.JET     -> jetRGB(t)
                Palette.GRAY    -> grayRGB(t)
            }
            lut[v] = (0xFF shl 24) or
                    ((rgb.first  and 0xFF) shl 16) or
                    ((rgb.second and 0xFF) shl 8)  or
                    (rgb.third  and 0xFF)
        }
        return lut
    }

    private fun viridisRGB(tIn: Float): Triple<Int,Int,Int> {
        val t = tIn.coerceIn(0f,1f)
        // small polynomial approx (looks good + tiny)
        val r = (0.281f + 1.50f*t - 1.80f*t*t + 1.10f*t*t*t).coerceIn(0f,1f)
        val g = (0.000f + 1.30f*t + 0.30f*t*t - 0.80f*t*t*t).coerceIn(0f,1f)
        val b = (0.330f + 0.20f*t + 1.20f*t*t - 1.20f*t*t*t).coerceIn(0f,1f)
        return Triple((r*255).toInt(), (g*255).toInt(), (b*255).toInt())
    }

    // close “Jet”/rainbow, which visually matches A010’s LCD
    private fun jetRGB(tIn: Float): Triple<Int,Int,Int> {
        val t = (tIn * 4f) // 0..4
        val r = (255f * clamp01(minOf(maxOf(t - 1.5f, 0f), 1f))).toInt()
        val g = (255f * clamp01(minOf(maxOf(t - 0.5f, 0f), 1f) - minOf(maxOf(t - 2.5f, 0f), 1f))).toInt()
        val b = (255f * clamp01(1f - minOf(maxOf(t - 1.5f, 0f), 1f))).toInt()
        return Triple(r, g, b)
    }

    private fun grayRGB(t: Float): Triple<Int,Int,Int> {
        val v = (t.coerceIn(0f,1f) * 255f).toInt()
        return Triple(v, v, v)
    }

    private fun clamp01(x: Float) = x.coerceIn(0f, 1f)
}
