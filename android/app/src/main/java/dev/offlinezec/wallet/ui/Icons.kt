package dev.offlinezec.wallet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Thin line icons, drawn on a 24-unit grid like the design's (no icon font in the APK). */
enum class Glyph { Up, Down, Relay, Scan, Copy, Profile, ChevronDown, ChevronRight, ChevronLeft, Back, Close, Notes, Bluetooth, Paste, Check, Swap, Warning, Memo, Info }

@Composable
fun LineIcon(g: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Ink.text, weight: Float = 1.6f) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val st = Stroke(width = weight * u * 1.0f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg pts: Float) = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            var i = 2
            while (i < pts.size) { lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        }
        fun line(vararg pts: Float) = drawPath(p(*pts), color, style = st)
        fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = st)
        when (g) {
            Glyph.Up -> { line(12f, 19f, 12f, 5f); line(6f, 11f, 12f, 5f, 18f, 11f) }
            Glyph.Down -> { line(12f, 5f, 12f, 19f); line(6f, 13f, 12f, 19f, 18f, 13f) }
            Glyph.Relay, Glyph.Swap -> { line(4f, 8f, 18f, 8f); line(15f, 5f, 18f, 8f, 15f, 11f); line(20f, 16f, 6f, 16f); line(9f, 13f, 6f, 16f, 9f, 19f) }
            Glyph.Scan -> {
                line(4f, 9f, 4f, 4f, 9f, 4f); line(15f, 4f, 20f, 4f, 20f, 9f)
                line(20f, 15f, 20f, 20f, 15f, 20f); line(9f, 20f, 4f, 20f, 4f, 15f); line(7f, 12f, 17f, 12f)
            }
            Glyph.Copy -> { rr(9f, 9f, 11f, 11f, 2.5f); line(5f, 15f, 5f, 6.5f, 6.5f, 5f, 15f, 5f) }
            Glyph.Profile -> drawCircle(color, 8f * u, Offset(12f * u, 12f * u), style = st)
            Glyph.ChevronDown -> line(6f, 9f, 12f, 15f, 18f, 9f)
            Glyph.ChevronRight -> line(9f, 6f, 15f, 12f, 9f, 18f)
            Glyph.ChevronLeft, Glyph.Back -> line(15f, 6f, 9f, 12f, 15f, 18f)
            Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.Notes -> { rr(3f, 7f, 18f, 11f, 2.5f); drawCircle(color, 2.5f * u, Offset(12f * u, 12.5f * u), style = st); line(6f, 4f, 18f, 4f) }
            Glyph.Bluetooth -> line(7f, 7f, 17f, 16f, 12f, 20f, 12f, 4f, 17f, 8f, 7f, 17f)
            Glyph.Paste -> { rr(5f, 5f, 14f, 16f, 2.5f); rr(9f, 3f, 6f, 4f, 1.5f) }
            Glyph.Check -> line(5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Glyph.Memo -> { line(4f, 20f, 5f, 15.5f, 15.5f, 5f, 19f, 8.5f, 8.5f, 19f, 4f, 20f); line(13f, 7.5f, 16.5f, 11f) }
            Glyph.Info -> { drawCircle(color, 9f * u, Offset(12f * u, 12f * u), style = st); line(12f, 11f, 12f, 16.5f); line(12f, 7.8f, 12f, 7.81f) }
            Glyph.Warning -> { line(12f, 4f, 21f, 20f, 3f, 20f, 12f, 4f); line(12f, 10f, 12f, 14f); line(12f, 17f, 12f, 17.01f) }
        }
    }
}

/** The Amana mark (docs/brand/amana-mark.svg): an arch keeping a green dot, drawn on its 100-unit grid. */
@Composable
fun AmanaMark(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Ink.text, dot: Color = Ink.green) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 100f
        val arch = Path().apply {
            moveTo(27f * u, 82f * u); lineTo(27f * u, 50f * u)
            arcTo(androidx.compose.ui.geometry.Rect(27f * u, 27f * u, 73f * u, 73f * u), 180f, 180f, false)
            lineTo(73f * u, 82f * u)
        }
        drawPath(arch, color, style = Stroke(width = 11f * u))
        drawCircle(dot, 8f * u, Offset(50f * u, 64f * u))
    }
}
