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
 * The palette: greys and white, one green for settled and one red for problems. No other hues.
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
    val red = Color(0xFFFF453A)
    val redCard = Color(0xFF2A1211)
    val green = Color(0xFF30D158)
}

@OptIn(ExperimentalTextApi::class)
private fun inter(w: Int, opsz: Float) = Font(
    R.font.inter, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", opsz)),
)

/** Inter's text cut (optical size 14), for 17 sp and under. Three weights only. */
val Inter = FontFamily(inter(400, 14f), inter(500, 14f), inter(600, 14f))

/** Inter Display (optical size 32): the tighter, finer cut for titles and amounts. */
val InterDisplay = FontFamily(inter(400, 32f), inter(500, 32f), inter(600, 32f))

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
    onErrorContainer = Color(0xFFF2F2F7),
)

private fun t(size: Int, w: FontWeight = FontWeight.Normal, track: Double = 0.0, color: Color = Color.Unspecified, family: FontFamily = Inter) =
    TextStyle(fontFamily = family, fontSize = size.sp, fontWeight = w, letterSpacing = track.sp, color = color)

/** Compact, like the reference: money large (48 / 40), titles 28 and 20, text 15 / 14 / 13 / 12. */
private val type = Typography(
    displayLarge = t(48, FontWeight.SemiBold, -1.0, family = InterDisplay),
    displayMedium = t(40, FontWeight.SemiBold, -0.8, family = InterDisplay),
    displaySmall = t(28, FontWeight.SemiBold, -0.4, family = InterDisplay),
    headlineLarge = t(28, FontWeight.SemiBold, -0.4, family = InterDisplay),
    headlineMedium = t(20, FontWeight.SemiBold, -0.2, family = InterDisplay),
    titleLarge = t(15, FontWeight.SemiBold),
    titleMedium = t(15, FontWeight.Medium),
    titleSmall = t(13, FontWeight.Medium, 0.2),
    bodyLarge = t(15),
    bodyMedium = t(14),
    bodySmall = t(12, color = Ink.dim),
    labelLarge = t(16, FontWeight.SemiBold),
    labelMedium = t(13, FontWeight.Medium),
    labelSmall = t(12, FontWeight.Medium),
)

@Composable
fun WalletTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
