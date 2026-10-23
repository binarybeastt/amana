package dev.offlinezec.wallet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import dev.offlinezec.wallet.App
import dev.offlinezec.wallet.AppController
import dev.offlinezec.wallet.BuildConfig
import dev.offlinezec.wallet.PayState
import dev.offlinezec.wallet.ReceiveState
import dev.offlinezec.wallet.incomingUnsettled
import dev.offlinezec.wallet.mesh.Mesh
import dev.offlinezec.wallet.qr.FrameEncoder
import dev.offlinezec.wallet.qr.QrAnalyzer
import dev.offlinezec.wallet.receivedLabel
import dev.offlinezec.wallet.wallet.Caps
import dev.offlinezec.wallet.wallet.Limits
import dev.offlinezec.wallet.wallet.PrepareStatus
import dev.offlinezec.wallet.wallet.SPEND_CONFIRMATIONS
import dev.offlinezec.wallet.wallet.SeedWarning
import dev.offlinezec.wallet.wallet.SyncInfo
import dev.offlinezec.wallet.wallet.WalletState
import dev.offlinezec.wallet.zec
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.util.concurrent.Executors

enum class Screen { Home, Prepare, Pay, Receive, Payments, Limits }

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val c = App.controller(this)
        // Bluetooth relaying needs these; the mesh starts as soon as they are granted.
        val missing = Mesh.PERMISSIONS.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.mesh.permissionsChanged() }
                .launch(missing.toTypedArray())
        } else {
            c.mesh.permissionsChanged()
        }
        setContent {
            WalletTheme {
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = Ink.page) {
                    Root(c)
                }
            }
        }
    }
}

@Composable
fun Root(c: AppController) {
    val state by c.wallet.state.collectAsState()
    when (val s = state) {
        WalletState.Loading -> Centered("Loading…")
        WalletState.NoWallet -> SetupScreen(c)
        is WalletState.Opening -> Centered("${s.what}…")
        is WalletState.Failed -> Centered("Wallet failed to open:\n${s.error}")
        is WalletState.Ready -> WalletScreens(c)
    }
    SeedWarningDialog(c)
}

@Composable
fun Centered(text: String) {
    Column(
        Modifier.fillMaxSize().background(Ink.black).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZecAvatar(72.dp)
        Spacer(Modifier.height(24.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("status_text"))
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Ink.text, unfocusedBorderColor = Ink.line, disabledBorderColor = Ink.card,
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
    focusedLabelColor = Ink.text, unfocusedLabelColor = Ink.dim, cursorColor = Ink.text,
)

/** Big centred title with at most one short grey line under it, as on the design's Receive screen. */
@Composable
fun BigTitle(title: String, sub: String?, modifier: Modifier = Modifier, tag: String? = null) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ink.dim, textAlign = TextAlign.Center)
    }
}

/** A sheet screen's left-aligned large title (34) and its one optional line. */
@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
    }
}

@Composable
fun SetupScreen(c: AppController) {
    var restoring by remember { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf("") }
    val msg by c.message.collectAsState()
    Page(bg = Ink.black, gap = 16.dp) {
        Spacer(Modifier.height(64.dp))
        ZecAvatar(64.dp)
        Spacer(Modifier.height(8.dp))
        Text("Zcash that works\nwithout signal", style = MaterialTheme.typography.displaySmall)
        Text("Pay phone to phone, settle later.", style = MaterialTheme.typography.bodyLarge, color = Ink.dim)
        FactPill(bg = Ink.card) { Dot(Ink.amber, 6.dp); Text("Testnet", style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.height(32.dp))
        if (!restoring) {
            PrimaryButton("Create wallet", { c.createWallet() }, Modifier.testTag("create_wallet"))
            SecondaryButton("Restore", { restoring = true }, Modifier.testTag("restore_wallet"))
        } else {
            OutlinedTextField(words, { words = it }, label = { Text("Seed phrase") }, shape = RoundedCornerShape(16.dp),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("restore_words"))
            OutlinedTextField(birthday, { birthday = it.filter(Char::isDigit) }, label = { Text("Birthday height") }, shape = RoundedCornerShape(16.dp),
                colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("restore_birthday"))
            PrimaryButton("Restore", { c.restoreWallet(words, birthday.toLongOrNull() ?: 0) },
                Modifier.testTag("restore_go"), enabled = words.isNotBlank() && birthday.isNotBlank())
            TextButton("Back", { restoring = false }, Modifier.align(Alignment.CenterHorizontally))
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.red) }
    }
}

/** P5.G3: blocks the whole app until acknowledged; Back and taps outside do nothing. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeedWarningDialog(c: AppController) {
    val w by c.seedWarning.collectAsState()
    val warning = w ?: return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        DarkCard(Modifier.semantics { testTagsAsResourceId = true }.testTag("seed_warning"), color = Ink.sheet, padding = 24.dp, gap = 16.dp) {
            Avatar(Glyph.Warning, Ink.red, 48.dp)
            Text(
                if (warning == SeedWarning.RESTORED) "Is this seed on another phone?" else "One phone per seed",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("seed_warning_title"),
            )
            Text("Never pay offline from two phones with the same seed. Only one payment would settle.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("I understand", { c.acknowledgeSeedWarning(warning) }, Modifier.testTag("seed_ack"))
        }
    }
}

@Composable
fun WalletScreens(c: AppController) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var payScan by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> HomeScreen(c) { s, scan -> payScan = scan; screen = s }
        Screen.Prepare -> PrepareScreen(c) { screen = Screen.Home }
        Screen.Pay -> PayScreen(c, payScan, prepare = { screen = Screen.Prepare }) { screen = Screen.Home }
        Screen.Receive -> ReceiveScreen(c) { screen = Screen.Home }
        Screen.Payments -> PaymentsScreen(c, showQr = { payScan = false; screen = Screen.Pay }) { screen = Screen.Home }
        Screen.Limits -> LimitsScreen(c) { screen = Screen.Home }
    }
}

/** One word for the connection: "Online", "Offline", "Syncing 42%". Block heights stay out of sight. */
fun syncText(s: SyncInfo): String = when {
    s.status == "SYNCING" && s.networkHeight != null -> "Syncing ${(s.progress * 100).toInt()}%"
    isOffline(s) -> "Offline"
    s.atTip -> "Online"
    else -> s.status.lowercase().replaceFirstChar { it.uppercase() }
}

fun isOffline(s: SyncInfo) = s.networkHeight == null || s.status == "DISCONNECTED"

/** "0.0036 ZEC" with the unit smaller and grey. */
fun zecText(zats: Long, unitSize: Int = 22): AnnotatedString = buildAnnotatedString {
    append(zec(zats))
    withStyle(SpanStyle(color = Ink.dim, fontSize = unitSize.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp)) { append(" ZEC") }
}

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(c: AppController, go: (Screen, Boolean) -> Unit) {
    c.wallet.wallet ?: return
    val sync by c.wallet.sync.collectAsState()
    val prep by c.prepare.collectAsState()
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    val notes by c.notes.collectAsState()
    val m by c.meshInfo.collectAsState()
    val amounts by c.sentAmounts.collectAsState()
    val msg by c.message.collectAsState()
    val split = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight)
    val now = c.now()
    val rows = paymentRows(pending, received, m.queue, amounts, now)
    Page(gap = 0.dp) {
        TopBar(c, sync, go)
        ConflictBanner(c)
        HeroCard(sync, split)
        // P4.G4: received offline but unmined is shown apart from the balance; conflicted never counts.
        val incoming = incomingUnsettled(received)
        if (incoming > 0) Row(Modifier.padding(start = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(Ink.amber, 6.dp)
            Text("Received offline, not yet settled: ${zec(incoming)}", style = MaterialTheme.typography.labelMedium,
                color = Ink.amber, modifier = Modifier.testTag("balance_incoming"))
        }
        val lost = received.filter { it.conflicted }
        if (lost.isNotEmpty()) FactPill(Modifier.fillMaxWidth().padding(top = 8.dp), bg = Ink.redCard) {
            Dot(Ink.red, 6.dp)
            Text(
                "Conflicted, not counted: ${zec(lost.sumOf { it.amount })} (${lost.size} double spend${if (lost.size == 1) "" else "s"})",
                style = MaterialTheme.typography.labelMedium, color = Ink.red, modifier = Modifier.testTag("balance_conflicted"),
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionButton(Glyph.Up, "Pay", { c.clearPay(); go(Screen.Pay, false) }, Modifier.testTag("nav_pay"), primary = true)
            ActionButton(Glyph.Down, "Receive", { c.resetReceive(); go(Screen.Receive, false) }, Modifier.testTag("nav_receive"))
            val badge = when (val p = prep) {
                is PrepareStatus.Waiting -> Ink.amber
                is PrepareStatus.Idle -> if (p.ready) Ink.green else if (p.notes.isEmpty()) null else Ink.amber
                null -> null
            }
            ActionButton(Glyph.Notes, "Prepare", { go(Screen.Prepare, false) }, Modifier.testTag("nav_prepare"), badge = badge)
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton("See all", { go(Screen.Payments, false) }, Modifier.testTag("nav_payments"), glyph = null, color = Ink.dim)
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) Text("No payments yet", style = MaterialTheme.typography.bodyMedium, color = Ink.dim,
            modifier = Modifier.padding(vertical = 16.dp))
        rows.take(5).forEach { PaymentListRow(it, now) { go(Screen.Payments, false) } }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp).testTag("message")) }
        Spacer(Modifier.height(24.dp))
    }
}

/** The design's minimal bar: connection on the left, scan in the middle, the profile circle (limits) on the right. */
@Composable
fun TopBar(c: AppController, sync: SyncInfo, go: (Screen, Boolean) -> Unit) {
    val m by c.meshInfo.collectAsState()
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(if (isOffline(sync)) Ink.amber else Ink.green, 8.dp)
            Text(syncText(sync), style = MaterialTheme.typography.labelMedium, color = Ink.dim, maxLines = 1, modifier = Modifier.testTag("sync_status"))
            // Bluetooth relay, as a glyph and the number of phones in reach.
            val on = m.permitted && m.bluetooth && m.running
            Spacer(Modifier.width(4.dp))
            LineIcon(Glyph.Bluetooth, size = 16.dp, color = if (on) Ink.blue else Ink.faint)
            Text(if (on) "${m.peers}" else "off", style = MaterialTheme.typography.labelMedium, color = Ink.dim, modifier = Modifier.testTag("mesh_status"))
        }
        IconButton(Glyph.Scan, { c.clearPay(); go(Screen.Pay, true) }, tag = "nav_scan", filled = false, modifier = Modifier.align(Alignment.Center))
        IconButton(Glyph.Profile, { go(Screen.Limits, false) }, tag = "nav_limits", filled = false, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** The black hero card: coin, total, what's ready offline. Tap for the rest of the P5.G2 split. */
@Composable
fun HeroCard(sync: SyncInfo, split: BalanceSplit) {
    var open by rememberSaveable { mutableStateOf(false) }
    DarkCard(color = Ink.black, padding = 24.dp, gap = 0.dp, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ZecAvatar(48.dp)
            Column {
                Text("Zcash", style = MaterialTheme.typography.titleMedium)
                Text("Testnet", style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(zecText(split.total), style = MaterialTheme.typography.displayMedium, maxLines = 1, modifier = Modifier.testTag("balance_total"))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FactPill(bg = Ink.card) {
                Dot(Ink.green, 6.dp)
                Text("${zec(split.available)} ready offline", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("balance_offline"))
            }
            Spacer(Modifier.weight(1f))
            LineIcon(if (open) Glyph.ChevronDown else Glyph.ChevronRight, size = 20.dp, color = Ink.dim)
        }
        if (open) {
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueRow("Confirming", zec(split.locked), Modifier.testTag("balance_ironwood"))
                ValueRow("Change due", zec(split.pending), Modifier.testTag("balance_pending"))
                if (split.inPayments > 0) ValueRow("Paid, settling", zec(split.inPayments), Modifier.testTag("balance_in_payments"))
            }
        }
        // Only a real sync shows progress; an offline phone shows a plain word, never a stuck bar.
        if (sync.status == "SYNCING") {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { sync.progress }, modifier = Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
        }
    }
}

/** P4.G2: two payments spend the same note. Stays up; it is not something to tap away. */
@Composable
fun ConflictBanner(c: AppController) {
    val m by c.meshInfo.collectAsState()
    if (m.conflicts.isEmpty()) return
    val ours = m.queue.filter { it.mine || it.forUs }.associateBy { it.txid }
    DarkCard(Modifier.padding(bottom = 16.dp).testTag("conflict_alert"), color = Ink.redCard, border = Ink.red.copy(alpha = 0.5f), gap = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(Glyph.Warning, Ink.red, 40.dp)
            Text(
                "Double spend" + if (m.conflicts.size > 1) " (${m.conflicts.size})" else "",
                style = MaterialTheme.typography.headlineMedium, color = Ink.red,
            )
        }
        m.conflicts.sortedByDescending { it.detectedAt }.take(3).forEach { k ->
            val involved = k.txids.mapNotNull { ours[it] }
            Text(
                when {
                    involved.any { it.forUs } -> "A payment to you won't settle. Hold the goods."
                    involved.any { it.mine } -> "Your seed may be in use on another phone."
                    else -> "Two payments spend the same note. Only one can settle."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

enum class PaymentFilter(val title: String) {
    ALL("All"), SENT("Sent"), RECEIVED("Received"), RELAY("Relaying");

    fun keeps(r: PaymentRow) = when (this) {
        ALL -> true
        SENT -> r.direction == Direction.SENT
        RECEIVED -> r.direction == Direction.RECEIVED
        RELAY -> r.direction == Direction.RELAY
    }
}

@Composable
fun FilterChips(filter: PaymentFilter, set: (PaymentFilter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentFilter.entries.forEach { f ->
            Chip(f.title, Modifier.testTag("filter_${f.name.lowercase()}"), selected = filter == f, onClick = { set(f) })
        }
    }
}

fun PaymentRow.glyph() = when (direction) { Direction.SENT -> Glyph.Up; Direction.RECEIVED -> Glyph.Down; Direction.RELAY -> Glyph.Relay }

fun PaymentRow.tint() = when {
    status == PayStatus.CONFLICTED || status == PayStatus.REJECTED -> Ink.red
    direction == Direction.SENT -> Ink.text
    direction == Direction.RECEIVED -> Ink.green
    else -> Ink.blue
}

fun PaymentRow.title() = when (direction) {
    Direction.SENT -> "Sent"
    Direction.RECEIVED -> "Received"
    Direction.RELAY -> "Relaying"
}

fun PaymentRow.value(): String? = amount?.let { (if (direction == Direction.SENT) "−" else "+") + zec(it) }

/** One payment as the design's token row: title over its age, amount over one status word. */
@Composable
fun PaymentListRow(r: PaymentRow, now: Long, modifier: Modifier = Modifier, ageTag: String? = null, onClick: (() -> Unit)? = null) {
    val bad = r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED
    ListRow(
        avatar = { Avatar(r.glyph(), r.tint(), 44.dp) },
        title = r.title(), tag = ago(r.since, now), tagModifier = if (ageTag != null) Modifier.testTag(ageTag) else Modifier,
        value = r.value(), valueColor = if (bad) Ink.red else Ink.text,
        status = { StatusText(r.status, Modifier.testTag("status_${r.txid.take(16)}")) },
        modifier = modifier, onClick = onClick,
    )
}

// ---------------------------------------------------------------- Prepare

@Composable
fun PrepareScreen(c: AppController, back: () -> Unit) {
    val prep by c.prepare.collectAsState()
    val busy by c.prepareBusy.collectAsState()
    val sync by c.wallet.sync.collectAsState()
    val msg by c.message.collectAsState()
    val waiting = prep is PrepareStatus.Waiting
    // The split spends everything, so every note must be spendable by the SDK's rules first.
    val allSpendable = sync.ironwoodAvailable > 0 && sync.ironwoodAvailable == sync.ironwoodTotal
    Page {
        SheetTop(back)
        ScreenTitle("Offline cash", "Exact notes you can spend without signal.")
        DarkCard(color = Ink.black, padding = 24.dp, gap = 8.dp) {
            when (val p = prep) {
                is PrepareStatus.Waiting -> {
                    Text("Confirming split…", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("prepare_state"))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
                }
                is PrepareStatus.Idle -> {
                    Text(zecText(p.notes.sumOf { it.value }), style = MaterialTheme.typography.displayMedium, maxLines = 1)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val color = if (p.ready) Ink.green else if (p.notes.isEmpty()) Ink.dim else Ink.amber
                        Dot(color, 6.dp)
                        Text(if (p.ready) "Ready" else if (p.notes.isEmpty()) "Not prepared" else "Confirming",
                            style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.testTag("prepare_state"))
                        if (p.notes.isNotEmpty()) Text("· ${p.notes.size} notes", style = MaterialTheme.typography.titleSmall, color = Ink.dim)
                    }
                }
                null -> Text("…")
            }
        }
        (prep as? PrepareStatus.Idle)?.let { p ->
            // One row per note size: "0.01 ZEC × 2" and whether it can be spent offline yet.
            Column {
                p.notes.groupBy { it.value }.toSortedMap(compareByDescending { it }).entries.forEachIndexed { i, (value, notes) ->
                    val conf = notes.minOf { n ->
                        if (n.minedHeight != null && p.tip != null) (p.tip - n.minedHeight + 1).coerceAtLeast(0) else 0
                    }
                    val ok = conf >= SPEND_CONFIRMATIONS
                    if (i > 0) HorizontalDivider(color = Ink.card)
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${zec(value)} ZEC × ${notes.size}", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).testTag("denomination"))
                        Text(if (ok) "ready" else "$conf/$SPEND_CONFIRMATIONS", style = MaterialTheme.typography.labelMedium,
                            color = if (ok) Ink.green else Ink.amber)
                    }
                }
            }
        }
        val hint = when {
            !sync.atTip -> "Waiting for sync"
            !waiting && !allSpendable -> "Waiting for funds to confirm (${zec(sync.ironwoodAvailable)} of ${zec(sync.ironwoodTotal)})"
            else -> null
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            if (busy) "Sending…" else if (waiting) "Confirming…" else "Prepare",
            { c.prepareForOffline() }, Modifier.testTag("prepare_button"),
            enabled = !busy && !waiting && sync.atTip && allSpendable,
        )
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("prepare_hint")) }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- Pay

/** ZEC decimal text to zatoshis, or null if invalid. */
fun parseZec(s: String): Long? = try {
    BigDecimal(s.trim()).movePointRight(8).longValueExact().takeIf { it > 0 }
} catch (_: Exception) {
    null
}

@Composable
fun PayScreen(c: AppController, scanFirst: Boolean, prepare: () -> Unit, back: () -> Unit) {
    val pay by c.pay.collectAsState()
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }
    var withMemo by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(scanFirst) }
    val notes by c.notes.collectAsState()
    val sync by c.wallet.sync.collectAsState()
    val caps by c.capsState.collectAsState()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    if (pay is PayState.Ready) {
        PaymentQr(c, pay as PayState.Ready, back)
        return
    }
    val small = LocalConfiguration.current.screenHeightDp < 760
    val ready = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight).available
    val zats = parseZec(amount)
    val building = pay is PayState.Building
    Page(gap = if (small) 8.dp else 16.dp) {
        SheetTop(back) {
            if (!withMemo) IconButton(Glyph.Memo, { withMemo = true }, tag = "pay_add_memo", filled = false)
        }
        // Amount first, on the design's lighter sheet card; its one sub line is the only hint on the screen.
        DarkCard(color = Ink.sheet, padding = if (small) 16.dp else 24.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (small) 4.dp else 16.dp)) {
                FactPill(bg = Ink.raised) { ZecAvatar(20.dp); Text("ZEC", style = MaterialTheme.typography.labelMedium) }
                val over = zats != null && zats > ready
                Text(
                    amount.ifEmpty { "0" }, style = if (small) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                    maxLines = 1, color = if (amount.isEmpty()) Ink.faint else if (over) Ink.red else Ink.text, modifier = Modifier.testTag("pay_amount"),
                )
                val (sub, color) = when {
                    ready == 0L -> "Nothing ready offline" to Ink.amber
                    over -> "Only ${zec(ready)} ready offline" to Ink.red
                    caps.enabled -> "${zec(ready)} ready · limit ${zec(caps.perPayment)}" to Ink.dim
                    else -> "${zec(ready)} ready offline" to Ink.dim
                }
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1,
                    modifier = if (caps.enabled) Modifier.testTag("pay_limits") else Modifier)
            }
        }
        if (scanning) {
            DarkCard(color = Ink.black, border = Ink.line, padding = 8.dp) {
                CameraScanner(if (small) 220 else 280, inverted = true) { text ->
                    val addr = text.removePrefix("zcash:").substringBefore('?')
                    if (addr.startsWith("utest") || addr.startsWith("u1")) {
                        to = addr
                        scanning = false
                    }
                }
            }
            TextButton("Cancel", { scanning = false }, Modifier.align(Alignment.CenterHorizontally))
        } else {
            // The recipient, styled as the design's bank row.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Ink.card), contentAlignment = Alignment.Center) {
                    LineIcon(Glyph.Up, size = 20.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("To", style = MaterialTheme.typography.titleSmall)
                    Box {
                        if (to.isEmpty()) Text("Address", color = Ink.dim, style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            to, { to = it.trim() }, singleLine = true, cursorBrush = SolidColor(Ink.text),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.dim, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                            modifier = Modifier.fillMaxWidth().testTag("pay_to"),
                        )
                    }
                }
                if (to.isEmpty()) IconButton(Glyph.Paste, { clipboard.getText()?.text?.trim()?.let { to = it } }, tag = "pay_paste", filled = false)
                else IconButton(Glyph.Close, { to = "" }, filled = false)
                IconButton(Glyph.Scan, { scanning = true }, tag = "pay_scan_address")
            }
            if (withMemo) {
                OutlinedTextField(memo, { memo = it }, placeholder = { Text("Memo") }, shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pay_memo"))
            }
            Keypad({ k -> amount = keypadInput(amount, k) }, keyHeight = if (small) 48.dp else 64.dp)
        }
        val canPay = !building && zats != null && to.isNotBlank() && zats <= ready
        PrimaryButton(if (building) "Signing…" else "Pay", { c.payOffline(to, zats!!, memo.ifBlank { null }) }, Modifier.testTag("pay_go"), enabled = canPay)
        if (!building && ready == 0L) TextButton("Prepare offline cash", prepare, Modifier.align(Alignment.CenterHorizontally))
        if (building) LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
        if (pay is PayState.Failed) Text((pay as PayState.Failed).error, style = MaterialTheme.typography.bodySmall, color = Ink.red,
            modifier = Modifier.testTag("pay_error"))
        Spacer(Modifier.height(8.dp))
    }
}

/** The signed, stored payment as an animated QR. Shown only after write-before-send. */
@Composable
fun PaymentQr(c: AppController, p: PayState.Ready, back: () -> Unit) {
    val encoder = remember(p.payment.txid) { FrameEncoder(p.payment.raw) }
    var frame by remember(p.payment.txid) { mutableStateOf(encoder.next()) }
    LaunchedEffect(p.payment.txid) {
        c.onPaymentQrShown(p.payment)
        while (true) {
            delay(FRAME_MS)
            frame = encoder.next()
        }
    }
    Page(bg = Ink.black, gap = 16.dp) {
        SheetTop({ c.clearPay(); back() })
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(zecText(p.payment.amount, 22), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("pay_summary"))
            Text("fee ${zec(p.payment.fee)} · ${shortTxid(p.payment.txid)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pay_txid"))
        }
        DarkCard(color = Ink.black, border = Ink.line, padding = 24.dp) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrTile(frame, 280.dp, tag = "payment_qr") }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show the receiver", style = MaterialTheme.typography.headlineMedium)
            Text("Settles when either phone gets online", style = MaterialTheme.typography.bodyMedium, color = Ink.dim, textAlign = TextAlign.Center)
        }
        PrimaryButton("Done", { c.clearPay(); back() }, Modifier.testTag("pay_done"))
        Spacer(Modifier.height(8.dp))
    }
}

private const val FRAME_MS = 200L

// ---------------------------------------------------------------- Receive

@Composable
fun ReceiveScreen(c: AppController, back: () -> Unit) {
    val w = c.wallet.wallet ?: return
    val st by c.receive.collectAsState()
    var scan by rememberSaveable { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Page(bg = Ink.black, gap = 24.dp) {
        SheetTop(back)
        when (val s = st) {
            is ReceiveState.Scanning -> {
                // The design's address pill: short address; tap for the whole thing.
                if (!scan) Row(
                    Modifier.align(Alignment.CenterHorizontally).height(48.dp).clip(Pill).border(1.dp, Ink.line, Pill).clickable { full = !full }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(shortAddress(w.address), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("address"))
                    LineIcon(Glyph.ChevronDown, size = 16.dp, color = Ink.dim)
                }
                if (full && !scan) Text(w.address, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Ink.dim, modifier = Modifier.padding(horizontal = 8.dp))
                DarkCard(color = Ink.black, border = Ink.line, padding = if (scan) 8.dp else 32.dp) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!scan) AddressQr(w.address, 240.dp)
                        else CameraScanner(300) { c.onFrame(it) }
                    }
                    if (scan) LinearProgressIndicator(progress = { s.progress.toFloat() }, color = Ink.text, trackColor = Ink.raised,
                        modifier = Modifier.fillMaxWidth().clip(Pill).testTag("receive_progress"))
                }
                if (!scan) {
                    BigTitle("Receive", "Scan or copy to receive ZEC")
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(if (copied) "Copied" else "Copy", {
                            clipboard.setText(AnnotatedString(w.address)); copied = true
                        }, Modifier.testTag("copy_address"))
                        TextButton("Scan a payment", { scan = true }, Modifier.testTag("recv_tab_scan"), glyph = Glyph.Scan)
                    }
                } else {
                    BigTitle("Scan", if (s.progress > 0) "Reading… ${(s.progress * 100).toInt()}%" else "Point at the payer's QR")
                    SecondaryButton("Show my address", { scan = false }, Modifier.testTag("recv_tab_address"))
                }
            }
            ReceiveState.Verifying -> {
                DarkCard(color = Ink.black, border = Ink.line, padding = 32.dp) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
                }
                BigTitle("Checking…", null, tag = "receive_state")
            }
            is ReceiveState.Accepted -> {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(Glyph.Check, Ink.green, 72.dp)
                    Text(zecText(s.payment.amount), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("received_amount"))
                    Text(receivedLabel(s.payment), style = MaterialTheme.typography.bodyMedium, color = Ink.amber, modifier = Modifier.testTag("received_status"))
                    s.payment.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center) }
                }
                s.warning?.let {
                    DarkCard(Modifier.testTag("receive_warning"), color = Ink.redCard, padding = 16.dp) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Scan another", { c.resetReceive() })
            }
            is ReceiveState.Rejected -> {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(Glyph.Close, Ink.red, 72.dp)
                    Text("Not accepted", style = MaterialTheme.typography.displaySmall, color = Ink.red, modifier = Modifier.testTag("receive_state"))
                    Text(s.reason, style = MaterialTheme.typography.bodyMedium, color = Ink.dim, textAlign = TextAlign.Center, modifier = Modifier.testTag("receive_reason"))
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Scan again", { c.resetReceive() })
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------- Limits

/** P4.G3: optional caps on paying offline, and a warning on large incoming payments. Off by default. */
@Composable
fun LimitsScreen(c: AppController, back: () -> Unit) {
    val caps by c.capsState.collectAsState()
    var on by rememberSaveable { mutableStateOf(caps.enabled) }
    var perPayment by rememberSaveable { mutableStateOf(zec(caps.perPayment)) }
    var perSession by rememberSaveable { mutableStateOf(zec(caps.perSession)) }
    var saved by remember { mutableStateOf(false) }
    Page {
        SheetTop(back)
        ScreenTitle("Limits", "Cap what rides on unsettled payments.")
        DarkCard(gap = 16.dp) {
            Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Offline limits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(on, { on = it; saved = false }, Modifier.testTag("limits_on"),
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.text, checkedThumbColor = Color.Black,
                        uncheckedTrackColor = Ink.raised, uncheckedThumbColor = Ink.dim, uncheckedBorderColor = Ink.line))
            }
            if (on) {
                OutlinedTextField(perPayment, { perPayment = it; saved = false }, label = { Text("Per payment") }, suffix = { Text("ZEC") },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("limits_per_payment"))
                OutlinedTextField(perSession, { perSession = it; saved = false }, label = { Text("Unsettled total") }, suffix = { Text("ZEC") },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("limits_per_session"))
            }
        }
        val p = parseZec(perPayment)
        val s = parseZec(perSession)
        PrimaryButton(
            if (saved) "Saved" else "Save",
            { c.setCaps(Caps(on, p ?: caps.perPayment, s ?: caps.perSession)); saved = true },
            Modifier.testTag("limits_save"), enabled = !on || (p != null && s != null),
        )
    }
}

// ---------------------------------------------------------------- Payments

@Composable
fun PaymentsScreen(c: AppController, showQr: () -> Unit, back: () -> Unit) {
    val received by c.receivedPayments.collectAsState()
    val pending by c.pendingSent.collectAsState()
    val m by c.meshInfo.collectAsState()
    val amounts by c.sentAmounts.collectAsState()
    var filter by rememberSaveable { mutableStateOf(PaymentFilter.ALL) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val now = c.now()
    val rows = paymentRows(pending, received, m.queue, amounts, now)
    Page(gap = 8.dp) {
        SheetTop(back)
        ScreenTitle("Payments")
        FilterChips(filter) { filter = it }
        Spacer(Modifier.height(8.dp))
        val shown = rows.filter { filter.keeps(it) }
        if (shown.isEmpty()) Text("No payments yet", style = MaterialTheme.typography.bodyMedium, color = Ink.dim, modifier = Modifier.padding(vertical = 16.dp))
        shown.forEach { r ->
            val key = r.txid.take(16)
            val tag = when (r.direction) { Direction.SENT -> "sent_$key"; Direction.RECEIVED -> "received_$key"; Direction.RELAY -> "relay_$key" }
            val bad = r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED
            val expanded = open == r.txid
            Column(
                Modifier.fillMaxWidth().let { if (bad) it.clip(RoundedCornerShape(24.dp)).background(Ink.redCard).padding(horizontal = 16.dp) else it }
                    .testTag(tag),
            ) {
                PaymentListRow(r, now, ageTag = "age_$key", onClick = { open = if (expanded) null else r.txid })
                // Detail stays quiet: the label only when it says more than the status word, the rest on tap.
                val showLabel = bad || r.status != PayStatus.SETTLED || expanded
                if (showLabel || r.memo != null || r.canShowQr) Column(Modifier.padding(start = 60.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showLabel) Text(
                        r.label, style = MaterialTheme.typography.bodySmall, color = if (bad) Ink.red else Ink.dim,
                        modifier = if (r.direction == Direction.RECEIVED) Modifier.testTag("received_label_$key") else Modifier,
                    )
                    r.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium) }
                    if (expanded) {
                        Text(r.txid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (r.direction == Direction.RELAY) Text("${r.hops} hop${if (r.hops == 1) "" else "s"} away", style = MaterialTheme.typography.bodySmall)
                    }
                    if (r.canShowQr) {
                        Box(
                            Modifier.height(36.dp).clip(Pill).background(Color.White)
                                .clickable { c.showPending(r.txid); showQr() }.padding(horizontal = 16.dp)
                                .testTag("pending_show_$key"),
                            contentAlignment = Alignment.Center,
                        ) { Text("Show QR", color = Color.Black, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
        }
        // Debug builds: push everything to the network now instead of waiting for the next try.
        if (BuildConfig.DEBUG) TextButton("Broadcast now (debug)", { c.broadcastNow() }, Modifier.align(Alignment.CenterHorizontally).testTag("broadcast_now"), color = Ink.dim)
        val msg by c.message.collectAsState()
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- Camera

/**
 * Camera preview that reports every decoded QR text. Asks for the camera permission.
 * [inverted]: also read light-on-dark codes (address QRs drawn like this app's).
 */
@Composable
fun CameraScanner(heightDp: Int, inverted: Boolean = false, onText: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Text("Camera permission is needed to scan.", color = Ink.dim)
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        // COMPATIBLE = a TextureView: it clips to the rounded card and stays under the header,
        // where the default SurfaceView drew over the Back control.
        modifier = Modifier.fillMaxWidth().height(heightDp.dp).clip(RoundedCornerShape(22.dp)).testTag("camera"),
        factory = { context ->
            val view = PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().also { it.setAnalyzer(executor, QrAnalyzer(inverted = inverted, onText = onText)) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(context))
            view
        },
    )
}
