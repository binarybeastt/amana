package dev.offlinezec.wallet.qr

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import kotlin.math.hypot

/**
 * The address QR as the design draws it: light dots on a dark ground, rounded finder squares and
 * a clear disc in the middle for the logo (level H absorbs the cleared modules). Pure ARGB
 * pixels, so a JVM test can check it still decodes. Phone cameras read light-on-dark codes; the
 * app's own scanner tries the inverted image too ([QrAnalyzer] `inverted`).
 */
fun styledQrPixels(text: String, size: Int, fg: Int = 0xFFFFFFFF.toInt(), bg: Int = 0xFF000000.toInt(), logoHole: Double = 0.24): IntArray {
    val qr = Encoder.encode(text, ErrorCorrectionLevel.H, mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1"))
    val m = qr.matrix
    val n = m.width
    val quiet = 2
    val cell = size.toDouble() / (n + 2 * quiet)
    val px = IntArray(size * size) { bg }
    val c = size / 2.0
    val hole = size * logoHole / 2
    fun finder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
    for (py in 0 until size) for (pxX in 0 until size) {
        val gx = pxX / cell - quiet
        val gy = py / cell - quiet
        if (gx < 0 || gy < 0 || gx >= n || gy >= n) continue
        val mx = gx.toInt()
        val my = gy.toInt()
        val on = if (finder(mx, my)) {
            // Rounded finder: a ring 1 module thick, a gap, then a 3×3 rounded centre.
            val ox = if (mx < 7) 0.0 else (n - 7).toDouble()
            val oy = if (my < 7) 0.0 else (n - 7).toDouble()
            val lx = gx - ox
            val ly = gy - oy
            val outer = roundRectIn(lx, ly, 0.0, 7.0, 2.0)
            val inner = roundRectIn(lx, ly, 1.0, 6.0, 1.3)
            val dot = roundRectIn(lx, ly, 2.0, 5.0, 1.0)
            (outer && !inner) || dot
        } else {
            if (hypot(pxX - c, py - c) < hole) false
            else m.get(mx, my).toInt() == 1 && hypot(gx - mx - 0.5, gy - my - 0.5) < 0.45
        }
        if (on) px[py * size + pxX] = fg
    }
    return px
}

/** Inside the square [lo, hi)² with corner radius [r]? */
private fun roundRectIn(x: Double, y: Double, lo: Double, hi: Double, r: Double): Boolean {
    if (x < lo || y < lo || x >= hi || y >= hi) return false
    val cx = x.coerceIn(lo + r, hi - r)
    val cy = y.coerceIn(lo + r, hi - r)
    return hypot(x - cx, y - cy) <= r
}
