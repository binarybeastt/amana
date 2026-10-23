package dev.offlinezec.wallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The look: near-black page, slightly lighter rounded cards, white text, grey secondary text. */
object Ink {
    val page = Color(0xFF0B0B0C)
    val card = Color(0xFF18181A)
    val raised = Color(0xFF242427)
    val line = Color(0xFF2E2E32)
    val text = Color(0xFFFFFFFF)
    val dim = Color(0xFF8E8E93)
    val faint = Color(0xFF5C5C61)
    val red = Color(0xFFFF5A5F)
    val redCard = Color(0xFF3B1214)
    val green = Color(0xFF4CD787)
    val amber = Color(0xFFFFB547)
    val blue = Color(0xFF6EA8FF)
    /** Zcash yellow, for the logo only. */
    val zec = Color(0xFFF4B728)
}

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
    onErrorContainer = Color(0xFFFFD9DA),
)

private val type = Typography(
    displayLarge = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp, color = Ink.dim),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun WalletTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)
