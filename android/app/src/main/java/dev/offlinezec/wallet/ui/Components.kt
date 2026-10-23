package dev.offlinezec.wallet.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.offlinezec.wallet.qr.qrBitmap

val CardShape = RoundedCornerShape(28.dp)
val Pill = RoundedCornerShape(50)

/** A screen: page background, system-bar padding, scrolling column. */
@Composable
fun Page(scroll: Boolean = true, gap: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val m = Modifier.fillMaxSize().background(Ink.page).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = 16.dp).let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
    Column(m, verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

/** Sheet-like screens: grabber line, back arrow, big bold title ([compact]: title beside the arrow). */
@Composable
fun SheetHeader(title: String, back: () -> Unit, compact: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(4.dp).clip(Pill).background(Ink.line))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon("←", onClick = back, tag = "back")
            if (compact) Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 14.dp))
            Spacer(Modifier.weight(1f))
            trailing()
        }
        if (!compact) Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
}

/** A round dark button holding a glyph (no icon font needed). */
@Composable
fun RoundIcon(glyph: String, onClick: () -> Unit, tag: String? = null, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Ink.card).clickable(onClick = onClick)
            .let { if (tag != null) it.testTag(tag) else it },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 20.sp, color = Ink.text) }
}

@Composable
fun DarkCard(modifier: Modifier = Modifier, color: Color = Ink.card, onClick: (() -> Unit)? = null, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(color)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Full-width white pill with black text: the one main action of a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = Pill,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black,
            disabledContainerColor = Ink.raised, disabledContentColor = Ink.faint),
        modifier = modifier.fillMaxWidth().height(60.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** Dark pill: secondary actions. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = Pill,
        colors = ButtonDefaults.buttonColors(containerColor = Ink.raised, contentColor = Ink.text,
            disabledContainerColor = Ink.card, disabledContentColor = Ink.faint),
        modifier = modifier.fillMaxWidth().height(56.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

/** Small outlined pill; [selected] fills it white-outlined like the reference's filter chips. */
@Composable
fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, color: Color = Ink.dim, onClick: (() -> Unit)? = null) {
    Box(
        modifier.clip(Pill)
            .border(1.dp, if (selected) Ink.text else Ink.line, Pill)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Ink.text else color) }
}

fun PayStatus.color(): Color = when (this) {
    PayStatus.PENDING -> Ink.amber
    PayStatus.RELAYED -> Ink.blue
    PayStatus.SETTLED -> Ink.green
    PayStatus.CONFLICTED, PayStatus.REJECTED -> Ink.red
}

/** P5.G1: the status a payment is in, as a tinted pill with a dot. Never a spinner. */
@Composable
fun StatusChip(s: PayStatus, modifier: Modifier = Modifier) {
    val c = s.color()
    Row(
        modifier.clip(Pill).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        Text(s.chip, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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

/** QR on a white rounded tile (black on white scans everywhere), optionally with the ZEC logo. */
@Composable
fun QrTile(text: String, size: Dp, tag: String = "qr", logo: Boolean = false) {
    val bmp = remember(text, logo) { qrBitmap(text, 720, if (logo) ErrorCorrectionLevel.H else ErrorCorrectionLevel.L, margin = 1) }
    Box(Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.fillMaxSize().testTag(tag))
        if (logo) ZecLogo(size * 0.2f)
    }
}

/** A black disc with a yellow Z: the centre of the address QR (and the app's mark). */
@Composable
fun ZecLogo(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White).padding(size * 0.08f), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Z", color = Ink.zec, fontWeight = FontWeight.Black, fontSize = (size.value * 0.48f).sp)
        }
    }
}

/** 1–9 . 0 ⌫, as on the reference's Buy screen. Keys carry testTags key_0…key_9, key_dot, key_back. */
@Composable
fun Keypad(onKey: (Char) -> Unit, modifier: Modifier = Modifier, keyHeight: Dp = 46.dp) {
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
                        Text(if (k == '<') "⌫" else k.toString(), fontSize = 28.sp, fontWeight = FontWeight.Medium, color = Ink.text)
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
