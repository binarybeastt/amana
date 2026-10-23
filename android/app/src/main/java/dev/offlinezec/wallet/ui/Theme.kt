package dev.offlinezec.wallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.offlinezec.wallet.R

/**
 * The design's palette: a dark grey page, a deeper black for the hero card, a lighter grey for
 * sheet cards (the amount card), hairline outlines, white text and quiet grey secondary text.
 */
object Ink {
    val page = Color(0xFF161617)
    val black = Color(0xFF050505)
    val card = Color(0xFF1F1F21)
    val sheet = Color(0xFF2A2A2D)
    val raised = Color(0xFF2C2C2F)
    val line = Color(0xFF3A3A3D)
    val text = Color(0xFFFFFFFF)
    val dim = Color(0xFF8E8E93)
    val faint = Color(0xFF5A5A5F)
    val red = Color(0xFFFF6B4A)
    val redCard = Color(0xFF2A1210)
    val green = Color(0xFF5BD6A0)
    val amber = Color(0xFFF2B84B)
    val blue = Color(0xFF7FA8FF)
    /** Zcash yellow, for the logo and its avatar. */
    val zec = Color(0xFFF4B728)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/** Inter, the design's typeface (variable font in res/font). */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

private val scheme = darkColorScheme(
    primary = Ink.text,
    onPrimary = Color.Black,
    secondary = Ink.raised,
    onSecondary = Ink.text,
    background = Ink.page,
    onBackground = Ink.text,
    surface = Ink.page,
    onSurface = Ink.text,
    surfaceVariant = Ink.card,
    onSurfaceVariant = Ink.dim,
    surfaceContainer = Ink.card,
    surfaceContainerHigh = Ink.raised,
    surfaceContainerHighest = Ink.raised,
    outline = Ink.line,
    outlineVariant = Ink.line,
    error = Ink.red,
    onError = Color.Black,
    errorContainer = Ink.redCard,
    onErrorContainer = Color(0xFFFFD6CC),
)

private fun t(size: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0, color: Color = Color.Unspecified) =
    TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = w, letterSpacing = track.sp, color = color)

private val type = Typography(
    displayLarge = t(60, FontWeight.Bold, -2.0),
    displayMedium = t(44, FontWeight.Bold, -1.4),
    displaySmall = t(36, FontWeight.Bold, -1.0),
    headlineLarge = t(32, FontWeight.Bold, -0.8),
    headlineMedium = t(26, FontWeight.Bold, -0.5),
    titleLarge = t(20, FontWeight.SemiBold, -0.2),
    titleMedium = t(16, FontWeight.SemiBold),
    titleSmall = t(15, FontWeight.Medium),
    bodyLarge = t(16),
    bodyMedium = t(15),
    bodySmall = t(13, color = Ink.dim),
    labelLarge = t(17, FontWeight.SemiBold),
    labelMedium = t(14, FontWeight.Medium),
    labelSmall = t(12, FontWeight.Medium),
)

@Composable
fun WalletTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
