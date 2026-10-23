package dev.offlinezec.wallet.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.offlinezec.wallet.qr.qrBitmap
import dev.offlinezec.wallet.qr.styledQrPixels

val CardShape = RoundedCornerShape(32.dp)
val ButtonShape = RoundedCornerShape(16.dp)
val Pill = RoundedCornerShape(50)

/** A screen: background, system-bar padding, a scrolling column with 24 dp gutters (8 pt grid). */
@Composable
fun Page(bg: Color = Ink.page, scroll: Boolean = true, gap: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val m = Modifier.fillMaxSize().background(bg).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 24.dp).let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
    Column(m, verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** Top of a sheet-like screen: the grabber line, with a quiet close control at the left. */
@Composable
fun SheetTop(close: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.align(Alignment.TopCenter).width(40.dp).height(4.dp).clip(Pill).background(Ink.line))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.ChevronDown, close, tag = "back", filled = false)
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/** A thin icon in a round hit area. [filled]: on a dark disc. */
@Composable
fun IconButton(g: Glyph, onClick: () -> Unit, tag: String? = null, filled: Boolean = true, size: Dp = 48.dp, tint: Color = Ink.text, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape).background(if (filled) Ink.card else Color.Transparent).clickable(onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) { LineIcon(g, size = 22.dp, color = tint) }
}

@Composable
fun DarkCard(
    modifier: Modifier = Modifier,
    color: Color = Ink.card,
    border: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = 24.dp,
    gap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(color)
            .let { if (border != null) it.border(1.dp, border, CardShape) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/** The one main action of a screen: full-width white, rounded, black text. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
            disabledContainerColor = Ink.raised, disabledContentColor = Ink.dim),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Secondary action: same shape, dark. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: Glyph? = null) {
    Button(
        onClick = onClick, enabled = enabled, shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(containerColor = Ink.raised, contentColor = Ink.text,
            disabledContainerColor = Ink.card, disabledContentColor = Ink.faint),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        if (glyph != null) { LineIcon(glyph, size = 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** A home action: a 56 dp round button with a short label under it. [primary]: white disc. */
@Composable
fun ActionButton(g: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, badge: Color? = null) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(56.dp)) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(if (primary) Color.White else Ink.raised), contentAlignment = Alignment.Center) {
                LineIcon(g, size = 24.dp, color = if (primary) Color.Black else Ink.text, weight = 2f)
            }
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Ink.page).padding(3.dp).clip(CircleShape).background(badge))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A quiet text-only action, 48 dp tall. */
@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyph? = null, color: Color = Ink.text) {
    Row(
        modifier.height(48.dp).clip(Pill).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph != null) LineIcon(glyph, size = 20.dp, color = color)
        Text(text, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
    }
}

/** The design's filter chip: outlined pill; the selected one has a white outline and white text. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, color: Color = Ink.dim, onClick: (() -> Unit)? = null) {
    Box(
        modifier.height(40.dp).clip(Pill)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Ink.text else Ink.line, Pill)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleSmall, color = if (selected) Ink.text else color) }
}

/** The small filled grey tag under a row's name (the design's "BTC"). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Ink.dim, mono: Boolean = false) {
    Text(
        text, color = color, fontSize = 13.sp, maxLines = 1,
        fontFamily = if (mono) FontFamily.Monospace else Inter,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Ink.raised).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A filled pill holding a short fact (the design's "▲ 1.72% (24h)"). */
@Composable
fun FactPill(modifier: Modifier = Modifier, bg: Color = Ink.card, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.clip(Pill).background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

/** Round avatar: a tinted disc with a thin icon, like the design's token logos. */
@Composable
fun Avatar(g: Glyph, tint: Color, size: Dp = 52.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        LineIcon(g, size = size * 0.44f, color = tint, weight = 2f)
    }
}

/** The ZEC avatar: a yellow Z on a dark-yellow disc. */
@Composable
fun ZecAvatar(size: Dp = 52.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Ink.zec.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Text("Z", color = Ink.zec, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
    }
}

fun PayStatus.color(): Color = when (this) {
    PayStatus.PENDING -> Ink.amber
    PayStatus.RELAYED -> Ink.blue
    PayStatus.SETTLED -> Ink.green
    PayStatus.CONFLICTED, PayStatus.REJECTED -> Ink.red
}

/** P5.G1: a payment's status as coloured text with a dot, where the design shows the day's change. Never a spinner. */
@Composable
fun StatusText(s: PayStatus, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(s.color(), 6.dp)
        Text(s.chip, color = s.color(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Inter)
    }
}

/** A tinted status pill, for single-payment screens. */
@Composable
fun StatusChip(s: PayStatus, modifier: Modifier = Modifier) {
    FactPill(modifier, bg = s.color().copy(alpha = 0.14f)) {
        Dot(s.color(), 6.dp)
        Text(s.chip, color = s.color(), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Inter)
    }
}

/** The design's list row: avatar, UPPERCASE name over a grey tag, value over a coloured status line. */
@Composable
fun ListRow(
    avatar: @Composable () -> Unit,
    title: String,
    tag: String?,
    value: String?,
    modifier: Modifier = Modifier,
    valueColor: Color = Ink.text,
    tagModifier: Modifier = Modifier,
    status: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().let { if (onClick != null) it.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        avatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleSmall, letterSpacing = 0.3.sp, maxLines = 1)
            if (tag != null) Tag(tag, tagModifier)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value != null) Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
            status?.invoke()
        }
    }
}

/** Label left, value right. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, color: Color = Ink.text, labelColor: Color = Ink.dim) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = labelColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** The animated payment QR: plain black on a white tile, the densest and fastest to read. */
@Composable
fun QrTile(text: String, size: Dp, tag: String = "qr") {
    val bmp = remember(text) { qrBitmap(text, 720, margin = 1) }
    Box(Modifier.size(size).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(12.dp), contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxSize().testTag(tag))
    }
}

/** The address QR as the design has it: light dots on black, rounded finders, the logo in the middle. */
@Composable
fun AddressQr(text: String, size: Dp, tag: String = "qr") {
    val bmp = remember(text) {
        val n = 720
        Bitmap.createBitmap(styledQrPixels(text, n, bg = 0xFF050505.toInt()), n, n, Bitmap.Config.ARGB_8888)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), contentDescription = "Address QR code", modifier = Modifier.fillMaxSize().testTag(tag))
        // The logo: a white disc with a black Z, like the design's coin mark.
        Box(Modifier.size(size * 0.17f).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text("Z", color = Color.Black, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.085f).sp)
        }
    }
}

/** 1–9 . 0 ‹, light numerals as in the design. Keys carry testTags key_0…key_9, key_dot, key_back. */
@Composable
fun Keypad(onKey: (Char) -> Unit, modifier: Modifier = Modifier, keyHeight: Dp = 56.dp) {
    val rows = listOf("123", "456", "789", ".0<")
    Column(modifier.fillMaxWidth()) {
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                r.forEach { k ->
                    val tag = when (k) { '.' -> "key_dot"; '<' -> "key_back"; else -> "key_$k" }
                    Box(
                        Modifier.weight(1f).height(keyHeight).clip(RoundedCornerShape(16.dp)).clickable { onKey(k) }.testTag(tag),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == '<') LineIcon(Glyph.ChevronLeft, size = 24.dp)
                        else Text(k.toString(), fontSize = 28.sp, fontFamily = Inter, fontWeight = FontWeight.Normal, color = Ink.text)
                    }
                }
            }
        }
    }
}

/** Applies one keypad key to a ZEC amount being typed: at most 8 decimals, one dot, no leading zeros. */
fun keypadInput(cur: String, k: Char): String = when {
    k == '<' -> cur.dropLast(1)
    k == '.' -> if ('.' in cur) cur else if (cur.isEmpty()) "0." else "$cur."
    cur == "0" -> k.toString()
    '.' in cur && cur.substringAfter('.').length >= 8 -> cur
    cur.substringBefore('.').length >= 8 && '.' !in cur -> cur
    else -> cur + k
}
