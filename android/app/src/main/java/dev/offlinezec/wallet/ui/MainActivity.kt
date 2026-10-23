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

/** Big centred screen title with a grey line under it, as on the design's Receive screen. */
@Composable
fun BigTitle(title: String, sub: String?, modifier: Modifier = Modifier, tag: String? = null) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ink.dim, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
fun SetupScreen(c: AppController) {
    var restoring by remember { mutableStateOf(false) }
    var words by rememberSaveable { mutableStateOf("") }
    var birthday by rememberSaveable { mutableStateOf("") }
    val msg by c.message.collectAsState()
    Page(bg = Ink.black) {
        Spacer(Modifier.height(56.dp))
        ZecAvatar(72.dp)
        Text("Cash-like Zcash,\nno signal needed.", style = MaterialTheme.typography.displaySmall)
        Text("Pay shielded ZEC phone to phone with no internet. Whichever phone gets online first settles it.",
            style = MaterialTheme.typography.bodyLarge, color = Ink.dim)
        FactPill { Dot(Ink.amber); Text("Testnet", style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.height(32.dp))
        if (!restoring) {
            PrimaryButton("Create a new wallet", { c.createWallet() }, Modifier.testTag("create_wallet"))
            SecondaryButton("Restore from seed phrase", { restoring = true }, Modifier.testTag("restore_wallet"))
        } else {
            OutlinedTextField(words, { words = it }, label = { Text("24-word seed phrase") }, shape = RoundedCornerShape(20.dp),
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("restore_words"))
            OutlinedTextField(birthday, { birthday = it.filter(Char::isDigit) }, label = { Text("Birthday height") }, shape = RoundedCornerShape(20.dp),
                colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("restore_birthday"))
            PrimaryButton(
                if (words.isBlank()) "Enter the seed phrase" else if (birthday.isBlank()) "Enter the birthday height" else "Restore",
                { c.restoreWallet(words, birthday.toLongOrNull() ?: 0) },
                Modifier.testTag("restore_go"), enabled = words.isNotBlank() && birthday.isNotBlank(),
            )
            SecondaryButton("Back", { restoring = false })
        }
        msg?.let { Text(it, color = Ink.red) }
    }
}

/** P5.G3: blocks the whole app until acknowledged; Back and taps outside do nothing. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeedWarningDialog(c: AppController) {
    val w by c.seedWarning.collectAsState()
    val warning = w ?: return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        DarkCard(Modifier.semantics { testTagsAsResourceId = true }.testTag("seed_warning"), color = Ink.sheet, padding = 26.dp, gap = 14.dp) {
            Avatar(Glyph.Warning, Ink.red)
            Text(
                if (warning == SeedWarning.RESTORED) "Is this seed on another phone?" else "You're offline: one phone per seed",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("seed_warning_title"),
            )
            Text(
                if (warning == SeedWarning.RESTORED)
                    "You restored this wallet from a seed phrase. If the same seed is still on another phone, never pay offline from both. " +
                        "Two phones paying offline from one seed spend the same notes: only one payment can settle, and the other receiver goes unpaid."
                else
                    "Offline payments are only checked once a phone gets online. This wallet was restored from a seed: " +
                        "if that seed is on another phone too, pay offline from only one of them.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.dim,
            )
            Spacer(Modifier.height(4.dp))
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

/** "Offline · last synced at block 4,530,694", or how far sync has got. */
fun syncText(s: SyncInfo): String {
    val at = s.scannedHeight?.let { "%,d".format(it) } ?: "?"
    return when {
        s.status == "SYNCING" && s.networkHeight != null -> "Syncing · ${(s.progress * 100).toInt()}% · block $at"
        isOffline(s) -> "Offline · last synced at block $at"
        s.atTip -> "Online · synced to block $at"
        else -> "${s.status.lowercase().replaceFirstChar { it.uppercase() }} · block $at"
    }
}

fun isOffline(s: SyncInfo) = s.networkHeight == null || s.status == "DISCONNECTED"

/** "0.0036 ZEC" with the unit smaller and grey. */
fun zecText(zats: Long, unitSize: Int = 22): AnnotatedString = buildAnnotatedString {
    append(zec(zats))
    withStyle(SpanStyle(color = Ink.dim, fontSize = unitSize.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)) { append(" ZEC") }
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
    val caps by c.capsState.collectAsState()
    var filter by rememberSaveable { mutableStateOf(PaymentFilter.ALL) }
    val split = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight)
    val now = c.now()
    val rows = paymentRows(pending, received, m.queue, amounts, now)
    Page(gap = 14.dp) {
        // The design's minimal bar: a status dot, one action in the middle, a round profile control.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (isOffline(sync)) Ink.amber else Ink.green, 8.dp)
            Spacer(Modifier.weight(1f))
            IconButton(Glyph.Scan, { c.clearPay(); go(Screen.Pay, true) }, tag = "nav_scan", filled = false)
            Spacer(Modifier.weight(1f))
            IconButton(Glyph.Profile, { go(Screen.Limits, false) }, tag = "nav_limits", filled = false)
        }
        ConflictBanner(c)
        HeroCard(sync, split, prep)
        // P4.G4: received offline but unmined is shown apart from the balance; conflicted never counts.
        val incoming = incomingUnsettled(received)
        if (incoming > 0) FactPill(Modifier.fillMaxWidth()) {
            Dot(Ink.amber)
            Text("Received offline, not yet settled: ${zec(incoming)}", style = MaterialTheme.typography.labelMedium,
                color = Ink.amber, modifier = Modifier.testTag("balance_incoming"))
        }
        val lost = received.filter { it.conflicted }
        if (lost.isNotEmpty()) FactPill(Modifier.fillMaxWidth(), bg = Ink.redCard) {
            Dot(Ink.red)
            Text(
                "Conflicted, not counted: ${zec(lost.sumOf { it.amount })} (${lost.size} double spend${if (lost.size == 1) "" else "s"})",
                style = MaterialTheme.typography.labelMedium, color = Ink.red, modifier = Modifier.testTag("balance_conflicted"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Pay", { c.clearPay(); go(Screen.Pay, false) }, Modifier.weight(1f).testTag("nav_pay"))
            SecondaryButton("Receive", { c.resetReceive(); go(Screen.Receive, false) }, Modifier.weight(1f).testTag("nav_receive"))
        }
        // Prepare and Payments side by side, so both are on the first screen.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val (prepText, prepColor) = when (val p = prep) {
                is PrepareStatus.Waiting -> "confirming" to Ink.amber
                is PrepareStatus.Idle -> when {
                    p.ready -> "ready" to Ink.green
                    p.notes.isEmpty() -> "not prepared" to Ink.dim
                    else -> "confirming" to Ink.amber
                }
                null -> "…" to Ink.dim
            }
            Tile(Glyph.Notes, Ink.zec, "Offline notes", prepText, prepColor, Modifier.weight(1f).testTag("nav_prepare")) { go(Screen.Prepare, false) }
            val mine = rows.filter { it.direction != Direction.RELAY }
            val waiting = mine.count { it.status == PayStatus.PENDING || it.status == PayStatus.RELAYED }
            val bad = mine.count { it.status == PayStatus.CONFLICTED || it.status == PayStatus.REJECTED }
            Tile(
                Glyph.Swap, Ink.blue, "Payments",
                if (bad > 0) "$bad conflicted" else if (waiting > 0) "$waiting unsettled" else "all settled",
                if (bad > 0) Ink.red else if (waiting > 0) Ink.amber else Ink.green,
                Modifier.weight(1f).testTag("nav_payments"),
            ) { go(Screen.Payments, false) }
        }
        RelayRow(c)
        if (caps.enabled) Text("Offline limits on: ${zec(caps.perPayment)} ZEC per payment", style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        // Activity: the design's filter chips over token-style rows.
        Spacer(Modifier.height(4.dp))
        FilterChips(filter) { filter = it }
        val shown = rows.filter { filter.keeps(it) }
        if (shown.isEmpty()) Text("Nothing here yet. Payments you send, receive or carry show up here.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.dim, modifier = Modifier.padding(vertical = 12.dp))
        Column {
            shown.take(8).forEach { PaymentListRow(it) { go(Screen.Payments, false) } }
        }
        if (shown.size > 8) Text("See all ${shown.size}", style = MaterialTheme.typography.titleSmall, color = Ink.dim,
            modifier = Modifier.clip(Pill).clickable { go(Screen.Payments, false) }.padding(vertical = 8.dp))
        Spacer(Modifier.height(16.dp))
    }
}

/** The black hero card: coin, total, the P5.G2 split, sync state, and the offline notes as bars. */
@Composable
fun HeroCard(sync: SyncInfo, split: BalanceSplit, prep: PrepareStatus?) {
    DarkCard(color = Ink.black, padding = 24.dp, gap = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ZecAvatar(52.dp)
            Column(Modifier.weight(1f)) {
                Text("Zcash", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text("ZEC · shielded", style = MaterialTheme.typography.bodyLarge, color = Ink.dim)
            }
            FactPill(bg = Ink.card) { Dot(Ink.zec, 6.dp); Text("testnet", fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
        Spacer(Modifier.height(26.dp))
        Text(zecText(split.total), style = MaterialTheme.typography.displayMedium, maxLines = 1, modifier = Modifier.testTag("balance_total"))
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Offline ready", zec(split.available), Ink.green, Modifier.weight(1f).testTag("balance_offline"))
            Stat("Confirming", zec(split.locked), Ink.text, Modifier.weight(1f).testTag("balance_ironwood"))
            Stat("Change due", zec(split.pending), Ink.text, Modifier.weight(1f).testTag("balance_pending"))
        }
        if (split.inPayments > 0) {
            Spacer(Modifier.height(10.dp))
            Text("Paid offline, settling: ${zec(split.inPayments)} ZEC (not in the total)", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("balance_in_payments"))
        }
        Spacer(Modifier.height(20.dp))
        FactPill(bg = Ink.card) {
            Dot(if (isOffline(sync)) Ink.amber else Ink.green, 6.dp)
            Text(syncText(sync), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("sync_status"))
        }
        // Only a real sync shows progress; an offline phone shows a plain line, never a stuck bar.
        if (sync.status == "SYNCING") {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { sync.progress }, modifier = Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
        }
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
    }
}

/** A square-ish shortcut card: avatar, name, coloured status. */
@Composable
fun Tile(g: Glyph, tint: Color, title: String, status: String, statusColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(Ink.card).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(g, tint, 40.dp)
            Spacer(Modifier.weight(1f))
            LineIcon(Glyph.ChevronRight, size = 18.dp, color = Ink.dim)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(statusColor, 6.dp)
                Text(status, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
fun RelayRow(c: AppController) {
    val m by c.meshInfo.collectAsState()
    val unsettled = m.queue.count { it.unsettled }
    val on = m.permitted && m.bluetooth && m.running
    val status = when {
        !m.permitted -> "Bluetooth permission needed to relay payments"
        !m.bluetooth -> "Bluetooth is off: not relaying"
        !m.running -> "Bluetooth relay starting…"
        else -> "${m.peers} phone${if (m.peers == 1) "" else "s"} nearby"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Ink.card).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Avatar(Glyph.Bluetooth, if (on) Ink.blue else Ink.dim, 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Bluetooth relay", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("mesh_status"))
            Text(
                "$unsettled unsettled · " + if (m.online) "broadcasting" else "offline",
                style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.testTag("mesh_queue"),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${m.queue.size}", style = MaterialTheme.typography.titleLarge)
            Text("carried", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** P4.G2: two payments spend the same note. Stays up; it is not something to tap away. */
@Composable
fun ConflictBanner(c: AppController) {
    val m by c.meshInfo.collectAsState()
    if (m.conflicts.isEmpty()) return
    val ours = m.queue.filter { it.mine || it.forUs }.associateBy { it.txid }
    DarkCard(Modifier.testTag("conflict_alert"), color = Ink.redCard, border = Ink.red.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(Glyph.Warning, Ink.red, 40.dp)
            Text(
                "Double spend detected" + if (m.conflicts.size > 1) " (${m.conflicts.size})" else "",
                style = MaterialTheme.typography.titleMedium, color = Ink.red,
            )
        }
        m.conflicts.sortedByDescending { it.detectedAt }.take(3).forEach { k ->
            val involved = k.txids.mapNotNull { ours[it] }
            val text = buildString {
                append(k.txids.joinToString(" and ") { it.take(10) + "…" })
                append(" spend the same note; at most one can ever settle.")
                when {
                    involved.any { it.forUs } -> append(" One is a payment TO YOU: don't hand over goods until it settles.")
                    involved.any { it.mine } -> append(" One is your own payment: your seed may be in use on another phone.")
                }
                k.settled?.let { append(" Mined: ${it.take(10)}….") }
                if (!k.verified) append(" (Partly reported by nearby phones.)")
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
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
    Direction.RELAY -> "Relaying · $hops hop${if (hops == 1) "" else "s"}"
}

fun PaymentRow.value() = amount?.let { (if (direction == Direction.SENT) "−" else "+") + zec(it) } ?: "—"

/** One payment as the design's token row. */
@Composable
fun PaymentListRow(r: PaymentRow, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val bad = r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED
    ListRow(
        avatar = { Avatar(r.glyph(), r.tint()) },
        title = r.title(), tag = shortTxid(r.txid), tagMono = true,
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
        Text("Prepare for offline", style = MaterialTheme.typography.headlineLarge)
        Text("Splits your balance into cash-like notes (two each of 0.01 ZEC upward, plus small fee notes) so you can pay exact amounts with no internet. Needs a connection; takes a few minutes to confirm.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
        DarkCard(color = Ink.black, padding = 24.dp) {
            when (val p = prep) {
                is PrepareStatus.Waiting -> {
                    Text("Waiting for the split to be mined…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("prepare_state"))
                    Tag(shortTxid(p.txid), mono = true)
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
                }
                is PrepareStatus.Idle -> {
                    Text(zecText(p.notes.sumOf { it.value }), style = MaterialTheme.typography.displaySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(if (p.ready) Ink.green else Ink.amber, 6.dp)
                        Text(if (p.ready) "Ready for offline" else if (p.notes.isEmpty()) "No spendable notes yet" else "Confirming notes…",
                            style = MaterialTheme.typography.titleSmall, color = if (p.ready) Ink.green else Ink.amber,
                            modifier = Modifier.testTag("prepare_state"))
                    }
                    if (p.notes.isNotEmpty()) Text("${p.notes.size} notes · pay exact amounts with no internet", style = MaterialTheme.typography.bodySmall)
                }
                null -> Text("…")
            }
        }
        (prep as? PrepareStatus.Idle)?.let { p ->
            // One row per note size: "0.01 ZEC × 2" and whether it can be spent offline yet.
            p.notes.groupBy { it.value }.toSortedMap(compareByDescending { it }).forEach { (value, notes) ->
                val conf = notes.minOf { n ->
                    if (n.minedHeight != null && p.tip != null) (p.tip - n.minedHeight + 1).coerceAtLeast(0) else 0
                }
                val ok = conf >= SPEND_CONFIRMATIONS
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(Glyph.Notes, Ink.zec, 44.dp)
                    Text("${zec(value)} ZEC × ${notes.size}", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).testTag("denomination"))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(if (ok) Ink.green else Ink.amber, 6.dp)
                        Text(if (ok) "ready" else "$conf/$SPEND_CONFIRMATIONS", color = if (ok) Ink.green else Ink.amber, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        val hint = when {
            !sync.atTip -> "Needs a connection: wait for sync to finish first."
            !waiting && !allSpendable -> "Waiting for recent funds to confirm (${zec(sync.ironwoodAvailable)} of ${zec(sync.ironwoodTotal)} spendable)."
            else -> null
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("prepare_hint")) }
        PrimaryButton(
            if (busy) "Sending…" else if (waiting) "Waiting for the split" else "Prepare for offline",
            { c.prepareForOffline() }, Modifier.testTag("prepare_button"),
            enabled = !busy && !waiting && sync.atTip && allSpendable,
        )
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
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
    val pending by c.pendingSent.collectAsState()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    if (pay is PayState.Ready) {
        PaymentQr(c, pay as PayState.Ready, back)
        return
    }
    val small = LocalConfiguration.current.screenHeightDp < 760
    val ready = balanceSplit(notes, sync.networkHeight ?: sync.scannedHeight).available
    Page(gap = if (small) 10.dp else 14.dp) {
        SheetTop(back) {
            if (!withMemo) Chip("+ Memo", Modifier.testTag("pay_add_memo"), onClick = { withMemo = true })
        }
        // Amount first, on the design's lighter sheet card.
        DarkCard(color = Ink.sheet, padding = if (small) 18.dp else 28.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (small) 8.dp else 14.dp)) {
                FactPill(bg = Ink.raised) { ZecAvatar(20.dp); Text("ZEC", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                Text(
                    amount.ifEmpty { "0" }, style = if (small) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
                    maxLines = 1, color = if (amount.isEmpty()) Ink.faint else Ink.text, modifier = Modifier.testTag("pay_amount"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LineIcon(Glyph.Notes, size = 16.dp, color = Ink.dim)
                    Text("${zec(ready)} ready offline", style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
                }
            }
        }
        if (scanning) {
            DarkCard(color = Ink.black, border = Ink.line, padding = 12.dp) {
                CameraScanner(260, inverted = true) { text ->
                    val addr = text.removePrefix("zcash:").substringBefore('?')
                    if (addr.startsWith("utest") || addr.startsWith("u1")) {
                        to = addr
                        scanning = false
                    }
                }
                Text("Point at the receiver's address QR", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            SecondaryButton("Cancel scan", { scanning = false })
        } else {
            // The recipient, styled as the design's bank row.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Ink.card), contentAlignment = Alignment.Center) {
                    LineIcon(Glyph.Up, size = 22.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SEND TO", style = MaterialTheme.typography.titleSmall, letterSpacing = 0.3.sp)
                    Box {
                        if (to.isEmpty()) Text("Paste or scan", color = Ink.dim, style = MaterialTheme.typography.bodyMedium)
                        BasicTextField(
                            to, { to = it.trim() }, singleLine = true, cursorBrush = SolidColor(Ink.text),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.dim, fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth().testTag("pay_to"),
                        )
                    }
                }
                if (to.isEmpty()) Chip("Paste", onClick = { clipboard.getText()?.text?.trim()?.let { to = it } })
                else IconButton(Glyph.Close, { to = "" }, size = 40.dp)
                IconButton(Glyph.Scan, { scanning = true }, tag = "pay_scan_address", size = 40.dp)
            }
            if (withMemo) {
                OutlinedTextField(memo, { memo = it }, label = { Text("Memo (optional)") }, shape = RoundedCornerShape(20.dp),
                    colors = fieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("pay_memo"))
            }
            Keypad({ k -> amount = keypadInput(amount, k) }, keyHeight = if (small) 46.dp else 62.dp)
        }
        val zats = parseZec(amount)
        if (caps.enabled) {
            Text(
                "Offline limits on: up to ${zec(caps.perPayment)} ZEC per payment; " +
                    "${zec(Limits.unsettled(pending))} of ${zec(caps.perSession)} ZEC used until your payments settle.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pay_limits"),
            )
        }
        val building = pay is PayState.Building
        // The button says why it can't pay yet.
        val why = when {
            building -> "Signing… no internet needed"
            zats == null -> "Enter an amount"
            to.isBlank() -> "Add the receiver's address"
            ready == 0L -> "Nothing ready offline yet"
            zats > ready -> "Only ${zec(ready)} ZEC is ready offline"
            else -> null
        }
        PrimaryButton(why ?: "Pay", { c.payOffline(to, zats!!, memo.ifBlank { null }) }, Modifier.testTag("pay_go"), enabled = why == null)
        if (!building && ready == 0L) SecondaryButton("Prepare for offline", prepare)
        if (building) LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
        if (pay is PayState.Failed) Text((pay as PayState.Failed).error, color = Ink.red, modifier = Modifier.testTag("pay_error"))
        Spacer(Modifier.height(8.dp))
    }
}

/** The signed, stored payment as an animated QR. Shown only after write-before-send. */
@Composable
fun PaymentQr(c: AppController, p: PayState.Ready, back: () -> Unit) {
    val encoder = remember(p.payment.txid) { FrameEncoder(p.payment.raw) }
    var frame by remember(p.payment.txid) { mutableStateOf(encoder.next()) }
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.payment.txid) {
        c.onPaymentQrShown(p.payment)
        while (true) {
            delay(FRAME_MS)
            frame = encoder.next()
            shown++
        }
    }
    Page {
        SheetTop({ c.clearPay(); back() })
        DarkCard(color = Ink.sheet, padding = 22.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FactPill(bg = Ink.raised) { ZecAvatar(20.dp); Text("ZEC", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                Text(zecText(p.payment.amount, 24), style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("pay_summary"))
                Text("fee ${zec(p.payment.fee)} ZEC · ${shortTxid(p.payment.txid)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pay_txid"))
                Spacer(Modifier.height(2.dp))
                QrTile(frame, 290.dp, tag = "payment_qr")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(PayStatus.PENDING)
                    FactPill(bg = Ink.raised) { Text("frame ${shown + 1} · ${encoder.seqLen} parts", fontSize = 13.sp, color = Ink.dim) }
                }
            }
        }
        BigTitle("Show the receiver", "Signed and saved; your notes are spent. It also goes out over Bluetooth, and whichever phone gets online first broadcasts it.")
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
    Page(bg = Ink.black, gap = 22.dp) {
        SheetTop(back)
        when (val s = st) {
            is ReceiveState.Scanning -> {
                // The design's address pill: short address; tap for the whole thing.
                Row(
                    Modifier.align(Alignment.CenterHorizontally).clip(Pill).border(1.dp, Ink.line, Pill).clickable { full = !full }
                        .padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(shortAddress(w.address), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.testTag("address"))
                    LineIcon(Glyph.ChevronDown, size = 18.dp, color = Ink.dim)
                }
                if (full) Text(w.address, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink.dim, modifier = Modifier.padding(horizontal = 8.dp))
                DarkCard(color = Ink.black, border = Ink.line, padding = if (scan) 12.dp else 30.dp) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (!scan) AddressQr(w.address, 250.dp)
                        else CameraScanner(300) { c.onFrame(it) }
                    }
                    if (scan) LinearProgressIndicator(progress = { s.progress.toFloat() }, color = Ink.text, trackColor = Ink.raised,
                        modifier = Modifier.fillMaxWidth().clip(Pill).testTag("receive_progress"))
                }
                if (!scan) BigTitle("Receive", "Scan the QR code or copy the address to receive ZEC")
                else BigTitle("Scan payment", if (s.progress > 0) "Reading… ${(s.progress * 100).toInt()}%" else "Point the camera at the payer's moving QR code. Missed frames fill in by themselves.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!scan) {
                        SecondaryButton("Scan", { scan = true }, Modifier.weight(1f).testTag("recv_tab_scan"), glyph = Glyph.Scan)
                        PrimaryButton(if (copied) "Copied" else "Copy", {
                            clipboard.setText(AnnotatedString(w.address)); copied = true
                        }, Modifier.weight(1f).testTag("copy_address"))
                    } else {
                        SecondaryButton("Show my address", { scan = false }, Modifier.testTag("recv_tab_address"))
                    }
                }
            }
            ReceiveState.Verifying -> {
                DarkCard(color = Ink.black, border = Ink.line, padding = 30.dp) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(Pill), color = Ink.text, trackColor = Ink.raised)
                }
                BigTitle("Checking…", "Verifying proofs and signatures on this phone", tag = "receive_state")
            }
            is ReceiveState.Accepted -> {
                DarkCard(color = Ink.black, border = Ink.line, padding = 30.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Avatar(Glyph.Check, Ink.green, 64.dp)
                        Text("${zec(s.payment.amount)} ZEC", style = MaterialTheme.typography.displayMedium, modifier = Modifier.testTag("received_amount"))
                        StatusChip(PayStatus.PENDING)
                        Text(receivedLabel(s.payment), style = MaterialTheme.typography.bodyMedium, color = Ink.dim, modifier = Modifier.testTag("received_status"))
                        s.payment.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                s.warning?.let {
                    DarkCard(Modifier.testTag("receive_warning"), color = Ink.redCard, padding = 18.dp) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                BigTitle("Received", "Verified offline. It settles once either phone gets online and broadcasts it.")
                PrimaryButton("Receive another", { c.resetReceive() })
            }
            is ReceiveState.Rejected -> {
                DarkCard(color = Ink.redCard, border = Ink.red.copy(alpha = 0.5f), padding = 26.dp) {
                    Avatar(Glyph.Close, Ink.red, 56.dp)
                    Text("Not accepted", style = MaterialTheme.typography.headlineMedium, color = Ink.red, modifier = Modifier.testTag("receive_state"))
                    Text(s.reason, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.testTag("receive_reason"))
                }
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
        Text("Offline limits", style = MaterialTheme.typography.headlineLarge)
        Text("An offline payment is final only once it settles. Limits cap how much rides on unsettled payments: " +
            "this phone won't sign above them, and warns when it receives more than the per-payment limit.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.dim)
        DarkCard(gap = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Avatar(Glyph.Notes, Ink.zec, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text("Use limits", style = MaterialTheme.typography.titleMedium)
                    Text(if (on) "On" else "Off · default", style = MaterialTheme.typography.bodySmall)
                }
                Switch(on, { on = it; saved = false }, Modifier.testTag("limits_on"),
                    colors = SwitchDefaults.colors(checkedTrackColor = Ink.text, checkedThumbColor = Color.Black,
                        uncheckedTrackColor = Ink.raised, uncheckedThumbColor = Ink.dim, uncheckedBorderColor = Ink.line))
            }
            HorizontalDivider(color = Ink.line)
            OutlinedTextField(perPayment, { perPayment = it; saved = false }, label = { Text("Per payment (ZEC)") }, shape = RoundedCornerShape(18.dp),
                colors = fieldColors(), enabled = on, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("limits_per_payment"))
            OutlinedTextField(perSession, { perSession = it; saved = false }, label = { Text("Unsettled total (ZEC)") }, shape = RoundedCornerShape(18.dp),
                supportingText = { Text("Paid offline and not yet mined. Frees up as your payments settle.") },
                colors = fieldColors(), enabled = on, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().testTag("limits_per_session"))
        }
        val p = parseZec(perPayment)
        val s = parseZec(perSession)
        PrimaryButton(
            if (saved) "Saved" else if (on && (p == null || s == null)) "Enter both limits" else "Save",
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
    val now = c.now()
    val rows = paymentRows(pending, received, m.queue, amounts, now)
    Page(gap = 12.dp) {
        SheetTop(back)
        Text("Payments", style = MaterialTheme.typography.headlineLarge)
        FilterChips(filter) { filter = it }
        val shown = rows.filter { filter.keeps(it) }
        if (shown.isEmpty()) Text("No payments here yet.", color = Ink.dim, modifier = Modifier.padding(vertical = 12.dp))
        shown.forEach { r ->
            val key = r.txid.take(16)
            val tag = when (r.direction) { Direction.SENT -> "sent_$key"; Direction.RECEIVED -> "received_$key"; Direction.RELAY -> "relay_$key" }
            val bad = r.status == PayStatus.CONFLICTED || r.status == PayStatus.REJECTED
            Column(
                Modifier.fillMaxWidth().let { if (bad) it.clip(RoundedCornerShape(26.dp)).background(Ink.redCard).padding(horizontal = 14.dp) else it }
                    .padding(vertical = 2.dp).testTag(tag),
            ) {
                PaymentListRow(r)
                Row(Modifier.fillMaxWidth().padding(start = 68.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        r.label, style = MaterialTheme.typography.bodySmall, color = if (bad) Ink.red else Ink.dim,
                        modifier = Modifier.weight(1f).let { if (r.direction == Direction.RECEIVED) it.testTag("received_label_$key") else it },
                    )
                    Text(ago(r.since, now), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.testTag("age_$key"))
                }
                r.memo?.let { Text("“$it”", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 68.dp, bottom = 6.dp)) }
                if (r.canShowQr) {
                    Box(
                        Modifier.padding(start = 68.dp, bottom = 10.dp).clip(Pill).background(Color.White)
                            .clickable { c.showPending(r.txid); showQr() }.padding(horizontal = 16.dp, vertical = 9.dp)
                            .testTag("pending_show_$key"),
                    ) { Text("Show QR to the receiver again", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
                HorizontalDivider(color = Ink.card, modifier = Modifier.padding(start = 68.dp))
            }
        }
        // Debug builds: push everything to the network now instead of waiting for the next try.
        if (BuildConfig.DEBUG) SecondaryButton("Broadcast now (debug)", { c.broadcastNow() }, Modifier.testTag("broadcast_now"))
        val msg by c.message.collectAsState()
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("message")) }
        Spacer(Modifier.height(8.dp))
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
